package com.margelo.nitro.queueplayer

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * integration tests for the canSkip surface
 * + onSkipCapabilityChange listener emission. Pairs with the
 * pure-helper coverage in [QueueSkipArithmeticTest] (canSkipNext /
 * canSkipPrevious) — these tests cover the [TrackPlayer] wiring:
 * cache, dedup, hook-point coverage, auto-fire on subscribe,
 * final emit on destroy.
 *
 * `canSkipPrevious` is symmetric with `canSkipNext` — defined as
 * "is there a previous track to skip to under the current repeat
 * mode". Apps that want the Apple-Music-style "tap Previous restarts
 * current at >3s" UX implement that themselves in their button
 * handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerSkipCapabilityTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun track(id: String) = TrackItem(
    id = id, url = "https://example.com/$id.m4a",
    title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null
  )

  /** Drain main-looper posts so the auto-fire-on-subscribe lands. */
  private fun flushMain() {
    shadowOf(Looper.getMainLooper()).idle()
  }

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

  // --- Initial state

  @Test
  fun `initial state — empty queue gives false-false on the getters`() {
    assertEquals(false, player.getCanSkipPrevious())
    assertEquals(false, player.getCanSkipNext())
  }

  // --- Auto-fire on subscribe

  @Test
  fun `onSkipCapabilityChange fires once on subscribe with cached values`() {
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    assertEquals(1, emissions.size)
    assertEquals(SkipCapability(canSkipPrevious = false, canSkipNext = false), emissions[0])
  }

  @Test
  fun `onSkipCapabilityChange auto-fire reflects post-mutation state`() {
    // Mutate first, then subscribe — auto-fire should give the
    // current cached values, not the initial (false, false).
    player.setQueueInternal((1..5).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    assertEquals(1, emissions.size)
    // 5-track queue at cur=0 OFF → canSkipPrevious=false (no prev),
    // canSkipNext=true (has next).
    assertEquals(
      SkipCapability(canSkipPrevious = false, canSkipNext = true),
      emissions[0]
    )
  }

  // --- Mutation hooks

  @Test
  fun `setQueueInternal multi-track at index 0 emits (false, true) under OFF`() {
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.setQueueInternal((1..5).map { track("t$it") })
    // 5 tracks, cur=0, OFF → no prev (idx 0), has next (5 tracks).
    assertEquals(
      listOf(SkipCapability(canSkipPrevious = false, canSkipNext = true)),
      emissions
    )
  }

  @Test
  fun `setQueueInternal single-track emits (false, false) under OFF`() {
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.setQueueInternal(listOf(track("only")))
    // Single track, cur=0, OFF → no prev (idx 0), no next (only one).
    // Tuple (false, false) matches the initial state → dedup skips.
    assertEquals(emptyList<SkipCapability>(), emissions)
  }

  @Test
  fun `setQueueInternal empty after non-empty emits false-false`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.setQueueInternal(emptyList())
    assertEquals(listOf(SkipCapability(canSkipPrevious = false, canSkipNext = false)), emissions)
  }

  @Test
  fun `clearQueueInternal emits false-false`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.clearQueueInternal()
    assertEquals(listOf(SkipCapability(canSkipPrevious = false, canSkipNext = false)), emissions)
  }

  @Test
  fun `addToQueueInternal into empty queue stays false-false (cur stays -1)`() {
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.addToQueueInternal((1..3).map { track("t$it") }, insertBefore = null)
    // add-into-empty leaves cur = -1 (consumer calls skipToIndex(0)
    // explicitly to start playback). With cur = -1 both bools stay
    // false, so dedup skips the emit.
    assertEquals(emptyList<SkipCapability>(), emissions)
  }

  @Test
  fun `removeFromQueueInternal down to the pinned current emits false-false`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    // cur=0 is pinned, so removing every index keeps the current track
    // as the only entry — a single-track queue has no prev/next.
    player.removeFromQueueInternal(listOf(0, 1, 2))
    assertEquals(listOf(SkipCapability(canSkipPrevious = false, canSkipNext = false)), emissions)
  }

  // --- Skip mutations

  @Test
  fun `skipToIndexInternal updates currentTrackIndex and recomputes`() {
    player.setQueueInternal((1..5).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    // Move to last index (4). Under OFF, canSkipNext flips true →
    // false, canSkipPrevious stays true (idx 4 has prev under OFF).
    player.skipToIndexInternal(4)
    assertEquals(listOf(SkipCapability(canSkipPrevious = true, canSkipNext = false)), emissions)
  }

  @Test
  fun `skipToPreviousInternal with multi-track in OFF goes to prev`() {
    // From cur=2 in OFF, computePrevious returns 1. skipToPrevious
    // applies the index update via applyNewCurrentIndex.
    player.setQueueInternal((1..5).map { track("t$it") })
    player.skipToIndexInternal(2)
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.skipToPreviousInternal()
    // currentTrackIndex moved from 2 → 1; both bools stay (true, true)
    // so dedup skips emission. The real assert: currentTrackIndex
    // updated.
    assertEquals(1, player.currentTrackIndex)
  }

  @Test
  fun `skipToPreviousInternal under TRACK repeat goes to the previous track`() {
    player.setQueueInternal((1..5).map { track("t$it") })
    player.skipToIndexInternal(2)
    player.repeatModeState = RepeatMode.TRACK
    player.skipToPreviousInternal()
    assertEquals(1, player.currentTrackIndex)
  }

  // --- Repeat-mode flips

  @Test
  fun `setRepeatMode QUEUE flips canSkipNext at last-track boundary`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    player.skipToIndexInternal(2) // last index, OFF → canSkipNext false
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.setRepeatModeInternal(RepeatMode.QUEUE)
    // Wraps → canSkipNext flips false → true.
    assertEquals(listOf(SkipCapability(canSkipPrevious = true, canSkipNext = true)), emissions)

    player.setRepeatModeInternal(RepeatMode.OFF)
    // Reverses.
    assertEquals(listOf(SkipCapability(canSkipPrevious = true, canSkipNext = true), SkipCapability(canSkipPrevious = true, canSkipNext = false)), emissions)
  }

  @Test
  fun `setRepeatMode TRACK on last track keeps canSkipNext false (no wrap like OFF)`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    player.skipToIndexInternal(2) // OFF, last → (true, false)
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.setRepeatModeInternal(RepeatMode.TRACK)
    // TRACK shares OFF's no-wrap capability, so the (true, false)
    // tuple at the last track is unchanged → dedup, no emission.
    assertEquals(emptyList<SkipCapability>(), emissions)
  }

  // --- Dedup

  @Test
  fun `redundant setRepeatMode does not re-emit (dedup)`() {
    player.setQueueInternal((1..5).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    // Setting the same value again should not emit.
    player.setRepeatModeInternal(RepeatMode.OFF) // already OFF
    player.setRepeatModeInternal(RepeatMode.OFF)
    assertTrue("expected no emissions, got $emissions", emissions.isEmpty())
  }

  // --- Multi-subscriber

  @Test
  fun `each subscriber gets its own auto-fire`() {
    player.setQueueInternal((1..3).map { track("t$it") })

    val a = mutableListOf<SkipCapability>()
    val b = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> a.add(cap) }
    player.onSkipCapabilityChange { cap -> b.add(cap) }
    flushMain()

    assertEquals(1, a.size)
    assertEquals(1, b.size)
    // 3-track queue at cur=0 OFF → no prev (idx 0), has next.
    val expected = SkipCapability(canSkipPrevious = false, canSkipNext = true)
    assertEquals(expected, a[0])
    assertEquals(expected, b[0])
  }

  // --- Destroy emits final (false, false)

  @Test
  fun `destroyInternal emits a final false-false`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.destroyInternal()
    // The configure-rebuild reset path inside destroy also calls
    // recompute (separate emit point); the final state is (false,
    // false) regardless. Assert the LAST emission is (false, false)
    // so an extra dedup-skipped intermediate doesn't break the test.
    assertTrue("expected at least one emission", emissions.isNotEmpty())
    assertEquals(SkipCapability(canSkipPrevious = false, canSkipNext = false), emissions.last())

    // Re-init player so @After's destroyInternal succeeds idempotently.
    serviceHandle.destroy()
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
  }

  // --- skipToPreviousBehavior = restart-or-previous forces canSkipPrevious true

  private fun configureRestart() {
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = SkipToPreviousBehavior.RESTART_OR_PREVIOUS), context()
    )
  }

  @Test
  fun `restart mode — addToQueueInternal into empty queue keeps Previous false (cur stays -1)`() {
    configureRestart()
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.addToQueueInternal((1..3).map { track("t$it") }, insertBefore = null)
    // No loaded track: nothing to restart and nothing before it, so the
    // lock-screen Previous control stays disabled until skipToIndex.
    assertEquals(false, player.getCanSkipPrevious())
    assertEquals(emptyList<SkipCapability>(), emissions)
  }

  @Test
  fun `restart mode forces canSkipPrevious true at index 0`() {
    configureRestart()
    player.setQueueInternal((1..5).map { track("t$it") })
    // At index 0 under OFF the default mode reports false (no previous);
    // restart mode forces it true so Previous never greys out.
    assertEquals(true, player.getCanSkipPrevious())
    assertEquals(true, player.getCanSkipNext())
  }

  @Test
  fun `default previous mode leaves canSkipPrevious false at index 0`() {
    // setUp configured skipToPreviousBehavior = null (= 'previous').
    player.setQueueInternal((1..5).map { track("t$it") })
    assertEquals(false, player.getCanSkipPrevious())
  }

  @Test
  fun `restart mode emits canSkipPrevious true via onSkipCapabilityChange`() {
    configureRestart()
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain() // auto-fire: empty queue → (false, false)
    player.setQueueInternal((1..5).map { track("t$it") })
    flushMain()
    assertTrue(
      "restart mode makes canSkipPrevious true once a track is loaded",
      emissions.last().canSkipPrevious
    )
  }

  // --- cast index writes keep the capability current

  @Test
  fun `emitRemoteTrackChange to the last index disables Next`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    assertEquals(true, player.getCanSkipNext())

    // The receiver advanced to the last track; the lock-screen Next
    // control must grey out exactly as it does for a local advance.
    player.emitRemoteTrackChange(2)
    assertEquals(2, player.currentTrackIndex)
    assertEquals(false, player.getCanSkipNext())
    assertEquals(true, player.getCanSkipPrevious())
  }

  @Test
  fun `emitRemoteTrackChange emits the capability change`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    val emissions = mutableListOf<SkipCapability>()
    player.onSkipCapabilityChange { cap -> emissions.add(cap) }
    flushMain()
    emissions.clear()

    player.emitRemoteTrackChange(2)
    flushMain()
    assertEquals(
      listOf(SkipCapability(canSkipPrevious = true, canSkipNext = false)),
      emissions
    )
  }

  @Test
  fun `emitRemoteTrackChange classifies the new track's source`() {
    // A local file at index 2, streaming tracks elsewhere: the source the
    // JS side reads must follow the index the receiver moved to.
    val local = TrackItem(
      id = "local", url = "file:///tmp/local.m4a",
      title = null, artist = null, album = null,
      duration = null, artworkUrl = null, extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null
    )
    player.setQueueInternal(listOf(track("t1"), track("t2"), local))
    assertEquals(TrackSource.STREAMING, player.getCurrentTrackSource())

    player.emitRemoteTrackChange(2)
    assertEquals(TrackSource.LOCAL, player.getCurrentTrackSource())
  }

  @Test
  fun `resumeLocalAfterCast lands on the handback index with its capability and source`() {
    val local = TrackItem(
      id = "local", url = "file:///tmp/local.m4a",
      title = null, artist = null, album = null,
      duration = null, artworkUrl = null, extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null
    )
    player.setQueueInternal(listOf(track("t1"), track("t2"), local))
    assertEquals(true, player.getCanSkipNext())
    assertEquals(TrackSource.STREAMING, player.getCurrentTrackSource())

    player.resumeLocalAfterCast(trackIndex = 2, positionMs = 0L)
    flushMain()
    assertEquals(2, player.currentTrackIndex)
    assertEquals(false, player.getCanSkipNext())
    assertEquals(TrackSource.LOCAL, player.getCurrentTrackSource())
  }
}
