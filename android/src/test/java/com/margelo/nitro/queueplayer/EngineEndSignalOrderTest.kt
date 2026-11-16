package com.margelo.nitro.queueplayer

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When a player reaches STATE_ENDED the engines hand the delegate the end
 * signal before the generic state callback, so the queue-end path owns the
 * ENDED emit and its reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class EngineEndSignalOrderTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private class OrderingDelegate : PlaybackEngineDelegate {
    val calls = mutableListOf<String>()
    override fun onStateMaybeChanged(engine: PlaybackEngine, playWhenReadyReason: Int?) { calls.add("state") }
    override fun onPlaybackEnded(engine: PlaybackEngine) { calls.add("ended") }
    override fun onIsPlayingChanged(engine: PlaybackEngine, isPlaying: Boolean) {}
    override fun onTrackTransition(engine: PlaybackEngine, platformReason: Int) {}
    override fun onActiveItemFormatChanged(engine: PlaybackEngine) {}
    override fun onError(engine: PlaybackEngine, exception: PlaybackException, failedIndex: Int) {}
    override fun onAutoTransitionDiscontinuity(engine: PlaybackEngine) {}
    override fun onCrossfadeBegin(engine: PlaybackEngine, incomingIndex: Int) {}
    override fun onCrossfadeCancel(engine: PlaybackEngine, revertedIndex: Int) {}
  }

  /** An empty playlist prepared is STATE_ENDED at once. */
  private fun assertEndedBeforeState(engine: PlaybackEngine, delegate: OrderingDelegate) {
    engine.setMediaItems(emptyList(), 0, 0L)
    shadowOf(android.os.Looper.getMainLooper()).idle()
    val ended = delegate.calls.indexOf("ended")
    val stateAfter = delegate.calls.withIndex().any { it.value == "state" && it.index > ended }
    assertEquals("the end signal must be delivered", true, ended >= 0)
    assertEquals(
      "the generic state callback follows the end signal: ${delegate.calls}",
      true, stateAfter)
    assertEquals(
      "no state callback precedes the end signal for this transition: ${delegate.calls}",
      -1, delegate.calls.subList(0, ended).lastIndexOf("state"))
    engine.release()
  }

  @Test
  fun `gapless reports the end before the state`() {
    val delegate = OrderingDelegate()
    val engine = GaplessEngine(context = context)
    engine.delegate = delegate
    assertEndedBeforeState(engine, delegate)
  }

  @Test
  fun `crossfade reports the end before the state`() {
    val delegate = OrderingDelegate()
    val engine = CrossfadeEngine(context = context, crossfadeDurationMs = 3_000L)
    engine.delegate = delegate
    assertEndedBeforeState(engine, delegate)
  }
}
