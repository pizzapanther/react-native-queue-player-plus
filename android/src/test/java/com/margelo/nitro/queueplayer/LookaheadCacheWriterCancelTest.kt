package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cancelling a prefetch stops the write that is running, not only the
 * coroutine around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class LookaheadCacheWriterCancelTest {

  private fun httpTrack(id: String) = TrackItem(
    id = id, url = "https://example.com/$id.m4a", title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null)

  /**
   * `CacheWriter.cache()` blocks and polls only its own cancelled flag, so a
   * cancelled coroutine alone would leave it writing. The download body
   * relays the cancellation onto the writer; `CacheWriter` then closes its
   * source, which is what this observes.
   */
  @Test
  fun `cancel stops the running download, not just its coroutine`() {
    val cache = LookaheadCache(
      context = ApplicationProvider.getApplicationContext(),
      maxSizeBytes = 4L * 1024L * 1024L,
      cacheDirName = "writer-cancel-${System.nanoTime()}"
    )
    val source = TricklingDataSource()
    val writer = LookaheadCacheWriter.create(
      cache = cache,
      httpFactory = androidx.media3.datasource.DataSource.Factory { source },
      isOnCellular = { false }
    )
    try {
      // The window is the tracks ahead of the current one.
      writer.reschedule(listOf(httpTrack("current"), httpTrack("slow")), currentIndex = 0, lookaheadCount = 1)
      assertTrue("the download must have opened its source", source.opened.await(5, TimeUnit.SECONDS))

      writer.cancel()

      assertTrue("a cancelled write closes its source", source.closed.await(5, TimeUnit.SECONDS))
    } finally {
      writer.release()
      cache.release()
    }
  }

  /** A source that hands out one byte at a time, forever, until closed. */
  private class TricklingDataSource : androidx.media3.datasource.DataSource {
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    private var uri: android.net.Uri? = null

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
      uri = dataSpec.uri
      opened.countDown()
      return androidx.media3.common.C.LENGTH_UNSET.toLong()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      Thread.sleep(2)
      buffer[offset] = 0
      return 1
    }
    override fun getUri(): android.net.Uri? = uri
    override fun close() {
      closed.countDown()
    }
  }

}
