package com.margelo.nitro.queueplayer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Robolectric coverage for [PlaybackServiceCallback] and
 * the [TrackPlayer]-side [PlaybackService.MediaSessionCommandHandler]
 * implementation. The callback bridges system-surface commands
 * (lock-screen, Bluetooth, Android Auto, hardware media keys) into
 * the lib's queue + state.
 *
 * Two slices:
 *  - The callback's `Player.Command` → handler-method routing,
 *    driven against a recording fake handler.
 *  - The TrackPlayer-side handler that production registers, exercised
 *    via direct method invocation on the handler object so
 *    `pendingStateChangeReason` / `pendingTrackChangeReason` /
 *    `currentTrackIndex` updates are observable without standing up a
 *    real `MediaController`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackServiceCallbackTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var callback: PlaybackServiceCallback
  private lateinit var binder: PlaybackService.LocalBinder
  private lateinit var session: MediaSession
  private lateinit var controllerInfo: MediaSession.ControllerInfo

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(context.applicationContext, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    binder = service.onBind(intent) as PlaybackService.LocalBinder
    callback = PlaybackServiceCallback(service.callbackEnvironment)
    session = binder.session
    controllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "test-controller", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
    // Process-wide count; reset so headless connect/disconnect assertions
    // start from a known-disconnected state regardless of test order.
    CarConnectionState.reset()
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  // --- Callback: routing matrix

  @Test
  fun `onConnect accepts every controller`() {
    val result = callback.onConnect(session, controllerInfo)
    assertTrue("onConnect returns an accepted ConnectionResult", result.isAccepted)
  }

  // Controller-driven `Player.Command` interception (skip-next /
  // skip-previous veto, transport `system`-reason stamping) now lives
  // on [SessionCommandForwardingPlayer]. This suite keeps the
  // callback's connect / resumption / available-commands surface.

  @Test
  fun `onPlaybackResumption returns an empty MediaItemsWithStartPosition`() {
    val future = callback.onPlaybackResumption(
      session, controllerInfo, /* isForPlayback = */ true
    )
    assertTrue("future is already complete (synchronous immediateFuture)", future.isDone)
    val result = future.get()
    assertNotNull(result)
    assertTrue("no persisted queue is signalled by an empty media-items list", result.mediaItems.isEmpty())
    assertEquals(C.INDEX_UNSET, result.startIndex)
    assertEquals(C.TIME_UNSET, result.startPositionMs)
  }

  @Test
  fun `onPlaybackResumption returns the same empty payload for metadata-only requests`() {
    val future = callback.onPlaybackResumption(
      session, controllerInfo, /* isForPlayback = */ false
    )
    val result = future.get()
    assertTrue(result.mediaItems.isEmpty())
    assertEquals(C.INDEX_UNSET, result.startIndex)
    assertEquals(C.TIME_UNSET, result.startPositionMs)
  }

  @Test
  fun `manifest registers PlaybackService with MediaLibraryService and MEDIA_BUTTON intent filters`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val component = ComponentName(context, PlaybackService::class.java)
    val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
    assertTrue("service must be exported for cross-process controller binding", info.exported)
    val packageManager = context.packageManager
    // MediaLibraryService is the canonical action; we intentionally
    // do NOT declare the MediaSessionService action — declaring both
    // can cause Android Auto's gearhead bind probe to resolve via
    // the MSS interface (no library commands) and emit the
    // `MediaBrowserService: No root for client` retry storm.
    // androidx/media#1774.
    val libraryResolved = packageManager.queryIntentServices(
      Intent("androidx.media3.session.MediaLibraryService").setPackage(context.packageName),
      0
    )
    assertTrue(
      "MediaLibraryService action must resolve to PlaybackService — got: " +
        libraryResolved.map { it.serviceInfo.name },
      libraryResolved.any { it.serviceInfo.name == component.className }
    )
    val mediaButtonResolved = packageManager.queryIntentServices(
      Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName),
      0
    )
    assertTrue(
      "MEDIA_BUTTON action must resolve to PlaybackService — got: " +
        mediaButtonResolved.map { it.serviceInfo.name },
      mediaButtonResolved.any { it.serviceInfo.name == component.className }
    )
  }

  @Test
  fun `manifest registers MediaButtonReceiver for hardware media-button intents`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName)
    val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)
    val receiverNames = resolved.map { it.activityInfo.name }
    assertTrue(
      "manifest declares androidx.media3.session.MediaButtonReceiver — got: $receiverNames",
      receiverNames.contains("androidx.media3.session.MediaButtonReceiver")
    )
  }

  // --- Available player commands driven by cached skip capability

  @Test
  fun `onConnect with no handler accepts every controller with default commands`() {
    val result = callback.onConnect(session, controllerInfo)
    assertTrue(result.isAccepted)
    val commands = result.availablePlayerCommands
    assertTrue(
      "default command set includes COMMAND_SEEK_TO_NEXT_MEDIA_ITEM",
      commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    )
    assertTrue(
      "default command set includes COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM",
      commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    )
  }

  @Test
  fun `onConnect strips skip-next commands when capability disallows skip-next`() {
    val handler = RecordingHandler(
      capability = SkipCapability(canSkipPrevious = true, canSkipNext = false)
    )
    binder.setCommandHandler(handler)

    val commands = callback.onConnect(session, controllerInfo).availablePlayerCommands

    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `onConnect strips skip-previous commands when capability disallows skip-previous`() {
    val handler = RecordingHandler(
      capability = SkipCapability(canSkipPrevious = false, canSkipNext = true)
    )
    binder.setCommandHandler(handler)

    val commands = callback.onConnect(session, controllerInfo).availablePlayerCommands

    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
  }

  @Test
  fun `onConnect retains both skip commands when capability allows both`() {
    val handler = RecordingHandler(
      capability = SkipCapability(canSkipPrevious = true, canSkipNext = true)
    )
    binder.setCommandHandler(handler)

    val commands = callback.onConnect(session, controllerInfo).availablePlayerCommands

    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `playerCommandsFor strips both skips when capability disallows both`() {
    val commands = service.playerCommandsFor(
      SkipCapability(canSkipPrevious = false, canSkipNext = false)
    )
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    assertTrue(
      "non-skip default commands survive the strip when media items are present",
      commands.contains(Player.COMMAND_PLAY_PAUSE)
    )
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
    assertTrue(commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
  }

  @Test
  fun `playerCommandsFor keeps transport commands when queue is empty`() {
    // Transport commands stay available so the API 33+ System UI keeps the
    // play/pause button + the draggable seek scrubber; only the skip pair
    // greys out with the queue boundary.
    val commands = service.playerCommandsFor(
      SkipCapability(canSkipPrevious = false, canSkipNext = false)
    )
    assertTrue(
      "play/pause is retained for the System UI play/pause button",
      commands.contains(Player.COMMAND_PLAY_PAUSE)
    )
    assertTrue(commands.contains(Player.COMMAND_PREPARE))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
    assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
  }

  // --- TrackPlayer-side handler implementation

  @Test
  fun `TrackPlayer onTransportCommand stamps SYSTEM on pendingStateChangeReason`() {
    val player = newConfiguredPlayer()
    try {
      assertNull(player.pendingStateChangeReason)
      player.mediaSessionCommandHandler.onTransportCommand()
      assertEquals(StateChangeReason.SYSTEM, player.pendingStateChangeReason)
    } finally {
      tearDownPlayer(player)
    }
  }

  @Test
  fun `TrackPlayer onSkipToNextCommand advances index and stamps reasons`() {
    val player = newConfiguredPlayer()
    try {
      player.tracks = listOf(
        TrackItem("a", "https://example.com/a.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("b", "https://example.com/b.m4a", null, null, null, null, null, null, null, null, null, null),
      )
      player.currentTrackIndex = 0

      val intercepted = player.mediaSessionCommandHandler.onSkipToNextCommand()

      assertTrue(
        "skip-next is queue-aware and reports interception so the default routing skips",
        intercepted
      )
      assertEquals(1, player.currentTrackIndex)
      assertEquals(
        StateChangeReason.SYSTEM, player.pendingStateChangeReason
      )
      assertEquals(
        TrackChangeReason.USER_SKIP_NEXT, player.pendingTrackChangeReason
      )
    } finally {
      tearDownPlayer(player)
    }
  }

  @Test
  fun `TrackPlayer recomputeCapabilities feeds the latest skip cap into the registered handler`() {
    val player = newConfiguredPlayer()
    try {
      // Empty queue — both skips disallowed.
      player.recomputeCapabilities()
      val empty = player.mediaSessionCommandHandler.getCachedSkipCapability()
      assertFalse(empty.canSkipNext)
      assertFalse(empty.canSkipPrevious)

      // Two-track queue at index 0 — skip-next now allowed.
      player.tracks = listOf(
        TrackItem("a", "https://example.com/a.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("b", "https://example.com/b.m4a", null, null, null, null, null, null, null, null, null, null),
      )
      player.currentTrackIndex = 0
      player.recomputeCapabilities()

      val mid = player.mediaSessionCommandHandler.getCachedSkipCapability()
      assertTrue(mid.canSkipNext)
      assertFalse(mid.canSkipPrevious)

      // The handler's value is what `PlaybackCallback.onConnect` reads,
      // so a fresh `onConnect` against any session sharing this handler
      // sees the post-flip command set.
      val playerCommands = service.playerCommandsFor(mid)
      assertTrue(playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
      assertFalse(playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    } finally {
      tearDownPlayer(player)
    }
  }

  @Test
  fun `TrackPlayer onSkipToPreviousCommand walks index back and stamps reasons`() {
    val player = newConfiguredPlayer()
    try {
      player.tracks = listOf(
        TrackItem("a", "https://example.com/a.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("b", "https://example.com/b.m4a", null, null, null, null, null, null, null, null, null, null),
      )
      player.currentTrackIndex = 1

      val intercepted = player.mediaSessionCommandHandler.onSkipToPreviousCommand()

      assertTrue(intercepted)
      assertEquals(0, player.currentTrackIndex)
      assertEquals(
        StateChangeReason.SYSTEM, player.pendingStateChangeReason
      )
      assertEquals(
        TrackChangeReason.USER_SKIP_PREVIOUS, player.pendingTrackChangeReason
      )
    } finally {
      tearDownPlayer(player)
    }
  }

  @Test
  fun `TrackPlayer onSeekToMediaItemCommand jumps to the tapped index and stamps reasons`() {
    val player = newConfiguredPlayer()
    try {
      player.tracks = listOf(
        TrackItem("a", "https://example.com/a.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("b", "https://example.com/b.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("c", "https://example.com/c.m4a", null, null, null, null, null, null, null, null, null, null),
      )
      player.currentTrackIndex = 0

      val intercepted = player.mediaSessionCommandHandler.onSeekToMediaItemCommand(2)

      assertTrue(
        "an Android Auto queue-row tap is queue-aware and reports interception",
        intercepted
      )
      assertEquals(2, player.currentTrackIndex)
      assertEquals(StateChangeReason.SYSTEM, player.pendingStateChangeReason)
      assertEquals(
        TrackChangeReason.USER_SKIP_TO_INDEX, player.pendingTrackChangeReason
      )
    } finally {
      tearDownPlayer(player)
    }
  }

  @Test
  fun `TrackPlayer onSeekToMediaItemCommand declines an out-of-range index and leaves the current index`() {
    val player = newConfiguredPlayer()
    try {
      player.tracks = listOf(
        TrackItem("a", "https://example.com/a.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("b", "https://example.com/b.m4a", null, null, null, null, null, null, null, null, null, null),
      )
      player.currentTrackIndex = 1

      val intercepted = player.mediaSessionCommandHandler.onSeekToMediaItemCommand(5)

      assertFalse(
        "an out-of-range tap is declined so the default routing no-ops it",
        intercepted
      )
      assertEquals(1, player.currentTrackIndex)
    } finally {
      tearDownPlayer(player)
    }
  }

  @Test
  fun `TrackPlayer onSeekToMediaItemCommand on the current index is handled and keeps the index`() {
    val player = newConfiguredPlayer()
    try {
      player.tracks = listOf(
        TrackItem("a", "https://example.com/a.m4a", null, null, null, null, null, null, null, null, null, null),
        TrackItem("b", "https://example.com/b.m4a", null, null, null, null, null, null, null, null, null, null),
      )
      player.currentTrackIndex = 1

      val intercepted = player.mediaSessionCommandHandler.onSeekToMediaItemCommand(1)

      // Tapping the playing row behaves like skipToIndex(current): handled,
      // index unchanged, the engine restarts the track from the top.
      assertTrue(intercepted)
      assertEquals(1, player.currentTrackIndex)
    } finally {
      tearDownPlayer(player)
    }
  }

  // --- Voice / browse-leaf routing through onAddMediaItems / onSetMediaItems

  @Test
  fun `onAddMediaItems asks for append and onSetMediaItems does not`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)
    fakeEnv.idResolution = listOf(
      MediaItem.Builder().setMediaId("resolved")
        .setUri("https://example.com/resolved.m4a".toUri()).build()
    )
    fun idOnlyItem() = MediaItem.Builder().setMediaId("album:1").build()

    routingCallback.onAddMediaItems(
      session, controllerInfo, mutableListOf(idOnlyItem())
    ).get()
    // "Queue up X" grows the queue; Media3 appends what the resolution
    // returns, so the lib's own queue has to grow by the same amount rather
    // than being replaced by the new tracks alone.
    assertEquals(listOf(true), fakeEnv.idAppendFlags)

    fakeEnv.idAppendFlags.clear()
    routingCallback.onSetMediaItems(
      session, controllerInfo, mutableListOf(idOnlyItem()), 0, 0L
    ).get()
    // A browse-leaf tap is a genuine replace: it takes the no-sync route,
    // where the JS handler's own `setQueue` is the only writer, so no
    // append-flagged request reaches the environment at all.
    assertEquals(listOf("album:1"), fakeEnv.idRequestsNoSync)
    assertEquals(emptyList<Boolean>(), fakeEnv.idAppendFlags)
  }

  @Test
  fun `onAddMediaItems with searchQuery dispatches to onPlayFromSearchRequest`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)
    val resolvedUri = "https://example.com/resolved.m4a".toUri()
    fakeEnv.searchResolution = listOf(
      MediaItem.Builder().setMediaId("resolved").setUri(resolvedUri).build()
    )

    val extras = android.os.Bundle().apply {
      putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/album")
      putString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM, "Pet Sounds")
    }
    val item = MediaItem.Builder()
      .setRequestMetadata(
        MediaItem.RequestMetadata.Builder()
          .setSearchQuery("play Pet Sounds")
          .setExtras(extras)
          .build()
      )
      .build()
    val future = routingCallback.onAddMediaItems(
      session, controllerInfo, mutableListOf(item)
    )
    val result = future.get()

    assertEquals(1, fakeEnv.searchRequests.size)
    val req = fakeEnv.searchRequests.first()
    assertEquals("play Pet Sounds", req.query)
    assertEquals(MediaSearchType.ALBUM, req.type)
    assertEquals("Pet Sounds", req.album)
    assertEquals(0, fakeEnv.idRequests.size)
    assertEquals(1, result.size)
    assertEquals("resolved", result.first().mediaId)
  }

  @Test
  fun `onAddMediaItems with mediaId only dispatches to onPlayFromIdRequest and returns resolved items`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)
    val resolvedUri = "https://example.com/r1.m4a".toUri()
    fakeEnv.idResolution = listOf(
      MediaItem.Builder().setMediaId("r1").setUri(resolvedUri).build()
    )

    val item = MediaItem.Builder().setMediaId("album:42").build()
    val future = routingCallback.onAddMediaItems(
      session, controllerInfo, mutableListOf(item)
    )
    val result = future.get()

    // Add-from-mediaId routes through the JS handler so Media3
    // appends the resolved items to the existing queue.
    assertEquals(0, fakeEnv.searchRequests.size)
    assertEquals(listOf("album:42"), fakeEnv.idRequests)
    assertEquals(1, result.size)
    assertEquals("r1", result.first().mediaId)
  }

  @Test
  fun `onSetMediaItems with mediaId only fires onPlayFromIdRequestNoSync and returns noop`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)

    val item = MediaItem.Builder().setMediaId("album:42:track:3").build()
    val future = routingCallback.onSetMediaItems(
      session,
      controllerInfo,
      mutableListOf(item),
      /* startIndex = */ 0,
      /* startPositionMs = */ 0L
    )
    val result = future.get()

    // Browse-leaf tap: JS handler owns the queue. Lib fires the
    // no-sync dispatch (so the lib does NOT re-apply the resolved
    // tracks and clobber `currentTrackIndex` back to 0) + returns
    // a marker consumed by the session wrapper so JS is the only writer.
    assertEquals(listOf("album:42:track:3"), fakeEnv.idRequestsNoSync)
    assertEquals(0, fakeEnv.idRequests.size)
    assertEquals(listOf(SessionCommandForwardingPlayer.JS_MANAGED_QUEUE_ITEM), result.mediaItems)
    assertEquals(C.INDEX_UNSET, result.startIndex)
    assertEquals(C.TIME_UNSET, result.startPositionMs)
  }

  @Test
  fun `repeated browse selections do not clear the session or announce the previous item`() {
    val routingCallback = PlaybackServiceCallback(RecordingEnvironment(service))
    val nativePlayer = binder.engine.mediaSessionPlayer
    val items = (0..2).map { index ->
      MediaItem.Builder().setMediaId("track:$index")
        .setUri("file:///queue-player-test/$index.m4a").build()
    }
    nativePlayer.setMediaItems(items)
    val events = mutableListOf<Pair<String?, Int>>()
    nativePlayer.addListener(object : Player.Listener {
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        events.add(mediaItem?.mediaId to nativePlayer.currentMediaItemIndex)
      }
    })

    for (index in 1..2) {
      val result = routingCallback.onSetMediaItems(
        session, controllerInfo,
        mutableListOf(MediaItem.Builder().setMediaId("track:$index").build()),
        0, 0L
      ).get()
      // Media3 applies INDEX_UNSET results using setMediaItems(items, true).
      // This must preserve the queue until the JS callback selects its target.
      session.player.setMediaItems(result.mediaItems, true)
      assertEquals(3, nativePlayer.mediaItemCount)
      assertEquals(index - 1, nativePlayer.currentMediaItemIndex)
      nativePlayer.seekTo(index, 0L)
    }
    assertEquals(listOf("track:1" to 1, "track:2" to 2), events)

    // Real empty playlists must continue to clear the player.
    session.player.setMediaItems(emptyList(), true)
    assertEquals(0, nativePlayer.mediaItemCount)
  }

  @Test
  fun `onAddMediaItems with localConfiguration falls through to super`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)
    val resolvedUri = "https://example.com/playable.m4a".toUri()
    val item = MediaItem.Builder().setMediaId("playable").setUri(resolvedUri).build()
    val future = routingCallback.onAddMediaItems(
      session, controllerInfo, mutableListOf(item)
    )
    val result = future.get()

    assertEquals(0, fakeEnv.searchRequests.size)
    assertEquals(0, fakeEnv.idRequests.size)
    assertEquals(
      "default super impl returns the input items unchanged when they have localConfiguration",
      1, result.size
    )
    assertEquals("playable", result.first().mediaId)
  }

  @Test
  fun `onSetMediaItems with searchQuery returns resolved items at index 0`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)
    val resolvedUri = "https://example.com/voice.m4a".toUri()
    fakeEnv.searchResolution = listOf(
      MediaItem.Builder().setMediaId("voice").setUri(resolvedUri).build()
    )

    val item = MediaItem.Builder()
      .setRequestMetadata(
        MediaItem.RequestMetadata.Builder()
          .setSearchQuery("play something")
          .build()
      )
      .build()
    val future = routingCallback.onSetMediaItems(
      session,
      controllerInfo,
      mutableListOf(item),
      /* startIndex = */ 7,
      /* startPositionMs = */ 30_000L
    )
    val result = future.get()

    assertEquals(1, result.mediaItems.size)
    assertEquals("voice", result.mediaItems.first().mediaId)
    assertEquals(
      "voice routing always plays from the start regardless of controller's requested startIndex",
      0,
      result.startIndex
    )
    assertEquals(C.TIME_UNSET, result.startPositionMs)
  }

  @Test
  fun `onAddMediaItems with empty list returns empty without dispatching`() {
    val fakeEnv = RecordingEnvironment(service)
    val routingCallback = PlaybackServiceCallback(fakeEnv)
    val future = routingCallback.onAddMediaItems(
      session, controllerInfo, mutableListOf()
    )
    val result = future.get()
    assertTrue(result.isEmpty())
    assertEquals(0, fakeEnv.searchRequests.size)
    assertEquals(0, fakeEnv.idRequests.size)
  }

  // --- Becoming-noisy routing

  @Test
  fun `ACTION_AUDIO_BECOMING_NOISY broadcast routes through the registered handler`() {
    val handler = RecordingHandler()
    binder.setCommandHandler(handler)

    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, handler.becomingNoisyCalls)
  }

  @Test
  fun `ACTION_AUDIO_BECOMING_NOISY broadcast is a no-op when no handler is registered`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    // No handler set on the binder — receiver fires with commandHandler == null.
    context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    shadowOf(Looper.getMainLooper()).idle()
    // Reaching here without throwing means the receiver tolerated the
    // null handler. Register a handler afterwards to confirm the
    // receiver is still alive for subsequent broadcasts.
    val handler = RecordingHandler()
    binder.setCommandHandler(handler)
    context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, handler.becomingNoisyCalls)
  }

  @Test
  fun `headless onConnect fires onFirstHeadlessControllerAttached exactly once across multiple connects`() {
    val fakeEnv = RecordingEnvironment(service)
    val cb = PlaybackServiceCallback(fakeEnv)
    val gearhead = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.google.android.projection.gearhead", 0, 0, 0, 0, false,
      android.os.Bundle.EMPTY, false
    )
    val wear = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.google.android.wearable.app", 0, 0, 0, 0, false,
      android.os.Bundle.EMPTY, false
    )
    cb.onConnect(session, gearhead)
    cb.onConnect(session, wear)
    assertEquals(1, fakeEnv.firstAttachedCalls)
    assertEquals(0, fakeEnv.disconnectCalls)
    assertEquals(listOf(gearhead.packageName, wear.packageName), fakeEnv.headlessConnectPackages)
  }

  @Test
  fun `headless onDisconnected fires onHeadlessControllerDisconnect only when the LAST controller drops`() {
    val fakeEnv = RecordingEnvironment(service)
    val cb = PlaybackServiceCallback(fakeEnv)
    val a = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.google.android.projection.gearhead", 0, 0, 0, 0, false,
      android.os.Bundle.EMPTY, false
    )
    val b = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.google.android.wearable.app", 0, 0, 0, 0, false,
      android.os.Bundle.EMPTY, false
    )
    cb.onConnect(session, a)
    cb.onConnect(session, b)
    cb.onDisconnected(session, a)
    assertEquals("first disconnect with one remaining: no fire", 0, fakeEnv.disconnectCalls)
    cb.onDisconnected(session, b)
    assertEquals("last disconnect: fire once", 1, fakeEnv.disconnectCalls)
  }

  @Test
  fun `non-headless package connect+disconnect does not move headless tracking`() {
    val fakeEnv = RecordingEnvironment(service)
    val cb = PlaybackServiceCallback(fakeEnv)
    val phone = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.example.regular.app", 0, 0, 0, 0, false,
      android.os.Bundle.EMPTY, false
    )
    cb.onConnect(session, phone)
    cb.onDisconnected(session, phone)
    assertEquals(0, fakeEnv.firstAttachedCalls)
    assertEquals(0, fakeEnv.disconnectCalls)
    assertEquals(0, fakeEnv.headlessConnectPackages.size)
  }

  // --- isCarConnected: the process-wide flag TrackPlayer.isCarConnected() reads

  @Test
  fun `CarConnectionState is disconnected with no controller attached`() {
    assertFalse(CarConnectionState.isConnected())
  }

  @Test
  fun `headless connect marks connected, last disconnect marks disconnected`() {
    val cb = PlaybackServiceCallback(RecordingEnvironment(service))
    val gearhead = controllerFor("com.google.android.projection.gearhead")
    cb.onConnect(session, gearhead)
    assertTrue("connected after the first headless controller", CarConnectionState.isConnected())
    cb.onDisconnected(session, gearhead)
    assertFalse("disconnected after the last controller drops", CarConnectionState.isConnected())
  }

  @Test
  fun `stays connected while any headless controller remains`() {
    val cb = PlaybackServiceCallback(RecordingEnvironment(service))
    val gearhead = controllerFor("com.google.android.projection.gearhead")
    val wear = controllerFor("com.google.android.wearable.app")
    cb.onConnect(session, gearhead)
    cb.onConnect(session, wear)
    cb.onDisconnected(session, gearhead)
    assertTrue("still connected with one controller remaining", CarConnectionState.isConnected())
    cb.onDisconnected(session, wear)
    assertFalse("disconnected once the last controller drops", CarConnectionState.isConnected())
  }

  @Test
  fun `non-headless controller leaves the flag disconnected`() {
    val cb = PlaybackServiceCallback(RecordingEnvironment(service))
    val phone = controllerFor("com.example.regular.app")
    cb.onConnect(session, phone)
    assertFalse(CarConnectionState.isConnected())
    cb.onDisconnected(session, phone)
    assertFalse(CarConnectionState.isConnected())
  }

  @Test
  fun `untracked headless disconnect does not mark connected`() {
    val cb = PlaybackServiceCallback(RecordingEnvironment(service))
    val gearhead = controllerFor("com.google.android.projection.gearhead")
    // A stray disconnect for a controller that never connected (service-
    // restart edge) must not mark connected; the count floors at zero.
    cb.onDisconnected(session, gearhead)
    assertFalse(CarConnectionState.isConnected())
  }

  @Test
  fun `a stray disconnect floors the count so a later connect still reads connected`() {
    val cb = PlaybackServiceCallback(RecordingEnvironment(service))
    val gearhead = controllerFor("com.google.android.projection.gearhead")
    cb.onConnect(session, gearhead)
    cb.onDisconnected(session, gearhead)
    // Extra (stray) disconnect: the count must floor at zero, not go negative.
    cb.onDisconnected(session, gearhead)
    assertFalse("disconnected after the real drop", CarConnectionState.isConnected())
    // If the stray disconnect had driven the count to -1, this connect would
    // land it back at 0 and read disconnected.
    cb.onConnect(session, gearhead)
    assertTrue("connected again after a fresh attach", CarConnectionState.isConnected())
  }

  private fun controllerFor(packageName: String): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      packageName, 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )

  @Test
  fun `becoming-noisy receiver unregisters in onDestroy`() {
    val handler = RecordingHandler()
    binder.setCommandHandler(handler)
    controller.destroy()

    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      "no broadcasts route through after the service is destroyed",
      0, handler.becomingNoisyCalls
    )
  }

  // --- Helpers

  private fun newConfiguredPlayer(): TrackPlayer {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val player = TrackPlayer()
    val handle = bindPlaybackServiceForTest(player, context)
    player.configureInternal(
      PlayerConfig(
        enableDiagnosticLogs = null,
        httpHeaders = null,
        userAgent = null,
        autoRetries = null,
        retryBackoffMs = null,
        networkTimeoutMs = null,
        audioContentType = null,
        audioCategoryOptions = null,
        voiceVocabularyDonationEnabled = null,
        allowsExternalPlayback = null,
      visualizationEnabled = null,
        clampSeekToBuffered = null,
      lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null,
      ),
      context,
    )
    activeHandles[player] = handle
    return player
  }

  private fun tearDownPlayer(player: TrackPlayer) {
    player.destroyInternal()
    activeHandles.remove(player)?.destroy()
  }

  private val activeHandles = HashMap<TrackPlayer, PlaybackServiceTestHandle>()

  private class RecordingHandler(
    private val skipNextHandled: Boolean = true,
    private val skipPreviousHandled: Boolean = true,
    var capability: SkipCapability = SkipCapability(
      canSkipPrevious = true, canSkipNext = true
    ),
  ) : PlaybackService.MediaSessionCommandHandler {
    var transportCalls = 0
    var skipNextCalls = 0
    var skipPreviousCalls = 0
    var becomingNoisyCalls = 0

    override fun onTransportCommand() {
      transportCalls++
    }

    override fun onSkipToNextCommand(): Boolean {
      skipNextCalls++
      return skipNextHandled
    }

    override fun onSkipToPreviousCommand(): Boolean {
      skipPreviousCalls++
      return skipPreviousHandled
    }

    override fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean = false

    override fun getCachedSkipCapability(): SkipCapability = capability

    // This suite covers the controller-driven callback routing only.
    override fun onMediaPlay() {}
    override fun onMediaPause() {}
    override fun onMediaTogglePlayPause() {}
    override fun onMediaSkipNext() {}
    override fun onMediaSkipPrevious() {}

    override fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping) {}

    override fun onBecomingNoisy() {
      becomingNoisyCalls++
    }
  }

  /**
   * Test-side [PlaybackServiceCallback.Environment] that records voice
   * + browse-leaf dispatches and returns canned MediaItem futures so
   * the callback's routing logic can be exercised without spinning up
   * the production coroutine path.
   */
  private class RecordingEnvironment(
    private val service: PlaybackService,
  ) : PlaybackServiceCallback.Environment {
    var searchResolution: List<MediaItem> = emptyList()
    var idResolution: List<MediaItem> = emptyList()
    val searchRequests = mutableListOf<MediaSearchRequest>()
    val idRequests = mutableListOf<String>()
    val idRequestsNoSync = mutableListOf<String>()
    var firstAttachedCalls = 0
    var disconnectCalls = 0
    val headlessConnectPackages = mutableListOf<String>()

    override val browseDataProvider: BrowseDataProvider
      get() = service.browseDataProvider

    override val searchResultCache: SearchResultCache
      get() = service.searchResultCache

    override fun commandHandler(): PlaybackService.MediaSessionCommandHandler? = null

    override fun mediaButtonDispatch(): MediaButtonDispatch? = null

    override fun playerCommandsFor(
      capability: SkipCapability
    ): Player.Commands =
      service.playerCommandsFor(capability)

    override fun onPlayFromSearchRequest(
      request: MediaSearchRequest,
      append: Boolean
    ): ListenableFuture<List<MediaItem>> {
      searchRequests += request
      searchAppendFlags += append
      return Futures.immediateFuture(searchResolution)
    }

    override fun onPlayFromIdRequest(
      mediaId: String,
      append: Boolean
    ): ListenableFuture<List<MediaItem>> {
      idRequests += mediaId
      idAppendFlags += append
      return Futures.immediateFuture(idResolution)
    }

    val searchAppendFlags = mutableListOf<Boolean>()
    val idAppendFlags = mutableListOf<Boolean>()

    override fun onPlayFromIdRequestNoSync(mediaId: String) {
      idRequestsNoSync += mediaId
    }

    override fun onHeadlessControllerConnect(packageName: String) {
      headlessConnectPackages += packageName
    }

    override fun onFirstHeadlessControllerAttached() {
      firstAttachedCalls++
    }

    override fun onHeadlessControllerDisconnect() {
      disconnectCalls++
    }
  }
}
