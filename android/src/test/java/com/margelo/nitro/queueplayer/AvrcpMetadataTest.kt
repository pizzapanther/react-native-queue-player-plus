package com.margelo.nitro.queueplayer

import android.content.Intent
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the AVRCP-relevant surface of the
 * [PlaybackService] [MediaSession]. Media3 bridges a connected
 * [androidx.media3.session.MediaController] (which is what an
 * Android Auto, Wear, or Bluetooth A2DP/AVRCP head unit binds as)
 * directly to `MediaSession.player.currentMediaItem.mediaMetadata`
 * + the session's available `Player.Commands`. The bridge is built
 * into Media3 — the lib does not write any AVRCP code itself, so
 * this suite asserts that the session surface a controller would
 * read reflects the lib's queue + skip-capability state.
 *
 * `MediaController.Builder.buildAsync()` is too heavyweight to drive
 * to a connected state under Robolectric (the connection handshake
 * blocks on the framework's process binder). The bridge ingests the
 * same fields exercised here, so direct reads against
 * `session.player` cover the AVRCP-visible state without standing
 * up a controller. The end-to-end Bluetooth pairing pass is the
 * user-led manual verification lane (per AGENTS rule on physical-
 * device test lanes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class AvrcpMetadataTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var binder: PlaybackService.LocalBinder
  private lateinit var session: MediaSession

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(context.applicationContext, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    binder = service.onBind(intent) as PlaybackService.LocalBinder
    session = binder.session
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  // --- Metadata propagation through the session surface

  @Test
  fun `currentMediaItem mediaMetadata reflects title artist album for the head of the queue`() {
    val track = TrackItem(
      id = "t1",
      url = "https://example.com/a.m4a",
      title = "Song X",
      artist = "Artist Y",
      album = "Album Z",
      duration = null,
      artworkUrl = null,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
    )
    setQueueOnSession(listOf(track))

    val metadata = session.player.currentMediaItem?.mediaMetadata
    assertEquals("Song X", metadata?.title?.toString())
    assertEquals("Artist Y", metadata?.artist?.toString())
    assertEquals("Album Z", metadata?.albumTitle?.toString())
  }

  @Test
  fun `currentMediaItem mediaMetadata exposes artworkUri to the AVRCP surface`() {
    val track = TrackItem(
      id = "t1",
      url = "https://example.com/a.m4a",
      title = "Song",
      artist = "Artist",
      album = "Album",
      duration = null,
      artworkUrl = "https://example.com/cover.jpg",
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
    )
    setQueueOnSession(listOf(track))

    assertEquals(
      "https://example.com/cover.jpg",
      session.player.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
    )
  }

  @Test
  fun `currentMediaItem mediaMetadata exposes durationMs when track duration is positive`() {
    val track = TrackItem(
      id = "t1",
      url = "https://example.com/a.m4a",
      title = "Song",
      artist = "Artist",
      album = "Album",
      duration = 42.0,
      artworkUrl = null,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
    )
    setQueueOnSession(listOf(track))

    assertEquals(42_000L, session.player.currentMediaItem?.mediaMetadata?.durationMs)
  }

  @Test
  fun `setMediaItems followed by prepare swaps the AVRCP-visible metadata to the new head`() {
    val first = TrackItem(
      id = "first",
      url = "https://example.com/first.m4a",
      title = "First",
      artist = "A1",
      album = "B1",
      duration = null,
      artworkUrl = null,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
    )
    val second = TrackItem(
      id = "second",
      url = "https://example.com/second.m4a",
      title = "Second",
      artist = "A2",
      album = "B2",
      duration = null,
      artworkUrl = null,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
    )

    setQueueOnSession(listOf(first))
    assertEquals("First", session.player.currentMediaItem?.mediaMetadata?.title?.toString())

    setQueueOnSession(listOf(second))
    assertEquals("Second", session.player.currentMediaItem?.mediaMetadata?.title?.toString())
    assertEquals("A2", session.player.currentMediaItem?.mediaMetadata?.artist?.toString())
  }

  // --- Empty queue surface

  @Test
  fun `setMediaItems with an empty list leaves the AVRCP surface with no current item`() {
    val track = TrackItem(
      id = "t1",
      url = "https://example.com/a.m4a",
      title = "Song",
      artist = "Artist",
      album = "Album",
      duration = null,
      artworkUrl = null,
      extras = null,
      replayGainTrackGain = null, replayGainTrackPeak = null,
      replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
    )
    setQueueOnSession(listOf(track))
    assertEquals(1, session.player.mediaItemCount)

    setQueueOnSession(emptyList())

    assertEquals(0, session.player.mediaItemCount)
    assertNull(session.player.currentMediaItem)
  }

  // --- Skip-capability reflection on the session surface

  @Test
  fun `available player commands include skip-next and exclude skip-previous when the cap so allows`() {
    val handler = StaticCapabilityHandler(
      SkipCapability(canSkipPrevious = false, canSkipNext = true)
    )
    binder.setCommandHandler(handler)

    val controllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "avrcp-controller", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
    val commands = PlaybackServiceCallback(service.callbackEnvironment)
      .onConnect(session, controllerInfo)
      .availablePlayerCommands

    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `available player commands flip with the cached skip capability between consecutive connects`() {
    val handler = StaticCapabilityHandler(
      SkipCapability(canSkipPrevious = false, canSkipNext = false)
    )
    binder.setCommandHandler(handler)
    val callback = PlaybackServiceCallback(service.callbackEnvironment)
    val controllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "avrcp-controller", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )

    val noSkips = callback.onConnect(session, controllerInfo).availablePlayerCommands
    assertFalse(noSkips.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(noSkips.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

    handler.capability = SkipCapability(canSkipPrevious = true, canSkipNext = true)
    val bothSkips = callback.onConnect(session, controllerInfo).availablePlayerCommands
    assertTrue(bothSkips.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(bothSkips.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  // --- Helpers

  /**
   * Hand the player the production-built MediaItems for [tracks]
   * and pump the main looper so the resulting metadata change
   * propagates to `currentMediaItem`. Mirrors the call shape the
   * lib uses inside `TrackPlayer.setQueueInternal`.
   */
  private fun setQueueOnSession(tracks: List<TrackItem>) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val items: List<MediaItem> = MediaItemBuilder.buildMediaItems(context, tracks)
    session.player.setMediaItems(items)
    session.player.prepare()
    shadowOf(Looper.getMainLooper()).idle()
  }

  /**
   * Minimal handler that surfaces a static skip capability and
   * tolerates every other callback as a no-op. The metadata path
   * doesn't exercise the transport / skip / media-button hooks; the
   * cap-reflection tests only need the cap getter to return the
   * value under test.
   */
  private class StaticCapabilityHandler(
    var capability: SkipCapability,
  ) : PlaybackService.MediaSessionCommandHandler {
    override fun onTransportCommand() {}
    override fun onSkipToNextCommand(): Boolean = false
    override fun onSkipToPreviousCommand(): Boolean = false
    override fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean = false
    override fun getCachedSkipCapability(): SkipCapability = capability
    override fun onMediaPlay() {}
    override fun onMediaPause() {}
    override fun onMediaTogglePlayPause() {}
    override fun onMediaSkipNext() {}
    override fun onMediaSkipPrevious() {}
    override fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping) {}
    override fun onBecomingNoisy() {}
  }
}
