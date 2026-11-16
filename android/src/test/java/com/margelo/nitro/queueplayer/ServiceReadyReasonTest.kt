package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Coverage for [TrackPlayer.decideReadyReason] — the pure function
 * that maps a (priorBinderId, currentBinderId) pair to the
 * [ServiceReadyReason] (if any) the lib dispatches on a fresh
 * service-bind.
 */
@UnstableApi
class ServiceReadyReasonTest {

  @Test
  fun `first bind with no prior identity dispatches FIRST_BIND`() {
    val current = UUID.randomUUID()
    assertEquals(
      ServiceReadyReason.FIRST_BIND,
      TrackPlayer.decideReadyReason(prior = null, current = current)
    )
  }

  @Test
  fun `rebind against a different binder identity dispatches SERVICE_REBORN`() {
    val prior = UUID.randomUUID()
    val current = UUID.randomUUID()
    assertEquals(
      ServiceReadyReason.SERVICE_REBORN,
      TrackPlayer.decideReadyReason(prior = prior, current = current)
    )
  }

  @Test
  fun `rebind against the same binder identity returns null - no dispatch`() {
    val same = UUID.randomUUID()
    assertNull(TrackPlayer.decideReadyReason(prior = same, current = same))
  }
}
