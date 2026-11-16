package com.margelo.nitro.queueplayer.cast.http

import java.io.File
import java.util.Locale

object MimeTypes {
  private val EXTENSION_MAP = mapOf(
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "flac" to "audio/flac",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/ogg",
    "wav" to "audio/x-wav",
    "wave" to "audio/x-wav",
  )

  const val DEFAULT = "application/octet-stream"

  fun fromExtension(extension: String?): String {
    if (extension.isNullOrBlank()) return DEFAULT
    return EXTENSION_MAP[extension.lowercase(Locale.ROOT)] ?: DEFAULT
  }

  fun fromFile(file: File): String = fromExtension(file.extension)
}
