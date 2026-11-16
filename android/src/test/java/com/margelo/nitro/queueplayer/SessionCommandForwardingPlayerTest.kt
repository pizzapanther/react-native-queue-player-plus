package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.runWithControllerForCurrentRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [SessionCommandForwardingPlayer] — the
 * `MediaSession`-stable wrapper that intercepts external-controller
 * transport commands (lock-screen / Android Auto / Assistant /
 * Bluetooth) before they reach the bound engine player.
 *
 * The wrapper's `handle*` overrides are `protected` on a `final`
 * Kotlin class, so the suite drives them through [HandleDriver] — a
 * thin reflection helper into the exact overridden methods — to assert
 * the routing directly without standing up a live `MediaController`.
 *
 * Controller-vs-internal gating turns on whether
 * `MediaSession.getControllerForCurrentRequest()` is non-null. The
 * tests run the wrapper call inside
 * [runWithControllerForCurrentRequest] to mark it controller-originated
 * (matching how Media3 marks real controller calls), or outside it to
 * model the JS bridge driving the engine player directly.
 *
 * The wrapped delegate is [RecordingPlayer], a [SimpleBasePlayer] whose
 * `handle*` overrides record exactly which transport the wrapper
 * forwarded — so a veto (no forward) is observably distinct from a
 * delegated seek (forward recorded).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class SessionCommandForwardingPlayerTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService
  private lateinit var session: MediaSession
  private lateinit var controllerInfo: MediaSession.ControllerInfo

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = Intent(context.applicationContext, PlaybackService::class.java)
      .setAction(PlaybackService.ACTION_LOCAL_BIND)
    val binder = service.onBind(intent) as PlaybackService.LocalBinder
    session = binder.session
    controllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "test-controller", 0, 0, 0, 0, false, android.os.Bundle.EMPTY, false
    )
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  // --- Controller skip-next: queue-aware veto

  @Test
  fun `controller skip-next runs queue-aware skip and vetoes the default seek when handled`() {
    for (command in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
      val wrapped = RecordingPlayer()
      val handler = RecordingHandler(skipNextHandled = true)
      val wrapper = newWrapper(wrapped, handler)

      asController { wrapper.handleSeek(C_INDEX, 0L, command) }

      assertEquals("skip-next routes into onSkipToNextCommand for $command", 1, handler.skipNextCalls)
      assertTrue("handled skip vetoes the default seek for $command", wrapped.seekCommands.isEmpty())
      assertEquals(0, handler.transportCalls)
    }
  }

  @Test
  fun `controller skip-next falls through to the default seek when not handled`() {
    for (command in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
      val wrapped = RecordingPlayer()
      val handler = RecordingHandler(skipNextHandled = false)
      val wrapper = newWrapper(wrapped, handler)

      asController { wrapper.handleSeek(C_INDEX, 0L, command) }

      assertEquals(1, handler.skipNextCalls)
      assertEquals("unhandled skip delegates one seek to the wrapped player for $command",
        listOf(command), wrapped.seekCommands)
    }
  }

  // --- Controller skip-previous: symmetric

  @Test
  fun `controller skip-previous runs queue-aware skip and vetoes the default seek when handled`() {
    for (command in listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
      val wrapped = RecordingPlayer()
      val handler = RecordingHandler(skipPreviousHandled = true)
      val wrapper = newWrapper(wrapped, handler)

      asController { wrapper.handleSeek(C_INDEX, 0L, command) }

      assertEquals("skip-previous routes into onSkipToPreviousCommand for $command", 1, handler.skipPreviousCalls)
      assertTrue("handled skip vetoes the default seek for $command", wrapped.seekCommands.isEmpty())
      assertEquals(0, handler.transportCalls)
    }
  }

  @Test
  fun `controller skip-previous falls through to the default seek when not handled`() {
    for (command in listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
      val wrapped = RecordingPlayer()
      val handler = RecordingHandler(skipPreviousHandled = false)
      val wrapper = newWrapper(wrapped, handler)

      asController { wrapper.handleSeek(C_INDEX, 0L, command) }

      assertEquals(1, handler.skipPreviousCalls)
      assertEquals("unhandled skip delegates one seek to the wrapped player for $command",
        listOf(command), wrapped.seekCommands)
    }
  }

  // --- Controller seek-to-queue-item (Android Auto queue-row tap): queue-aware veto
  // (COMMAND_SEEK_TO_MEDIA_ITEM routes through onSeekToMediaItemCommand rather than
  // the raw per-item seek, so the lib's current index + track-change reason land.)

  @Test
  fun `controller seek-to-queue-item runs queue-aware skip and vetoes the default seek when handled`() {
    val wrapped = RecordingPlayer()
    val handler = RecordingHandler(seekToMediaItemHandled = true)
    val wrapper = newWrapper(wrapped, handler)

    asController { wrapper.handleSeek(2, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM) }

    assertEquals("seek-to-queue-item routes into onSeekToMediaItemCommand", 1, handler.seekToMediaItemCalls)
    assertEquals("the tapped queue index is forwarded", 2, handler.lastSeekToMediaItemIndex)
    assertTrue("a handled queue tap vetoes the default seek", wrapped.seekCommands.isEmpty())
    assertEquals(0, handler.transportCalls)
  }

  @Test
  fun `controller seek-to-queue-item falls through to the default seek when not handled`() {
    val wrapped = RecordingPlayer()
    val handler = RecordingHandler(seekToMediaItemHandled = false)
    val wrapper = newWrapper(wrapped, handler)

    asController { wrapper.handleSeek(2, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM) }

    assertEquals(1, handler.seekToMediaItemCalls)
    assertEquals("a declined queue tap delegates one seek to the wrapped player",
      listOf(Player.COMMAND_SEEK_TO_MEDIA_ITEM), wrapped.seekCommands)
  }

  // --- Controller transport seeks: stamp system then delegate
  // (SEEK_BACK / SEEK_FORWARD are recomputed to the runtime interval — see the
  // interval-skip tests above — so they are not part of this pass-through set.)

  @Test
  fun `controller transport seeks stamp a transport command and delegate to the wrapped player`() {
    val transportSeeks = listOf(
      Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    )
    for (command in transportSeeks) {
      val wrapped = RecordingPlayer()
      val handler = RecordingHandler()
      val wrapper = newWrapper(wrapped, handler)

      asController { wrapper.handleSeek(C_INDEX, 0L, command) }

      assertEquals("transport seek $command stamps onTransportCommand exactly once", 1, handler.transportCalls)
      assertEquals(0, handler.skipNextCalls)
      assertEquals(0, handler.skipPreviousCalls)
      assertEquals("transport seek $command delegates to the wrapped player",
        listOf(command), wrapped.seekCommands)
    }
  }

  // --- Interval-skip mode: recompute the target from the runtime jump interval

  @Test
  fun `controller seek-forward jumps by the runtime forward interval from the current position`() {
    val wrapped = RecordingPlayer() // currentPosition 0, duration unknown
    val handler = RecordingHandler()
    val driver = newWrapper(wrapped, handler) // default forwardJumpMs = 15_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_FORWARD) }

    assertEquals("interval seek stamps onTransportCommand exactly once", 1, handler.transportCalls)
    // Delegated as an absolute in-item seek, not a relative seekForward() —
    // the wrapped player must land on the computed target, not its own
    // build-time increment.
    assertEquals(listOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM), wrapped.seekCommands)
    assertEquals("forward seeks currentPosition(0) + forwardJumpMs(15000)",
      listOf(15_000L), wrapped.seekPositions)
  }

  @Test
  fun `controller seek-back clamps to zero at the start of the track`() {
    val wrapped = RecordingPlayer()
    val driver = newWrapper(wrapped, RecordingHandler()) // default backwardJumpMs = 15_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_BACK) }

    assertEquals(listOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM), wrapped.seekCommands)
    assertEquals("back from 0 clamps to 0, never negative", listOf(0L), wrapped.seekPositions)
  }

  @Test
  fun `a runtime forward-interval change takes effect on the next seek without a rebuild`() {
    val wrapped = RecordingPlayer()
    val driver = newWrapper(wrapped, RecordingHandler())
    driver.wrapper.forwardJumpMs = 30_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_FORWARD) }

    assertEquals("forward uses the updated 30s interval", listOf(30_000L), wrapped.seekPositions)
  }

  // --- clampSeekToBuffered: cap an interval skip-forward at the buffered edge
  //     when the target is past what's downloaded (buffered-based, any source).

  @Test
  fun `clampSeekToBuffered caps an interval skip-forward past the buffered edge`() {
    val wrapped = RecordingPlayer(bufferedPositionMs = 20_000L)
    val driver = newWrapper(wrapped, RecordingHandler())
    driver.wrapper.clampSeekToBuffered = true
    driver.wrapper.forwardJumpMs = 30_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_FORWARD) }

    assertEquals(
      "forward past the buffer clamps to the buffered position",
      listOf(20_000L),
      wrapped.seekPositions,
    )
  }

  @Test
  fun `clampSeekToBuffered off leaves an interval skip-forward past the buffer untouched`() {
    val wrapped = RecordingPlayer(bufferedPositionMs = 20_000L)
    val driver = newWrapper(wrapped, RecordingHandler())
    driver.wrapper.clampSeekToBuffered = false
    driver.wrapper.forwardJumpMs = 30_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_FORWARD) }

    assertEquals("with the clamp off, the full interval seeks free", listOf(30_000L), wrapped.seekPositions)
  }

  @Test
  fun `clampSeekToBuffered leaves a target within the buffered range untouched`() {
    val wrapped = RecordingPlayer(bufferedPositionMs = 40_000L)
    val driver = newWrapper(wrapped, RecordingHandler())
    driver.wrapper.clampSeekToBuffered = true
    driver.wrapper.forwardJumpMs = 30_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_FORWARD) }

    assertEquals(
      "a target inside the buffered range (or a fully-buffered track) seeks free",
      listOf(30_000L),
      wrapped.seekPositions,
    )
  }

  @Test
  fun `an interval skip on an unseekable track is a no-op`() {
    val wrapped = RecordingPlayer(seekable = false)
    val driver = newWrapper(wrapped, RecordingHandler())
    driver.wrapper.forwardJumpMs = 30_000

    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_FORWARD) }
    asController { driver.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_BACK) }

    assertEquals(
      "an unseekable track is not sought (no reset-to-start)",
      emptyList<Long>(),
      wrapped.seekPositions,
    )
  }

  // --- Controller play/pause + speed: stamp system then delegate

  @Test
  fun `controller setPlayWhenReady stamps a transport command and delegates`() {
    val wrapped = RecordingPlayer()
    val handler = RecordingHandler()
    val wrapper = newWrapper(wrapped, handler)

    asController { wrapper.handleSetPlayWhenReady(true) }

    assertEquals(1, handler.transportCalls)
    assertEquals(0, handler.skipNextCalls)
    assertEquals(listOf(true), wrapped.playWhenReadyCalls)
  }

  @Test
  fun `controller setPlaybackParameters stamps a transport command and delegates`() {
    val wrapped = RecordingPlayer()
    val handler = RecordingHandler()
    val wrapper = newWrapper(wrapped, handler)
    val params = PlaybackParameters(1.5f)

    asController { wrapper.handleSetPlaybackParameters(params) }

    assertEquals(1, handler.transportCalls)
    assertEquals(listOf(params), wrapped.playbackParameterCalls)
  }

  // --- Non-controller (JS-bridge) requests: never touch the handler

  @Test
  fun `non-controller skip delegates straight through without consulting the handler`() {
    for (command in listOf(
      Player.COMMAND_SEEK_TO_NEXT,
      Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_PREVIOUS,
      Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )) {
      val wrapped = RecordingPlayer()
      val handler = RecordingHandler(skipNextHandled = true, skipPreviousHandled = true)
      val wrapper = newWrapper(wrapped, handler)

      // No controller-request scope: this models the JS bridge driving
      // the engine player directly.
      wrapper.handleSeek(C_INDEX, 0L, command)

      assertEquals("internal skip never consults onSkipToNextCommand for $command", 0, handler.skipNextCalls)
      assertEquals("internal skip never consults onSkipToPreviousCommand for $command", 0, handler.skipPreviousCalls)
      assertEquals(0, handler.transportCalls)
      assertEquals("internal skip delegates straight to the wrapped player for $command",
        listOf(command), wrapped.seekCommands)
    }
  }

  @Test
  fun `non-controller seek-to-queue-item delegates straight through without consulting the handler`() {
    val wrapped = RecordingPlayer()
    val handler = RecordingHandler(seekToMediaItemHandled = true)
    val wrapper = newWrapper(wrapped, handler)

    // No controller-request scope: models the JS bridge / engine driving
    // the player directly, which must never re-enter the skip path.
    wrapper.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM)

    assertEquals("internal seek never consults onSeekToMediaItemCommand", 0, handler.seekToMediaItemCalls)
    assertEquals("internal seek delegates straight to the wrapped player",
      listOf(Player.COMMAND_SEEK_TO_MEDIA_ITEM), wrapped.seekCommands)
  }

  @Test
  fun `non-controller transport delegates straight through without consulting the handler`() {
    val wrapped = RecordingPlayer()
    val handler = RecordingHandler()
    val wrapper = newWrapper(wrapped, handler)

    wrapper.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_BACK)
    wrapper.handleSetPlayWhenReady(true)
    wrapper.handleSetPlaybackParameters(PlaybackParameters(2.0f))

    assertEquals("internal transport never stamps onTransportCommand", 0, handler.transportCalls)
    assertEquals(listOf(Player.COMMAND_SEEK_BACK), wrapped.seekCommands)
    assertEquals(listOf(true), wrapped.playWhenReadyCalls)
    assertEquals(1, wrapped.playbackParameterCalls.size)
  }

  // --- No registered handler: every override delegates without crashing

  @Test
  fun `controller requests delegate with no registered handler and do not crash`() {
    val wrapped = RecordingPlayer()
    val wrapper = newWrapper(wrapped, handler = null)

    asController { wrapper.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_TO_NEXT) }
    asController { wrapper.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_TO_PREVIOUS) }
    asController { wrapper.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM) }
    asController { wrapper.handleSeek(C_INDEX, 0L, Player.COMMAND_SEEK_BACK) }
    asController { wrapper.handleSetPlayWhenReady(true) }
    asController { wrapper.handleSetPlaybackParameters(PlaybackParameters(1.25f)) }

    // With no handler the skip / queue-tap cannot be vetoed, so all delegate.
    // SEEK_BACK is recomputed to the interval and forwarded as an in-item seek.
    assertEquals(
      listOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
      ),
      wrapped.seekCommands
    )
    assertEquals(listOf(true), wrapped.playWhenReadyCalls)
    assertEquals(1, wrapped.playbackParameterCalls.size)
  }

  // --- Helpers

  private fun newWrapper(
    wrapped: RecordingPlayer,
    handler: PlaybackService.MediaSessionCommandHandler?,
  ): HandleDriver =
    HandleDriver(SessionCommandForwardingPlayer(wrapped, { session }, { handler }))

  private fun asController(block: () -> Unit) {
    session.runWithControllerForCurrentRequest(controllerInfo, block)
  }

  /**
   * Reflection helper into a [SessionCommandForwardingPlayer]'s
   * `protected` `handle*` overrides. The wrapper is a `final` Kotlin
   * class so it cannot be subclassed; reflecting straight onto the
   * declared overrides invokes the exact production methods without
   * routing through the wrapper's own `SimpleBasePlayer` command-gating
   * machinery, isolating the routing logic under test. Lib visibility
   * is untouched.
   */
  private class HandleDriver(val wrapper: SessionCommandForwardingPlayer) {
    private val seek = SessionCommandForwardingPlayer::class.java
      .getDeclaredMethod(
        "handleSeek", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
      ).apply { isAccessible = true }
    private val playWhenReady = SessionCommandForwardingPlayer::class.java
      .getDeclaredMethod("handleSetPlayWhenReady", Boolean::class.javaPrimitiveType)
      .apply { isAccessible = true }
    private val playbackParameters = SessionCommandForwardingPlayer::class.java
      .getDeclaredMethod("handleSetPlaybackParameters", PlaybackParameters::class.java)
      .apply { isAccessible = true }

    fun handleSeek(mediaItemIndex: Int, positionMs: Long, @Player.Command seekCommand: Int) {
      seek.invoke(wrapper, mediaItemIndex, positionMs, seekCommand)
    }

    fun handleSetPlayWhenReady(value: Boolean) {
      playWhenReady.invoke(wrapper, value)
    }

    fun handleSetPlaybackParameters(params: PlaybackParameters) {
      playbackParameters.invoke(wrapper, params)
    }
  }

  /**
   * Minimal [SimpleBasePlayer] standing in for the wrapped engine
   * player. A three-window seekable playlist at index 1 lets every
   * forwarded seek (including the queue-relative next/previous) clear
   * Media3's command-availability + timeline gates and reach the
   * recording `handle*` overrides, so each forward is observable.
   */
  private class RecordingPlayer(
    // Non-null = a known window duration (µs); null = unknown duration
    // (getDuration() == C.TIME_UNSET), the signal the buffered-seek clamp keys
    // on. Default = 1-hour windows so interval jumps never clamp to a short
    // default duration (keeps the interval math deterministic).
    windowDurationUs: Long? = 3_600_000_000L,
    // Buffered position (ms) the clamp caps an over-buffer interval seek at.
    bufferedPositionMs: Long = 3_600_000L,
    // Whether the windows are seekable (the seekability guard reads this).
    seekable: Boolean = true,
  ) : SimpleBasePlayer(android.os.Looper.getMainLooper()) {
    val seekCommands = mutableListOf<Int>()
    val seekPositions = mutableListOf<Long>()
    val playWhenReadyCalls = mutableListOf<Boolean>()
    val playbackParameterCalls = mutableListOf<PlaybackParameters>()

    private val state: State = run {
      fun window(id: String): MediaItemData {
        val b = MediaItemData.Builder(id).setIsSeekable(seekable)
        if (windowDurationUs != null) b.setDurationUs(windowDurationUs)
        return b.build()
      }
      State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaybackState(Player.STATE_READY)
        .setPlaylist(listOf(window("a"), window("b"), window("c")))
        .setCurrentMediaItemIndex(1)
        .setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedPositionMs))
        .build()
    }

    override fun getState(): State = state

    override fun handleSeek(
      mediaItemIndex: Int,
      positionMs: Long,
      @Player.Command seekCommand: Int,
    ): com.google.common.util.concurrent.ListenableFuture<*> {
      seekCommands += seekCommand
      seekPositions += positionMs
      return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(
      playWhenReady: Boolean,
    ): com.google.common.util.concurrent.ListenableFuture<*> {
      playWhenReadyCalls += playWhenReady
      return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(
      playbackParameters: PlaybackParameters,
    ): com.google.common.util.concurrent.ListenableFuture<*> {
      playbackParameterCalls += playbackParameters
      return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
  }

  /**
   * Recording [PlaybackService.MediaSessionCommandHandler] that counts
   * the controller-routed transport / skip hooks and reports a
   * configurable handled/not-handled result for each skip direction.
   */
  private class RecordingHandler(
    private val skipNextHandled: Boolean = true,
    private val skipPreviousHandled: Boolean = true,
    private val seekToMediaItemHandled: Boolean = true,
  ) : PlaybackService.MediaSessionCommandHandler {
    var transportCalls = 0
    var skipNextCalls = 0
    var skipPreviousCalls = 0
    var seekToMediaItemCalls = 0
    var lastSeekToMediaItemIndex = Int.MIN_VALUE

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

    override fun onSeekToMediaItemCommand(mediaItemIndex: Int): Boolean {
      seekToMediaItemCalls++
      lastSeekToMediaItemIndex = mediaItemIndex
      return seekToMediaItemHandled
    }

    override fun getCachedSkipCapability(): SkipCapability =
      SkipCapability(canSkipPrevious = true, canSkipNext = true)

    override fun onMediaPlay() {}
    override fun onMediaPause() {}
    override fun onMediaTogglePlayPause() {}
    override fun onMediaSkipNext() {}
    override fun onMediaSkipPrevious() {}
    override fun onAudioFocusEvent(mapping: AudioFocusErrorMapping.Mapping) {}
    override fun onBecomingNoisy() {}
  }

  private companion object {
    // Position-only seeks ignore the media-item index; a fixed value
    // keeps the driver calls readable.
    const val C_INDEX = 1
  }
}
