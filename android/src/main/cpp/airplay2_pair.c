/**
 * AirPlay 2 pairing and connection establishment — implementation.
 *
 * Reference: OwnTone's pair_homekit.c + airplay.c
 */

#include "airplay2_pair.h"
#include "airplay2_rtsp.h"
#include "airplay2_crypto.h"
#include "airplay2_tlv.h"
#include "airplay2_plist.h"
#include "pair.h"  /* OwnTone vendored pair_ap */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "AP2Pair"
#include "airplay2_filelog.h"

/* ---- Hex encode/decode helpers ---- */

static void hex_encode(const uint8_t *data, size_t len, char *out) {
    for (size_t i = 0; i < len; i++)
        sprintf(out + i * 2, "%02x", data[i]);
}

static int hex_decode(const char *hex, uint8_t *out, size_t out_len) {
    size_t hex_len = strlen(hex);
    if (hex_len != out_len * 2) return -1;
    for (size_t i = 0; i < out_len; i++) {
        unsigned int byte;
        if (sscanf(hex + i * 2, "%02x", &byte) != 1) return -1;
        out[i] = (uint8_t)byte;
    }
    return 0;
}

/* ---- Feature bit extraction ---- */

/* Check if a feature bit is set in a 64-bit features value. */
static int has_feature(uint64_t features, int bit) {
    return (features & ((uint64_t)1 << bit)) != 0;
}

/* ================================================================
 * GET /info
 * ================================================================ */

int ap2_probe_info(ap2_session_t *session) {
    ap2_rtsp_response_t resp;
    if (ap2_rtsp_request(session, "GET", "/info", NULL, NULL, 0, &resp) != 0)
        return -1;

    if (resp.status != 200) {
        LOGE("GET /info returned %d", resp.status);
        ap2_rtsp_response_free(&resp);
        return -1;
    }

    /* Parse binary plist response. */
    ap2_plist_value_t *plist = ap2_plist_parse(resp.body, resp.body_len);
    if (!plist) {
        LOGE("Failed to parse /info plist");
        ap2_rtsp_response_free(&resp);
        return -1;
    }

    /* Extract features. */
    int64_t features = ap2_plist_dict_get_int(plist, "features", 0);
    LOGI("/info: features=0x%llx", (unsigned long long)features);

    /* Extract statusFlags — runtime flags that override feature bits.
     * OwnTone uses these (not features) to decide pairing mode.
     * Bit 3 = PIN_REQUIRED, Bit 7 = PASSWORD_REQUIRED,
     * Bit 9 = ONE_TIME_PAIRING_REQUIRED (→ full pair-setup + pair-verify). */
    int64_t status_flags = ap2_plist_dict_get_int(plist, "statusFlags", 0);
    LOGI("/info: statusFlags=0x%llx", (unsigned long long)status_flags);

    /* Store individual statusFlags bits for the auth decision tree.
     * Per OwnTone airplay.c. */
    session->one_time_pairing = (status_flags & ((uint64_t)1 << 9)) ? 1 : 0;
    session->pin_required     = (status_flags & ((uint64_t)1 << 3)) ? 1 : 0;
    session->password_required = (status_flags & ((uint64_t)1 << 7)) ? 1 : 0;
    LOGI("/info: one_time_pairing=%d pin_required=%d password_required=%d",
         session->one_time_pairing, session->pin_required, session->password_required);

    /* Decide auth mode.  Per OwnTone airplay.c and pyatv:
     * ONE_TIME_PAIRING ALWAYS means PIN mode, even when PASSWORD_REQUIRED
     * is also set.  The PIN is displayed on the receiver; the password is
     * a separate concern used for HTTP Digest auth on encrypted RTSP.
     * Using the password as SRP credential produces a working SRP exchange
     * but the Mac does not activate the encrypted channel afterwards. */
    if (session->one_time_pairing) {
        session->auth_mode = AP2_AUTH_PIN;
        session->pairing_required = session->has_auth_key ? 0 : 1;
        session->transient_ok = 0;
    } else if (session->pin_required) {
        session->auth_mode = AP2_AUTH_PIN;
        session->pairing_required = 1;
        session->transient_ok = 0;
        /* PIN_REQUIRED always clears auth_key — per OwnTone airplay.c. */
        session->has_auth_key = 0;
    } else if (session->password_required) {
        session->auth_mode = AP2_AUTH_PASSWORD;
        session->pairing_required = session->password[0] ? 0 : 1;
        session->transient_ok = 0;
    } else {
        session->auth_mode = AP2_AUTH_TRANSIENT;
        session->pairing_required = has_feature(features, 19) ? 1 : 0;
        session->transient_ok = has_feature(features, 48) ? 1 : 0;
    }

    LOGI("/info: auth_mode=%d pairing_required=%d transient_ok=%d",
         session->auth_mode, session->pairing_required, session->transient_ok);

    /* Extract device ID. */
    const char *dev_id = ap2_plist_dict_get_string(plist, "deviceID");
    if (dev_id) {
        strncpy(session->device_id, dev_id, sizeof(session->device_id) - 1);
        LOGI("/info: deviceID=%s", session->device_id);
    }

    /* Extract server public key (pk) from /info.
     * This is the device's TRANSPORT key — NOT the identity key from
     * pair-setup. Do NOT overwrite server_pk if we already have an auth
     * key loaded (pair-setup identity key takes priority for pair-verify).
     * Per OwnTone: pair keys and /info pk are stored separately. */
    size_t pk_len = 0;
    const uint8_t *pk = ap2_plist_dict_get_data(plist, "pk", &pk_len);
    if (pk && pk_len == 32) {
        if (session->has_auth_key) {
            LOGI("/info: got server pk (32 bytes) — NOT overwriting (auth key loaded)");
        } else {
            memcpy(session->server_pk, pk, 32);
            LOGI("/info: got server pk (32 bytes)");
        }
    }

    session->state = AP2_STATE_INFO;

    ap2_plist_free(plist);
    ap2_rtsp_response_free(&resp);
    return 0;
}

