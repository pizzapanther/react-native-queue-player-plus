package com.margelo.nitro.queueplayer

internal object UrlSchemes {
  /** Returns true for http(s) URLs; false for file/data/content/asset etc. */
  fun isRemote(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
  }
}
