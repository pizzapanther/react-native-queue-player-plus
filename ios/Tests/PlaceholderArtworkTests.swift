import XCTest
@testable import QueuePlayer
import UIKit

/// `PlaceholderArtwork` swaps in a consumer-supplied cover-art placeholder
/// (`PlayerConfig.placeholderArtworkUri`) in place of the bundled RNQP
/// image. Only a local `file://` image is honoured; a remote or unset URI,
/// or an unloadable file, keeps the bundled placeholder.
@MainActor
final class PlaceholderArtworkTests: XCTestCase {

  override func tearDown() {
    // Process-wide singleton — reset so a set custom image does not leak
    // into other suites that read the bundled placeholder.
    PlaceholderArtwork.setCustom(uri: nil)
    super.tearDown()
  }

  func testCustomFileUriReplacesTheBundledPlaceholder() throws {
    let bundled = PlaceholderArtwork.image
    let tmp = FileManager.default.temporaryDirectory
      .appendingPathComponent("rnqp-placeholder-\(UUID().uuidString).jpg")
    try makeJpegBytes().write(to: tmp)
    defer { try? FileManager.default.removeItem(at: tmp) }

    PlaceholderArtwork.setCustom(uri: tmp.absoluteString)

    let image = PlaceholderArtwork.image
    XCTAssertFalse(image === bundled,
                   "a loadable file:// image replaces the bundled placeholder")
    XCTAssertTrue(image === PlaceholderArtwork.image,
                  "the custom image is stable across reads")
  }

  func testUnsetUriKeepsTheBundledPlaceholder() {
    let bundled = PlaceholderArtwork.image
    PlaceholderArtwork.setCustom(uri: nil)
    XCTAssertTrue(PlaceholderArtwork.image === bundled)
  }

  func testNonFileUriIsRejectedAndKeepsTheBundledPlaceholder() {
    let bundled = PlaceholderArtwork.image
    PlaceholderArtwork.setCustom(uri: "https://example.com/logo.png")
    XCTAssertTrue(PlaceholderArtwork.image === bundled,
                  "a remote URI is rejected — offline-only file:// contract")
  }

  func testUnloadableFileUriFallsBackToTheBundledPlaceholder() {
    let bundled = PlaceholderArtwork.image
    PlaceholderArtwork.setCustom(uri: "file:///nonexistent/path/missing.jpg")
    XCTAssertTrue(PlaceholderArtwork.image === bundled)
  }

  func testEmptyUriKeepsTheBundledPlaceholder() {
    let bundled = PlaceholderArtwork.image
    PlaceholderArtwork.setCustom(uri: "")
    XCTAssertTrue(PlaceholderArtwork.image === bundled)
  }

  func testUppercaseFileSchemeIsHonoured() throws {
    let bundled = PlaceholderArtwork.image
    let tmp = FileManager.default.temporaryDirectory
      .appendingPathComponent("rnqp-placeholder-\(UUID().uuidString).jpg")
    try makeJpegBytes().write(to: tmp)
    defer { try? FileManager.default.removeItem(at: tmp) }

    PlaceholderArtwork.setCustom(uri: "FILE://" + tmp.path)

    XCTAssertFalse(PlaceholderArtwork.image === bundled,
                   "an uppercase FILE:// scheme is honoured (case-insensitive)")
  }

  private func makeJpegBytes() -> Data {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1))
    let image = renderer.image { ctx in
      UIColor.blue.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
    return image.jpegData(compressionQuality: 1.0) ?? Data()
  }
}
