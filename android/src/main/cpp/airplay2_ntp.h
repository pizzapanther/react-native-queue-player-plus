/**
 * AirPlay 2 NTP timing service — responds to receiver timing requests.
 *
 * The receiver sends 32-byte NTP timing requests (PT=0xD2) to our
 * advertised timingPort.  We respond with 32-byte timing responses
 * (PT=0xD3) containing CLOCK_MONOTONIC timestamps in NTP epoch format.
 *
 * This replaces PTP timing for Android where binding to privileged
 * ports 319/320 is not possible.  OwnTone uses NTP timing exclusively
 * for AirPlay 2 and it works against all real Apple hardware.
 */
#ifndef AIRPLAY2_NTP_H
#define AIRPLAY2_NTP_H

#include "airplay2_session.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Start the NTP timing service.  Binds an ephemeral UDP port and starts
 * a listener thread that responds to timing requests.  Sets
 * session->timing_port to the bound port number.
 * Returns 0 on success, -1 on failure.
 */
int ap2_ntp_start(ap2_session_t *session);

/**
 * Stop the NTP timing service and close the timing socket.
 * Safe to call even if NTP was never started.
 */
void ap2_ntp_stop(ap2_session_t *session);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_NTP_H */
