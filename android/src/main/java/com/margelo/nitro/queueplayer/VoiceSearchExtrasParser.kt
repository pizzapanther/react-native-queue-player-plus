package com.margelo.nitro.queueplayer

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi

/**
 * Parse a Google Assistant / Android Auto voice-search Bundle into
 * a lib-level [MediaSearchRequest]. Inputs come from the legacy
 * `MediaSession.Callback.onPlayFromSearch(query, extras)` callback;
 * Assistant routes "Hey Google, play X" + tile / app voice queries
 * through there with a structured extras Bundle that carries the
 * focus type (artist / album / song / playlist / genre) and the
 * named slot that produced the focus.
 *
 * Defensive: every extras key is optional. Missing extras / unknown
 * focus types fall through to `null` fields, leaving consumers to
 * fuzzy-match on the raw `query` text.
 *
 * Reference: [Google Voice Actions for Media](
 * https://developer.android.com/training/wearables/data/voice-search).
 */
@UnstableApi
object VoiceSearchExtrasParser {

  /**
   * Voice-search extra carrying the playlist name. Assistant and
   * Android Auto still send this key on "play my <playlist>" queries.
   * Held as a literal because the `MediaStore` constant for it is
   * deprecated — the deprecation targets the MediaStore Playlists
   * provider, not this intent extra — and the platform ships no
   * replacement constant. The wire value is stable across releases.
   */
  private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"

  /**
   * Build a [MediaSearchRequest] from the raw `(query, extras)`
   * pair Assistant hands to `onPlayFromSearch`.
   *
   * - `query`: the user's spoken / typed text. Always present;
   *   empty string is a valid input meaning "no constraint".
   * - `extras`: nullable Bundle carrying the focus taxonomy. When
   *   null or empty, the returned request has `type == null` and
   *   every named-slot field null.
   */
  fun parse(query: String, extras: Bundle?): MediaSearchRequest {
    if (extras == null) {
      return MediaSearchRequest(
        query = query,
        type = null,
        artist = null,
        album = null,
        song = null,
        playlist = null,
        genre = null,
        origin = MediaSearchOrigin.ANDROID_ASSISTANT,
        reference = null,
        playShuffled = null,
        playbackRepeatMode = null,
        resumePlayback = null,
        playbackSpeed = null,
        releaseDate = null,
        // iOS-only; Google Assistant App Actions schema has no
        // equivalent queue-position slot.
        playbackQueueLocation = null
      )
    }
    val focus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS)
    val type = mediaSearchTypeFor(focus)
    return MediaSearchRequest(
      query = query,
      type = type,
      artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST),
      album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM),
      song = extras.getString(MediaStore.EXTRA_MEDIA_TITLE),
      playlist = extras.getString(EXTRA_MEDIA_PLAYLIST),
      genre = extras.getString(MediaStore.EXTRA_MEDIA_GENRE),
      origin = MediaSearchOrigin.ANDROID_ASSISTANT,
      reference = null,
      playShuffled = null,
      playbackRepeatMode = null,
      resumePlayback = null,
      playbackSpeed = null,
      releaseDate = null,
      // iOS-only; Google Assistant App Actions schema has no
      // equivalent queue-position slot.
      playbackQueueLocation = null
    )
  }

  /** Map an `EXTRA_MEDIA_FOCUS` MIME-type string onto our enum.
   *  Unknown focus types — including the catch-all
   *  `vnd.android.cursor.item/&#42;` (star) Assistant emits when no
   *  slot was recognised — return null. */
  internal fun mediaSearchTypeFor(focus: String?): MediaSearchType? = when (focus) {
    "vnd.android.cursor.item/artist" -> MediaSearchType.ARTIST
    "vnd.android.cursor.item/album" -> MediaSearchType.ALBUM
    "vnd.android.cursor.item/audio" -> MediaSearchType.SONG
    "vnd.android.cursor.item/playlist" -> MediaSearchType.PLAYLIST
    "vnd.android.cursor.item/genre" -> MediaSearchType.GENRE
    else -> null
  }
}
