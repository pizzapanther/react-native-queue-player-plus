package com.margelo.nitro.queueplayer

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for [AudioFocusErrorMapping]. No Robolectric
 * needed — the mapping reads only [Player] reason int constants and
 * has no Android dependency at runtime.
 */
class AudioFocusErrorMappingTest {

  @Test
  fun `playWhenReady AUDIO_FOCUS_LOSS reason maps to LOSS_PERMANENT`() {
    val mapping = AudioFocusErrorMapping.mapPlayWhenReadyReason(
      Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
    )
    assertEquals("AUDIO_FOCUS_LOSS_PERMANENT", mapping?.nativeDomain)
    assertEquals(
      "Audio focus lost permanently; Media3 has paused playback.",
      mapping?.message
    )
  }

  @Test
  fun `non-focus playWhenReady reasons map to null`() {
    assertNull(AudioFocusErrorMapping.mapPlayWhenReadyReason(
      Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    ))
    assertNull(AudioFocusErrorMapping.mapPlayWhenReadyReason(
      Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
    ))
    assertNull(AudioFocusErrorMapping.mapPlayWhenReadyReason(
      Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
    ))
    assertNull(AudioFocusErrorMapping.mapPlayWhenReadyReason(-999))
  }

  @Test
  fun `entry into TRANSIENT_AUDIO_FOCUS_LOSS maps to LOSS_TRANSIENT`() {
    val mapping = AudioFocusErrorMapping.mapSuppressionReason(
      reason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
      previous = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    )
    assertEquals("AUDIO_FOCUS_LOSS_TRANSIENT", mapping?.nativeDomain)
    assertEquals(
      "Audio focus lost transiently; Media3 has paused playback.",
      mapping?.message
    )
  }

  @Test
  fun `exit from TRANSIENT_AUDIO_FOCUS_LOSS to NONE maps to GAIN`() {
    val mapping = AudioFocusErrorMapping.mapSuppressionReason(
      reason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
      previous = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
    )
    assertEquals("AUDIO_FOCUS_GAIN", mapping?.nativeDomain)
    assertEquals("Audio focus regained.", mapping?.message)
  }

  @Test
  fun `redundant TRANSIENT to TRANSIENT transition maps to null`() {
    assertNull(
      "an unchanged suppression reason carries no new focus event",
      AudioFocusErrorMapping.mapSuppressionReason(
        reason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
        previous = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
      )
    )
  }

  @Test
  fun `NONE to NONE transition maps to null`() {
    assertNull(AudioFocusErrorMapping.mapSuppressionReason(
      reason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
      previous = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    ))
  }

  @Test
  fun `non-focus suppression reasons map to null`() {
    // UNSUITABLE_AUDIO_OUTPUT or UNSUITABLE_AUDIO_ROUTE (Media3 1.x)
    // — non-focus suppression. Mapping ignores it.
    val unsuitable = Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
    assertNull(AudioFocusErrorMapping.mapSuppressionReason(
      reason = unsuitable,
      previous = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    ))
    assertNull(AudioFocusErrorMapping.mapSuppressionReason(
      reason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
      previous = unsuitable,
    ))
  }

  @Test
  fun `every emitted nativeDomain shares the AUDIO_FOCUS_ prefix`() {
    val emitted = listOfNotNull(
      AudioFocusErrorMapping.mapPlayWhenReadyReason(
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
      ),
      AudioFocusErrorMapping.mapSuppressionReason(
        reason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
        previous = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
      ),
      AudioFocusErrorMapping.mapSuppressionReason(
        reason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        previous = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
      ),
    )
    assertEquals(3, emitted.size)
    for (mapping in emitted) {
      assertTrue(
        "domain ${mapping.nativeDomain} must start with the shared AUDIO_FOCUS_ prefix",
        mapping.nativeDomain.startsWith(AudioFocusErrorMapping.NATIVE_DOMAIN_PREFIX)
      )
    }
  }
}
