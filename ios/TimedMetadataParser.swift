import AVFoundation
import Foundation

/// Parses only the sample delivered by AVPlayerItemMetadataOutput. Reading
/// asset metadata here would mix static / future values into the timed event.
enum TimedMetadataParser {
  static func parse(_ items: [AVMetadataItem]) -> TimedMetadata? {
    var title: String?
    var artist: String?
    var album: String?
    var artwork: String?
    var streamTitle: String?

    for item in items {
      let key = item.key as? String ?? ""
      let info = item.extraAttributes?[.info] as? String
      let isIcyTitle = key.lowercased() == "streamtitle"
        || item.identifier?.rawValue.lowercased() == "icy/streamtitle"
      let name = (isIcyTitle ? "streamtitle" : (info ?? item.commonKey?.rawValue ?? key)).lowercased()
        .replacingOccurrences(of: "_", with: "")
      // Values in a timed output sample are already loaded. Keep extraction
      // synchronous so async loads cannot reorder samples or outlive a seek.
      switch name {
      case "title", "tit2", "tt2": title = item.stringValue
      case "artist", "tpe1", "tp1": artist = item.stringValue
      case "album", "albumname", "albumtitle", "talb", "tal": album = item.stringValue
      case "streamtitle": streamTitle = item.stringValue
      case "artworkurl", "coverarturl": artwork = item.stringValue
      case "artwork", "apic", "pic":
        if let data = item.dataValue, !data.isEmpty {
          // AVFoundation exposes decoded picture bytes for common artwork.
          if data.starts(with: [0x89, 0x50, 0x4e, 0x47]) {
            artwork = "data:image/png;base64,\(data.base64EncodedString())"
          } else if data.starts(with: [0xff, 0xd8, 0xff]) {
            artwork = "data:image/jpeg;base64,\(data.base64EncodedString())"
          }
        } else if let value = item.stringValue {
          artwork = value
        }
      default: break
      }
    }
    if let streamTitle {
      if let range = streamTitle.range(of: " - ") {
        artist = artist ?? String(streamTitle[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
        title = title ?? String(streamTitle[range.upperBound...]).trimmingCharacters(in: .whitespaces)
      } else {
        title = title ?? streamTitle
      }
    }
    guard title != nil || artist != nil || album != nil || artwork != nil else { return nil }
    return TimedMetadata(title: title, artist: artist, albumTitle: album, artworkUrl: artwork)
  }
}
