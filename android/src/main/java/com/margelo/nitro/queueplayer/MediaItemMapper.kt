package com.margelo.nitro.queueplayer

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi

/**
 * Convert a lib-level [BrowseItem] into a Media3 [MediaItem] for
 * delivery through the `MediaLibrarySession.Callback` browse
 * surface. Pure data mapping — no AVFoundation / IO. Maps the
 * native `BrowseDataProvider` cache + `SearchResultCache` entries
 * into the `ListenableFuture<LibraryResult<...>>` shapes Media3
 * expects from the browse + search callbacks.
 *
 * The browse-vs-playable axis maps onto Media3's
 * [MediaMetadata.MEDIA_TYPE_*] convention:
 *  - [BrowseItem.playable] `true` → `IS_PLAYABLE = true`,
 *    `IS_BROWSABLE = false`, `mediaType = MEDIA_TYPE_MUSIC`.
 *  - [BrowseItem.hasChildren] `true` → `IS_BROWSABLE = true`,
 *    `IS_PLAYABLE = false`, `mediaType = MEDIA_TYPE_FOLDER_MIXED`.
 *  - Both flags `true` (consumer error per the BrowseItem contract,
 *    but defensively handled): both `IS_*` flags set; `mediaType`
 *    defaults to `MEDIA_TYPE_MIXED`. Media3 controllers treat this
 *    as a "branch-or-leaf" entry and pick whichever the UI surface
 *    can render.
 *  - Both `false` (an item that is neither tappable nor browsable,
 *    e.g. a header-style row): both `IS_*` flags `false`,
 *    `mediaType = MEDIA_TYPE_MIXED`. Useful only for spacer rows;
 *    most consumers won't emit these.
 *
 * Artwork: when [BrowseItem.artworkUrl] is non-null + non-empty,
 * the URI is set on [MediaMetadata.Builder.setArtworkUri]. Private
 * `file://` paths route through [ArtworkContentProvider.mapFileUri]
 * so cross-process media controllers (Android Auto's gearhead, Wear,
 * Assistant) can resolve them in their own process via the lib's
 * exported provider — `http(s)://` and `content://` URIs pass
 * through unchanged.
 */
@UnstableApi
object MediaItemMapper {

  /** Map a single [BrowseItem] to a Media3 [MediaItem]. */
  fun toMediaItem(item: BrowseItem): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(item.title)
      .apply {
        item.subtitle?.takeIf { it.isNotEmpty() }?.let(::setSubtitle)
        item.artworkUrl
          ?.takeIf { it.isNotEmpty() }
          ?.let { setArtworkUri(ArtworkContentProvider.mapFileUri(Uri.parse(it))) }
      }
      .setIsBrowsable(item.hasChildren == true)
      .setIsPlayable(item.playable)
      .setMediaType(mediaTypeFor(item))
      .build()
    return MediaItem.Builder()
      .setMediaId(item.id)
      .setMediaMetadata(metadata)
      .build()
  }

  /** Map a list of [BrowseItem]s. */
  fun toMediaItems(items: List<BrowseItem>): List<MediaItem> =
    items.map { toMediaItem(it) }

  private fun mediaTypeFor(item: BrowseItem): Int {
    val isBrowsable = item.hasChildren == true
    val isPlayable = item.playable
    return when {
      isPlayable && !isBrowsable -> MediaMetadata.MEDIA_TYPE_MUSIC
      isBrowsable && !isPlayable -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
      else -> MediaMetadata.MEDIA_TYPE_MIXED
    }
  }
}
