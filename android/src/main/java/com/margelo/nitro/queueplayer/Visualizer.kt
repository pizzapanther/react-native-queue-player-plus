package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.ArrayBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * FFT extractor HybridObject. Each subscribe call attaches a
 * per-subscription [FFTProcessorTee] to the active playback engine's
 * `bufferSinks` accumulator; Tee callbacks fire on the audio render
 * thread, marshal frames as Nitro [ArrayBuffer]s, and fire onFrame on
 * the JS-bridge thread.
 *
 * Lifecycle mirrors the Equalizer hybrid: the static `current`
 * reference plus `wireToPlaybackEngine` / `unwireFromPlaybackEngine`
 * lets `PlaybackService` rewire the visualizer to the active engine
 * across configure() rebuilds + crossfade engine swaps without
 * threading the Hybrid reference through Nitro's JNI bridge.
 *
 * Subscribers attached BEFORE wireToPlaybackEngine queue locally and
 * attach the moment the engine arrives. Subscribers added AFTER the
 * engine is wired attach immediately. Detach cleanly handles either
 * state.
 */
@UnstableApi
@DoNotStrip
class Visualizer : HybridVisualizerSpec() {

  companion object {
    /**
     * The most recently constructed [Visualizer] instance.
     * `PlaybackService` reaches for this on engine boot to rewire
     * the FFT pipeline to the active engine. JS callers consume
     * `getVisualizer()` which caches the same Hybrid singleton in
     * `clients.ts`, so the static reference and the JS-visible
     * instance always coincide in practice.
     */
    @JvmStatic
    @Volatile
    internal var current: Visualizer? = null
  }

  private data class Subscription(
    val id: Long,
    val processor: FFTProcessorTee,
    // Flipped true by the disposer before it detaches the sink. Every queued
    // emit task checks it, so frames already marshaled onto the emit executor
    // are dropped instead of firing onFrame after dispose() returned.
    val disposed: AtomicBoolean,
  )

  private val subscriptions = CopyOnWriteArrayList<Subscription>()
  private val nextSubscriptionId = AtomicLong(1L)

