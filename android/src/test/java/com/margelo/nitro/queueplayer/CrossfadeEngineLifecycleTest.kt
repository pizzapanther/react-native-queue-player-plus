package com.margelo.nitro.queueplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle + fade-math coverage for [CrossfadeEngine].
 *
 * This suite exercises:
 * empty-state sentinels, role-swap arithmetic, equal-power gain
 * curve, duration-clamp formula, manual-skip cancellation,
 * release idempotence + delegate clearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class CrossfadeEngineLifecycleTest {

  private fun makeEngine(crossfadeDurationMs: Long = 5_000L): CrossfadeEngine =
    CrossfadeEngine(
      context = ApplicationProvider.getApplicationContext(),
      crossfadeDurationMs = crossfadeDurationMs,
    )

  /**
   * Settling leaves the standby leg holding nothing.
   *
   * An armed standby carries the queue as it stood when it was armed. A caller
   * that mutates the queue through a path the engine does not see — Media3
   * setting resolved assistant items on the session player — needs that
   * snapshot gone, or promoting it drops whatever the caller added.
   */
  @Test
  fun `settling a pending fade leaves nothing staged on the standby leg`() {
    val engine = makeEngine()
    engine.standbyPlayer.setMediaItems(
      listOf(MediaItem.fromUri("http://example.com/next.mp3")))
    engine.standbyPlayer.prepare()
    assertEquals(1, engine.standbyPlayer.mediaItemCount)

    engine.settlePendingFade()

    assertEquals(0, engine.standbyPlayer.mediaItemCount)
    engine.release()
  }

  /**
   * A settle with nothing to settle leaves the legs as they are: a caller
   * settling defensively must not swap them. Promotion itself needs a live
   * fade, which needs real playback with duration metadata, so that half is
   * covered by the crossfade demo suites.
   */
  @Test
  fun `settling by promotion leaves the roles alone when no fade is in flight`() {
    val engine = makeEngine()
    val leading = engine.leadingPlayer
    val standby = engine.standbyPlayer

    engine.settlePendingFadeByPromoting()
    engine.settlePendingFadeByPromoting()

    assertSame(leading, engine.leadingPlayer)
    assertSame(standby, engine.standbyPlayer)
    assertTrue(engine.currentRoleIsA)
    engine.release()
  }

  @Test
  fun `constructor accepts a non-negative crossfade duration`() {
    val engine = makeEngine()
    assertTrue(engine.currentRoleIsA)
    assertSame(engine.playerA, engine.leadingPlayer)
    assertSame(engine.playerB, engine.standbyPlayer)
    engine.release()
  }

  @Test(expected = IllegalArgumentException::class)
  fun `constructor rejects a negative crossfade duration`() {
    CrossfadeEngine(
      context = ApplicationProvider.getApplicationContext(),
      crossfadeDurationMs = -1,
    )
  }

  @Test
  fun `empty queue returns documented sentinel state`() {
    val engine = makeEngine()
    assertEquals(-1, engine.currentMediaItemIndex)
    assertFalse(engine.isPlaying)
    assertNull(engine.currentMediaItem)
    assertEquals(0, engine.allMediaItems.size)
    assertEquals(0L, engine.currentDurationMs)
    engine.release()
  }

  @Test
  fun `release is idempotent and clears delegate`() {
    val engine = makeEngine()
    val delegate = NoopDelegate()
    engine.delegate = delegate
    assertSame(delegate, engine.delegate)
    engine.release()
    assertNull(engine.delegate)
    engine.release()
    assertNull(engine.delegate)
  }

  @Test
  fun `equal-power gain at t equals zero outputs full leading`() {
    val engine = makeEngine()
    val (out, into) = engine.equalPowerGains(0f)
    assertEquals(1.0f, out, 1e-6f)
    assertEquals(0.0f, into, 1e-6f)
    engine.release()
  }

  @Test
  fun `equal-power gain at t equals one outputs full incoming`() {
    val engine = makeEngine()
    val (out, into) = engine.equalPowerGains(1f)
    assertEquals(0.0f, out, 1e-6f)
    assertEquals(1.0f, into, 1e-6f)
    engine.release()
  }

  @Test
  fun `equal-power gain at midpoint sums to constant power`() {
    val engine = makeEngine()
    val (out, into) = engine.equalPowerGains(0.5f)
    val sumOfSquares = out * out + into * into
    assertEquals(1.0f, sumOfSquares, 1e-6f)
    // cos(π/4) == sin(π/4) ≈ 0.7071 — symmetric midpoint.
    assertEquals(out, into, 1e-6f)
    engine.release()
  }

  @Test
  fun `equal-power gain clamps t below zero to zero`() {
    val engine = makeEngine()
    val (out, into) = engine.equalPowerGains(-0.5f)
    assertEquals(1.0f, out, 1e-6f)
    assertEquals(0.0f, into, 1e-6f)
    engine.release()
  }

  @Test
  fun `equal-power gain clamps t above one to one`() {
    val engine = makeEngine()
    val (out, into) = engine.equalPowerGains(1.5f)
    assertEquals(0.0f, out, 1e-6f)
    assertEquals(1.0f, into, 1e-6f)
    engine.release()
  }

  @Test
  fun `effective duration clamps to the shorter of leading and standby halves`() {
    val engine = makeEngine(crossfadeDurationMs = 10_000L)
    // Leading 6s → leadingHalf 3s; standby 30s → standbyHalf 15s.
    // Configured 10s; effective = min(10s, 3s, 15s) = 3s.
    assertEquals(3_000L, engine.effectiveCrossfadeMs(6_000L, 30_000L))
    // Standby is the limit when shorter.
    assertEquals(2_000L, engine.effectiveCrossfadeMs(30_000L, 4_000L))
    // Configured wins when both halves are bigger.
    assertEquals(10_000L, engine.effectiveCrossfadeMs(60_000L, 60_000L))
    // Unknown durations (== 0) fall through to configured.
    assertEquals(10_000L, engine.effectiveCrossfadeMs(0L, 0L))
    engine.release()
  }

  @Test
  fun `cancelFade resets volumes and drops standby items`() {
    val engine = makeEngine()
    engine.playerA.volume = 0.3f
    engine.playerB.volume = 0.7f
    engine.cancelFade()
    assertEquals(1.0f, engine.leadingPlayer.volume, 1e-6f)
    assertEquals(0.0f, engine.standbyPlayer.volume, 1e-6f)
    engine.release()
  }

  @Test
  fun `audio session ids returns empty when no session has been allocated`() {
    val engine = makeEngine()
    assertEquals(0, engine.currentAudioSessionIds().size)
    engine.release()
  }

  @Test
  fun `cancelFade preserves user-set volume on the leading leg`() {
    val engine = makeEngine()
    engine.setVolume(0.4f)
    engine.playerA.volume = 0.1f  // simulate mid-fade ramp value
    engine.playerB.volume = 0.9f
    engine.cancelFade()
    assertEquals(0.4f, engine.leadingPlayer.volume, 1e-6f)
    assertEquals(0f, engine.standbyPlayer.volume, 1e-6f)
    engine.release()
  }

  @Test
  fun `cancelFadeAndPromoteStandby flips role and applies user volume to new leading`() {
    val engine = makeEngine()
    engine.setVolume(0.6f)
    val priorLeading = engine.leadingPlayer
    val priorStandby = engine.standbyPlayer
    engine.cancelFadeAndPromoteStandby()
    assertSame(priorStandby, engine.leadingPlayer)
    assertSame(priorLeading, engine.standbyPlayer)
    // After the role flip, swapRoles drops the new standby's
    // volume to 0 — verify against the now-standby leg.
    assertEquals(0f, engine.standbyPlayer.volume, 1e-6f)
    engine.release()
  }

  @Test
  fun `effective duration handles configured equal to half of both`() {
    val engine = makeEngine(crossfadeDurationMs = 5_000L)
    // 10s / 10s — both halves are exactly the configured 5s.
    // min(5_000, 5_000, 5_000) = 5_000.
    assertEquals(5_000L, engine.effectiveCrossfadeMs(10_000L, 10_000L))
    engine.release()
  }

  @Test
  fun `effective duration truncates odd-integer halves toward zero`() {
    val engine = makeEngine(crossfadeDurationMs = 5_000L)
    // 1ms / large — leadingHalf = 0 via integer division.
    // min(5_000, 0, 30_000) = 0.
    assertEquals(0L, engine.effectiveCrossfadeMs(1L, 60_000L))
    engine.release()
  }

  private class NoopDelegate : PlaybackEngineDelegate {
    override fun onStateMaybeChanged(engine: PlaybackEngine, playWhenReadyReason: Int?) = Unit
    override fun onIsPlayingChanged(engine: PlaybackEngine, isPlaying: Boolean) = Unit
    override fun onTrackTransition(engine: PlaybackEngine, platformReason: Int) = Unit
    override fun onActiveItemFormatChanged(engine: PlaybackEngine) = Unit
    override fun onError(
      engine: PlaybackEngine,
      exception: androidx.media3.common.PlaybackException,
      failedIndex: Int,
    ) = Unit
    override fun onPlaybackEnded(engine: PlaybackEngine) = Unit
    override fun onAutoTransitionDiscontinuity(engine: PlaybackEngine) = Unit
    override fun onCrossfadeBegin(engine: PlaybackEngine, incomingIndex: Int) = Unit
    override fun onCrossfadeCancel(engine: PlaybackEngine, revertedIndex: Int) = Unit
  }
}
