package com.margelo.nitro.queueplayer

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * audio focus + becoming-noisy + wake lock.
 *
 * Media3 does the heavy lifting — `setAudioAttributes(attrs,
 * handleAudioFocus = true)` + `setHandleAudioBecomingNoisy(true)`
 * + `setWakeMode(WAKE_MODE_NETWORK)`. Tests verify:
 *
 * 1. configure applies the expected audio attributes.
 * 2. The PLAY_WHEN_READY_CHANGE_REASON → StateChangeReason
 *    translation is correct for every reason value.
 * 3. The manifest ships the WAKE_LOCK permission (verified
 *    structurally — the Robolectric PackageManager exposes it
 *    via the declared manifest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerAudioFocusTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- Audio attributes applied on configure

  @Test
  fun `configure installs MEDIA usage + MUSIC content-type audio attributes`() {
    val attrs = player.player!!.audioAttributes
    assertEquals(
      "USAGE_MEDIA — required for consumer music apps under Android O+ audio-focus rules",
      C.USAGE_MEDIA, attrs.usage
    )
    assertEquals(
      "AUDIO_CONTENT_TYPE_MUSIC — routes correctly for duck-on-nav-voice interactions",
      C.AUDIO_CONTENT_TYPE_MUSIC, attrs.contentType
    )
  }

  // --- PLAY_WHEN_READY_CHANGE_REASON translation

  @Test
  fun `mapPlayWhenReadyReason AUDIO_FOCUS_LOSS maps to INTERRUPTION`() {
    assertEquals(
      StateChangeReason.INTERRUPTION,
      player.mapPlayWhenReadyReason(
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
      )
    )
  }

  @Test
  fun `mapPlayWhenReadyReason AUDIO_BECOMING_NOISY maps to ROUTE_CHANGE`() {
    // Headphones unplugged / BT disconnected — Apple-HIG
    // equivalent on Android, never blast to the speaker.
    assertEquals(
      StateChangeReason.ROUTE_CHANGE,
      player.mapPlayWhenReadyReason(
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
      )
    )
  }

  @Test
  fun `mapPlayWhenReadyReason END_OF_MEDIA_ITEM maps to SYSTEM`() {
    // End-of-queue; maybeEmitQueueEnd will fire QUEUE_END as the
    // terminal state, so the in-transit reason is SYSTEM (not
    // QUEUE_END — that would mis-fire on buffering pauses before
    // the terminal state lands).
    assertEquals(
      StateChangeReason.SYSTEM,
      player.mapPlayWhenReadyReason(
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
      )
    )
  }

  @Test
  fun `mapPlayWhenReadyReason USER_REQUEST maps to USER`() {
    // Canonical user intent. Unconditionally overwriting the
    // stash on every playWhenReady change is load-bearing:
    // a stale INTERRUPTION from an earlier focus loss would
    // otherwise mislabel the subsequent USER-initiated resume.
    assertEquals(
      StateChangeReason.USER,
      player.mapPlayWhenReadyReason(
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
      )
    )
  }

  @Test
  fun `mapPlayWhenReadyReason REMOTE maps to SYSTEM`() {
    // SYSTEM is the neutral non-user default, chosen over null so
    // a stale stash cannot leak through.
    assertEquals(
      StateChangeReason.SYSTEM,
      player.mapPlayWhenReadyReason(
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
      )
    )
  }

  @Test
  fun `mapPlayWhenReadyReason unknown reason falls back to SYSTEM`() {
    // Guards against Media3 adding new reason values in future
    // versions — the fallback keeps consumers from seeing a
    // stale reason leak through an unrecognised transition.
    assertEquals(
      StateChangeReason.SYSTEM,
      player.mapPlayWhenReadyReason(-999)
    )
  }

  // --- Manifest / WAKE_LOCK permission

  @Test
  fun `library manifest declares WAKE_LOCK permission`() {
    val pm = context().packageManager
    val packageName = context().packageName
    val pkgInfo = pm.getPackageInfo(
      packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS
    )
    val requested = pkgInfo.requestedPermissions?.toList().orEmpty()
    assertTrue(
      "WAKE_LOCK is required by setWakeMode(WAKE_MODE_NETWORK) — the library manifest must declare it",
      requested.contains(android.Manifest.permission.WAKE_LOCK)
    )
  }

  // --- destroyInternal teardown

  @Test
  fun `destroyInternal releases the player cleanly after audio configuration`() {
    // Confirms the audio-attributes / wake-mode / noisy-handling
    // configuration doesn't block release. Before 1.22 the player
    // held no resources beyond the MediaSource.Factory; after, it
    // holds an AudioFocus request + a BroadcastReceiver + a
    // WakeLock. Media3 is expected to release all three on
    // player.release() — this test is the first-class
    // regression check.
    assertNotNull(player.player)
    player.destroyInternal()
    assertNull(player.player)
  }
}
