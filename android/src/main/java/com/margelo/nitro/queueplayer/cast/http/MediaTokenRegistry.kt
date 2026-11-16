package com.margelo.nitro.queueplayer.cast.http

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RegisteredMedia(
  val sessionId: String,
  val file: File,
  val contentType: String,
)

internal class MediaTokenRegistry {
  private val tokens = ConcurrentHashMap<String, RegisteredMedia>()

  fun register(sessionId: String, file: File, contentType: String? = null): String {
    val token = UUID.randomUUID().toString().replace("-", "")
    val mime = contentType ?: MimeTypes.fromFile(file)
    tokens[token] = RegisteredMedia(sessionId, file, mime)
    return token
  }

  fun resolve(token: String): RegisteredMedia? = tokens[token]

  fun revokeAll(sessionId: String) {
    val iter = tokens.entries.iterator()
    while (iter.hasNext()) {
      if (iter.next().value.sessionId == sessionId) iter.remove()
    }
  }
}
