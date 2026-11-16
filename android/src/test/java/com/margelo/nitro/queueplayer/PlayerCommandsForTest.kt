package com.margelo.nitro.queueplayer

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PlaybackService.playerCommandsFor] — the
 * single function that translates the lib's [SkipCapability] into the
 * [Player.Commands] set Media3 reports through `onConnect` and
 * `setAvailableCommands`. The mapping is the contract the lock-screen,
 * Bluetooth AVRCP, Android Auto, and Wear surfaces consume to grey
 * out skip buttons that the queue boundary disallows.
 *
 * Two slices:
 *  - The 4-cell capability matrix: every (canSkipPrevious × canSkipNext)
 *    combination → the expected presence / absence of the four skip
 *    commands plus the preserved non-skip commands.
 *  - The repeat-mode × queue-position derivation: drive
 *    [QueueSkipArithmetic] under each [RepeatMode] at the queue
 *    boundary and the queue interior, build the [SkipCapability], and
 *    assert the resulting [Player.Commands] match the four-cell matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlayerCommandsForTest {

  private lateinit var controller: ServiceController<PlaybackService>
  private lateinit var service: PlaybackService

  @Before
  fun setUp() {
    controller = Robolectric.buildService(PlaybackService::class.java).create()
    service = controller.get()
    // Bind the local binder so onCreate's session is initialised. The
    // capability-mapping code only reads constants, but the service
    // must be in its post-onCreate state to expose `playerCommandsFor`.
    val intent = Intent(
      ApplicationProvider.getApplicationContext<android.content.Context>(),
      PlaybackService::class.java,
    ).setAction(PlaybackService.ACTION_LOCAL_BIND)
    service.onBind(intent)
  }

  @After
  fun tearDown() {
    controller.destroy()
  }

  // --- 4-cell capability matrix

  @Test
  fun `commands include every skip pair when capability allows both`() {
    val commands = service.playerCommandsFor(
      SkipCapability(canSkipPrevious = true, canSkipNext = true),
    )
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    assertNonSkipCommandsPreserved(commands)
  }

  @Test
  fun `commands omit only the next pair when capability disallows skip-next`() {
    val commands = service.playerCommandsFor(
      SkipCapability(canSkipPrevious = true, canSkipNext = false),
    )
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    assertNonSkipCommandsPreserved(commands)
  }

  @Test
  fun `commands omit only the previous pair when capability disallows skip-previous`() {
    val commands = service.playerCommandsFor(
      SkipCapability(canSkipPrevious = false, canSkipNext = true),
    )
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    assertNonSkipCommandsPreserved(commands)
  }

  @Test
  fun `commands omit both skip pairs when capability disallows both`() {
    val commands = service.playerCommandsFor(
      SkipCapability(canSkipPrevious = false, canSkipNext = false),
    )
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    assertNonSkipCommandsPreserved(commands)
  }

  // --- RepeatMode × queue-position derivation

  @Test
  fun `repeat OFF at index 0 of a two-track queue exposes only skip-next`() {
    val commands = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.OFF, count = 2, current = 0),
    )
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `repeat OFF at the last index of a two-track queue exposes only skip-previous`() {
    val commands = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.OFF, count = 2, current = 1),
    )
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `repeat OFF on a single-track queue exposes neither skip command`() {
    val commands = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.OFF, count = 1, current = 0),
    )
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `repeat QUEUE wraps so both skip commands stay enabled at every queue position`() {
    val first = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.QUEUE, count = 3, current = 0),
    )
    val mid = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.QUEUE, count = 3, current = 1),
    )
    val last = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.QUEUE, count = 3, current = 2),
    )
    for (commands in listOf(first, mid, last)) {
      assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
      assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }
  }

  @Test
  fun `repeat TRACK exposes skip commands with no wrap, like OFF`() {
    // First track of 2: next available, no previous (no wrap).
    val atFirst = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.TRACK, count = 2, current = 0),
    )
    assertTrue(atFirst.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertFalse(atFirst.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

    // Last track of 2: previous available, no next (no wrap).
    val atLast = service.playerCommandsFor(
      capabilityFor(repeat = QueueSkipRepeatMode.TRACK, count = 2, current = 1),
    )
    assertFalse(atLast.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    assertTrue(atLast.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
  }

  @Test
  fun `empty queue disables skip but keeps transport commands for the System UI`() {
    for (mode in QueueSkipRepeatMode.entries) {
      val commands = service.playerCommandsFor(
        capabilityFor(repeat = mode, count = 0, current = -1),
      )
      assertFalse(
        "skip-next is disabled with no tracks (repeat=$mode)",
        commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
      )
      assertFalse(
        "skip-previous is disabled with no tracks (repeat=$mode)",
        commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
      )
      assertTrue(
        "play/pause is retained so the API 33+ System UI keeps the button (repeat=$mode)",
        commands.contains(Player.COMMAND_PLAY_PAUSE),
      )
      assertTrue(
        "in-item seek is retained so the seek scrubber stays draggable (repeat=$mode)",
        commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
      )
    }
  }

  // --- Helpers

  private fun capabilityFor(
    repeat: QueueSkipRepeatMode,
    count: Int,
    current: Int,
  ): SkipCapability = SkipCapability(
    canSkipPrevious = QueueSkipArithmetic.canSkipPrevious(count, current, repeat),
    canSkipNext = QueueSkipArithmetic.canSkipNext(count, current, repeat),
  )

  private fun assertNonSkipCommandsPreserved(commands: Player.Commands) {
    assertTrue(
      "PLAY_PAUSE survives the skip strip",
      commands.contains(Player.COMMAND_PLAY_PAUSE),
    )
    assertTrue(
      "SEEK_TO_DEFAULT_POSITION survives the skip strip",
      commands.contains(Player.COMMAND_SEEK_TO_DEFAULT_POSITION),
    )
    assertTrue(
      "SEEK_TO_MEDIA_ITEM survives the skip strip",
      commands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
    )
    assertTrue(
      "SET_SPEED_AND_PITCH survives the skip strip",
      commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH),
    )
    assertTrue(
      "SEEK_BACK survives the skip strip",
      commands.contains(Player.COMMAND_SEEK_BACK),
    )
    assertTrue(
      "SEEK_FORWARD survives the skip strip",
      commands.contains(Player.COMMAND_SEEK_FORWARD),
    )
  }
}
