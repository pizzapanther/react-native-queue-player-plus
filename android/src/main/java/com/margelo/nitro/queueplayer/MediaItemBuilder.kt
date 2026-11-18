package com.margelo.nitro.queueplayer

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Builder utility for ExoPlayer [MediaItem] arrays and the
 * [MediaSource.Factory] that plays them. Android mirror of
 * `ios/AVQueueBuilder.swift`.
 *
 * iOS attaches HTTP headers per-AVURLAsset via an options dict;
 * Media3 attaches them at the [DefaultHttpDataSource.Factory] level
 * — one factory serves every network request, so headers are
 * player-wide. That matches our [PlayerConfig] shape (no per-track
 * header override) and lines up with which names
 * [DefaultHttpDataSource.Factory.setDefaultRequestProperties] as the
 * integration point. Per-track headers would require a custom
 * MediaSource.Factory reading per-item metadata extras, which this
 * builder does not do.
 *
 * `file://` URIs transparently route through [DefaultDataSource]
 * without touching the HTTP factory — matches iOS's file-URL
 * short-circuit + (local files opt out of the lookahead
 * cache too).
 */
@UnstableApi
object MediaItemBuilder {
  private val videoSlot = Any()

  internal fun isVideoSlot(item: MediaItem): Boolean = item.localConfiguration?.tag === videoSlot

  /**
   * Build the [MediaItem] list for the player's queue, attaching a
   * [MediaMetadata] per item so Media3 mirrors title / artist / album /
   * artwork / duration to the MediaSession surface (lock-screen,
   * notification, Bluetooth/AVRCP) without further work.
   *
   * MIME hint: when [lookaheadCache] reports a Content-Type for a
   * track's URL (captured during a prior prefetch), it is passed via
   * [MediaItem.Builder.setMimeType] so Media3's MediaSourceFactory
   * picks the right extractor immediately instead of byte-sniffing.
   * URL-derived MIME is the fallback for `http(s)://` paths whose
   * extension is recognisable. MIME capture is queue-build-time;
   * later prefetch hits do not retroactively refresh items.
   *
   * [Uri.parse] is total — invalid URLs do not produce null here,
   * they produce items that fail at load time via the listener's
   * error channel.
   */
  fun buildMediaItems(
    context: Context,
    tracks: List<TrackItem>,
    lookaheadCache: LookaheadCache? = null,
    customPlaceholderUri: String? = null
  ): List<MediaItem> {
    return tracks.map { track ->
      val builder = MediaItem.Builder()
        // mediaId is consumer-facing — Media3 surfaces it on
        // notifications + MediaSession. Never used internally for
        // identity / lookup; Media3's Player.currentMediaItemIndex
        // is the authoritative queue position. Empty string is
        // Media3's documented default when track.id is omitted.
        .setMediaId(track.id ?: "")
        // Keep queue indices stable without giving the audio pipeline the
        // video's URL. The factory supplies a local, non-network queue slot.
        .setUri(if (track.isVideo == true) Uri.EMPTY else Uri.parse(track.url))
        .setTag(if (track.isVideo == true) videoSlot else null)
        .setMediaMetadata(buildMetadata(context, track, customPlaceholderUri))
      val mime = if (track.isVideo == true) null else resolveMimeTypeForBuild(track.url, lookaheadCache)
      if (mime != null) builder.setMimeType(mime)
      builder.build()
    }
  }

  /**
   * Build the [MediaMetadata] attached to each MediaItem. Empty-string
   * coerce on title/artist/album keeps Android OEM skins on the
   * artwork-present render path (null fields drop the notification
   * controls on Xiaomi / Oppo / Realme). Duration is set only when
   * positive — omitting it hides the lock-screen scrubber rather than
   * rendering `0:00 / 0:00`. Missing/empty/whitespace artworkUrl
   * substitutes the bundled placeholder via `android.resource://` URI
   * so Media3's BitmapLoader resolves it once per process rather than
   * cloning a byte[] into every MediaMetadata.
   */
  internal fun buildMetadata(
    context: Context,
    track: TrackItem,
    customPlaceholderUri: String?
  ): MediaMetadata {
    val builder = MediaMetadata.Builder()
      .setTitle(track.title.orEmpty())
      .setArtist(track.artist.orEmpty())
      .setAlbumTitle(track.album.orEmpty())

    val artworkUrl = track.artworkUrl?.trim()
    // Only a local `file://` placeholder is honoured (the built-in is a
    // local file; a remote URL would break offline + change behaviour).
    // Scheme match is case-insensitive to mirror iOS's `URL.isFileURL`;
    // normalize to a lowercase `file` scheme so the case-sensitive
    // `content://` rewrite below applies to an uppercase `FILE://` too.
    val customFileUri = customPlaceholderUri
      ?.let { Uri.parse(it) }
      ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
      ?.buildUpon()?.scheme("file")?.build()
    val artworkUri = if (!artworkUrl.isNullOrEmpty()) {
      // Rewrite private `file://` paths through the lib's exported
      // `ArtworkContentProvider` so cross-process controllers (Android
      // Auto's gearhead, Wear, Assistant) can resolve the cover in
      // their own process. `http(s)://` and `content://` URIs pass
      // through unchanged.
      ArtworkContentProvider.mapFileUri(Uri.parse(artworkUrl))
    } else if (customFileUri != null) {
      // Consumer-supplied placeholder in place of the built-in — same
      // cross-process file-URI rewrite as the real-art branch.
      ArtworkContentProvider.mapFileUri(customFileUri)
    } else {
      placeholderArtworkUri(context)
    }
    builder.setArtworkUri(artworkUri)

    val duration = track.duration
    if (duration != null && duration > 0) {
      builder.setDurationMs((duration * 1000).toLong())
    }

    return builder.build()
  }

