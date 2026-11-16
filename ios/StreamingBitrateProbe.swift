import AudioToolbox
import Foundation

/// One-shot local-file bitrate probe via Core Audio's
/// `AudioFileStream`.
///
/// **Why this exists.** `AVAssetTrack.estimatedDataRate` returns 0
/// for some local sources, and `AudioFile`'s
/// `kAudioFilePropertyBitRate` returns 0 for some transcoded MP3s
/// (some server-transcoded MP3s exhibit this).
/// Core Audio's `AudioFileStreamOpen` parses the bitstream from
/// raw bytes and surfaces format/packet info as soon as the first
/// frame headers land — same approach SwiftAudioPlayer,
/// AudioStreaming, and FreePlayer use.
///
/// **Source-agnostic.** AudioToolbox's parser auto-detects the
/// container/codec from byte content; works for MP3, AAC (m4a),
/// FLAC (m4a), WAV, AIFF, CAF — anything Core Audio understands.
/// Containers it cannot parse return nil and the caller falls
/// back to whatever the lib already had (typically null bitrate
/// → UI shows "?").
///
/// **Why we compute from packets.** Apple's
/// `kAudioFileStreamProperty_BitRate` is documented best-effort:
/// for streaming MP3 it routinely never fires even when
/// `kAudioFileStreamProperty_ReadyToProducePackets` does. The
/// canonical fix (per dimitris-c/AudioStreaming +
/// `msching/audio-in-ios-3` deep-dive) is to install BOTH a
/// property listener AND a packets listener: accumulate packet
/// bytes + counts, then compute
/// `bitrate = totalBytes × 8 × mSampleRate / (totalPackets ×
/// mFramesPerPacket)`. For 192 kbps CBR this converges within
/// ~1% after ~100 packets — well under our probe window.
///
/// **Cost.** One 256 KB `FileHandle` read per distinct local URL,
/// and no network request at all. The 256 KB window tolerates
/// large ID3v2 tags (sometimes 32 KB+ for embedded album art)
/// without starving the parser of audio frames. Parse runs
/// synchronously on a detached utility-priority Task. In-memory
/// cache keyed by URL string serves repeat lookups. Does NOT
/// touch the lookahead cache or AVPlayer's media pipeline.
enum StreamingBitrateProbe {

  /// Read-side cache: probes for the same URL after the first
  /// hit return immediately. `NSCache` bounds the entry count
  /// (LRU eviction at the limit) so a long session walking a
  /// large library can't grow the cache without bound. Wrapped
  /// values are `NSNumber` because `NSCache` requires class
  /// types.
  private static let cache: NSCache<NSString, NSNumber> = {
    let c = NSCache<NSString, NSNumber>()
    c.countLimit = 512
    return c
  }()

  /// 256 KB prefix: enough to skip past large ID3v2 tags + still
  /// gather enough MP3 frames to compute a stable average.
  private static let probeByteCount = 262_144

  /// Resolve the encoded bitrate (bits/sec) of the audio at a
  /// `file://` `url` by reading its prefix with `FileHandle` and
  /// running an AudioFileStream parse over it. Necessary because
  /// Core Audio's `AudioFile` API's `kAudioFilePropertyBitRate`
  /// returns 0 for some transcoded MP3s (e.g. server-transcoded MP3s
  /// the lookahead cache has downloaded). The format extractor uses it as
  /// the third-tier fallback for `file://` sources whose codec has no
  /// dedicated reader.
  ///
  /// Returns nil for a non-`file://` URL, an empty or unreadable
  /// file, a format the parser can't identify, or a probe window
  /// that yielded no packets. Results are cached per URL string.
  static func bitrate(forUrl url: URL) async -> Double? {
    let key = url.absoluteString
    if let cached = readCache(key) {
      #if DEBUG
      NSLog("[RNQP-BITRATE-PROBE] cache-hit value=%f url=%@", cached, key)
      #endif
      return cached
    }
    guard url.isFileURL else { return nil }
    let bps = await fetchAndParseLocal(url: url)
    if let bps = bps {
      writeCache(key, bps)
    }
    return bps
  }