/* ================================================================
 * POST /auth-setup
 * ================================================================ */

int ap2_auth_setup(ap2_session_t *session) {
    /* Generate ephemeral Curve25519 keypair. */
    uint8_t sk[32], pk[32];
    ap2_x25519_keygen(sk, pk);

    /* Request body: [0x01][32-byte public key] */
    uint8_t body[33];
    body[0] = 0x01;
    memcpy(body + 1, pk, 32);

    ap2_rtsp_response_t resp;
    if (ap2_rtsp_request(session, "POST", "/auth-setup",
                          "application/octet-stream",
                          body, 33, &resp) != 0)
        return -1;

    if (resp.status != 200) {
        LOGE("POST /auth-setup returned %d", resp.status);
        ap2_rtsp_response_free(&resp);
        return -1;
    }

    /* Response: [32-byte server pub][variable MFi data] */
    if (resp.body_len < 32) {
        LOGE("/auth-setup response too short: %zu", resp.body_len);
        ap2_rtsp_response_free(&resp);
        return -1;
    }

    /* We don't verify the MFi certificate — just complete the exchange. */
    LOGI("/auth-setup completed, response=%zu bytes", resp.body_len);

    session->state = AP2_STATE_AUTH_SETUP;

    ap2_rtsp_response_free(&resp);
    return 0;
}

/* ================================================================
 * Transient pairing via OwnTone pair_ap/ API
 * ================================================================ */

/**
 * Helper: send one pair-setup/pair-verify step over RTSP.
 * Returns 0 on 200 OK, -1 on error.  Sets pairing_required on 470.
 */
