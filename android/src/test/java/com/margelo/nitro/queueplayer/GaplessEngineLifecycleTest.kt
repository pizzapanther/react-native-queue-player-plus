package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle smoke coverage for [GaplessEngine] — empty-state
 * sentinels and the idempotent / delegate-clearing release contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class GaplessEngineLifecycleTest {

  private fun makeEngine(): GaplessEngine = GaplessEngine(
    context = ApplicationProvider.getApplicationContext(),
  )

  @Test
  fun `empty queue returns the documented sentinel state`() {
    val engine = makeEngine()
    assertEquals(-1, engine.currentMediaItemIndex)
    assertFalse(engine.isPlaying)
    assertNull(engine.currentMediaItem)
    assertTrue(engine.allMediaItems.isEmpty())
  }

  @Test
  fun `release is idempotent and clears the delegate`() {
    val engine = makeEngine()
    val delegate = object : PlaybackEngineDelegate {
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
    engine.delegate = delegate
    assertNotNull(engine.delegate)

    engine.release()
    assertNull(engine.delegate)

    engine.release()
    assertNull(engine.delegate)
  }
}
