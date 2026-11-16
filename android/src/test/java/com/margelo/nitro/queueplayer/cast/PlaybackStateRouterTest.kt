package com.margelo.nitro.queueplayer.cast

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `PlaybackStateRouter.clearActive` is a compare-and-clear: it clears the
 * active session only when it still matches the expected one, so a newer
 * session that registered in parallel isn't wiped out by a closing
 * session's teardown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStateRouterTest {

  @After
  fun tearDown() {
    PlaybackStateRouter.setActive(null)
  }

  @Test
  fun `clearActive clears the matching session`() {
    val session = FakeRemotePlayer()
    PlaybackStateRouter.setActive(session)
    assertSame(session, PlaybackStateRouter.activeRemote())
    PlaybackStateRouter.clearActive(session)
    assertNull(PlaybackStateRouter.activeRemote())
  }

  @Test
  fun `clearActive leaves a non-matching session active`() {
    val active = FakeRemotePlayer()
    val unrelated = FakeRemotePlayer()
    PlaybackStateRouter.setActive(active)
    PlaybackStateRouter.clearActive(unrelated)
    assertSame(active, PlaybackStateRouter.activeRemote())
  }
}
