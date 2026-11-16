package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * Unit coverage for [AudioPipelineRenderersFactory] construction — it wraps a
 * caller-supplied [androidx.media3.common.audio.AudioProcessorChain] and leaves
 * the produced sink at 16-bit (float output off). `buildAudioSink`
 * itself is `protected` on [DefaultRenderersFactory] and cannot be called
 * outside the Media3 internal call path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class AudioPipelineRenderersFactoryTest {

  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun `factory accepts an audio processor chain`() {
    val factory =
      AudioPipelineRenderersFactory(context, PitchAwareAudioProcessorChain(emptyArray()))
    assertNotNull(factory)
  }

  @Test
  fun `factory leaves audio float output off so the chain survives on hi-res sources`() {
    // Float output would make a hi-res (24-bit+) source produce a float sink,
    // and DefaultAudioSink.configure()'s float branch DROPS the custom
    // AudioProcessorChain — silently disabling playback speed, pitch-correction,
    // ReplayGain, and the visualizer on hi-res content. Keeping the sink at
    // 16-bit (Media3 downconverts hi-res) keeps the chain in the pipeline for
    // every source. Pin that the factory leaves float output off (the
    // DefaultRenderersFactory default) via reflection — no public accessor
    // exists, but the field name is stable across Media3 1.x.
    val factory =
      AudioPipelineRenderersFactory(context, PitchAwareAudioProcessorChain(emptyArray()))
    val field: Field =
      DefaultRenderersFactory::class.java.getDeclaredField("enableFloatOutput")
    field.isAccessible = true
    assertFalse(
      "AudioPipelineRenderersFactory must NOT enable float output (it drops the chain on hi-res)",
      field.getBoolean(factory),
    )
  }
}
