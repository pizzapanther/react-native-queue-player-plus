import AVFoundation
import AudioToolbox
import CoreMedia
import NitroModules

/// Resolves the active item's runtime audio format from AVFoundation. Pure
/// function — no shared state. Caller owns invocation cadence + emit dedup.
///
/// Codec maps from the audio track's `CMAudioFormatDescription` ASBD; container
/// maps from the asset URL's path extension (HLS detected as `.m3u8`). Bitrate
/// is read from `AVAssetTrack.estimatedDataRate` (bits/sec) when AVFoundation
/// reports a non-zero value; for MP3 assets the value is unreliable at
/// `.readyToPlay` so a codec-gated fallback chain takes over: Core Audio's MP3
/// frame parser (`kAudioFilePropertyBitRate`) for `file://` and AVPlayerItem
/// access-log telemetry for `http(s)://`. Each field falls back to its `null`
/// sentinel when no signal is available.
enum NowPlayingFormatExtractor {

  /// Extract the format snapshot for `item`. Returns `nil` only when the asset
  /// has no audio track at all; otherwise every field is best-effort with
  /// per-field null fallback. May suspend while AVAsset properties load.
  ///
  /// Track pick: prefers the player's currently-enabled
  /// AVPlayerItemTrack (matches what's actually decoding for HLS
  /// multi-rendition assets) and falls back to the asset's first
  /// audio AVAssetTrack when no enabled item-track is found.
  static func extract(item: AVPlayerItem) async -> NowPlayingFormat? {
    // `AVPlayerItem.asset` is a MainActor-isolated property under
    // strict concurrency. Hop to main for the read; the remainder of
    // this function works against the captured asset reference.
    let asset: AVAsset = await MainActor.run { item.asset }
    let sourceURL: String? = await MainActor.run { item.sourceURL }
    let tracks: [AVAssetTrack]
    do {
      tracks = try await asset.load(.tracks)
    } catch {
      return nil
    }
    let audioTrack = pickAudioAssetTrack(item: item, assetTracks: tracks)
    guard let audioTrack = audioTrack else { return nil }

    var formatDescriptions: [CMFormatDescription] = []
    var estimatedDataRate: Float = 0
    var descriptionsFailed = false
    var rateFailed = false
    do {
      formatDescriptions = try await audioTrack.load(.formatDescriptions)
    } catch {
      descriptionsFailed = true
    }
    do {
      estimatedDataRate = try await audioTrack.load(.estimatedDataRate)
    } catch {
      rateFailed = true
    }
    // If BOTH loads failed there's no signal worth emitting — return
    // nil so the consumer can distinguish "extractor ran but couldn't
    // read" from "asset has no audio track" cleanly. A single-load
    // failure still produces a partial snapshot via per-field null.
    if descriptionsFailed && rateFailed { return nil }

    var codec: AudioCodec = .unknown
    var sampleRate: Double? = nil
    var channelCount: Double? = nil
    var bitsPerChannel: Double? = nil

    if let desc = formatDescriptions.first,
       let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(desc) {
      let asbd = asbdPtr.pointee
      codec = mapCodec(formatID: asbd.mFormatID)
      if asbd.mSampleRate > 0 { sampleRate = asbd.mSampleRate }
      if asbd.mChannelsPerFrame > 0 { channelCount = Double(asbd.mChannelsPerFrame) }
      if asbd.mBitsPerChannel > 0 { bitsPerChannel = Double(asbd.mBitsPerChannel) }
    }

    // The media's URL, not the transport's: a routed track's asset URL is the
    // read-through server's, which has no extension to map a container from
    // and is never a file URL even when the bytes come off local disk.
    let url = sourceURL.flatMap { URL(string: $0) } ?? (asset as? AVURLAsset)?.url
    let container = mapContainer(url: url)
    let mimeType = mapMimeType(codec: codec)
    var bitrate: Double? = estimatedDataRate > 0 ? Double(estimatedDataRate) : nil
    // Fallback chain when AVFoundation didn't surface a bitrate:
    //
    // 1. Local MP3 file://: Core Audio's frame parser
    //    (`kAudioFilePropertyBitRate`) — reliable for CBR, average
    //    for VBR-with-Xing. MP3-specific because the local-file
    //    AudioFile API needs the format hint up-front.
    // 2. Local file:// of any other codec: `StreamingBitrateProbe`
    //    reads a 256 KB prefix off disk and derives a rate via Core
    //    Audio's `AudioFileStream` (codec-agnostic).
    // 3. Remote http(s)://, any codec: `event.averageAudioBitrate` /
    //    `event.indicatedBitrate` from the AVPlayer access log —
    //    surfaces a real value for HLS variants whose playlist
    //    declares BANDWIDTH, and for progressive servers that declare
    //    one. A remote source whose server declares nothing has no
    //    bitrate: `estimatedDataRate` reports 0 and nothing reads the
    //    container header off the wire.
    if bitrate == nil, let url = url {
      if url.isFileURL {
        // Local file: try Core Audio's `AudioFile`-based reader
        // first when the codec is MP3 (cheapest path: no parse,
        // single property read). Falls back to the
        // `AudioFileStream` packet-derived probe for codecs
        // without a dedicated reader OR when AudioFile returned
        // 0 (some server-transcoded MP3s).
        if codec == .mp3 {
          bitrate = await readLocalMP3Bitrate(url: url)
        }
        if bitrate == nil, codec == .flac {
          bitrate = await readLocalFLACBitrate(url: url)
        }
        if bitrate == nil {
          bitrate = await StreamingBitrateProbe.bitrate(forUrl: url)
        }
      } else if let scheme = url.scheme?.lowercased(),
                scheme == "http" || scheme == "https" {
        // Remote: nothing here reads bytes off the wire. The library issues
        // no side-band range request for a remote track, so the only source
        // is what the player itself reports — the access log, which declares
        // a bitrate for HLS variants and for progressive servers that send
        // one. Not gated on codec: the access log reports whatever the player
        // negotiated, and MP3 is neither the only nor the usual codec.
        bitrate = readStreamingBitrate(item: item)
      }
    }

    // Read duration from the asset, not the AVPlayerItem. AVPlayerItem
    // mirrors `asset.duration` but the mirror is racy: a freshly-built
    // item against a `file://` AVURLAsset can briefly report
    // `kCMTimeIndefinite` even after `automaticallyLoadedAssetKeys`
    // resolves on the asset. The asset is the canonical source — and
    // because the AVPlayerItem here was constructed with `"duration"`
    // in `automaticallyLoadedAssetKeys`, the value is already loaded;
    // this `load(.duration)` call is a property read, not a fetch.
    let assetDuration: CMTime
    do {
      assetDuration = try await asset.load(.duration)
    } catch {
      assetDuration = .indefinite
    }
    let durationSec: Double? = {
      guard assetDuration.isValid, !assetDuration.isIndefinite else { return nil }
      let secs = assetDuration.seconds
      return secs.isFinite ? secs : nil
    }()

    // ReplayGain fields read from `AudioTapProvider`'s per-item
    // cache. The provider's `audioMix(for:)` path kicks off async
    // metadata extraction at item construction; by the time the JS
    // side asks for `getNowPlayingFormat()` the data is typically
    // already cached. Items that haven't seen the tap path
    // (`AudioMixProvider` not wired, HLS bypass, track-less item)
    // surface all fields as null + `replayGainActive = false`.
    let rgData = AudioTapProvider.shared.replayGainDataForItem(item)
    let rgActive = AudioTapProvider.shared.replayGainActiveForItem(item)

    return NowPlayingFormat(
      codec: codec,
      container: container,
      mimeType: mimeType.map { .second($0) } ?? .first(.null),
      sampleRate: sampleRate.map { .second($0) } ?? .first(.null),
      channelCount: channelCount.map { .second($0) } ?? .first(.null),
      bitrate: bitrate.map { .second($0) } ?? .first(.null),
      durationSec: durationSec.map { .second($0) } ?? .first(.null),
      replayGainTrackDb: rgData?.trackGainDb.map { .second($0) } ?? .first(.null),
      replayGainTrackPeak: rgData?.trackPeak.map { .second($0) } ?? .first(.null),
      replayGainAlbumDb: rgData?.albumGainDb.map { .second($0) } ?? .first(.null),
      replayGainAlbumPeak: rgData?.albumPeak.map { .second($0) } ?? .first(.null),
      replayGainActive: rgActive,
      ios: NowPlayingFormatIosExtras(
        bitsPerChannel: bitsPerChannel.map { .second($0) } ?? .first(.null)
      ),
      android: nil
    )
  }