  /** Change display fields only; retain decoded duration, extras and media configuration. */
  internal fun withDisplayMetadata(item: MediaItem, metadata: MediaMetadata): MediaItem {
    val builder = item.mediaMetadata.buildUpon()
      .setTitle(metadata.title)
      .setArtist(metadata.artist)
      .setAlbumTitle(metadata.albumTitle)
    if (item.mediaMetadata.artworkUri != metadata.artworkUri) {
      builder.setArtworkUri(metadata.artworkUri).setArtworkData(null, null)
    }
    return item.buildUpon().setMediaMetadata(builder.build()).build()
  }

  /**
   * Duration (ms) to write onto the current item's metadata for the API 33+
   * System UI seekbar, or `null` to leave it untouched. A positive
   * consumer-supplied duration wins; otherwise the decoded player duration is
   * used; a non-positive player duration (unknown / live / `C.TIME_UNSET`,
   * which is negative) yields `null`.
   */
  internal fun durationToBackfill(existingDurationMs: Long?, playerDurationMs: Long): Long? {
    if (existingDurationMs != null && existingDurationMs > 0L) return null
    if (playerDurationMs <= 0L) return null
    return playerDurationMs
  }

  /**
   * URI for the bundled placeholder artwork in `res/raw/`. Cached
   * per-process; the integer resource ID is package-stable at runtime
   * so the URI string is identical across calls.
   */
  private fun placeholderArtworkUri(context: Context): Uri {
    cachedPlaceholderUri?.let { return it }
    synchronized(this) {
      cachedPlaceholderUri?.let { return it }
      val uri = Uri.parse(
        "android.resource://${context.packageName}/${R.raw.rnqp_placeholder}"
      )
      cachedPlaceholderUri = uri
      return uri
    }
  }

  @Volatile
  private var cachedPlaceholderUri: Uri? = null

