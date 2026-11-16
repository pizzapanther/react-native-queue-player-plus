package com.margelo.nitro.queueplayer.cast

import android.net.Uri

/**
 * Protocol-neutral media item handed to cast backends. Cast backends
 * consume typed fields directly — no reflection. Translation from the
 * lib's `TrackItem` happens at the bridge boundary so the backend
 * never depends on the Nitro JS-bridge contract.
 */
data class CastMediaItem(
  val uri: Uri,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val artwork: Uri? = null,
  val durationMs: Long? = null,
  val contentType: String? = null,
  val headers: Map<String, String>? = null,
)
