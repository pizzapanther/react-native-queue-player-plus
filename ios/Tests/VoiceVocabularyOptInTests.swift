import XCTest
@testable import QueuePlayer

/// Siri vocabulary donation is off unless the consumer opts in, and the
/// opt-in is fixed once taken.
///
/// The flag stands for the `com.apple.developer.siri` entitlement, which
/// `INVocabulary` checks at runtime and which iOS gives a process no way to
/// read. An entitlement is decided when the app is signed, so a value that
/// changed later could not describe anything — and re-reading one would let a
/// consumer re-arm a path that terminates a host without the capability.
final class VoiceVocabularyOptInTests: XCTestCase {

  func testDonationIsOffUntilTheConsumerOptsIn() {
    let player = TrackPlayer()
    XCTAssertFalse(
      player.voiceVocabularyDonationEnabled,
      "an untouched player has not been given the entitlement to assert")
  }

  func testTheFirstOptInIsTheOneThatCounts() {
    let player = TrackPlayer()
    player.pinVoiceVocabularyDonation(true)
    player.pinVoiceVocabularyDonation(false)
    XCTAssertTrue(
      player.voiceVocabularyDonationEnabled,
      "a later value does not unset an opt-in already taken")
  }

  func testDecliningIsAlsoFixed() {
    let player = TrackPlayer()
    player.pinVoiceVocabularyDonation(false)
    player.pinVoiceVocabularyDonation(true)
    XCTAssertFalse(
      player.voiceVocabularyDonationEnabled,
      "a later value does not turn donation on after it was declined")
  }
}
