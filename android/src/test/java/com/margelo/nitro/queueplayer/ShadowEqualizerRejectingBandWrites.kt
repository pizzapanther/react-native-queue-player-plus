package com.margelo.nitro.queueplayer

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import org.robolectric.annotation.Implements
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A five-band equalizer whose construction and parameter reads succeed and
 * whose first band-level write fails, so an attach reaches its configure
 * step and throws there. `Equalizer.setBandLevel` routes the returned error
 * through `checkStatus`, which raises `RuntimeException`.
 */
@Implements(Equalizer::class)
class ShadowEqualizerRejectingBandWrites : ShadowEqualizer() {

  override fun native_setParameter(psize: Int, param: ByteArray, vsize: Int, value: ByteArray): Int {
    val id = ByteBuffer.wrap(param).order(ByteOrder.nativeOrder()).int
    if (id == PARAM_BAND_LEVEL) return AudioEffect.ERROR_INVALID_OPERATION
    return super.native_setParameter(psize, param, vsize, value)
  }

  private companion object {
    const val PARAM_BAND_LEVEL = 2
  }
}
