package com.margelo.nitro.queueplayer

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.queueplayer.cast.FakeRemotePlayer
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * `PlaybackService` retargets the stable
 * [SessionCommandForwardingPlayer] wrapper's delegate to a Chromecast
 * RemotePlayer's CastPlayer on cast connect (so the system notification +
 * lockscreen follow the receiver) and restores the local engine's player
 * on disconnect. The session stays bound to the same wrapper identity
 * throughout, so the swap is observed via the wrapped delegate. A
 * RemotePlayer with no CastPlayer — and structurally AirPlay/PcmStream,
 * which is never a RemotePlayer and never reaches `activeFlow` — does
 * not bind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceCastBindTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var binder: PlaybackService.LocalBinder

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val intent = Intent(context(), PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    binder = service.onBind(intent) as PlaybackService.LocalBinder
    idle()
  }

  @After
  fun tearDown() {
    PlaybackStateRouter.setActive(null)
    idle()
    controller.destroy()
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private fun context(): Context = ApplicationProvider.getApplicationContext()

  // The session is bound to the stable forwarding wrapper for its
  // lifetime; the engine/cast swap is observed via the wrapped delegate.
  private fun boundDelegate(): Player =
    (binder.session.player as SessionCommandForwardingPlayer).wrappedPlayer

  private fun newPlayer(): ExoPlayer =
    ExoPlayer.Builder(context()).build()

  @Test
  fun `binds the cast player on connect and restores local on disconnect`() {
    val sessionPlayer = binder.session.player
    val local = boundDelegate()
    val cast = newPlayer()

    PlaybackStateRouter.setActive(FakeRemotePlayer(player = cast))
    idle()
    assertSame("wrapper retargets its delegate to the cast player", cast, boundDelegate())
    assertSame(
      "the session stays bound to the same wrapper identity across the swap",
      sessionPlayer, binder.session.player,
    )

    // Disconnect — retarget restores the local delegate and releases the
    // cast player (PlaybackService owns its binding lifetime).
    PlaybackStateRouter.setActive(null)
    idle()
    assertSame("wrapper restores its delegate to the local player", local, boundDelegate())
  }

  @Test
  fun `a RemotePlayer without a cast player does not bind`() {
    val local = boundDelegate()
    PlaybackStateRouter.setActive(FakeRemotePlayer(player = null))
    idle()
    assertSame(local, boundDelegate())
  }
}
