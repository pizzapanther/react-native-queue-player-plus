package com.margelo.nitro.queueplayer.cast.airplay

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Receiver-display sync lifecycle: what reaches the receiver while a sync is
 * running, and that nothing reaches it once the session has been torn down.
 *
 * The teardown cases drive `stop()` from a non-looper thread, which is where
 * it comes from in production — the JS thread on a user disconnect, the
 * native drain thread on receiver death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class AirPlayMetadataSyncTest {

  private lateinit var player: FakePlayer
  private lateinit var target: RecordingTarget
  private lateinit var sync: AirPlayMetadataSync
  private val scope = CoroutineScope(Dispatchers.Main.immediate)

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private fun idleFor(ms: Long) =
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

  /** Runs [block] off the looper and waits for it, as a real teardown does. */
  private fun offLooper(block: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    try {
      executor.submit(block).get(5, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }
  }

  /** Waits for work dispatched off the looper, e.g. the artwork fetch. */
  private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
      idle()
      if (condition()) return true
      Thread.sleep(20)
    }
    return condition()
  }

  @Before
  fun setUp() {
    player = FakePlayer(Looper.getMainLooper())
    target = RecordingTarget()
    sync = AirPlayMetadataSync(context(), target, player, scope)
  }

  @After
  fun tearDown() {
    sync.stop()
    idle()
    scope.cancel()
  }

  @Test
  fun `start sends the current track metadata`() {
    sync.start()
    idle()

    assertEquals(
      listOf(Triple("Everlong", "Foo Fighters", "The Colour and the Shape")),
      target.metadata,
    )
  }

  @Test
  fun `progress is pushed while playing`() {
    sync.start()
    idle()
    val afterStart = target.progress.size

    idleFor(11_000)

    assertTrue(
      "expected progress pushes while playing, got ${target.progress.size - afterStart}",
      target.progress.size - afterStart >= 2,
    )
  }

  @Test
  fun `stop from another thread halts the progress loop`() {
    sync.start()
    idle()
    idleFor(6_000)
    assertTrue(target.progress.isNotEmpty())

    offLooper { sync.stop() }
    idle()
    val atStop = target.progress.size

    idleFor(30_000)

    assertEquals(atStop, target.progress.size)
  }

  @Test
  fun `a resume callback arriving after stop starts no progress loop`() {
    sync.start()
    idle()
    player.emitPlayWhenReady(false)
    idle()

    // The resume lands while listener removal is still queued behind the
    // off-looper stop, so it reaches startProgressTimer on a spent sync.
    offLooper { sync.stop() }
    player.emitPlayWhenReady(true)
    idle()
    val atStop = target.progress.size

    idleFor(30_000)

    assertEquals(atStop, target.progress.size)
  }

  @Test
  fun `a track change arriving after stop pushes nothing`() {
    sync.start()
    idle()
    val atStop = target.metadata.size

    offLooper { sync.stop() }
    player.emitTrackChange(trackMediaItem("Monkey Wrench", "Foo Fighters", "TCATS"))
    idle()

    assertEquals(atStop, target.metadata.size)
  }

  @Test
  fun `artwork from a local file reaches the receiver while running`() {
    player.emitTrackChange(
      trackMediaItem("Everlong", "Foo Fighters", "TCATS", artworkUri = artworkFile())
    )

    sync.start()

    assertTrue("artwork never arrived", awaitTrue { target.artwork.isNotEmpty() })
  }

  @Test
  fun `artwork is not fetched after stop`() {
    sync.start()
    idle()

    // Fired while listener removal is still queued behind the off-looper
    // stop, so the fetch is reached on a spent sync.
    offLooper { sync.stop() }
    player.emitTrackChange(
      trackMediaItem("Monkey Wrench", "Foo Fighters", "TCATS", artworkUri = artworkFile())
    )

    // Real elapsed time, not looper time: the fetch runs on Dispatchers.IO,
    // so advancing the looper clock proves nothing about whether it ran.
    assertFalse(
      "artwork reached the receiver after stop",
      awaitTrue(2_000) { target.artwork.isNotEmpty() },
    )
  }

  @Test
  fun `start after stop does nothing`() {
    sync.start()
    idle()
    offLooper { sync.stop() }
    idle()
    val atStop = target.progress.size + target.metadata.size

    sync.start()
    idle()
    idleFor(30_000)

    assertEquals(atStop, target.progress.size + target.metadata.size)
  }

  private fun artworkFile(): Uri {
    val file = File(context().cacheDir, "artwork.png")
    if (!file.exists()) file.writeBytes(PNG_1X1)
    return Uri.fromFile(file)
  }

  private class RecordingTarget : MetadataSyncTarget {
    // The artwork fetch runs on Dispatchers.IO while the test thread reads
    // these, so plain ArrayLists would be shared unsynchronised.
    val metadata = CopyOnWriteArrayList<Triple<String?, String?, String?>>()
    val progress = CopyOnWriteArrayList<Pair<Int, Int>>()
    val artwork = CopyOnWriteArrayList<Int>()

    override fun setMetadata(title: String?, artist: String?, album: String?) {
      metadata += Triple(title, artist, album)
    }

    override fun setProgress(elapsedMs: Int, durationMs: Int) {
      progress += elapsedMs to durationMs
    }

    override fun setArtwork(imageData: ByteArray, contentType: String) {
      artwork += imageData.size
    }
  }

  /**
   * Player double exposing exactly what the sync reads: the current item's
   * metadata, position, duration and whether playback is running. Each item
   * carries its own period uid, so replacing it is delivered as a media-item
   * transition rather than a timeline change.
   */
  private class FakePlayer(looper: Looper) : SimpleBasePlayer(looper) {
    private var item: MediaItem =
      trackMediaItem("Everlong", "Foo Fighters", "The Colour and the Shape")
    private var playing = true

    fun emitTrackChange(next: MediaItem) {
      item = next
      invalidateState()
    }

    fun emitPlayWhenReady(value: Boolean) {
      playing = value
      invalidateState()
    }

    override fun getState(): State =
      State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaylist(
          ImmutableList.of(
            MediaItemData.Builder(item)
              .setMediaItem(item)
              .setDurationUs(300_000_000L)
              .build()
          )
        )
        .setCurrentMediaItemIndex(0)
        .setContentPositionMs(1_000L)
        .build()
  }

  private companion object {
    /**
     * A real 1x1 PNG — Robolectric decodes artwork through ImageIO, so a
     * stub header fails to decode and the fetch reports no artwork.
     */
    val PNG_1X1: ByteArray = Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    fun trackMediaItem(
      title: String,
      artist: String,
      album: String,
      artworkUri: Uri? = null,
    ): MediaItem =
      MediaItem.Builder()
        .setUri("https://example.invalid/${title.replace(' ', '-')}.mp3")
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply { artworkUri?.let { setArtworkUri(it) } }
            .build()
        )
        .build()
  }
}