static int pair_post(ap2_session_t *session, const char *endpoint,
                     const char *hkp_header,
                     const uint8_t *body, size_t body_len,
                     ap2_rtsp_response_t *resp) {
    if (ap2_rtsp_request_ex(session, "POST", endpoint,
                             "application/octet-stream",
                             body, body_len,
                             hkp_header, resp) != 0) {
        return -1;
    }
    if (resp->status != 200) {
        LOGE("%s returned %d", endpoint, resp->status);
        if (resp->status == 470) {
            session->pairing_required = 1;
            session->auth_mode = AP2_AUTH_PIN;
            session->state = AP2_STATE_PAIR;
        }
        ap2_rtsp_response_free(resp);
        return -1;
    }
    return 0;
}

/**
 * Run pair-setup using low-level request/response API (matching OwnTone).
 * Transient = 2 steps, normal = 3 steps.
 */
static int pair_setup_exchange(struct pair_setup_context *sctx,
                               ap2_session_t *session,
                               const char *endpoint,
                               const char *hkp_header,
                               int num_steps) {
    uint8_t *body;
    size_t len;
    ap2_rtsp_response_t resp;

    /* Step 1: request1 → response1 */
    body = pair_setup_request1(&len, sctx);
    if (!body) {
        LOGE("pair_setup_request1 failed: %s", pair_setup_errmsg(sctx));
        return -1;
    }
    if (pair_post(session, endpoint, hkp_header, body, len, &resp) != 0) {
        free(body);
        return -1;
    }
    free(body);
    if (pair_setup_response1(sctx, resp.body, resp.body_len) != 0) {
        LOGE("pair_setup_response1 failed: %s", pair_setup_errmsg(sctx));
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    /* Step 2: request2 → response2 */
    body = pair_setup_request2(&len, sctx);
    if (!body) {
        LOGE("pair_setup_request2 failed: %s", pair_setup_errmsg(sctx));
        return -1;
    }
    if (pair_post(session, endpoint, hkp_header, body, len, &resp) != 0) {
        free(body);
        return -1;
    }
    free(body);
    if (pair_setup_response2(sctx, resp.body, resp.body_len) != 0) {
        LOGE("pair_setup_response2 failed: %s", pair_setup_errmsg(sctx));
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    if (num_steps < 3)
        return 0;

    /* Step 3 (normal only): request3 → response3 */
    body = pair_setup_request3(&len, sctx);
    if (!body) {
        LOGE("pair_setup_request3 failed: %s", pair_setup_errmsg(sctx));
        return -1;
    }
    if (pair_post(session, endpoint, hkp_header, body, len, &resp) != 0) {
        free(body);
        return -1;
    }
    free(body);
    if (pair_setup_response3(sctx, resp.body, resp.body_len) != 0) {
        LOGE("pair_setup_response3 failed: %s", pair_setup_errmsg(sctx));
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    return 0;
}

/**
 * Run pair-verify using low-level request/response API (matching OwnTone).
 * Always 2 steps.
 */
static int pair_verify_exchange(struct pair_verify_context *vctx,
                                ap2_session_t *session) {
    uint8_t *body;
    size_t len;
    ap2_rtsp_response_t resp;

    /* Step 1: verify_request1 → verify_response1 */
    body = pair_verify_request1(&len, vctx);
    if (!body) {
        LOGE("pair_verify_request1 failed: %s", pair_verify_errmsg(vctx));
        return -1;
    }
    if (pair_post(session, "/pair-verify", NULL, body, len, &resp) != 0) {
        free(body);
        return -1;
    }
    free(body);
    if (pair_verify_response1(vctx, resp.body, resp.body_len) != 0) {
        LOGE("pair_verify_response1 failed: %s", pair_verify_errmsg(vctx));
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    /* Step 2: verify_request2 → verify_response2 */
    body = pair_verify_request2(&len, vctx);
    if (!body) {
        LOGE("pair_verify_request2 failed: %s", pair_verify_errmsg(vctx));
        return -1;
    }
    if (pair_post(session, "/pair-verify", NULL, body, len, &resp) != 0) {
        free(body);
        return -1;
    }
    free(body);
    if (pair_verify_response2(vctx, resp.body, resp.body_len) != 0) {
        LOGE("pair_verify_response2 failed: %s", pair_verify_errmsg(vctx));
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    return 0;
}

int ap2_pair_transient(ap2_session_t *session) {
    LOGI("Starting transient pairing (OwnTone pair_ap)");

    struct pair_setup_context *sctx =
        pair_setup_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, NULL, NULL, NULL,
                       session->device_id);
    if (!sctx) {
        LOGE("pair_setup_new(TRANSIENT) failed");
        return -1;
    }

    if (pair_setup_exchange(sctx, session, "/pair-setup",
                            "X-Apple-HKP: 4\r\n", 2) < 0) {
        LOGE("Transient pairing failed: %s", pair_setup_errmsg(sctx));
        pair_setup_free(sctx);
        return -1;
    }

    struct pair_result *result = NULL;
    if (pair_setup_result(NULL, &result, sctx) < 0 || !result) {
        LOGE("pair_setup_result failed");
        pair_setup_free(sctx);
        return -1;
    }

    /* For transient: shared_secret_len = 64. */
    size_t ss_len = result->shared_secret_len > 64 ? 64 : result->shared_secret_len;
    memcpy(session->shared_secret, result->shared_secret, ss_len);
    LOGI("Stored shared_secret: %zu bytes (transient)", ss_len);

    /* Derive control-channel encryption keys. */
    ap2_hkdf_sha512(result->shared_secret, result->shared_secret_len,
                     "Control-Salt", "Control-Write-Encryption-Key",
                     session->write_key, 32);
    ap2_hkdf_sha512(result->shared_secret, result->shared_secret_len,
                     "Control-Salt", "Control-Read-Encryption-Key",
                     session->read_key, 32);
    session->write_nonce = 0;
    session->read_nonce = 0;
    session->has_control_keys = 1;
    session->encrypted = 1;

    pair_setup_free(sctx);
    session->state = AP2_STATE_ENCRYPTED;
    LOGI("Transient pairing complete (OwnTone pair_ap) — encrypted channel active");
    return 0;
}

/* ================================================================
 * Full pair-setup (SRP6a with real PIN, 3 steps)
 * ================================================================ */

int ap2_pair_pin_start(ap2_session_t *session) {
    /* POST /pair-pin-start — triggers PIN display on receiver.
     * Per OwnTone airplay.c — AIRPLAY_SEQ_PIN_START is a separate
     * sequence from AIRPLAY_SEQ_PAIR_SETUP. */
    ap2_rtsp_response_t resp;
    if (ap2_rtsp_request(session, "POST", "/pair-pin-start",
                          NULL, NULL, 0, &resp) != 0)
        return -1;
    int status = resp.status;
    ap2_rtsp_response_free(&resp);
    LOGI("pair-pin-start returned %d", status);
    return (status == 200) ? 0 : -1;
}

int ap2_pair_setup(ap2_session_t *session, const char *pin, char **auth_key) {
    LOGI("Starting full pair-setup (OwnTone pair_ap) with credential");

    struct pair_setup_context *sctx =
        pair_setup_new(PAIR_CLIENT_HOMEKIT_NORMAL, pin, NULL, NULL,
                       session->device_id);
    if (!sctx) {
        LOGE("pair_setup_new(NORMAL) failed");
        return -1;
    }

    if (pair_setup_exchange(sctx, session, "/pair-setup",
                            "X-Apple-HKP: 3\r\n", 3) < 0) {
        LOGE("pair_setup(NORMAL) failed: %s", pair_setup_errmsg(sctx));
        pair_setup_free(sctx);
        return -1;
    }

    /* Extract keys and result. */
    const char *client_setup_keys = NULL;
    struct pair_result *result = NULL;
    if (pair_setup_result(&client_setup_keys, &result, sctx) < 0 || !result) {
        LOGE("pair_setup_result failed");
        pair_setup_free(sctx);
        return -1;
    }

    /* Store Ed25519 identity keys in session. */
    memcpy(session->ed25519_sk, result->client_private_key, 64);
    memcpy(session->ed25519_pk, result->client_public_key, 32);
    memcpy(session->server_pk, result->server_public_key, 32);

    /* Store shared secret for audio encryption. */
    size_t ss_len = result->shared_secret_len > 64 ? 64 : result->shared_secret_len;
    memcpy(session->shared_secret, result->shared_secret, ss_len);
    LOGI("Stored shared_secret: %zu bytes from pair-setup", ss_len);

    /* Build auth key for persistence (192 hex chars). */
    if (auth_key && client_setup_keys) {
        *auth_key = strdup(client_setup_keys);
    }

    session->has_auth_key = 1;
    session->state = AP2_STATE_PAIR;

    pair_setup_free(sctx);
    LOGI("Full pair-setup complete (OwnTone pair_ap) — run pair-verify next");
    return 0;
}

/* ================================================================
 * Pair-verify (X25519 DH + Ed25519, 2 steps)
 * ================================================================ */

int ap2_pair_verify(ap2_session_t *session) {
    LOGI("Starting pair-verify (OwnTone pair_ap)");

    /* Build hex key string from session's stored Ed25519 sk + server pk.
     * Format: hex(client_private_key[64] + server_public_key[32]) = 192 chars.
     * This matches pair_setup_result()'s client_setup_keys format exactly. */
    char keys_hex[193];
    {
        uint8_t combined[96];
        memcpy(combined, session->ed25519_sk, 64);
        memcpy(combined + 64, session->server_pk, 32);
        hex_encode(combined, 96, keys_hex);
        keys_hex[192] = '\0';
    }

    struct pair_verify_context *vctx =
        pair_verify_new(PAIR_CLIENT_HOMEKIT_NORMAL, keys_hex,
                        NULL, NULL, session->device_id);
    if (!vctx) {
        LOGE("pair_verify_new failed");
        return -1;
    }

    if (pair_verify_exchange(vctx, session) < 0) {
        LOGE("pair_verify failed: %s", pair_verify_errmsg(vctx));
        pair_verify_free(vctx);
        return -1;
    }

    /* Extract shared secret. */
    struct pair_result *result = NULL;
    if (pair_verify_result(&result, vctx) < 0 || !result) {
        LOGE("pair_verify_result failed");
        pair_verify_free(vctx);
        return -1;
    }

    /* Store X25519 shared secret for audio encryption (shk). */
    memcpy(session->shared_secret, result->shared_secret, 32);
    LOGI("Stored shared_secret: 32 bytes from pair-verify");

    /* Derive control channel keys. */
    ap2_hkdf_sha512(result->shared_secret, result->shared_secret_len,
                     "Control-Salt", "Control-Write-Encryption-Key",
                     session->write_key, 32);
    ap2_hkdf_sha512(result->shared_secret, result->shared_secret_len,
                     "Control-Salt", "Control-Read-Encryption-Key",
                     session->read_key, 32);

    session->write_nonce = 0;
    session->read_nonce = 0;
    session->encrypted = 1;
    session->state = AP2_STATE_ENCRYPTED;

    pair_verify_free(vctx);
    LOGI("Pair-verify complete (OwnTone pair_ap) — encrypted channel active");
    return 0;
}

/* ================================================================
 * Auth key management
 * ================================================================ */

int ap2_set_auth_key(ap2_session_t *session, const char *auth_key) {
    if (!auth_key || strlen(auth_key) != 192) return -1;

    uint8_t combined[96];
    if (hex_decode(auth_key, combined, 96) != 0) return -1;

    memcpy(session->ed25519_sk, combined, 64);
    /* Extract pk from the first 64 bytes of the secret key. */
    memcpy(session->ed25519_pk, combined + 32, 32);
    memcpy(session->server_pk, combined + 64, 32);
    session->has_auth_key = 1;

    LOGI("Auth key loaded (192 hex chars)");
    return 0;
}

/* ================================================================
 * Password
 * ================================================================ */

void ap2_set_password(ap2_session_t *session, const char *password) {
    if (password) {
        strncpy(session->password, password, sizeof(session->password) - 1);
        session->password[sizeof(session->password) - 1] = '\0';
    } else {
        session->password[0] = '\0';
    }
}

/* ================================================================
 * Full connection sequence
 * ================================================================ */

int ap2_connect(ap2_session_t *session) {
    /* Step 1: TCP connect. */
    if (ap2_rtsp_connect(session) != 0) return -1;

    /* Step 2: Probe /info — sets auth_mode, pairing_required, etc. */
    if (ap2_probe_info(session) != 0) return -1;

    /* Step 3: Pairing — per OwnTone airplay.c decision tree. */

    if (session->auth_mode == AP2_AUTH_PIN) {
        /* ONE_TIME_PAIRING or PIN_REQUIRED. */
        if (session->has_auth_key) {
            /* Try pair-verify with stored key first. */
            if (ap2_pair_verify(session) == 0) return 0;
            LOGI("pair-verify failed, clearing auth key");
            session->has_auth_key = 0;
        }
        /* Need PIN from user — return to Kotlin for prompt. */
        session->pairing_required = 1;
        session->state = AP2_STATE_PAIR;
        LOGI("PIN auth required — waiting for user input");
        return -1;
    }

    if (session->auth_mode == AP2_AUTH_PASSWORD) {
        /* PASSWORD_REQUIRED. */
        if (session->has_auth_key) {
            if (ap2_pair_verify(session) == 0) return 0;
            LOGI("pair-verify failed, clearing auth key");
            session->has_auth_key = 0;
        }
        if (session->password[0]) {
            /* Per OwnTone airplay.c — when ONE_TIME_PAIRING is set,
             * always call /pair-pin-start first to put the receiver in pairing
             * mode before SRP.  This is required even for password-protected
             * receivers: without it the Mac may process the SRP exchange but
             * not activate its encrypted channel. */
            if (session->one_time_pairing) {
                LOGI("one_time_pairing set — calling /pair-pin-start first");
                if (ap2_pair_pin_start(session) != 0) {
                    LOGE("/pair-pin-start failed");
                    return -1;
                }
            }
            char *auth_key = NULL;
            if (ap2_pair_setup(session, session->password, &auth_key) != 0) {
                LOGE("Password pair-setup failed");
                return -1;
            }
            free(auth_key); /* TODO: expose to JNI for persistence */
            /* pair-setup stores identity keys; pair-verify establishes
             * the session cipher (X25519 DH → encryption). */
            if (ap2_pair_verify(session) != 0) {
                LOGE("pair-verify failed after password pair-setup");
                return -1;
            }
            return 0;
        }
        /* No password — return to Kotlin for prompt.
         * macOS receivers set PASSWORD_REQUIRED even with "Everyone on
         * the Same Network" and no password.  Kotlin will send
         * pair-pin-start (triggering Accept dialog on Mac), then
         * pair-setup with whatever credential the user provides. */
        session->pairing_required = 1;
        session->state = AP2_STATE_PAIR;
        LOGI("Password auth required — waiting for user input");
        return -1;
    }

    /* AP2_AUTH_TRANSIENT — default path. */
    if (session->has_auth_key) {
        if (ap2_pair_verify(session) == 0) return 0;
        LOGI("pair-verify failed, falling back to transient");
    }

    if (session->transient_ok) {
        int ret = ap2_pair_transient(session);
        if (ret == 0) return 0;

        /* Transient→normal fallback: if receiver rejected transient with 470,
         * switch to PIN-based auth. Per OwnTone airplay.c. */
        if (session->state == AP2_STATE_PAIR) {
            LOGI("Transient rejected — receiver requires PIN");
            session->auth_mode = AP2_AUTH_PIN;
            return -1;
        }
        LOGI("Transient pairing failed");
    }

    /* Nothing worked — request user input. */
    session->pairing_required = 1;
    session->state = AP2_STATE_PAIR;
    LOGI("Full pairing required — waiting for user input");
    return -1;
}
