package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.margelo.nitro.core.ArrayBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wiring coverage for the [Visualizer] HybridObject — subscriber
 * refcount, attach/detach lifecycle against a [PlaybackEngine],
 * engine swap behaviour. This test exercises only the Hybrid surface
 * and the PlaybackEngine attach contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class VisualizerKtTest {

  @After
  fun clearStaticReference() {
    Visualizer.current?.unwireFromPlaybackEngine()
    Visualizer.current = null
  }

  private fun makeViz(): Visualizer = Visualizer()

  private fun makeConfig(): VisualizerConfig =
    VisualizerConfig(fftSize = 256.0, updateIntervalMs = 16.0, includeSamples = false)

  @Test
  fun `getSubscriberCount tracks subscribe and dispose`() {
    val viz = makeViz()
    assertEquals(0.0, viz.getSubscriberCount(), 0.0)
    val d1 = viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(1.0, viz.getSubscriberCount(), 0.0)
    val d2 = viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(2.0, viz.getSubscriberCount(), 0.0)
    d1()
    assertEquals(1.0, viz.getSubscriberCount(), 0.0)
    d2()
    assertEquals(0.0, viz.getSubscriberCount(), 0.0)
  }

  @Test
  fun `dispose is idempotent and does not double-detach from the engine`() {
    val viz = makeViz()
    val engine = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(engine)
    val d = viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(1.0, viz.getSubscriberCount(), 0.0)
    assertEquals(1, engine.attachCount)
    d()
    d() // second call is a no-op
    assertEquals(0.0, viz.getSubscriberCount(), 0.0)
    assertEquals("detach must fire exactly once for one subscription", 1, engine.detachCount)
  }

  @Test
  fun `subscribe before wire queues the processor and attaches on wire`() {
    val viz = makeViz()
    viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    val engine = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(engine)
    assertEquals(1, engine.attachedSinks.size)
  }

  @Test
  fun `subscribe after wire attaches immediately`() {
    val viz = makeViz()
    val engine = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(engine)
    viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(1, engine.attachedSinks.size)
  }

  @Test
  fun `dispose detaches from the wired engine`() {
    val viz = makeViz()
    val engine = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(engine)
    val d = viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(1, engine.attachedSinks.size)
    d()
    assertEquals(0, engine.attachedSinks.size)
  }

  @Test
  fun `wireToPlaybackEngine moves subscriptions across engine swaps`() {
    val viz = makeViz()
    viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    val first = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(first)
    assertEquals(1, first.attachedSinks.size)

    val second = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(second)
    assertEquals("first engine should be detached on swap", 0, first.attachedSinks.size)
    assertEquals("second engine should pick up the subscription", 1, second.attachedSinks.size)
  }

  @Test
  fun `unwireFromPlaybackEngine detaches from the engine but keeps subscriptions`() {
    val viz = makeViz()
    val engine = FakePlaybackEngineWithSinks()
    viz.wireToPlaybackEngine(engine)
    viz.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(1, engine.attachedSinks.size)
    viz.unwireFromPlaybackEngine()
    assertEquals(0, engine.attachedSinks.size)
    assertEquals("subscriber list survives unwire", 1.0, viz.getSubscriberCount(), 0.0)
  }

  @Test
  fun `static current is set on construction and overwrites on rebuild`() {
    val first = makeViz()
    assertEquals(first, Visualizer.current)
    val second = makeViz()
    assertEquals(second, Visualizer.current)
  }

  @Test
  fun `second construction tears down prior wiring before stealing static reference`() {
    val first = makeViz()
    val firstEngine = FakePlaybackEngineWithSinks()
    first.wireToPlaybackEngine(firstEngine)
    first.subscribe(makeConfig(), { _ -> }, { _ -> })
    assertEquals(1, firstEngine.attachedSinks.size)

    makeViz() // construction should clean up `first`'s wiring
    assertEquals(
      "prior instance's processor should be detached so audio-thread callbacks don't fire into stale JS",
      0,
      firstEngine.attachedSinks.size,
    )
    assertEquals(
      "prior instance's subscriber list should be cleared so it can't re-attach if its disposers are called later",
      0.0,
      first.getSubscriberCount(),
      0.0,
    )
  }
}

@UnstableApi
private class FakePlaybackEngineWithSinks : PlaybackEngine {
  val attachedSinks = CopyOnWriteArrayList<TeeAudioProcessor.AudioBufferSink>()
  var attachCount: Int = 0
    private set
  var detachCount: Int = 0
    private set

  override val mediaSessionPlayer: androidx.media3.exoplayer.ExoPlayer
    get() = error("not used in test")
  override fun release() = error("not used in test")
  override fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int, startPositionMs: Long) = error("not used in test")
  override fun addMediaItems(items: List<androidx.media3.common.MediaItem>, insertBefore: Int) = error("not used in test")
  override fun removeMediaItems(indices: IntArray) = error("not used in test")
  override fun moveMediaItem(fromIndex: Int, toIndex: Int) = error("not used in test")
  override fun play() = error("not used in test")
  override fun pause() = error("not used in test")
  override fun stop() = error("not used in test")
  override fun seekTo(index: Int, positionMs: Long) = error("not used in test")
  override fun skipToNext() = error("not used in test")
  override fun skipToPrevious() = error("not used in test")
  override fun setVolume(volume: Float) = error("not used in test")
  override fun setPlaybackSpeed(speed: Float) = error("not used in test")
  override fun setRepeatMode(mode: RepeatMode) = error("not used in test")
  override fun setPitchCorrectionMode(mode: PitchCorrectionMode) = error("not used in test")
  override fun setWakeMode(mode: Int) = error("not used in test")
  override val currentMediaItemIndex: Int get() = error("not used in test")
  override val currentPositionMs: Long get() = error("not used in test")
  override val currentDurationMs: Long get() = error("not used in test")
  override val bufferedPositionMs: Long get() = error("not used in test")
  override val isPlaying: Boolean get() = error("not used in test")
  override val currentMediaItem: androidx.media3.common.MediaItem? get() = error("not used in test")
  override val isCrossfadeActive: Boolean get() = false
  override val allMediaItems: List<androidx.media3.common.MediaItem> get() = error("not used in test")
  override fun currentAudioSessionIds(): IntArray = IntArray(0)
  override fun addAudioSessionIdListener(listener: (IntArray) -> Unit): () -> Unit = { /* noop */ }
  override fun attachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) {
    if (attachedSinks.addIfAbsent(sink)) attachCount++
  }
  override fun detachAudioBufferSink(sink: TeeAudioProcessor.AudioBufferSink) {
    if (attachedSinks.remove(sink)) detachCount++
  }
  override var delegate: PlaybackEngineDelegate? = null
}