  /// Pick the AVAssetTrack the player is actually decoding. Iterates
  /// the AVPlayerItem's `tracks` (each wraps an AVAssetTrack with an
  /// `isEnabled` flag) and prefers the first enabled audio track.
  /// Falls back to the asset's first audio AVAssetTrack when nothing
  /// is enabled (e.g. a fresh item before the player has selected
  /// renditions on an HLS playlist).
  static func pickAudioAssetTrack(
    item: AVPlayerItem, assetTracks: [AVAssetTrack]
  ) -> AVAssetTrack? {
    for itemTrack in item.tracks where itemTrack.isEnabled {
      if let assetTrack = itemTrack.assetTrack, assetTrack.mediaType == .audio {
        return assetTrack
      }
    }
    return assetTracks.first(where: { $0.mediaType == .audio })
  }

  static func mapCodec(formatID: AudioFormatID) -> AudioCodec {
    switch formatID {
    case kAudioFormatMPEG4AAC,
         kAudioFormatMPEG4AAC_HE,
         kAudioFormatMPEG4AAC_HE_V2,
         kAudioFormatMPEG4AAC_LD,
         kAudioFormatMPEG4AAC_ELD,
         kAudioFormatMPEG4AAC_ELD_V2,
         kAudioFormatMPEG4AAC_ELD_SBR,
         kAudioFormatMPEG4AAC_Spatial:
      return .aac
    case kAudioFormatMPEGLayer3:
      return .mp3
    case kAudioFormatFLAC:
      return .flac
    case kAudioFormatAppleLossless:
      return .alac
    case kAudioFormatOpus:
      return .opus
    case kAudioFormatLinearPCM:
      return .pcm
    default:
      return .unknown
    }
  }

