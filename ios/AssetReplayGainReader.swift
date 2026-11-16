import AVFoundation
import Foundation

/// Reads the four ReplayGain tags off an `AVAsset`'s own metadata,
/// replacing the byte-prefix probe. AVFoundation surfaces the same
/// tags under three different shapes depending on container:
///
/// - MP3 / ID3v2 `TXXX`: the name lives in the item's `extraAttributes`
///   info key; the item's own key is the frame id (`TXXX`).
/// - FLAC / Vorbis comment: the name *is* the item's key.
/// - M4A / iTunes freeform: the item's key is the reverse-DNS
///   identifier (`com.apple.iTunes.replaygain_track_gain`).
///
/// `tagName(info:key:)` is the pure reduction of those three shapes to
/// one comparable name, extracted so it is testable without building
/// an `AVMetadataItem`.
enum AssetReplayGainReader {

  /// The four names a derived tag name has to match to be worth
  /// loading a value for. Everything else on the asset is skipped
  /// before its `stringValue` is touched.
  private static let replayGainNames: Set<String> = [
    ReplayGainExtractor.trackGainKey,
    ReplayGainExtractor.trackPeakKey,
    ReplayGainExtractor.albumGainKey,
    ReplayGainExtractor.albumPeakKey,
  ]

  /// Read ReplayGain from an item AVPlayer has already prepared.
  ///
  /// Returns `nil` unless `item.status == .readyToPlay`. That single
  /// condition is the whole gate: on a prepared item `load(.metadata)`
  /// costs zero requests and zero bytes, while interrogating an
  /// unprepared asset downloads essentially the entire file (measured:
  /// 2.889 MB of a 2.89 MB MP3, 3.776 MB of a 3.78 MB FLAC).
  ///
  /// Deliberately NOT gated on `asset.statusOfValue(forKey: "metadata")`:
  /// without `"metadata"` in `automaticallyLoadedAssetKeys` that status
  /// reads `.unknown` even at `.readyToPlay` (measured 8/8), so such a
  /// guard would be permanently false. The key is not set because it
  /// buys nothing — the prepared read is already free.
  ///
  /// Values go through `ReplayGainExtractor.rgDataFromTags`, the same
  /// acceptance function every other ReplayGain entry point uses, so
  /// the dB / peak rules cannot drift per source.
  static func read(item: AVPlayerItem) async -> ReplayGainData? {
    guard item.status == .readyToPlay else { return nil }
    // `AVPlayerItem.asset` is a MainActor-isolated property under strict
    // concurrency (`AVAsset` is not Sendable); `status` is explicitly
    // nonisolated. Hop to main for the asset read, then work against the
    // captured reference — same shape as `NowPlayingFormatExtractor.extract`.
    let asset: AVAsset = await MainActor.run { item.asset }
    // Opus applies `OpusHead.output_gain` in the decoder, so applying a
    // `REPLAYGAIN_*` tag on top of it double-corrects (NOTES.md section 25).
    //
    // The container is read from `sourceURL`, the media's own URL. A routed
    // track's asset URL is the read-through server's, which carries no
    // extension — asking it would fail this guard open on every Opus track.
    let sourceURL = await MainActor.run { item.sourceURL }
    if let source = sourceURL, let url = URL(string: source), isOpusContainer(url: url) {
      return nil
    }

    let metadata: [AVMetadataItem]
    do {
      metadata = try await asset.load(.metadata)
    } catch {
      return nil
    }

    var tags: [String: String] = [:]
    for entry in metadata {
      // `try?` flattens here — both loaders already return an optional,
      // so a load failure and an absent value arrive as the same `nil`.
      let attributes = try? await entry.load(.extraAttributes)
      let info = attributes?[.info] as? String
      guard let name = tagName(info: info, key: entry.key as? String),
            replayGainNames.contains(name) else { continue }
      guard let value = try? await entry.load(.stringValue) else { continue }
      tags[name] = value
    }
    return ReplayGainExtractor.rgDataFromTags(tags)
  }

  /// Derive the comparable tag name from a metadata item's info key
  /// [info] and its own key [key]: [info] when it is non-blank, else
  /// [key]; then the segment after the last `.`; then uppercased.
  /// Returns `nil` when neither input yields a usable name.
  static func tagName(info: String?, key: String?) -> String? {
    let source: String
    if let info = info, !info.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      source = info.trimmingCharacters(in: .whitespacesAndNewlines)
    } else if let key = key, !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      source = key.trimmingCharacters(in: .whitespacesAndNewlines)
    } else {
      return nil
    }
    // Reverse-DNS freeform keys carry the tag name in their last
    // segment; ID3 info fields and Vorbis keys carry no `.` at all and
    // pass through whole.
    let name: String
    if let separator = source.lastIndex(of: ".") {
      name = String(source[source.index(after: separator)...])
    } else {
      name = source
    }
    guard !name.isEmpty else { return nil }
    return name.uppercased()
  }

  /// True when [url] names an Opus container. Opus carries its gain in
  /// `OpusHead.output_gain`, which the decoder applies, so a
  /// `REPLAYGAIN_*` tag found alongside it would be applied twice.
  static func isOpusContainer(url: URL) -> Bool {
    let ext = url.pathExtension.lowercased()
    return ext == "opus" || ext == "ogg"
  }
}
