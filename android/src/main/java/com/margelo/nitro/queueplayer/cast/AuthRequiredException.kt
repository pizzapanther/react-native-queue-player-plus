package com.margelo.nitro.queueplayer.cast

/**
 * Thrown by [CastBackend.connect] when the receiver requires
 * authentication (e.g. AirPlay password / PIN). `CastManager` catches
 * this + rejects the connect promise with `authFailed`, surfacing
 * [authMode] in the rejection payload so JS can show the right
 * prompt (`"pin"` = 4-digit code shown by receiver; `"password"` =
 * user already knows it).
 */
class AuthRequiredException(
  message: String,
  val authMode: String,
) : RuntimeException(message)
