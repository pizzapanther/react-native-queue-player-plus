package com.margelo.nitro.queueplayer

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A crossfade engine owns audio focus itself, so the pause a focus loss
 * makes reaches the delegate carrying the audio-focus reason, not the
 * user-request reason `pause()` stamps by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class CrossfadeEngineFocusLossTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Records every play-when-ready reason the engine reports. */
  private class RecordingDelegate : PlaybackEngineDelegate {
    val reasons = mutableListOf<Int?>()
    override fun onStateMaybeChanged(engine: PlaybackEngine, playWhenReadyReason: Int?) {
      reasons.add(playWhenReadyReason)
    }
    override fun onIsPlayingChanged(engine: PlaybackEngine, isPlaying: Boolean) {}
    override fun onTrackTransition(engine: PlaybackEngine, platformReason: Int) {}
    override fun onActiveItemFormatChanged(engine: PlaybackEngine) {}
    override fun onError(engine: PlaybackEngine, exception: PlaybackException, failedIndex: Int) {}
    override fun onPlaybackEnded(engine: PlaybackEngine) {}
    override fun onAutoTransitionDiscontinuity(engine: PlaybackEngine) {}
    override fun onCrossfadeBegin(engine: PlaybackEngine, incomingIndex: Int) {}
    override fun onCrossfadeCancel(engine: PlaybackEngine, revertedIndex: Int) {}
  }

  private fun playingEngine(delegate: RecordingDelegate): CrossfadeEngine {
    val engine = CrossfadeEngine(context = context, crossfadeDurationMs = 3_000L)
    engine.delegate = delegate
    engine.setMediaItems(listOf(MediaItem.fromUri("https://example.com/a.mp3")), 0, 0L)
    engine.play()
    shadowOf(android.os.Looper.getMainLooper()).idle()
    return engine
  }

  private fun focusListener(): AudioManager.OnAudioFocusChangeListener {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val request = shadowOf(audioManager).lastAudioFocusRequest
    assertNotNull("play() must request focus", request)
    return request.listener
  }

  @Test
  fun `a focus loss pauses with the audio-focus reason`() {
    val delegate = RecordingDelegate()
    val engine = playingEngine(delegate)
    delegate.reasons.clear()

    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(
      "the delegate stamps the last reason it is handed; a USER_REQUEST after the loss would label the pause a user pause",
      Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
      delegate.reasons.last { it != null },
    )
    engine.release()
  }

  @Test
  fun `a transient focus loss pauses with the audio-focus reason too`() {
    val delegate = RecordingDelegate()
    val engine = playingEngine(delegate)
    delegate.reasons.clear()

    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(
      Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
      delegate.reasons.last { it != null },
    )
    engine.release()
  }

  @Test
  fun `a user pause after the loss keeps its own reason`() {
    val delegate = RecordingDelegate()
    val engine = playingEngine(delegate)
    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
    engine.play()
    shadowOf(android.os.Looper.getMainLooper()).idle()
    delegate.reasons.clear()

    engine.pause()
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(
      "the substitution lasts only for the loss's own pauses",
      Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
      delegate.reasons.last { it != null },
    )
    engine.release()
  }
}
