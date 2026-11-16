/**
 * Minimal binary plist encoder/decoder for AirPlay 2.
 *
 * NOT a general-purpose plist library.  Handles only the specific
 * dictionary/array structures that AirPlay 2 RTSP uses:
 *   - SETUP session request/response
 *   - SETUP stream request/response
 *   - SETPEERS request
 *   - SETRATEANCHORTIME request
 *   - GET /info response
 *
 * Binary plist format: Apple's "bplist00" with offset table at the end.
 * We support: bool, int, real, data, string, dict, array.
 */
#ifndef AIRPLAY2_PLIST_H
#define AIRPLAY2_PLIST_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Parsed plist value (tagged union) ---- */

enum ap2_plist_type {
    AP2_PLIST_BOOL,
    AP2_PLIST_INT,
    AP2_PLIST_REAL,
    AP2_PLIST_STRING,
    AP2_PLIST_DATA,
    AP2_PLIST_DICT,
    AP2_PLIST_ARRAY,
};

typedef struct ap2_plist_value ap2_plist_value_t;

typedef struct ap2_plist_dict_entry {
    char *key;
    ap2_plist_value_t *value;
    struct ap2_plist_dict_entry *next;
} ap2_plist_dict_entry_t;

typedef struct ap2_plist_array_entry {
    ap2_plist_value_t *value;
    struct ap2_plist_array_entry *next;
} ap2_plist_array_entry_t;

struct ap2_plist_value {
    enum ap2_plist_type type;
    union {
        int bool_val;
        int64_t int_val;
        double real_val;
        char *string_val;
        struct { uint8_t *bytes; size_t length; } data_val;
        ap2_plist_dict_entry_t *dict_val;
        ap2_plist_array_entry_t *array_val;
    };
};

/* ---- Decode ---- */

/**
 * Parse a binary plist buffer into a value tree.
 * @return Root value, or NULL on parse failure.  Caller must call
 *         ap2_plist_free().
 */
ap2_plist_value_t *ap2_plist_parse(const uint8_t *data, size_t len);

/**
 * Free a parsed plist value tree.
 */
void ap2_plist_free(ap2_plist_value_t *val);

/* ---- Lookup helpers ---- */

/** Get a string value from a dict by key.  Returns NULL if missing/wrong type. */
const char *ap2_plist_dict_get_string(const ap2_plist_value_t *dict,
                                     const char *key);

/** Get an integer value from a dict by key.  Returns dflt if missing/wrong type. */
int64_t ap2_plist_dict_get_int(const ap2_plist_value_t *dict,
                               const char *key, int64_t dflt);

/** Get a data value from a dict by key.  Returns NULL/0 if missing. */
const uint8_t *ap2_plist_dict_get_data(const ap2_plist_value_t *dict,
                                       const char *key, size_t *out_len);

/** Get a nested dict from a dict by key. */
const ap2_plist_value_t *ap2_plist_dict_get_dict(const ap2_plist_value_t *dict,
                                                  const char *key);

/** Get the first array entry from a dict by key. */
const ap2_plist_value_t *ap2_plist_dict_get_array(const ap2_plist_value_t *dict,
                                                   const char *key);

/* ---- Encode (purpose-built for AirPlay 2 structures) ---- */

/**
 * Build SETUP session request plist.
 * @param device_id     Our device ID (MAC string)
 * @param session_uuid  Session UUID
 * @param timing        Timing protocol ("PTP" or "NTP")
 * @param out_buf       OUT: heap-allocated bplist00 bytes
 * @param out_len       OUT: output length
 * @return 0 on success
 */
int ap2_plist_build_setup_session(const char *device_id,
                                  const char *session_uuid,
                                  const char *timing,
                                  const char *name,
                                  const char *local_ip,
                                  uint64_t ptp_clock_id,
                                  uint8_t **out_buf, size_t *out_len);

/**
 * Build SETUP session request plist for NTP timing mode.
 * Simpler than PTP: no timingPeerInfo/timingPeerList needed.
 * @param device_id     Our device ID (MAC string)
 * @param session_uuid  Session UUID
 * @param name          Device name (may be NULL)
 * @param timing_port   Our NTP timing UDP port
 * @param out_buf       OUT: heap-allocated bplist00 bytes
 * @param out_len       OUT: output length
 * @return 0 on success
 */
int ap2_plist_build_setup_session_ntp(const char *device_id,
                                      const char *session_uuid,
                                      const char *name,
                                      int timing_port,
                                      uint8_t **out_buf, size_t *out_len);

/**
 * Build SETUP stream request plist.
 * @param audio_format  Audio format code (0x40000 for ALAC/44100/16/2)
 * @param ct            Compression type (2 = ALAC)
 * @param sr            Sample rate (44100)
 * @param spf           Samples per frame (352)
 * @param shk           Audio encryption key (32 bytes)
 * @param shk_len       Key length (32)
 * @param control_port  Our control port
 * @param out_buf       OUT: heap-allocated bplist00 bytes
 * @param out_len       OUT: output length
 * @return 0 on success
 */
int ap2_plist_build_setup_stream(int64_t audio_format, int ct, int sr,
                                 int spf, const uint8_t *shk, size_t shk_len,
                                 int control_port, uint32_t stream_conn_id,
                                 uint8_t **out_buf, size_t *out_len);

/**
 * Build SETPEERS request plist.
 * @param addresses     Array of IP address strings
 * @param count         Number of addresses
 * @param out_buf       OUT: heap-allocated bplist00 bytes
 * @param out_len       OUT: output length
 * @return 0 on success
 */
int ap2_plist_build_setpeers(const char **addresses, int count,
                             uint8_t **out_buf, size_t *out_len);

/**
 * Build SETRATEANCHORTIME request plist.
 * @param rate           Playback rate (1.0 = play, 0.0 = pause)
 * @param rtp_time       RTP timestamp
 * @param net_secs       Network time seconds (PTP)
 * @param net_frac       Network time fraction
 * @param timeline_id    Timeline ID
 * @param out_buf        OUT: heap-allocated bplist00 bytes
 * @param out_len        OUT: output length
 * @return 0 on success
 */
int ap2_plist_build_setrateanchortime(double rate, uint32_t rtp_time,
                                      uint64_t net_secs, uint64_t net_frac,
                                      int timeline_id,
                                      uint8_t **out_buf, size_t *out_len);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_PLIST_H */
