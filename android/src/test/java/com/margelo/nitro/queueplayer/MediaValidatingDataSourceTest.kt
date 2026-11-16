package com.margelo.nitro.queueplayer

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A response that is a document rather than media fails before any of its bytes
 * are returned, so neither cache write path — the prefetcher's `CacheWriter` nor
 * the playback read path's own sink — ever sees them.
 *
 * `android.net.Uri` is a framework type, so this runs under Robolectric like
 * every other test here that parses one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class MediaValidatingDataSourceTest {

  /** An HTTP source that answers with fixed headers and a fixed body. */
  private class FakeHttpSource(
    private val body: ByteArray,
    private val contentType: String?,
    /** Bytes to hand back per read, so a body arriving in dribs is covered. */
    private val chunk: Int = Int.MAX_VALUE
  ) : HttpDataSource {
    private var position = 0
    var readCount = 0
      private set

    override fun open(dataSpec: DataSpec): Long {
      position = dataSpec.position.toInt()
      return (body.size - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      readCount += 1
      if (position >= body.size) return -1
      val take = minOf(length, chunk, body.size - position)
      body.copyInto(buffer, offset, position, position + take)
      position += take
      return take
    }

    override fun getUri(): Uri = Uri.parse("https://example.com/track")
    override fun close() {}
    override fun addTransferListener(transferListener: TransferListener) {}
    override fun getResponseHeaders(): Map<String, List<String>> =
      if (contentType == null) emptyMap() else mapOf("Content-Type" to listOf(contentType))

    override fun setRequestProperty(name: String, value: String) {}
    override fun clearRequestProperty(name: String) {}
    override fun clearAllRequestProperties() {}
    override fun getResponseCode(): Int = 200
  }

  /** A non-HTTP source, which carries no declared type and is passed through. */
  private class FakeLocalSource(private val body: ByteArray) : DataSource {
    private var position = 0
    override fun open(dataSpec: DataSpec): Long = body.size.toLong()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (position >= body.size) return -1
      val take = minOf(length, body.size - position)
      body.copyInto(buffer, offset, position, position + take)
      position += take
      return take
    }
    override fun getUri(): Uri = Uri.parse("file:///track.mp3")
    override fun close() {}
    override fun addTransferListener(transferListener: TransferListener) {}
  }

  private val xmlEnvelope =
    """<api-response status="failed"><error code="70"/></api-response>""".toByteArray()
  private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0) + ByteArray(120)

  private fun openAndRead(
    source: DataSource,
    position: Long = 0L,
    uri: String = "https://example.com/track"
  ): ByteArray {
    source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
    val out = ArrayList<Byte>()
    val buffer = ByteArray(32)
    while (true) {
      val read = source.read(buffer, 0, buffer.size)
      if (read == -1) break
      for (i in 0 until read) out.add(buffer[i])
    }
    return out.toByteArray()
  }

  @Test
  fun aDeclaredDocumentFailsBeforeAnythingIsRead() {
    val upstream = FakeHttpSource(xmlEnvelope, "application/xml")
    val source = MediaValidatingDataSource(upstream)
    try {
      source.open(DataSpec(Uri.parse("https://example.com/track")))
      fail("a declared document must not open")
    } catch (e: HttpDataSource.HttpDataSourceException) {
      assertEquals(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, e.reason)
    }
    assertEquals("nothing may be read from a refused response", 0, upstream.readCount)
  }

  @Test
  fun anUndeclaredMarkupBodyFailsOnTheFirstRead() {
    val source = MediaValidatingDataSource(FakeHttpSource(xmlEnvelope, null))
    source.open(DataSpec(Uri.parse("https://example.com/track")))
    try {
      source.read(ByteArray(32), 0, 32)
      fail("a markup body must not be returned")
    } catch (e: HttpDataSource.HttpDataSourceException) {
      assertEquals(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, e.reason)
    }
  }

  @Test
  fun plainTextWithNoContainerSignatureFailsOnTheFirstRead() {
    val source = MediaValidatingDataSource(FakeHttpSource("not found".toByteArray(), "text/plain"))
    source.open(DataSpec(Uri.parse("https://example.com/track")))
    try {
      source.read(ByteArray(32), 0, 32)
      fail("text with no container signature must not be returned")
    } catch (e: HttpDataSource.HttpDataSourceException) {
      assertEquals(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, e.reason)
    }
  }

  @Test
  fun mediaIsPassedThroughWhole() {
    val source = MediaValidatingDataSource(FakeHttpSource(mp3, "audio/mpeg"))
    assertArrayEquals("every byte reaches the caller unchanged", mp3, openAndRead(source))
  }

  @Test
  fun mediaArrivingInSmallChunksIsStillPassedThroughWhole() {
    val source = MediaValidatingDataSource(FakeHttpSource(mp3, "audio/mpeg", chunk = 3))
    assertArrayEquals(mp3, openAndRead(source))
  }

  @Test
  fun aRangedRequestIsJudgedOnItsDeclaredTypeOnly() {
    // The served window opens with `<`, so the leading-byte rule would refuse it
    // if it were applied. A body read from an offset says nothing about the
    // resource's first bytes, so it is not.
    val markupAtOffset = "....<api-response/>".toByteArray()
    val ranged = MediaValidatingDataSource(
      FakeHttpSource(markupAtOffset, "application/octet-stream")
    )
    val read = openAndRead(ranged, position = 4L)
    assertTrue("a partial body is not judged by its first byte", read.isNotEmpty())

    // The same bytes as the whole resource are refused — which is what shows the
    // acceptance above turns on the range and not on the bytes.
    val whole = MediaValidatingDataSource(
      FakeHttpSource("<api-response/>".toByteArray(), "application/octet-stream")
    )
    whole.open(DataSpec(Uri.parse("https://example.com/track")))
    try {
      whole.read(ByteArray(32), 0, 32)
      fail("the same markup read from the start of the resource must be refused")
    } catch (e: HttpDataSource.HttpDataSourceException) {
      assertEquals(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, e.reason)
    }
  }

  @Test
  fun aLocalSourceIsPassedThroughUnjudged() {
    val source = MediaValidatingDataSource(FakeLocalSource(xmlEnvelope))
    assertArrayEquals(
      "a file:// source is not fetched from a server and is not the case this guards",
      xmlEnvelope,
      openAndRead(source, uri = "file:///track.mp3")
    )
  }
}