  /**
   * Single-threaded executor that runs every JS-bridge `onFrame` call.
   * The render thread builds a fresh ArrayBuffer per emit (which copies
   * out of the processor's scratch arrays) and posts the bridge call
   * here so a slow JS handler cannot back-pressure audio output.
   * Daemon thread — dies with the process; no explicit shutdown.
   */
  private val emitExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "rnqp-visualizer-emit").apply { isDaemon = true }
  }

  private val engineWireLock = Any()

  @Volatile
  private var engine: PlaybackEngine? = null

  init {
    // Mirror Equalizer pattern: a second Visualizer construction tears
    // the prior instance's playback wiring down before it loses the
    // static reference, so its sink-dispatching processors don't keep
    // firing into JS callbacks bound to a torn-down JS context.
    current?.let {
      it.unwireFromPlaybackEngine()
      it.disposeAllSubscriptions()
    }
    current = this
    // Late-construction recovery: if the PlaybackService already has
    // an active engine, wire to it now. Without this, a Hybrid
    // constructed AFTER service onCreate's `Visualizer.current?` no-op
    // wire never receives any engine reference, and subscribers added
    // through `subscribe()` never reach the engine's bufferSinks.
    // Guarded by `engineWireLock` so a concurrent service-side
    // `swapEngineInternal` cannot swap `activeEngine` between the read
    // and the wire.
    synchronized(engineWireLock) {
      PlaybackService.activeEngine?.let { wireToPlaybackEngine(it) }
    }
  }

  override fun subscribe(
    config: VisualizerConfig,
    onFrame: (frame: VisualizerFrame) -> Unit,
    onError: (reason: VisualizerErrorReason) -> Unit,
  ): () -> Unit {
    // Pin-at-configure visualization-disabled gate. The flag is
    // written by `TrackPlayer.configureInternal` from
    // `PlayerConfig.visualizationEnabled`. When false, fire onError
    // on the emit executor (matches the cross-thread JS dispatch
    // shape used for normal frames) and return a no-op dispose; the
    // subscription is not registered, so `getSubscriberCount()`
    // stays accurate.
    if (!VisualizationGate.isEnabled()) {
      emitExecutor.execute {
        onError(VisualizerErrorReason.VISUALIZATION_DISABLED)
      }
      return { /* no-op dispose */ }
    }
    val fftSize = config.fftSize.toInt()
    val intervalMs = config.updateIntervalMs.toInt()
    val includeSamples = config.includeSamples ?: false

    // Set by the disposer; read inside the emit closure so a frame already
    // queued on the single-threaded executor at dispose time is dropped
    // instead of crossing the JS bridge after the subscription is gone.
    val disposed = AtomicBoolean(false)

    val processor = FFTProcessorTee(
      fftSize = fftSize,
      intervalMs = intervalMs,
      includeSamples = includeSamples,
    ) { magnitudes, samples, sampleRate ->
      // Render thread: copy the scratch arrays into ArrayBuffers
      // immediately — the ArrayBuffer carries an independent direct
      // ByteBuffer, so the processor is free to overwrite the source
      // arrays on the next emit. Then post the JS-bridge crossing onto
      // the emit executor so a slow JS handler cannot stall audio.
      val fftBuffer = floatArrayToArrayBuffer(magnitudes)
      val samplesBuffer = samples?.let { floatArrayToArrayBuffer(it) }
      val sampleRateD = sampleRate.toDouble()
      emitExecutor.execute {
        // Drop frames queued before dispose — the disposer sets this flag
        // before detaching, so anything still sitting in the executor queue
        // short-circuits here rather than firing onFrame post-dispose.
        if (disposed.get()) return@execute
        val frame = VisualizerFrame(
          fft = fftBuffer,
          samples = samplesBuffer,
          sampleRate = sampleRateD,
        )
        onFrame(frame)
      }
    }

    val sub = Subscription(
      id = nextSubscriptionId.getAndIncrement(),
      processor = processor,
      disposed = disposed,
    )
    subscriptions.add(sub)
    engine?.attachAudioBufferSink(processor)

    return {
      // Mark disposed BEFORE detaching so any task already queued on the emit
      // executor short-circuits (see the closure above) instead of firing
      // onFrame after this returns.
      disposed.set(true)
      // Detach from the engine first so the Tee fan-out stops calling
      // into this processor before the subscription record disappears.
      // Removing-then-detaching would leave a window where an unwire
      // running on another thread takes its `subscriptions` snapshot
      // without this entry, then nulls `engine` before the disposer
      // can detach — leaking the processor in the engine's sink list.
      engine?.detachAudioBufferSink(processor)
      subscriptions.remove(sub)
    }
  }

  override fun getSubscriberCount(): Double = subscriptions.size.toDouble()

  /**
   * Module-internal escape hatch so the playback service can wire
   * the FFT pipeline to ExoPlayer's audio chain at attach time.
   * Pure JS-facing callers must not reach for this property.
   *
   * Replays every queued subscription's processor onto the new
   * engine's `bufferSinks` accumulator. A re-wire (configure()
   * rebuilding the playback engine, or crossfade engine swap) tears
   * the prior engine's attachments down before resubscribing.
   */
  internal fun wireToPlaybackEngine(playbackEngine: PlaybackEngine) {
    unwireFromPlaybackEngine()
    engine = playbackEngine
    for (sub in subscriptions) {
      playbackEngine.attachAudioBufferSink(sub.processor)
    }
  }

  internal fun unwireFromPlaybackEngine() {
    val e = engine ?: return
    for (sub in subscriptions) {
      e.detachAudioBufferSink(sub.processor)
    }
    engine = null
  }

  private fun disposeAllSubscriptions() {
    val e = engine
    for (sub in subscriptions) {
      e?.detachAudioBufferSink(sub.processor)
    }
    subscriptions.clear()
  }

  /**
   * Wrap [values] in a fresh direct `ArrayBuffer` for delivery to JS.
   *
   * Allocates a new `ByteBuffer.allocateDirect` per call: at
   * `fftSize=2048` + `includeSamples=true` + `updateIntervalMs=16`
   * the steady-state allocation rate is ~768 KB/s of off-heap
   * (DirectByteBuffer) memory across magnitudes + samples. Acceptable
   * at typical configs; consumers chasing very low intervalMs at
   * fftSize=2048 should expect proportionally higher GC pressure on
   * the audio render thread.
   *
   * Writes with `putFloat` rather than `bb.asFloatBuffer().put(values)`:
   * the view form delivered all-zero buffers to JS through
   * `ArrayBuffer.wrap` while `bb.getFloat(0)` read back correctly in
   * Kotlin. Cause not established.
   */
  private fun floatArrayToArrayBuffer(values: FloatArray): ArrayBuffer {
    val bb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
    for (v in values) bb.putFloat(v)
    bb.rewind()
    return ArrayBuffer.wrap(bb)
  }
}
