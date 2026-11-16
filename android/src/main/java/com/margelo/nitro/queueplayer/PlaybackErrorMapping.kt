package com.margelo.nitro.queueplayer

import androidx.annotation.VisibleForTesting
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/**
 * Android-side mapping from raw Media3 [PlaybackException.errorCode]
 * into the lib-standardized [PlaybackErrorCode] vocabulary. Pure
 * `object`; no `TrackPlayer` instance state.
 *
 * Mirrors `PlaybackErrorMapping.swift` on the iOS side. Both
 * implementations consult the standardized code (not the raw native
 * code) to drive the transient/fatal classifier so the
 * retry-eligibility contract is platform-agnostic.
 */
@UnstableApi
internal object PlaybackErrorMapping {

  /**
   * Classify a Media3 [PlaybackException] into the lib-standardized
   * code. Visible to JUnit unit tests.
   */
  @VisibleForTesting
  fun classify(error: PlaybackException): PlaybackErrorCode = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
      PlaybackErrorCode.TIMEOUT
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
      // Media3 1.10's catch-all for non-timeout network failures
      // (DNS, host unreachable, no internet). Later Media3 versions
      // split ERROR_CODE_IO_DNS_FAILED out separately; using the
      // 1.10-stable code keeps the build portable.
      PlaybackErrorCode.NETWORK_UNREACHABLE
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
      PlaybackErrorCode.CONNECTION_LOST
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
      // Media3 stuffs the HTTP status into PlaybackException.errorCode
      // additionally — but the field is private; nativeCode in the
      // PlaybackError shape carries it via the rich-shape builder.
      PlaybackErrorCode.HTTP_ERROR
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
      PlaybackErrorCode.AUTH_ERROR
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
      PlaybackErrorCode.CLEARTEXT_NOT_PERMITTED
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
      PlaybackErrorCode.SOURCE_UNREACHABLE
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
      // The source answered, and what it sent is not media — a server
      // reporting an API-level failure as a successful response, or a proxy
      // serving an error page. Not SOURCE_UNREACHABLE: it was reached, and
      // calling it unreachable invites a retry that cannot change the answer.
      // Not HTTP_ERROR either: the status was a success. What is wrong is the
      // payload, which is the same thing iOS reports when AVFoundation fails to
      // parse the identical body.
      PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
      PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
      PlaybackErrorCode.DECODER_NOT_FOUND
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
      PlaybackErrorCode.DECODING_FAILED
    else -> PlaybackErrorCode.UNKNOWN
  }

  /**
   * Whether the lib should retry on this code automatically (subject
   * to `PlayerConfig.autoRetries`). Mirrors iOS
   * `PlaybackErrorMapping.swift#isTransient`.
   */
  @VisibleForTesting
  fun isTransient(code: PlaybackErrorCode): Boolean = when (code) {
    PlaybackErrorCode.TIMEOUT,
    PlaybackErrorCode.CONNECTION_LOST,
    PlaybackErrorCode.NETWORK_UNREACHABLE,
    PlaybackErrorCode.SOURCE_UNREACHABLE,
    PlaybackErrorCode.CANNOT_OPEN,
    PlaybackErrorCode.UNKNOWN -> true
    PlaybackErrorCode.HTTP_ERROR,
    PlaybackErrorCode.AUTH_ERROR,
    PlaybackErrorCode.CLEARTEXT_NOT_PERMITTED,
    PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED,
    PlaybackErrorCode.DECODER_NOT_FOUND,
    PlaybackErrorCode.DECODING_FAILED -> false
  }

  /**
   * Strip query strings from a native error message so logged
   * `nativeMessage` doesn't leak auth tokens: stream URLs with
   * `?token=…`-style query strings end up inside
   * `PlaybackException.message`.
   *
   * Walks the full message removing EVERY `?…(whitespace|end)` run
   * so multi-URL error messages have all query strings stripped.
   * Mirrors iOS `PlaybackErrorMapping.stripQuery`.
   */
  @VisibleForTesting
  fun stripQuery(message: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < message.length) {
      if (message[i] == '?') {
        // Skip until whitespace or end.
        while (i < message.length && !message[i].isWhitespace()) i++
      } else {
        out.append(message[i])
        i++
      }
    }
    return out.toString()
  }

  /**
   * Lib-mapped human-readable message for a standardized code.
   * Mirrors iOS `PlaybackErrorMapping.swift#messageFor`.
   */
  @VisibleForTesting
  fun messageFor(code: PlaybackErrorCode): String = when (code) {
    PlaybackErrorCode.TIMEOUT -> "Network request timed out."
    PlaybackErrorCode.CONNECTION_LOST -> "Network connection lost."
    PlaybackErrorCode.NETWORK_UNREACHABLE -> "Network unreachable."
    PlaybackErrorCode.HTTP_ERROR -> "Server returned an error response."
    PlaybackErrorCode.AUTH_ERROR -> "Authentication required or rejected."
    PlaybackErrorCode.CLEARTEXT_NOT_PERMITTED ->
      "Cleartext HTTP not permitted by app transport policy."
    PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED ->
      "Audio format not recognized."
    PlaybackErrorCode.DECODER_NOT_FOUND ->
      "No decoder available for this audio format."
    PlaybackErrorCode.DECODING_FAILED -> "Audio decoder failure."
    PlaybackErrorCode.SOURCE_UNREACHABLE ->
      "Audio source could not be reached."
    PlaybackErrorCode.CANNOT_OPEN ->
      "Audio source could not be opened."
    PlaybackErrorCode.UNKNOWN -> "Playback failed."
  }

  /**
   * Construct a richly-populated [PlaybackError] from a
   * [PlaybackException] + the already-mapped standardized code.
   * Centralised so every Android error fire site produces the same
   * shape (sanitised `nativeMessage`, consistent `fatal` classifier,
   * etc.). Mirrors `TrackPlayer.swift#buildPlaybackError` on iOS.
   *
   * `nativeDomain` is `"Media3"` (matches iOS's domain-only
   * convention — `"NSURLErrorDomain"` / `"AVFoundationErrorDomain"`).
   * The Media3 `errorCodeName` (e.g.
   * `"ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"`) is surfaced via
   * the `nativeMessage` prefix so consumers parsing the message can
   * grep for the enum name without splitting fields.
   */
  fun buildPlaybackError(
    error: PlaybackException,
    mapped: PlaybackErrorCode,
    queueItemId: String = "",
    url: String = ""
  ): PlaybackError {
    val rawMessage = error.message ?: ""
    // Prefix the message with the Media3 errorCodeName so consumers
    // can branch on it without parsing nativeDomain. iOS doesn't
    // need this because NSError carries the code as a number that's
    // already in nativeCode + iOS error codes don't have stable
    // string names. nativeMessage stays PII-safe via stripQuery.
    val prefixedMessage = "${error.errorCodeName}: $rawMessage"
    return PlaybackError(
      code = mapped,
      message = messageFor(mapped),
      fatal = !isTransient(mapped),
      nativeCode = error.errorCode.toDouble(),
      nativeDomain = "Media3",
      nativeMessage = stripQuery(prefixedMessage),
      queueItemId = queueItemId,
      url = url,
    )
  }
}
