// Siri vocabulary donation bridge.
//
// `donateVoiceVocabulary` seeds Siri's NLU with the consumer's
// catalog via `INVocabulary.setVocabularyStrings`, so utterances like
// "Hey Siri play Pearl Jam on MyApp" map cleanly to the right artist /
// playlist strings when Siri parses the user's free-text search term.
// Fire-and-forget; failures log via OSLog but never surface to the
// consumer — Siri's NLU degrades gracefully without the donation.

import Foundation
import Intents
import OSLog

@available(iOS 13.0, *)
enum VoiceDonation {
  private static let logger = Logger(subsystem: "com.queueplayer.lib", category: "SiriDonation")

  /// Whether the host app has declared Siri support: the lib's config plugin
  /// (`react-native-queue-player/app.plugin.js`) sets `NSSiriUsageDescription`
  /// in Info.plist when the consumer opts in with `{ siri: true }`.
  ///
  /// The second of two gates, and the weaker one. `PlayerConfig`'s
  /// `voiceVocabularyDonationEnabled` is the first: it is the consumer
  /// asserting the `com.apple.developer.siri` entitlement is on the target,
  /// which is the requirement `INVocabulary` actually checks — it throws
  /// `NSInternalInconsistencyException` without it, and Swift cannot catch
  /// that. iOS exposes no API for a process to read its own entitlements, so
  /// no gate here can verify the assertion. This one catches the narrower
  /// misconfiguration of opting in without the usage description.
  private static var hostDeclaresSiri: Bool {
    return Bundle.main.object(forInfoDictionaryKey: "NSSiriUsageDescription") != nil
  }

  static func donateVoiceVocabulary(_ vocabulary: VoiceVocabulary) {
    guard hostDeclaresSiri else {
      logger.info("Skipping vocabulary donation — Siri not configured (Info.plist missing NSSiriUsageDescription)")
      return
    }
    // `setVocabularyStrings` is documented expensive — Apple recommends
    // running it off the main / Hybrid thread + at most once per
    // library refresh. Capture by value into the background closure.
    let artistList = vocabulary.artists
    let playlistList = vocabulary.playlists
    DispatchQueue.global(qos: .utility).async {
      let store = INVocabulary.shared()
      let artists = NSOrderedSet(array: artistList)
      let playlists = NSOrderedSet(array: playlistList)
      store.setVocabularyStrings(artists, of: .mediaMusicArtistName)
      store.setVocabularyStrings(playlists, of: .mediaPlaylistTitle)
      logger.info(
        "Donated vocabulary: artists=\(artistList.count) playlists=\(playlistList.count)"
      )
    }
  }
}
