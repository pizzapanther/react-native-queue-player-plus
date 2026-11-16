/**
 * AirPlay 2 PTP master clock — minimal IEEE 1588v2 sender for AirPlay 2.
 *
 * The AirPlay receiver requires timingProtocol:"PTP" and expects to receive
 * PTP Announce, Sync+Follow_Up, and Signaling messages from the sender.
 * Without these, the receiver buffers audio indefinitely.
 *
 * This is a one-way master clock implementation (no Delay_Req handling)
 * since binding to privileged ports 319/320 isn't possible on Android
 * without root.  The receiver can still synchronise from one-way Sync
 * messages — accuracy is sufficient for local-network audio playback.
 */
#ifndef AIRPLAY2_PTP_H
#define AIRPLAY2_PTP_H

#include "airplay2_session.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Start the PTP master clock thread.  Sends periodic timing messages
 * to the receiver at session->host on ports 319 (event) and 320 (general).
 * Must be called after the RTSP connection is established but before RECORD.
 * Returns 0 on success, -1 on failure.
 */
int ap2_ptp_start(ap2_session_t *session);

/**
 * Stop the PTP master clock thread and close PTP sockets.
 * Safe to call even if PTP was never started.
 */
void ap2_ptp_stop(ap2_session_t *session);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_PTP_H */
