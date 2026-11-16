package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the cache will and will not believe is a track, judged from a response's
 * declared type and the first bytes of its body. Mirrors iOS's
 * `MediaResponseCheckTests` case for case so the two platforms refuse and
 * accept the same responses.
 */
class MediaResponseCheckTest {

  private val xmlEnvelope =
    """<api-response status="failed"><error code="70"/></api-response>""".toByteArray()
  private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0)

  private fun rejection(
    contentType: String?,
    body: ByteArray,
    whole: Boolean = true
  ): MediaResponseCheck.Rejection? =
    MediaResponseCheck.rejection(contentType, body, headIsStartOfResource = whole)

  @Test
  fun markupAndJsonContentTypesAreRefused() {
    for (type in listOf(
      "application/xml", "text/xml", "text/html",
      "application/xhtml+xml", "application/json", "text/json",
    )) {
      assertEquals(
        "$type is a document type",
        MediaResponseCheck.Rejection.MarkupContentType(type),
        rejection(type, xmlEnvelope)
      )
    }
  }

  @Test
  fun theDeclaredTypeIsReadWithoutItsParameters() {
    assertEquals(
      MediaResponseCheck.Rejection.MarkupContentType("application/xml"),
      rejection("application/xml; charset=utf-8", xmlEnvelope)
    )
    assertEquals(
      MediaResponseCheck.Rejection.MarkupContentType("application/xml"),
      rejection("APPLICATION/XML", xmlEnvelope)
    )
  }

  @Test
  fun aDeclaredDocumentIsRefusedOnAPartialBodyToo() {
    assertEquals(
      MediaResponseCheck.Rejection.MarkupContentType("application/xml"),
      rejection("application/xml", byteArrayOf(0x3C, 0x00), whole = false)
    )
  }

  @Test
  fun aPartialBodyIsNotJudgedByItsLeadingBytes() {
    // The same markup, under a type that is not itself a document, so only the
    // leading-byte rule can decide: refused as a whole resource, accepted as a
    // range, which is what separates the two inputs.
    val markup = "<api-response/>".toByteArray()
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection("application/octet-stream", markup)
    )
    assertNull(
      "a body that begins part way into a file says nothing about its first bytes",
      rejection("application/octet-stream", markup, whole = false)
    )
  }

  @Test
  fun textWithoutAContainerSignatureIsRefused() {
    assertEquals(
      MediaResponseCheck.Rejection.TextWithoutMediaSignature("text/plain"),
      rejection("text/plain", "not found".toByteArray())
    )
  }

  @Test
  fun textCarryingAContainerSignatureIsAccepted() {
    assertNull(
      "a server mislabelling audio as text does not make it a document",
      rejection("text/plain", mp3)
    )
  }

  @Test
  fun aPlaylistDeclaredAsTextIsAccepted() {
    assertNull(rejection("text/plain", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
  }

  @Test
  fun aMarkupBodyIsRefusedWhenNothingUsableIsDeclared() {
    assertEquals(MediaResponseCheck.Rejection.MarkupBody, rejection(null, xmlEnvelope))
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection("application/octet-stream", xmlEnvelope)
    )
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection(null, "<!DOCTYPE html><html>".toByteArray())
    )
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection(null, """{"error":"not found"}""".toByteArray())
    )
  }

  @Test
  fun leadingSpaceAndAByteOrderMarkDoNotHideAMarkupBody() {
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection(null, "\n  <?xml version=\"1.0\"?>".toByteArray())
    )
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection(null, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<error/>".toByteArray())
    )
    // `FF FE` also satisfies the MPEG frame sync, so a UTF-16 document would
    // read as audio if the mark were not dropped first.
    assertEquals(
      MediaResponseCheck.Rejection.MarkupBody,
      rejection(null, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<e/>".toByteArray())
    )
  }

  @Test
  fun recognisedContainersAreAccepted() {
    val bodies = mapOf(
      "ID3" to byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0),
      "MPEG frame sync" to byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0, 0, 0, 0, 0),
      "ADTS AAC" to byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0, 0, 0, 0),
      "FLAC" to byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 0),
      "Ogg" to byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0, 0, 0, 0),
      "WAV" to byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0),
      "AIFF" to byteArrayOf(0x46, 0x4F, 0x52, 0x4D, 0, 0, 0, 0),
      "CAF" to byteArrayOf(0x63, 0x61, 0x66, 0x66, 0, 0, 0, 0),
      "MP4" to byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70),
    )
    for ((name, bytes) in bodies) {
      assertNull("$name is media", rejection("text/plain", bytes))
      assertNull("$name is media", rejection(null, bytes))
    }
  }

  @Test
  fun anUndeclaredOrOpaqueBinaryBodyIsAccepted() {
    val binary = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
    assertNull("an unrecognised container is not a document", rejection(null, binary))
    assertNull(rejection("application/octet-stream", binary))
    assertNull(rejection("audio/mpeg", binary))
  }

  @Test
  fun anEmptyBodyIsNotJudged() {
    assertNull(rejection(null, ByteArray(0)))
  }
}