  static func mapContainer(url: URL?) -> AudioContainer {
    guard let url = url else { return .unknown }
    let ext = url.pathExtension.lowercased()
    switch ext {
    case "mp3": return .mp3
    case "m4a", "m4b", "m4r", "mp4": return .mp4
    case "flac": return .flac
    case "ogg", "opus": return .ogg
    case "wav", "wave": return .wav
    case "aac": return .aacAdts
    case "m3u8": return .hls
    default: return .unknown
    }
  }

  static func mapMimeType(codec: AudioCodec) -> String? {
    switch codec {
    case .aac: return "audio/mp4a-latm"
    case .mp3: return "audio/mpeg"
    case .flac: return "audio/flac"
    case .alac: return "audio/alac"
    case .opus: return "audio/opus"
    case .pcm: return "audio/L16"
    case .unknown: return nil
    }
  }

  /// Read the average bitrate of a local MP3 by parsing frame headers
  /// via Core Audio's `kAudioFilePropertyBitRate`. Synchronous file I/O
  /// — typically sub-millisecond on a CBR file, scales with VBR XING/LAME
  /// header presence. Wrapped in a detached utility-priority Task so the
  /// extractor's async caller doesn't block the main actor on cold-cache
  /// disk reads.
  ///
  /// Internally double-checks the file is actually MP3 by reading the
  /// resolved `kAudioFilePropertyDataFormat` ASBD's format ID after open.
  /// The `kAudioFileMP3Type` argument to `AudioFileOpenURL` is a
  /// non-restrictive hint — Core Audio's content sniffing can succeed-
  /// open files that aren't MP3 (e.g. an .m4a/AAC fixture). The
  /// `mFormatID == kAudioFormatMPEGLayer3` ASBD check is the actual
  /// safety net that ensures we only return a bitrate read from MP3
  /// frames. Returns `nil` on open failure, format mismatch, property-
  /// read failure, or a zero/negative reported value.
  static func readLocalMP3Bitrate(url: URL) async -> Double? {
    return await Task.detached(priority: .utility) { () -> Double? in
      var fileID: AudioFileID?
      let openStatus = AudioFileOpenURL(
        url as CFURL, .readPermission, kAudioFileMP3Type, &fileID
      )
      guard openStatus == noErr, let fileID = fileID else { return nil }
      defer { AudioFileClose(fileID) }

      var asbd = AudioStreamBasicDescription()
      var asbdSize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
      let formatStatus = AudioFileGetProperty(
        fileID, kAudioFilePropertyDataFormat, &asbdSize, &asbd
      )
      guard formatStatus == noErr,
            asbd.mFormatID == kAudioFormatMPEGLayer3 else { return nil }

      var bitRate: UInt32 = 0
      var size = UInt32(MemoryLayout<UInt32>.size)
      let propStatus = AudioFileGetProperty(
        fileID, kAudioFilePropertyBitRate, &size, &bitRate
      )
      guard propStatus == noErr, bitRate > 0 else { return nil }
      return Double(bitRate)
    }.value
  }

