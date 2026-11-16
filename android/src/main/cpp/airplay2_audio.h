/**
 * AirPlay 2 audio pipeline — ring buffer drain, ALAC encode, encrypt, send.
 *
 * The drain thread is per-session: started by ap2_audio_start(), stopped
 * by ap2_audio_stop().  It pulls 352-sample chunks from the ring buffer,
 * ALAC-encodes them, encrypts with ChaCha20-Poly1305 using the audio key
 * (shk), and sends as RTP/UDP packets.
 */
#ifndef AIRPLAY2_AUDIO_H
#define AIRPLAY2_AUDIO_H

#include "airplay2_session.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialise the ALAC encoder for this session.
 * Must be called before ap2_audio_start().
 *
 * @return 0 on success, -1 on failure
 */
int ap2_audio_init(ap2_session_t *session);

/**
 * Start the drain thread.  Begins pulling from ring buffer and
 * sending encrypted audio packets.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_audio_start(ap2_session_t *session);

/**
 * Pause the drain thread (stops sending but keeps thread alive).
 */
void ap2_audio_pause(ap2_session_t *session);

/**
 * Resume a paused drain thread.
 */
void ap2_audio_resume(ap2_session_t *session);

/**
 * Stop the drain thread and free the ALAC encoder.
 */
void ap2_audio_stop(ap2_session_t *session);

/**
 * Write PCM data into the ring buffer.
 * Called from JNI nativeAp2FeedPcm.
 *
 * @return number of bytes consumed (may be less than len if buffer full)
 */
int ap2_audio_write(ap2_session_t *session, const uint8_t *pcm, size_t len);

/**
 * Get the number of bytes available in the ring buffer.
 */
size_t ap2_audio_ring_available(ap2_session_t *session);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_AUDIO_H */
