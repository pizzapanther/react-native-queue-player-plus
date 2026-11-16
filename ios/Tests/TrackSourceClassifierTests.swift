import XCTest
@testable import QueuePlayer

/// `TrackPlayer.classifyTrackSource` decides how the active track is being
/// played from lib-observable facts only: the URL scheme, whether the item is
/// routed through the read-through server, and a cache-hit probe. Pure +
/// static, so it's exercised directly without a live engine.
final class TrackSourceClassifierTests: XCTestCase {

  private func classify(
    _ url: String?, routed: Bool = true, cached: Bool = false
  ) -> TrackSource? {
    TrackPlayer.classifyTrackSource(url: url, isRouted: routed) { _ in cached }
  }

  func testNilUrlIsUnclassified() {
    XCTAssertNil(classify(nil))
  }

  func testFileUrlIsLocal() {
    XCTAssertEqual(classify("file:///var/mobile/track.m4a"), .local)
  }

  func testCachedHttpsIsCached() {
    XCTAssertEqual(classify("https://example.com/track.m4a", cached: true), .cached)
  }

  func testUncachedHttpsIsStreaming() {
    XCTAssertEqual(classify("https://example.com/track.m4a", cached: false), .streaming)
  }

  func testCachedHttpIsCached() {
    XCTAssertEqual(classify("http://example.com/track.mp3", cached: true), .cached)
  }

  func testUnknownSchemeIsUnclassified() {
    // Neither file:// nor http(s):// — the lib has no source contract to honour.
    XCTAssertNil(classify("data:audio/mp4;base64,AAAA"))
    XCTAssertNil(classify("ftp://example.com/track.m4a"))
    XCTAssertNil(classify("track.m4a"))
  }

  func testLocalFileNeverConsultsTheCacheProbe() {
    var probed = false
    let source = TrackPlayer.classifyTrackSource(url: "file:///a.m4a", isRouted: true) { _ in
      probed = true
      return true
    }
    XCTAssertEqual(source, .local)
    XCTAssertFalse(probed, "a file:// URL is local without a cache lookup")
  }

  /// Cached on disk is not the same as played from disk. Only an item bound to
  /// the read-through server reads the cache, so a complete file the player is
  /// not routed through is still being streamed — reporting otherwise is what
  /// made this field claim `cached` while every byte came from the origin.
  func testACachedTrackThePlayerIsNotRoutedThroughIsStreaming() {
    XCTAssertEqual(
      classify("https://example.com/a.mp3", routed: false, cached: true), .streaming)
  }

  func testARoutedTrackTheCacheHoldsInFullIsCached() {
    XCTAssertEqual(
      classify("https://example.com/a.mp3", routed: true, cached: true), .cached)
  }

  func testARoutedTrackTheCacheDoesNotHoldIsStreaming() {
    XCTAssertEqual(
      classify("https://example.com/a.mp3", routed: true, cached: false), .streaming)
  }

  /// A local file is local whatever the server and cache report.
  func testAFileTrackIsLocalRegardlessOfRouting() {
    XCTAssertEqual(classify("file:///a.m4a", routed: false, cached: true), .local)
  }
}