  private static func readCache(_ key: String) -> Double? {
    return cache.object(forKey: key as NSString).map { $0.doubleValue }
  }

  private static func writeCache(_ key: String, _ bps: Double) {
    cache.setObject(NSNumber(value: bps), forKey: key as NSString)
  }

  /// File-type hint from the URL path extension — speeds up the
  /// parser's format identification when the codec is known.
  /// `kAudioFileMP3Type` for `.mp3`, etc. Falls through to 0
  /// (sniff) for unknown extensions.
  private static func fileTypeHint(from url: URL) -> AudioFileTypeID {
    let ext = url.pathExtension.lowercased()
    switch ext {
    case "mp3": return kAudioFileMP3Type
    case "m4a", "m4b", "m4r", "mp4": return kAudioFileM4AType
    case "aac": return kAudioFileAAC_ADTSType
    case "flac": return kAudioFileFLACType
    case "wav", "wave": return kAudioFileWAVEType
    case "aiff", "aif": return kAudioFileAIFFType
    case "caf": return kAudioFileCAFType
    default: return 0
    }
  }

  /// Read the first `probeByteCount` bytes of a local file via
  /// FileHandle and run the same AudioFileStream parse.
  /// FileHandle is preferred over `Data(contentsOf:)` because
  /// the latter loads the WHOLE file into memory; for a 50 MB
  /// MP3 that's a wasted 50 MB allocation when 256 KB is
  /// sufficient to identify the encoded bitrate.
  private static func fetchAndParseLocal(url: URL) async -> Double? {
    let data: Data
    do {
      let handle = try FileHandle(forReadingFrom: url)
      defer { try? handle.close() }
      data = handle.readData(ofLength: probeByteCount)
    } catch {
      #if DEBUG
      NSLog("[RNQP-BITRATE-PROBE] file-open-failed err=%@ url=%@",
            String(describing: error), url.absoluteString)
      #endif
      return nil
    }
    if data.count < 4 {
      #if DEBUG
      NSLog("[RNQP-BITRATE-PROBE] file-too-small bytes=%d url=%@",
            data.count, url.absoluteString)
      #endif
      return nil
    }
    return await runParse(data: data, url: url, source: "local")
  }

  private static func runParse(data: Data, url: URL, source: String) async -> Double? {
    let hint = fileTypeHint(from: url)
    let result = await Task.detached(priority: .utility) { () -> Double? in
      return parseBitrate(from: data, hint: hint)
    }.value
    #if DEBUG
    NSLog("[RNQP-BITRATE-PROBE] parse-done source=%@ value=%@ bytes=%d hint=0x%x url=%@",
          source, result.map { "\($0)" } ?? "nil",
          data.count, Int(hint), url.absoluteString)
    #endif
    return result
  }

