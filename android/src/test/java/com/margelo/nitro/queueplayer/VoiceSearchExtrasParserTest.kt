package com.margelo.nitro.queueplayer

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [VoiceSearchExtrasParser]. Robolectric is required
 * because the Bundle access path uses the Android shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class VoiceSearchExtrasParserTest {

  private companion object {
    // Wire value Assistant/Android Auto send for the playlist slot,
    // held as a literal so this test independently pins the key the
    // parser reads (the platform's MediaStore constant is deprecated).
    const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
  }

  // --- mediaSearchTypeFor — focus MIME taxonomy

  @Test
  fun `artist focus maps to ARTIST`() {
    assertEquals(
      MediaSearchType.ARTIST,
      VoiceSearchExtrasParser.mediaSearchTypeFor("vnd.android.cursor.item/artist")
    )
  }

  @Test
  fun `album focus maps to ALBUM`() {
    assertEquals(
      MediaSearchType.ALBUM,
      VoiceSearchExtrasParser.mediaSearchTypeFor("vnd.android.cursor.item/album")
    )
  }

  @Test
  fun `audio focus maps to SONG`() {
    assertEquals(
      MediaSearchType.SONG,
      VoiceSearchExtrasParser.mediaSearchTypeFor("vnd.android.cursor.item/audio")
    )
  }

  @Test
  fun `playlist focus maps to PLAYLIST`() {
    assertEquals(
      MediaSearchType.PLAYLIST,
      VoiceSearchExtrasParser.mediaSearchTypeFor("vnd.android.cursor.item/playlist")
    )
  }

  @Test
  fun `genre focus maps to GENRE`() {
    assertEquals(
      MediaSearchType.GENRE,
      VoiceSearchExtrasParser.mediaSearchTypeFor("vnd.android.cursor.item/genre")
    )
  }

  @Test
  fun `unrecognised focus returns null type`() {
    assertNull(VoiceSearchExtrasParser.mediaSearchTypeFor("vnd.android.cursor.item/something"))
    assertNull(VoiceSearchExtrasParser.mediaSearchTypeFor(""))
    assertNull(VoiceSearchExtrasParser.mediaSearchTypeFor(null))
  }

  // --- parse — full request shape

  @Test
  fun `null extras yields query-only request with all named slots null`() {
    val r = VoiceSearchExtrasParser.parse("rock anthems", extras = null)
    assertEquals("rock anthems", r.query)
    assertNull(r.type)
    assertNull(r.artist)
    assertNull(r.album)
    assertNull(r.song)
    assertNull(r.playlist)
    assertNull(r.genre)
  }

  @Test
  fun `empty extras yields query-only request`() {
    val r = VoiceSearchExtrasParser.parse("rock", extras = Bundle())
    assertEquals("rock", r.query)
    assertNull(r.type)
    assertNull(r.artist)
  }

  @Test
  fun `artist focus + EXTRA_MEDIA_ARTIST populates artist field`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/artist")
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "The xx")
    }
    val r = VoiceSearchExtrasParser.parse("The xx", extras)
    assertEquals(MediaSearchType.ARTIST, r.type)
    assertEquals("The xx", r.artist)
  }

  @Test
  fun `album focus + EXTRA_MEDIA_ALBUM populates album field`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/album")
      putString(MediaStore.EXTRA_MEDIA_ALBUM, "xx")
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "The xx")
    }
    val r = VoiceSearchExtrasParser.parse("xx by The xx", extras)
    assertEquals(MediaSearchType.ALBUM, r.type)
    assertEquals("xx", r.album)
    assertEquals("The xx", r.artist)
  }

  @Test
  fun `audio focus + EXTRA_MEDIA_TITLE populates song field`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
      putString(MediaStore.EXTRA_MEDIA_TITLE, "Intro")
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "The xx")
      putString(MediaStore.EXTRA_MEDIA_ALBUM, "xx")
    }
    val r = VoiceSearchExtrasParser.parse("Intro by The xx", extras)
    assertEquals(MediaSearchType.SONG, r.type)
    assertEquals("Intro", r.song)
    assertEquals("The xx", r.artist)
    assertEquals("xx", r.album)
  }

  @Test
  fun `playlist focus + EXTRA_MEDIA_PLAYLIST populates playlist field`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
      putString(EXTRA_MEDIA_PLAYLIST, "Workout Mix")
    }
    val r = VoiceSearchExtrasParser.parse("Workout Mix", extras)
    assertEquals(MediaSearchType.PLAYLIST, r.type)
    assertEquals("Workout Mix", r.playlist)
  }

  @Test
  fun `genre focus + EXTRA_MEDIA_GENRE populates genre field`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/genre")
      putString(MediaStore.EXTRA_MEDIA_GENRE, "Indie Rock")
    }
    val r = VoiceSearchExtrasParser.parse("Indie Rock", extras)
    assertEquals(MediaSearchType.GENRE, r.type)
    assertEquals("Indie Rock", r.genre)
  }

  @Test
  fun `unknown focus + named slots still surface the slot values`() {
    // The catch-all "vnd.android.cursor.item/*" focus arrives when
    // Assistant recognised slots but couldn't pin a category. Slot
    // fields should still surface so consumers can fuzzy-match.
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "Some Artist")
      putString(MediaStore.EXTRA_MEDIA_ALBUM, "Some Album")
    }
    val r = VoiceSearchExtrasParser.parse("query", extras)
    assertNull(r.type)
    assertEquals("Some Artist", r.artist)
    assertEquals("Some Album", r.album)
  }

  @Test
  fun `extras with no focus key returns null type but still reads named slots`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "Standalone Artist")
    }
    val r = VoiceSearchExtrasParser.parse("query", extras)
    assertNull(r.type)
    assertEquals("Standalone Artist", r.artist)
  }

  @Test
  fun `empty query is preserved through the parse`() {
    val r = VoiceSearchExtrasParser.parse("", null)
    assertEquals("", r.query)
  }

  @Test
  fun `every named slot can populate from a single extras bundle`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "A")
      putString(MediaStore.EXTRA_MEDIA_ALBUM, "B")
      putString(MediaStore.EXTRA_MEDIA_TITLE, "C")
      putString(EXTRA_MEDIA_PLAYLIST, "D")
      putString(MediaStore.EXTRA_MEDIA_GENRE, "E")
    }
    val r = VoiceSearchExtrasParser.parse("query", extras)
    assertEquals(MediaSearchType.SONG, r.type)
    assertEquals("A", r.artist)
    assertEquals("B", r.album)
    assertEquals("C", r.song)
    assertEquals("D", r.playlist)
    assertEquals("E", r.genre)
  }
}
