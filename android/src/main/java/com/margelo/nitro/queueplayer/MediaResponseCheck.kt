package com.margelo.nitro.queueplayer

/**
 * Decides whether an HTTP response that claims to carry a track can be
 * believed, from its `Content-Type` and the first bytes of its body.
 *
 * A server that answers an API-level failure with `200` and an XML or JSON
 * envelope is the case this exists for: media servers commonly report
 * errors that way by design, and a proxy answering with an HTML page does the
 * same. Such a body passes every length and status check, so without this the
 * prefetcher writes it into the cache as a complete track and it is served from
 * disk on every later play — the track stays broken after the server is well
 * again.
 *
 * The rules identify what is **not** media rather than demanding proof of what
 * is. The two mistakes are not equal: a false rejection costs a prefetch and the
 * track still plays from its origin, while a false acceptance breaks the track
 * until the cache is cleared by hand.
 *
 * Mirrored rule for rule by `MediaResponseCheck.swift` on iOS.
 */
internal object MediaResponseCheck {

  /**
   * Why a response was refused. Each case carries what was judged so a caller's
   * message names the response rather than just the verdict.
   */
  sealed interface Rejection {
    /** The declared type is a markup or JSON document, which no audio container is. */
    data class MarkupContentType(val contentType: String) : Rejection

    /**
     * The declared type is text of some other kind and the body carries no
     * recognised container signature. `text/plain` is not refused on the label
     * alone — media servers do mislabel audio — so the body is given the chance
     * to prove itself.
     */
    data class TextWithoutMediaSignature(val contentType: String) : Rejection

    /** No usable declared type, and the body begins as a markup or JSON document. */
    data object MarkupBody : Rejection
  }

  /** Enough of a body to carry any container signature read below. */
  const val HEAD_BYTES: Int = 64

  /**
   * Types that are documents, never audio. Narrow on purpose:
   * `application/octet-stream` and `text/plain` are missing because servers
   * serve real audio under both.
   */
  private val MARKUP_TYPES = setOf(
    "text/html", "application/xhtml+xml",
    "application/xml", "text/xml",
    "application/json", "text/json",
  )

  private val SIGNATURE_PREFIXES = listOf(
    "ID3", "fLaC", "OggS", "RIFF", "FORM", "caff", "#!AMR", "#EXTM3U",
  )

  /**
   * The verdict for a response, or null to accept it.
   *
   * [head] is the first bytes of the body — [HEAD_BYTES] is plenty for every
   * signature below. [headIsStartOfResource] is false when the request carried a
   * range, and then only the declared type is judged: a body that begins part
   * way into a file says nothing about the file's first bytes.
   */
  fun rejection(
    contentType: String?,
    head: ByteArray,
    headIsStartOfResource: Boolean
  ): Rejection? {
    val declared = normalised(contentType)
    if (declared != null && declared in MARKUP_TYPES) {
      return Rejection.MarkupContentType(declared)
    }
    if (!headIsStartOfResource) return null
    val body = droppingByteOrderMark(head)
    if (declared != null && declared.startsWith("text/")) {
      return if (hasMediaSignature(body)) null else Rejection.TextWithoutMediaSignature(declared)
    }
    return if (beginsAsMarkup(body)) Rejection.MarkupBody else null
  }

  /**
   * Lowercased type with any `; charset=…` parameter and surrounding space
   * removed. Null for a missing or empty header.
   */
  private fun normalised(contentType: String?): String? {
    if (contentType.isNullOrEmpty()) return null
    return contentType.substringBefore(';').trim().lowercase().ifEmpty { null }
  }

  /**
   * Drop a byte-order mark so the checks below read the first real byte. A
   * UTF-16 mark matters twice over: `FF FE` also satisfies the MPEG frame sync
   * pattern, so a UTF-16 document would otherwise read as audio.
   */
  private fun droppingByteOrderMark(head: ByteArray): ByteArray {
    if (head.size >= 3 &&
      head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
    ) {
      return head.copyOfRange(3, head.size)
    }
    if (head.size >= 2 && (
        (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) ||
          (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte())
        )
    ) {
      return head.copyOfRange(2, head.size)
    }
    return head
  }

  /**
   * Whether the body opens with the magic bytes of a container either platform
   * plays, or with an HLS playlist's own first line.
   */
  private fun hasMediaSignature(body: ByteArray): Boolean {
    if (body.isEmpty()) return false
    // MPEG audio frame sync: eleven set bits across the first two bytes. ADTS
    // AAC shares it.
    if (body.size >= 2 &&
      body[0] == 0xFF.toByte() &&
      (body[1].toInt() and 0xE0) == 0xE0
    ) {
      return true
    }
    if (SIGNATURE_PREFIXES.any { startsWith(body, it, 0) }) return true
    // ISO base media (MP4 / M4A / ALAC): a box length, then the type.
    if (body.size >= 8 && startsWith(body, "ftyp", 4)) return true
    return false
  }

  /**
   * Whether the body opens as an XML / HTML document or a JSON value. No
   * container above begins with either character.
   */
  private fun beginsAsMarkup(body: ByteArray): Boolean {
    val first = body.firstOrNull { !isAsciiSpace(it) } ?: return false
    return first == '<'.code.toByte() || first == '{'.code.toByte()
  }

  private fun isAsciiSpace(b: Byte): Boolean =
    b == 0x20.toByte() || b == 0x09.toByte() || b == 0x0A.toByte() || b == 0x0D.toByte()

  private fun startsWith(body: ByteArray, ascii: String, at: Int): Boolean {
    if (body.size < at + ascii.length) return false
    for (i in ascii.indices) {
      if (body[at + i] != ascii[i].code.toByte()) return false
    }
    return true
  }
}