  /**
   * Resolve the MIME hint for a queue-build pass. The lookahead
   * cache wins when it has captured a Content-Type from prefetch.
   * URL-derived hints are restricted to `http(s)://` scheme — local
   * `file://`, `asset://`, `content://` URIs bypass the lookahead
   * cache entirely; setting MIME on them is a no-op at best and
   * could mis-cue Media3 at worst.
   */
  @VisibleForTesting
  internal fun resolveMimeTypeForBuild(
    url: String,
    lookaheadCache: LookaheadCache?
  ): String? {
    lookaheadCache?.mimeTypeFor(url)?.let { return it }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      return null
    }
    return CacheMimeTypes.fromUrl(url)
  }

  /**
   * Construct the [MediaSource.Factory] used by the ExoPlayer built
   * in [TrackPlayer.configureInternal]. Reads the [PlayerConfig]'s
   * HTTP headers + User-Agent and wires them onto the underlying
   * [DefaultHttpDataSource.Factory] so every remote fetch carries
   * the consumer app's auth.
   *
   * When [lookaheadCache] is non-null, the bare HTTP factory is
   * wrapped via [LookaheadCache.wrapDataSourceFactory]
   * so remote reads transparently populate + serve from the on-disk
   * LRU cache. file:// / asset:// / content:// URIs continue to bypass
   * the cache because [DefaultDataSource] dispatches them through
   * built-in DataSources before consulting the HTTP factory.
   */
  fun buildMediaSourceFactory(
    context: Context,
    config: PlayerConfig,
    lookaheadCache: LookaheadCache? = null
  ): MediaSource.Factory {
    return buildMediaSourceFactoryRaw(
      context = context,
      headers = config.httpHeaders,
      userAgent = config.userAgent,
      networkTimeoutMs = effectiveNetworkTimeoutMs(config.networkTimeoutMs),
      lookaheadCache = lookaheadCache
    )
  }

  /**
   * Primitive-arg factory builder. Mirrors iOS's @_spi(QueuePlayerTests)
   * `AVQueueBuilder.makeItemRaw` — tests call this with primitive maps
   * instead of constructing a Nitrogen-generated [PlayerConfig].
   */
  @VisibleForTesting
  internal fun buildMediaSourceFactoryRaw(
    context: Context,
    headers: Map<String, String>?,
    userAgent: String?,
    networkTimeoutMs: Int = DEFAULT_NETWORK_TIMEOUT_MS,
    lookaheadCache: LookaheadCache? = null
  ): MediaSource.Factory {
    val httpFactory = buildHttpFactory(headers, userAgent, networkTimeoutMs)
    val networkFactory: DataSource.Factory =
      lookaheadCache?.wrapDataSourceFactory(httpFactory) ?: httpFactory
    val dataSourceFactory = DefaultDataSource.Factory(context, networkFactory)
    // Enable constant-bitrate seeking so CBR MP3 / ADTS / AMR streams (common
    // for transcoded audio the server reports as length-unknown) become
    // seekable and expose an estimated duration. Without this ExoPlayer treats
    // them as unseekable and a seekTo silently collapses to the track start.
    // Positions are bitrate-approximated (accurate enough for scrubbing).
    val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    val audioFactory = DefaultMediaSourceFactory(context, extractorsFactory)
      .setDataSourceFactory(dataSourceFactory)
    return object : MediaSource.Factory by audioFactory {
      override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (isVideoSlot(mediaItem)) {
          SilenceMediaSource.Factory().setDurationUs(86_400_000_000L)
            .createMediaSource().apply { updateMediaItem(mediaItem) }
        } else audioFactory.createMediaSource(mediaItem)
    }
  }

  /**
   * Build a bare [DefaultHttpDataSource.Factory] from a config's
   * headers + User-Agent + read-timeout. Exposed so the proactive
   * prefetcher ([LookaheadCacheWriter]) can construct its own
   * `CacheDataSource` from the same auth context as the player.
   * Both the player factory and the writer factory share the same
   * underlying [LookaheadCache.simpleCache], so cache reads + writes
   * remain coherent.
   *
   * Read timeout: defaults to 30000 ms (vs Media3's 8s default).
   * The longer timeout tolerates slow-spin-up servers (a media server
   * on spun-down disks can take ~20s to respond). Connect timeout
   * stays at Media3's 8s default — TCP handshake is unaffected by
   * disk spin-up.
   */
  fun buildHttpFactory(
    headers: Map<String, String>?,
    userAgent: String?,
    networkTimeoutMs: Int = DEFAULT_NETWORK_TIMEOUT_MS
  ): DefaultHttpDataSource.Factory {
    val merged = mergeHttpRequestProperties(headers, userAgent)
    return DefaultHttpDataSource.Factory().apply {
      if (merged.isNotEmpty()) {
        setDefaultRequestProperties(merged)
      }
      // Some servers behind a reverse proxy redirect http→https (or
      // vice versa) for auth flows; disabling
      // cross-protocol redirects silently truncates downloads
      // partway through a redirect.
      setAllowCrossProtocolRedirects(true)
      setReadTimeoutMs(networkTimeoutMs)
    }
  }

  /**
   * Lib-clamped network timeout in ms. Defaults to 30000 (30s),
   * clamped to [1000, 60000]. Mirrors the cross-platform contract
   * documented in `PlayerConfig.networkTimeoutMs`. iOS exposes the
   * same field but doesn't honour it (AVPlayer drives loads via
   * CFNetwork; no public timeout knob).
   */
  internal fun effectiveNetworkTimeoutMs(raw: Double?): Int {
    val ms = (raw?.toInt() ?: DEFAULT_NETWORK_TIMEOUT_MS)
    return ms.coerceIn(MIN_NETWORK_TIMEOUT_MS, MAX_NETWORK_TIMEOUT_MS)
  }

  const val DEFAULT_NETWORK_TIMEOUT_MS: Int = 30_000
  private const val MIN_NETWORK_TIMEOUT_MS: Int = 1_000
  private const val MAX_NETWORK_TIMEOUT_MS: Int = 60_000

  /**
   * Merge [PlayerConfig.httpHeaders] with an explicit user-agent
   * into the single request-properties map that
   * [DefaultHttpDataSource.Factory.setDefaultRequestProperties]
   * accepts. The explicit `userAgent` argument wins over any
   * `User-Agent` key already present in `headers`. Empty / null
   * user-agent leaves any header-dict `User-Agent` untouched,
   * matching the iOS `httpOptionsRaw` semantics.
   */
  @VisibleForTesting
  internal fun mergeHttpRequestProperties(
    headers: Map<String, String>?,
    userAgent: String?
  ): Map<String, String> {
    val merged = headers?.toMutableMap() ?: mutableMapOf()
    if (!userAgent.isNullOrEmpty()) {
      merged["User-Agent"] = userAgent
    }
    return merged
  }
}
