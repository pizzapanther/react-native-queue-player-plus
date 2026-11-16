import Foundation

/// Decides whether an HTTP response that claims to carry a track can be
/// believed, from its `Content-Type` and the first bytes of its body.
///
/// A server that answers an API-level failure with `200` and an XML or JSON
/// envelope is the case this exists for: media servers commonly report
/// errors that way by design, and a proxy answering with an HTML page does the
/// same. Such a body passes every length and status check, so without this it
/// is committed to the cache as a complete track and served from disk on every
/// later play — the track stays broken after the server is well again.
///
/// The rules identify what is **not** media rather than demanding proof of what
/// is. The two mistakes are not equal: a false rejection costs a prefetch and
/// the track still plays from its origin, while a false acceptance breaks the
/// track until the cache is cleared by hand.
///
/// Mirrored rule for rule by `MediaResponseCheck.kt` on Android.
public enum MediaResponseCheck {

  /// Why a response was refused. Each case carries what was judged so a
  /// caller's log names the response rather than just the verdict.
  public enum Rejection: Equatable {
    /// The declared type is a markup or JSON document, which no audio
    /// container is.
    case markupContentType(String)
    /// The declared type is text of some other kind and the body carries no
    /// recognised container signature. `text/plain` is not refused on the
    /// label alone — media servers do mislabel audio — so the body is given
    /// the chance to prove itself.
    case textWithoutMediaSignature(String)
    /// No usable declared type, and the body begins as a markup or JSON
    /// document.
    case markupBody
  }

  /// Types that are documents, never audio. Narrow on purpose:
  /// `application/octet-stream` and `text/plain` are missing because servers
  /// serve real audio under both.
  private static let markupTypes: Set<String> = [
    "text/html", "application/xhtml+xml",
    "application/xml", "text/xml",
    "application/json", "text/json",
  ]

  /// The verdict for a response, or nil to accept it.
  ///
  /// `head` is the first bytes of the body — 64 is plenty for every signature
  /// below. `headIsStartOfResource` is false when the request carried a
  /// `Range`, and then only the declared type is judged: a body that begins
  /// part way into a file says nothing about the file's first bytes.
  public static func rejection(
    contentType: String?, head: Data, headIsStartOfResource: Bool
  ) -> Rejection? {
    let declared = normalised(contentType)
    if let declared, markupTypes.contains(declared) {
      return .markupContentType(declared)
    }
    guard headIsStartOfResource else { return nil }
    let body = droppingByteOrderMark(head)
    if let declared, declared.hasPrefix("text/") {
      return hasMediaSignature(body) ? nil : .textWithoutMediaSignature(declared)
    }
    return beginsAsMarkup(body) ? .markupBody : nil
  }

  /// Lowercased type with any `; charset=…` parameter and surrounding space
  /// removed. Nil for a missing or empty header.
  private static func normalised(_ contentType: String?) -> String? {
    guard let raw = contentType, !raw.isEmpty else { return nil }
    let head = raw.prefix(while: { $0 != ";" })
    let trimmed = String(head).trimmingCharacters(in: .whitespaces).lowercased()
    return trimmed.isEmpty ? nil : trimmed
  }

  /// Drop a byte-order mark so the checks below read the first real byte. A
  /// UTF-16 mark matters twice over: `FF FE` also satisfies the MPEG frame
  /// sync pattern, so a UTF-16 document would otherwise read as audio.
  private static func droppingByteOrderMark(_ head: Data) -> Data {
    let bytes = [UInt8](head)
    if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF {
      return head.dropFirst(3)
    }
    if bytes.count >= 2,
       (bytes[0] == 0xFF && bytes[1] == 0xFE) || (bytes[0] == 0xFE && bytes[1] == 0xFF) {
      return head.dropFirst(2)
    }
    return head
  }

  /// Whether the body opens with the magic bytes of a container either
  /// platform plays, or with an HLS playlist's own first line.
  private static func hasMediaSignature(_ body: Data) -> Bool {
    let bytes = [UInt8](body)
    guard !bytes.isEmpty else { return false }
    // MPEG audio frame sync: eleven set bits across the first two bytes. ADTS
    // AAC shares it.
    if bytes.count >= 2, bytes[0] == 0xFF, bytes[1] & 0xE0 == 0xE0 { return true }
    let prefixes = ["ID3", "fLaC", "OggS", "RIFF", "FORM", "caff", "#!AMR", "#EXTM3U"]
    if prefixes.contains(where: { starts(bytes, with: $0) }) { return true }
    // ISO base media (MP4 / M4A / ALAC): a box length, then the type.
    if bytes.count >= 8, starts(Array(bytes.dropFirst(4)), with: "ftyp") { return true }
    return false
  }

  /// Whether the body opens as an XML / HTML document or a JSON value. No
  /// container above begins with either character.
  private static func beginsAsMarkup(_ body: Data) -> Bool {
    guard let first = [UInt8](body).first(where: { !isAsciiSpace($0) }) else { return false }
    return first == UInt8(ascii: "<") || first == UInt8(ascii: "{")
  }

  private static func isAsciiSpace(_ byte: UInt8) -> Bool {
    byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
  }

  private static func starts(_ bytes: [UInt8], with ascii: String) -> Bool {
    let pattern = [UInt8](ascii.utf8)
    guard bytes.count >= pattern.count else { return false }
    return Array(bytes.prefix(pattern.count)) == pattern
  }
}
