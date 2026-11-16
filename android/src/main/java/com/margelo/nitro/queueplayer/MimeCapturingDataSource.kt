package com.margelo.nitro.queueplayer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Wraps an HTTP-capable [DataSource.Factory] so each `open(DataSpec)`
 * call surfaces the response `Content-Type` header to a sink callback.
 *
 * Mirrors the iOS lookahead cache's Content-Type-derived file
 * extension behaviour: the Android cache stores spans (Media3 owns
 * the on-disk format), so the symmetric move is to record the MIME
 * type observed during prefetch and let the playback path pass it
 * to Media3 via [androidx.media3.common.MediaItem.Builder.setMimeType].
 *
 * Only the prefetch path builds this, and its window holds http/https URLs
 * alone, so every response it sees is a server's.
 */
@UnstableApi
internal class MimeCapturingDataSourceFactory(
  private val upstream: DataSource.Factory,
  private val onMime: (uri: String, contentType: String?) -> Unit
) : DataSource.Factory {
  override fun createDataSource(): DataSource =
    MimeCapturingDataSource(upstream.createDataSource(), onMime)
}

@UnstableApi
internal class MimeCapturingDataSource(
  private val delegate: DataSource,
  private val onMime: (uri: String, contentType: String?) -> Unit
) : DataSource {

  override fun addTransferListener(transferListener: TransferListener) {
    delegate.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    val length = delegate.open(dataSpec)
    // `responseHeaders` is declared on `DataSource`, so this reads through
    // whatever wraps the HTTP source — casting to `HttpDataSource` would miss,
    // because a wrapper is a `DataSource`.
    // Reported even when null: the sink resolves a declared type first and
    // falls back to the URL's extension, so a response with no `Content-Type`
    // still gets a usable type recorded.
    onMime(dataSpec.uri.toString(), contentTypeOf(delegate.responseHeaders))
    return length
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    delegate.read(buffer, offset, length)

  override fun getUri(): Uri? = delegate.uri

  override fun close() {
    delegate.close()
  }

  override fun getResponseHeaders(): Map<String, List<String>> =
    delegate.responseHeaders

  private companion object {
    /** HTTP header names are case-insensitive; some servers and proxies emit
     *  lowercase and Media3 does not always normalise. */
    fun contentTypeOf(headers: Map<String, List<String>>): String? =
      headers.entries
        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value?.firstOrNull()
  }
}