  /// Read the average bitrate of a local FLAC by parsing STREAMINFO
  /// (`totalSamples`, `sampleRate`) and dividing the file's byte
  /// size by the resulting duration. AudioFileStream's packet-derived
  /// probe (`StreamingBitrateProbe`) doesn't yield a bitrate for FLAC
  /// because the FLAC parser there doesn't model variable-frame
  /// containers — STREAMINFO is the cheaper, more reliable path.
  ///
  /// `(file_size_bytes - metadata_overhead) * 8 / duration_seconds` is
  /// the textbook formula; metadata overhead is tiny (~kilobytes) for
  /// typical music FLACs in the tens-of-megabytes range, so we use
  /// the full file size and accept a sub-1% high read on average. The
  /// alternative (subtracting the position of the first audio frame
  /// from the file size) requires walking every metadata block to
  /// find the audio offset, more code for negligible accuracy gain.
  ///
  /// Returns nil when STREAMINFO can't be parsed or the file size /
  /// duration math doesn't produce a finite positive value.
  static func readLocalFLACBitrate(url: URL) async -> Double? {
    return await Task.detached(priority: .utility) { () -> Double? in
      // STREAMINFO sits in the first ~50 bytes after `fLaC`. A small
      // prefix read is plenty.
      let header: Data
      do {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        header = handle.readData(ofLength: 256)
      } catch {
        return nil
      }
      guard let info = FLACStreamInfo.parse(header) else {
        return nil
      }
      guard info.sampleRate > 0, info.totalSamples > 0 else { return nil }
      // `attributesOfItem` is preferred over `URLResourceKey` for
      // file:// URLs that have a path — sub-millisecond stat call.
      let attrs: [FileAttributeKey: Any]
      do {
        attrs = try FileManager.default.attributesOfItem(atPath: url.path)
      } catch {
        return nil
      }
      guard let size = (attrs[.size] as? NSNumber)?.int64Value else {
        return nil
      }
      let durationSec = Double(info.totalSamples) / Double(info.sampleRate)
      guard durationSec > 0 else { return nil }
      let bitsPerSecond = Double(size) * 8.0 / durationSec
      return bitsPerSecond.isFinite && bitsPerSecond > 0 ? bitsPerSecond : nil
    }.value
  }

  /// Read the active streaming bitrate from the AVPlayerItem's access
  /// log. Prefers `event.averageAudioBitrate` (audio stream's declared
  /// average), then `event.indicatedBitrate` (HLS playlist's BANDWIDTH
  /// for the active variant). Returns `nil` when no access-log event
  /// has landed yet or neither declared field is populated.
  ///
  /// Why declared-only: `event.observedBitrate` and the
  /// `numberOfBytesTransferred / transferDuration` derivation both
  /// measure NETWORK throughput — bytes/sec on the wire — not the
  /// encoded media bitrate. A 192 kbps progressive MP3 buffered in
  /// 0.3s over a fast LAN reports observedBitrate ≈ 50 Mbps, which
  /// would surface to consumers as "MP3 · 50000 kbps" — wrong by two
  /// orders of magnitude. Returning nil so the caller renders "?
  /// kbps" is the honest answer when the server didn't declare.
  ///
  /// Threading: invoked from the `extract(item:)` async pipeline (off
  /// main); `AVPlayerItem.accessLog()` returns a snapshot and is
  /// safe to call from any thread, matching the off-main usage in
  /// `TrackPlayer.swift`'s DEBUG access-log timeline logger.
  static func readStreamingBitrate(item: AVPlayerItem) -> Double? {
    guard let event = item.accessLog()?.events.last else { return nil }
    let avgAudio: Double = event.averageAudioBitrate
    if avgAudio.isFinite, avgAudio > 0 { return avgAudio }
    let indicated: Double = event.indicatedBitrate
    if indicated.isFinite, indicated > 0 { return indicated }
    return nil
  }
}
