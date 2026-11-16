package com.margelo.nitro.queueplayer

import android.media.audiofx.DynamicsProcessing
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDynamicsProcessing

/**
 * A [DynamicsProcessing] whose construction and band writes succeed and
 * whose `setEnabled` throws. The framework constructor never calls
 * `setEnabled`; the engine does, after applying the bands and before
 * recording the attach — so this is the one call that fails an attach
 * inside its configure step rather than at construction.
 */
@Implements(DynamicsProcessing::class)
class ShadowDynamicsProcessingRejectingEnable : ShadowDynamicsProcessing() {

  override fun native_setEnabled(enabled: Boolean): Int =
    throw IllegalStateException("setEnabled() called on uninitialized AudioEffect")
}
