import Foundation

enum MimeTypes {
  static let `default` = "application/octet-stream"

  private static let extensionMap: [String: String] = [
    "mp3": "audio/mpeg",
    "m4a": "audio/mp4",
    "aac": "audio/aac",
    "flac": "audio/flac",
    "ogg": "audio/ogg",
    "oga": "audio/ogg",
    "opus": "audio/ogg",
    "wav": "audio/x-wav",
    "wave": "audio/x-wav",
  ]

  static func fromExtension(_ ext: String?) -> String {
    guard let ext = ext?.lowercased(), !ext.isEmpty else { return `default` }
    return extensionMap[ext] ?? `default`
  }

  static func fromFile(_ url: URL) -> String {
    fromExtension(url.pathExtension)
  }
}
