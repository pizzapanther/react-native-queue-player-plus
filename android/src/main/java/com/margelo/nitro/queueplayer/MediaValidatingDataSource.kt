package com.margelo.nitro.queueplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Wraps an HTTP-capable [DataSource.Factory] so a response that is a document
 * rather than media fails before any of it is returned.
 *
 * Installed on the prefetcher's upstream only. Media3 writes to its cache sink
 * only after this `read` returns, so throwing instead of returning keeps the
 * body out of the cache — and the playback path is left alone, because there a
 * refusal would be an unplayable track rather than a prefetch that did not
 * happen. Playback cannot poison the cache either: its `CacheDataSource` is
 * built read-only.
 *
 * Transparent for a source with no response headers (file:// / asset://):
 * those carry no `Content-Type` and are not the case this exists for.
 */
@UnstableApi
internal class MediaValidatingDataSourceFactory(
  private val upstream: DataSource.Factory
) : DataSource.Factory {
  override fun createDataSource(): DataSource =
    MediaValidatingDataSource(upstream.createDataSource())
}

@UnstableApi
internal class MediaValidatingDataSource(
  private val delegate: DataSource
) : DataSource {

  private var dataSpec: DataSpec? = null
  private var contentType: String? = null
  private var isHttp = false
  private var startsAtResourceStart = true

  /**
   * Bytes read from the delegate to judge the body, held back until the verdict
   * is in and then served from here before the delegate is read again. Null once
   * drained — every later read passes straight through.
   */
  private var head: ByteArray? = null
  private var headOffset = 0
  private var bodyJudged = false

  override fun addTransferListener(transferListener: TransferListener) {
    delegate.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    this.dataSpec = dataSpec
    head = null
    headOffset = 0
    bodyJudged = false
    val length = delegate.open(dataSpec)
    // The scheme says whether this is a remote source. Response headers cannot:
    // an HTTP answer that carries no `Content-Type` has none, and that is
    // precisely a body this has to judge. Casting to `HttpDataSource` cannot
    // either — a wrapper is a `DataSource`, so the cast misses whenever one
    // sits between this and the HTTP source.
    isHttp = dataSpec.uri.scheme?.lowercase() in setOf("http", "https")
    if (!isHttp) {
      bodyJudged = true
      return length
    }
    // `responseHeaders` is declared on `DataSource`, so it reads through a
    // wrapper. Header names are case-insensitive and not every server or proxy
    // normalises them.
    contentType = delegate.responseHeaders.entries
      .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
      ?.value?.firstOrNull()
    startsAtResourceStart = dataSpec.position == 0L
    // A declared document type is refused here, before a byte is read.
    MediaResponseCheck.rejection(
      contentType = contentType,
      head = EMPTY,
      headIsStartOfResource = false
    )?.let { throw rejected(it, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN) }
    return length
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (!bodyJudged) judgeBody()
    val pending = head
    if (pending != null) {
      val remaining = pending.size - headOffset
      val take = minOf(remaining, length)
      pending.copyInto(buffer, offset, headOffset, headOffset + take)
      headOffset += take
      if (headOffset == pending.size) head = null
      return take
    }
    return delegate.read(buffer, offset, length)
  }

  /**
   * Pull enough of the body from the delegate to read a container signature,
   * judge it, and keep it for [read] to serve. Nothing reaches the caller — and
   * so nothing reaches the cache — until this has run.
   */
  private fun judgeBody() {
    bodyJudged = true
    if (!isHttp) return
    val scratch = ByteArray(MediaResponseCheck.HEAD_BYTES)
    var filled = 0
    while (filled < scratch.size) {
      val read = delegate.read(scratch, filled, scratch.size - filled)
      // A zero-length read would otherwise spin here; treat it as nothing more
      // to judge, the same as the end of the input.
      if (read == C.RESULT_END_OF_INPUT || read == 0) break
      filled += read
    }
    if (filled == 0) return
    val body = if (filled == scratch.size) scratch else scratch.copyOf(filled)
    MediaResponseCheck.rejection(
      contentType = contentType,
      head = body,
      headIsStartOfResource = startsAtResourceStart
    )?.let { throw rejected(it, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_READ) }
    head = body
    headOffset = 0
  }

  private fun rejected(
    rejection: MediaResponseCheck.Rejection,
    spec: DataSpec?,
    type: Int
  ): HttpDataSource.HttpDataSourceException {
    val detail = when (rejection) {
      is MediaResponseCheck.Rejection.MarkupContentType ->
        "declared type ${rejection.contentType} is a document, not media"
      is MediaResponseCheck.Rejection.TextWithoutMediaSignature ->
        "declared type ${rejection.contentType} with no recognised container signature"
      MediaResponseCheck.Rejection.MarkupBody ->
        "body begins as a markup or JSON document"
    }
    return HttpDataSource.HttpDataSourceException(
      "Response is not media: $detail",
      spec ?: DataSpec(Uri.EMPTY),
      PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
      type
    )
  }

  override fun getUri(): Uri? = delegate.uri

  override fun close() {
    head = null
    headOffset = 0
    delegate.close()
  }

  override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

  private companion object {
    val EMPTY = ByteArray(0)
  }
}
