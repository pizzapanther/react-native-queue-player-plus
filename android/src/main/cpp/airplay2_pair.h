/**
 * AirPlay 2 pairing and connection establishment.
 *
 * Implements the full connection sequence:
 *   1. GET /info — probe device capabilities
 *   2. POST /auth-setup — MFi challenge (if required)
 *   3. POST /pair-setup — SRP6a pairing (transient or full)
 *   4. POST /pair-verify — session key derivation
 *   5. Control channel encryption activation
 *
 * All functions operate on an ap2_session_t with an already-connected
 * RTSP socket (via ap2_rtsp_connect).
 */
#ifndef AIRPLAY2_PAIR_H
#define AIRPLAY2_PAIR_H

#include "airplay2_session.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Probe the receiver via GET /info.  Parses the binary plist response
 * and populates session flags:
 *   - pairing_required (features bit 19)
 *   - transient_ok (features bit 48)
 *   - server_pk (from "pk" field)
 *   - device_id (from "deviceID" field)
 *
 * @return 0 on success, -1 on failure
 */
int ap2_probe_info(ap2_session_t *session);

/**
 * POST /auth-setup — Curve25519 DH + MFi challenge exchange.
 * Mandatory when the receiver advertises HasUnifiedAdvertiserInfo (bit 26).
 * We don't verify the MFi certificate — just complete the exchange.
 *
 * @return 0 on success (or not required), -1 on failure
 */
int ap2_auth_setup(ap2_session_t *session);

/**
 * Transient pairing: PIN "3939", SRP6a steps 1-2 only.
 * Derives session key + control channel keys.
 * Does not persist any keys.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_pair_transient(ap2_session_t *session);

/**
 * POST /pair-pin-start — trigger PIN display on receiver.
 * Per OwnTone airplay.c — separate from pair-setup.
 * Call this for PIN-based auth BEFORE ap2_pair_setup().
 * Do NOT call this for password-based auth (password is known, not displayed).
 *
 * @return 0 on success, -1 on failure
 */
int ap2_pair_pin_start(ap2_session_t *session);

/**
 * Full pair-setup: SRP6a with user-provided credential (3 steps).
 * Generates and stores Ed25519 identity keys.
 * The credential can be a 4-digit PIN (displayed on device) or an
 * arbitrary password string — both use the same SRP mechanism.
 * Per OwnTone pair_homekit.c — strdup(pin) as SRP password.
 *
 * @param pin        PIN or password string
 * @param auth_key   OUT: hex-encoded auth key for persistence (192 chars + NUL)
 *                   Caller must free() this.
 * @return 0 on success, -1 on failure
 */
int ap2_pair_setup(ap2_session_t *session, const char *pin, char **auth_key);

/**
 * Set the receiver password for Digest auth and password-based pair-setup.
 * Must be called before ap2_connect() for PASSWORD_REQUIRED receivers.
 */
void ap2_set_password(ap2_session_t *session, const char *password);

/**
 * Pair-verify: X25519 DH + Ed25519 signature verification.
 * Uses stored Ed25519 keys (from previous pair-setup).
 * Derives control channel keys on success.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_pair_verify(ap2_session_t *session);

/**
 * Set the stored auth key on the session.  Called before connect
 * when a previously paired device is being reconnected.
 *
 * @param auth_key  192-char hex string: ed25519_sk[64] + server_pk[32]
 * @return 0 on success, -1 if key format is invalid
 */
int ap2_set_auth_key(ap2_session_t *session, const char *auth_key);

/**
 * Run the full connection sequence: info → auth-setup → pair → encrypt.
 * Chooses the right pairing path based on device capabilities and
 * whether an auth key is available.
 *
 * @return 0 on success, -1 on failure
 */
int ap2_connect(ap2_session_t *session);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_PAIR_H */
