import Foundation

struct RegisteredMedia {
  let sessionId: String
  let fileURL: URL
  let contentType: String
}

/// Thread-safe registry mapping ephemeral 32-hex tokens to local file
/// paths + content types. Tokens are minted by `register`, served by
/// `LocalMediaServer`'s `/media/{token}` handler, and revoked en masse
/// when a cast session ends (`revokeAll(sessionId:)`).
final class MediaTokenRegistry {
  private let lock = NSLock()
  private var entries: [String: RegisteredMedia] = [:]

  func register(sessionId: String, fileURL: URL, contentType: String? = nil) -> String {
    let token = Self.newToken()
    let mime = contentType ?? MimeTypes.fromFile(fileURL)
    lock.lock()
    entries[token] = RegisteredMedia(sessionId: sessionId, fileURL: fileURL, contentType: mime)
    lock.unlock()
    return token
  }

  func resolve(_ token: String) -> RegisteredMedia? {
    lock.lock(); defer { lock.unlock() }
    return entries[token]
  }

  func revokeAll(sessionId: String) {
    lock.lock()
    entries = entries.filter { $0.value.sessionId != sessionId }
    lock.unlock()
  }

  private static func newToken() -> String {
    var bytes = [UInt8](repeating: 0, count: 16)
    let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    if status != errSecSuccess {
      return UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }
    return bytes.map { String(format: "%02x", $0) }.joined()
  }
}
