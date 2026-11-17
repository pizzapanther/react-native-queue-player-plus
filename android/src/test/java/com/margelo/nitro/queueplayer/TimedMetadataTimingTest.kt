package com.margelo.nitro.queueplayer

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimedMetadataTimingTest {
  private val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ICY).build()

  /** The whole sample is readable immediately, but belongs at 5s of playback. */
  private inner class BufferedSample : SampleStream {
    var read = 0
    override fun isReady() = true
    override fun maybeThrowError() {}
    override fun skipData(positionUs: Long) = 0
    override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
      if (read++ == 0) {
        holder.format = format
        return C.RESULT_FORMAT_READ
      }
      if (read == 2) {
        val bytes = "StreamTitle='Artist - Song';".toByteArray()
        buffer.ensureSpaceForWrite(bytes.size)
        buffer.data!!.put(bytes)
        buffer.timeUs = 5_000_000
        buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
      } else {
        buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
      }
      return C.RESULT_BUFFER_READ
    }
  }

  private fun renderer(received: MutableList<TimedMetadata>, stream: SampleStream): MetadataRenderer {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val factory = AudioPipelineRenderersFactory(context, PitchAwareAudioProcessorChain(emptyArray()))
    // Exercise the renderer built by our production factory, so enabling
    // early output in the factory causes the timing assertion below to fail.
    val method = DefaultRenderersFactory::class.java.getDeclaredMethod(
      "buildMetadataRenderers", Context::class.java, MetadataOutput::class.java,
      Looper::class.java, Int::class.javaPrimitiveType, ArrayList::class.java,
    ).apply { isAccessible = true }
    val renderers = arrayListOf<Renderer>()
    val output = MetadataOutput { TimedMetadataParser.parse(it)?.let(received::add) }
    method.invoke(factory, context, output, null, 0, renderers)
    return (renderers.first() as MetadataRenderer).apply {
      init(0, PlayerId.UNSET, Clock.DEFAULT)
      enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, 0, false, true, 0, 0, MediaPeriodId(Any()))
      start()
    }
  }

  @Test fun `buffered metadata waits for playback position including while paused`() {
    val received = mutableListOf<TimedMetadata>()
    val renderer = renderer(received, BufferedSample())
    try {
      repeat(3) { renderer.render(0, 0) }
      assertTrue(received.isEmpty())
      // Advancing wall-clock time with a stationary playhead must not emit.
      renderer.render(0, 30_000_000)
      renderer.render(4_999_999, 40_000_000)
      assertTrue(received.isEmpty())
      renderer.render(5_000_000, 40_000_001)
      assertEquals(listOf(TimedMetadata("Song", "Artist", null, null)), received)
      renderer.render(6_000_000, 41_000_000)
      assertEquals(1, received.size)
    } finally {
      renderer.stop()
      renderer.disable()
    }
  }

  @Test fun `seeking flushes a pending sample from the old timeline`() {
    val received = mutableListOf<TimedMetadata>()
    val renderer = renderer(received, BufferedSample())
    try {
      repeat(3) { renderer.render(0, 0) }
      renderer.resetPosition(10_000_000, false)
      renderer.render(10_000_000, 10_000_000)
      assertTrue(received.isEmpty())
    } finally {
      renderer.stop()
      renderer.disable()
    }
  }
}
