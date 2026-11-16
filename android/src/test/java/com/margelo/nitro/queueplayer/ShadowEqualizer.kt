package com.margelo.nitro.queueplayer

import android.media.audiofx.Equalizer
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional

/**
 * Models a five-band hardware equalizer so the legacy attach path resolves
 * the same way on every run.
 *
 * [ShadowAudioEffect] answers the AudioEffect surface but falls back to a
 * four-byte zero for any parameter it does not know, which the constructor's
 * `short` reads cannot hold — so constructing an [Equalizer] throws before
 * any band is queried. Supplying the parameters it reads makes a real attach
 * distinguishable from a failed one. Levels written with `setBandLevel` land
 * in the base shadow's parameter map, so they read back through the ordinary
 * [Equalizer] API.
 */
@Implements(Equalizer::class)
open class ShadowEqualizer : ShadowAudioEffect() {

  override fun getDefaultParameter(parameter: ByteBuffer): Optional<ByteBuffer> {
    val key = parameter.duplicate().order(ByteOrder.nativeOrder())
    if (key.remaining() < Int.SIZE_BYTES) return Optional.empty()
    return when (key.int) {
      PARAM_NUM_BANDS -> Optional.of(shorts(BAND_COUNT))
      PARAM_LEVEL_RANGE -> Optional.of(shorts(MIN_LEVEL_MB, MAX_LEVEL_MB))
      PARAM_BAND_LEVEL -> Optional.of(shorts(0))
      PARAM_GET_NUM_OF_PRESETS -> Optional.of(shorts(0))
      PARAM_CENTER_FREQ -> {
        val band = if (key.remaining() >= Int.SIZE_BYTES) key.int else 0
        Optional.of(ints(CENTER_FREQS_MILLI_HZ[band.coerceIn(0, BAND_COUNT - 1)]))
      }
      else -> Optional.empty()
    }
  }

  private fun shorts(vararg values: Int): ByteBuffer =
    ByteBuffer.allocate(values.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
      values.forEach { putShort(it.toShort()) }
      flip()
    }

  private fun ints(vararg values: Int): ByteBuffer =
    ByteBuffer.allocate(values.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
      values.forEach { putInt(it) }
      flip()
    }

  companion object {
    const val BAND_COUNT = 5
    const val MIN_LEVEL_MB = -1500
    const val MAX_LEVEL_MB = 1500

    /** Centre frequencies in milliHz, matching a typical OEM five-band set. */
    private val CENTER_FREQS_MILLI_HZ =
      intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)

    private const val PARAM_NUM_BANDS = 0
    private const val PARAM_LEVEL_RANGE = 1
    private const val PARAM_BAND_LEVEL = 2
    private const val PARAM_CENTER_FREQ = 3
    private const val PARAM_GET_NUM_OF_PRESETS = 7
  }
}
