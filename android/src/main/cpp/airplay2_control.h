/**
 * AirPlay 2 session control — SETUP, RECORD, volume, teardown.
 *
 * These functions run on the RTSP connection after pairing/encryption
 * has been established.  They use the binary plist encoder for request
 * bodies and the plist decoder for response parsing.
 */
#ifndef AIRPLAY2_CONTROL_H
#define AIRPLAY2_CONTROL_H

#include "airplay2_session.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * SETUP (session): send binary plist with deviceID, sessionUUID,
 * timingProtocol="PTP".  Parse response for eventPort.
 * Opens event TCP connection on success.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_setup_session(ap2_session_t *session);

/**
 * SETUP (stream): derive shk (audio key) from shared secret,
 * send binary plist with stream parameters (ALAC/44100/16/2).
 * Parse response for dataPort and controlPort.
 * Creates UDP socket to receiver's dataPort.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_setup_stream(ap2_session_t *session);

/**
 * RECORD: send empty RECORD on RTSP channel.
 * @return 0 on success, -1 on failure
 */
int ap2_record(ap2_session_t *session);

/**
 * FLUSH: stop audio playback on the receiver.
 * Sends RTP-Info header with current seq/rtptime per OwnTone airplay.c.
 * @return 0 on success, -1 on failure
 */
int ap2_flush(ap2_session_t *session);

/**
 * SET_PARAMETER volume.
 * @param vol_db  Volume in dB: -144 (mute), -30..0 (audible range)
 * @return 0 on success, -1 on failure
 */
int ap2_set_volume(ap2_session_t *session, float vol_db);

/**
 * SET_PARAMETER metadata (DMAP-encoded title/artist/album).
 * Per OwnTone airplay.c.
 * @return 0 on success, -1 on failure
 */
int ap2_set_metadata(ap2_session_t *session,
                     const char *title, const char *artist, const char *album);

/**
 * SET_PARAMETER artwork (raw JPEG/PNG image bytes).
 * Per OwnTone airplay.c.
 * @param content_type  "image/jpeg" or "image/png"
 * @return 0 on success, -1 on failure
 */
int ap2_set_artwork(ap2_session_t *session,
                    const uint8_t *data, size_t len,
                    const char *content_type);

/**
 * SET_PARAMETER progress (elapsed/duration in milliseconds).
 * Per OwnTone airplay.c. Converts ms to RTP timestamps.
 * @return 0 on success, -1 on failure
 */
int ap2_set_progress(ap2_session_t *session,
                     uint32_t elapsed_ms, uint32_t duration_ms);

/**
 * TEARDOWN: send TEARDOWN to cleanly close the session.
 * @return 0 on success, -1 on failure
 */
int ap2_teardown(ap2_session_t *session);

/**
 * Full session establishment: SETUP session → SETUP stream → RECORD → volume.
 * Call after ap2_connect() succeeds (pairing + encryption done).
 * On success the session is ready for audio streaming.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_start_streaming(ap2_session_t *session);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_CONTROL_H */