  /// Run the synchronous AudioFileStream parse over `data`.
  /// Property listener captures format ASBD + an opportunistic
  /// `BitRate` if Core Audio happens to surface it. Packets
  /// listener accumulates total bytes + total packets across the
  /// parse. After parsing, compute the average bitrate from the
  /// accumulated packet stats; prefer the explicit BitRate
  /// property iff it landed.
  private static func parseBitrate(
    from data: Data, hint: AudioFileTypeID
  ) -> Double? {
    var streamID: AudioFileStreamID?
    let host = ListenerHost()
    let hostPtr = Unmanaged.passUnretained(host).toOpaque()

    let openStatus = AudioFileStreamOpen(
      hostPtr,
      // Property listener — captures DataFormat ASBD (sample
      // rate + frames-per-packet, needed for the packet-derived
      // bitrate calc) and the opportunistic BitRate property.
      { hostPtr, streamID, propertyId, _ in
        let host = Unmanaged<ListenerHost>.fromOpaque(hostPtr)
          .takeUnretainedValue()
        switch propertyId {
        case kAudioFileStreamProperty_DataFormat:
          var asbd = AudioStreamBasicDescription()
          var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
          if AudioFileStreamGetProperty(
            streamID, kAudioFileStreamProperty_DataFormat, &size, &asbd
          ) == noErr {
            host.sampleRate = asbd.mSampleRate
            host.framesPerPacket = asbd.mFramesPerPacket
          }
        case kAudioFileStreamProperty_BitRate:
          var bitRate: UInt32 = 0
          var size = UInt32(MemoryLayout<UInt32>.size)
          if AudioFileStreamGetProperty(
            streamID, kAudioFileStreamProperty_BitRate, &size, &bitRate
          ) == noErr, bitRate > 0 {
            host.explicitBitrate = Double(bitRate)
          }
        default:
          break
        }
      },
      // Packets listener — accumulates byte + packet counts.
      // Fires per parse-batch as the parser walks audio frames;
      // a single ParseBytes call typically yields multiple
      // packets-listener fires.
      { hostPtr, numberBytes, numberPackets, _, _ in
        let host = Unmanaged<ListenerHost>.fromOpaque(hostPtr)
          .takeUnretainedValue()
        host.totalBytes &+= UInt64(numberBytes)
        host.totalPackets &+= UInt64(numberPackets)
      },
      hint,
      &streamID
    )

    guard openStatus == noErr, let streamID = streamID else {
      #if DEBUG
      NSLog("[RNQP-BITRATE-PROBE] open-failed status=%d hint=0x%x",
            Int(openStatus), Int(hint))
      #endif
      return nil
    }
    defer { AudioFileStreamClose(streamID) }

    let parseStatus = data.withUnsafeBytes { rawBuf -> OSStatus in
      guard let baseAddr = rawBuf.baseAddress else { return noErr }
      return AudioFileStreamParseBytes(
        streamID, UInt32(data.count), baseAddr, []
      )
    }
    // `kAudioFileStreamError_DataUnavailable` is normal for a
    // truncated probe; the parser's already done useful work
    // (property + packets listeners fired) — keep going to read
    // accumulated state.

    if let explicit = host.explicitBitrate {
      #if DEBUG
      NSLog("[RNQP-BITRATE-PROBE] explicit-bitrate value=%f parseStatus=%d",
            explicit, Int(parseStatus))
      #endif
      return explicit
    }
    if host.totalPackets > 0,
       host.framesPerPacket > 0,
       host.sampleRate > 0 {
      let bps = Double(host.totalBytes) * 8.0 * host.sampleRate
        / (Double(host.totalPackets) * Double(host.framesPerPacket))
      #if DEBUG
      NSLog("[RNQP-BITRATE-PROBE] packets-derived value=%f totalBytes=%llu totalPackets=%llu sampleRate=%f framesPerPacket=%u parseStatus=%d",
            bps, host.totalBytes, host.totalPackets,
            host.sampleRate, host.framesPerPacket, Int(parseStatus))
      #endif
      return bps
    }
    #if DEBUG
    NSLog("[RNQP-BITRATE-PROBE] no-data totalBytes=%llu totalPackets=%llu sampleRate=%f framesPerPacket=%u parseStatus=%d",
          host.totalBytes, host.totalPackets,
          host.sampleRate, host.framesPerPacket, Int(parseStatus))
    #endif
    return nil
  }

  /// Reference-typed listener-host so the C callbacks can mutate
  /// fields through an opaque `void*`. Captures both the explicit
  /// BitRate property (if it fires) and the packet-derived stats
  /// (which always fire if the parser identified the format).
  private final class ListenerHost {
    var explicitBitrate: Double?
    var sampleRate: Double = 0
    var framesPerPacket: UInt32 = 0
    var totalBytes: UInt64 = 0
    var totalPackets: UInt64 = 0
  }
}
