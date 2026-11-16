/**
 * Blocking RTSP client for AirPlay 2.
 *
 * Simple TCP-based RTSP/1.0 client with CSeq auto-increment.
 * Supports plaintext and encrypted (ChaCha20-Poly1305) control
 * channel modes.  All I/O is blocking — runs on a background thread.
 */
#ifndef AIRPLAY2_RTSP_H
#define AIRPLAY2_RTSP_H

#include "airplay2_session.h"
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Response from an RTSP request. */
typedef struct {
    int status;             /* HTTP-style status code (200, 401, etc.) */
    char *content_type;     /* Content-Type header value (heap, may be NULL) */
    uint8_t *body;          /* Response body (heap, may be NULL) */
    size_t body_len;        /* Response body length */
} ap2_rtsp_response_t;

/**
 * Connect the RTSP TCP socket to the receiver.
 * Sets session->rtsp_fd on success.
 * @return 0 on success, -1 on failure
 */
int ap2_rtsp_connect(ap2_session_t *session);

/**
 * Send an RTSP request with optional extra headers and read the response.
 * Auto-increments session->cseq.  If session->encrypted is set,
 * the request/response are wrapped in ChaCha20-Poly1305 frames.
 *
 * @param session       Session with connected rtsp_fd
 * @param method        RTSP method ("GET", "POST", "SETUP", etc.)
 * @param path          Request path ("/info", "/pair-setup", etc.)
 * @param ct            Content-Type for request body (NULL if no body)
 * @param body          Request body bytes (NULL if no body)
 * @param body_len      Request body length
 * @param extra_headers NULL or "Header: value\r\n..." string appended before \r\n
 * @param resp          OUT: response struct (caller must call ap2_rtsp_response_free)
 * @return 0 on success, -1 on network/parse error
 */
int ap2_rtsp_request_ex(ap2_session_t *session,
                        const char *method, const char *path,
                        const char *ct, const uint8_t *body, size_t body_len,
                        const char *extra_headers,
                        ap2_rtsp_response_t *resp);

/**
 * Convenience wrapper — calls ap2_rtsp_request_ex with extra_headers=NULL.
 */
int ap2_rtsp_request(ap2_session_t *session,
                     const char *method, const char *path,
                     const char *ct, const uint8_t *body, size_t body_len,
                     ap2_rtsp_response_t *resp);

/**
 * Like ap2_rtsp_request but overrides the RTSP socket receive timeout
 * for this call only. Used by paths that must not hang the calling
 * thread when the receiver is unreachable (e.g. /feedback keep-alive).
 *
 * @param timeout_sec socket receive timeout in seconds (0 = leave alone)
 */
int ap2_rtsp_request_tmo(ap2_session_t *session,
                         const char *method, const char *path,
                         const char *ct, const uint8_t *body, size_t body_len,
                         int timeout_sec,
                         ap2_rtsp_response_t *resp);

/**
 * Free response fields (content_type, body).
 */
void ap2_rtsp_response_free(ap2_rtsp_response_t *resp);

/**
 * Close the RTSP connection.
 */
void ap2_rtsp_close(ap2_session_t *session);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_RTSP_H */
