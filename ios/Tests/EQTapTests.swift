import AVFoundation
import XCTest
@testable import QueuePlayer

/// Skeleton coverage for `EQTap` — tap creation, HLS detection,
/// pass-through process body. The actual tap firing is on the
/// audio render thread.
final class EQTapTests: XCTestCase {

  func testPassThroughCopiesInputToOutput() {
    let inputBuffer = UnsafeMutablePointer<Float>.allocate(capacity: 8)
    let outputBuffer = UnsafeMutablePointer<Float>.allocate(capacity: 8)
    defer {
      inputBuffer.deallocate()
      outputBuffer.deallocate()
    }
    for i in 0..<8 { inputBuffer[i] = Float(i) * 0.5 }
    for i in 0..<8 { outputBuffer[i] = -1 }

    EQTap.passThrough(inputBuffer, outputBuffer, 4, 2, 0, 1)
    for i in 0..<8 {
      XCTAssertEqual(inputBuffer[i], outputBuffer[i], accuracy: 0.0001)
    }
  }

  func testAudioMixReturnsNilForHLSPlaylistURL() {
    let url = URL(string: "https://example.test/stream.m3u8")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    XCTAssertNil(EQTap.audioMix(for: item))
  }

  func testAudioMixReturnsNilForHLSWithQueryString() {
    let url = URL(string: "https://example.test/stream.m3u8?token=abc")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    XCTAssertNil(EQTap.audioMix(for: item))
  }

  func testAudioMixReturnsNilForAssetWithUnloadedTracks() {
    // A fresh AVURLAsset over a remote URL hasn't loaded `tracks` yet;
    // the caller is expected to retry after the KVO fires.
    let url = URL(string: "https://example.test/song.mp3")!
    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    XCTAssertNil(EQTap.audioMix(for: item))
  }
}
