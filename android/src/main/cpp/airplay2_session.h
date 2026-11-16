/**
 * AirPlay 2 session — per-session state with no global singletons.
 *
 * Every field is owned by one session. Creating a new session allocates
 * a fresh struct; destroying it frees everything. This is the
 * fundamental fix for the libraop singleton mutex bug.
 */
#ifndef AIRPLAY2_SESSION_H
#define AIRPLAY2_SESSION_H

#include <stdint.h>
#include <stdbool.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Session state machine. */
enum ap2_state {
    AP2_STATE_INIT = 0,
    AP2_STATE_INFO,
    AP2_STATE_AUTH_SETUP,
    AP2_STATE_PAIR,
    AP2_STATE_ENCRYPTED,
    AP2_STATE_SETUP_SESSION,
    AP2_STATE_SETUP_STREAM,
    AP2_STATE_RECORD,
    AP2_STATE_STREAMING,
    AP2_STATE_TEARDOWN,
};

/* Auth mode — determined by statusFlags from GET /info. */
enum ap2_auth_mode {
    AP2_AUTH_TRANSIENT = 0,   /* No security flags → SRP "3939" */
    AP2_AUTH_PIN       = 1,   /* ONE_TIME_PAIRING or PIN_REQUIRED → device displays PIN */
    AP2_AUTH_PASSWORD  = 2,   /* PASSWORD_REQUIRED → user-configured arbitrary string */
};

/* Forward-declare the ALAC codec handle. */
struct alac_codec_s;

/**
 * Per-session struct. No field survives across sessions.
 * Thread safety: callers must hold `mutex` when touching ring buffer
 * or state fields from multiple threads.
 */
typedef struct ap2_session {
    /* ---- Network ---- */
    int rtsp_fd;            /* RTSP TCP socket                */
    int data_fd;            /* Audio UDP socket (realtime)    */
    int control_fd;         /* RTCP UDP socket (control)      */
    int event_fd;           /* Event channel TCP socket       */
    int ptp_event_fd;       /* PTP UDP socket (port 319)      */
    int ptp_general_fd;     /* PTP UDP socket (port 320)      */
    char host[256];
    int rtsp_port;
    int data_port;          /* Receiver's audio data port     */
    int control_port;       /* Receiver's RTCP control port   */
    int event_port;         /* Receiver's event port          */
    int ptp_port;           /* Receiver's PTP port (from ClockPorts) */

    /* ---- State machine ---- */
    enum ap2_state state;
    pthread_mutex_t mutex;  /* Per-session, NOT global        */

    /* ---- Crypto keys (per-session) ---- */
    uint8_t shared_secret[64];
    uint8_t write_key[32];  /* Control channel encrypt key    */
    uint8_t read_key[32];   /* Control channel decrypt key    */
    uint8_t audio_key[32];  /* shk for audio encryption       */
    uint64_t write_nonce;   /* Control channel nonce counter  */
    uint64_t read_nonce;
    uint8_t ed25519_sk[64]; /* Long-term signing key          */
    uint8_t ed25519_pk[32]; /* Long-term public key           */
    uint8_t server_pk[32];  /* Server's Ed25519 public key    */

    /* ---- Session identifiers ---- */
    char session_uuid[37];
    char device_id[18];     /* Receiver MAC                   */
    uint32_t ssrc;          /* RTP SSRC                       */
    uint32_t stream_conn_id; /* streamConnectionID for SETUP   */
    uint32_t session_id;    /* Random RTSP session ID         */
    char session_url[128];  /* rtsp://<host>/<session_id>     */

    /* ---- Audio pipeline (per-session ring buffer) ---- */
    uint8_t *ring;
    size_t ring_size;
    size_t write_pos;       /* Monotonically increasing       */
    size_t read_pos;        /* Monotonically increasing       */
    pthread_mutex_t ring_mutex;
    pthread_t drain_thread;
    volatile int drain_running; /* 1=running, 0=paused, -1=stop */

    /* Set to 1 by the drain thread when the receiver stops responding
     * to /feedback keep-alives (WiFi loss, receiver power-off, etc.).
     * Callers should skip RTSP TEARDOWN and just close sockets. */
    volatile int session_dead;
    int consecutive_feedback_failures;

    /* ---- RTP state ---- */
    uint16_t rtp_seq;
    uint32_t rtp_timestamp;
    uint64_t bytes_sent;

    /* ---- ALAC encoder (per-session) ---- */
    struct alac_codec_s *alac;

    /* ---- PTP state ---- */
    uint64_t ptp_clock_id;  /* Our PTP clock identifier        */
    uint32_t sync_counter;  /* Samples since last sync packet  */
    uint32_t feedback_counter; /* Samples since last /feedback  */
    pthread_t ptp_thread;
    volatile int ptp_running;
    int64_t ptp_offset_ns;  /* Local clock → PTP master offset */

    /* ---- NTP timing state ---- */
    int timing_fd;          /* NTP timing UDP socket           */
    int timing_port;        /* Our NTP timing port             */
    pthread_t timing_thread;
    volatile int timing_running;
    int use_ntp;            /* 1=NTP timing, 0=PTP timing      */

    /* ---- RTSP CSeq ---- */
    int cseq;

    /* ---- Flags ---- */
    int pairing_required;
    int transient_ok;
    int has_auth_key;
    int encrypted;          /* 1 after pair-verify completes  */
    int has_control_keys;   /* 1 when write_key/read_key are derived but encrypted=0 */
    int pin_required;       /* statusFlags bit 3              */
    int one_time_pairing;   /* statusFlags bit 9              */
    int password_required;  /* statusFlags bit 7              */
    int auth_mode;          /* AP2_AUTH_* enum                */

    /* ---- HTTP Digest auth (for password-protected receivers) ---- */
    char realm[128];
    char nonce[256];
    char password[128];
    int has_digest_auth;    /* 1 after 401 response parsed    */

    /* ---- Device identity ---- */
    char device_name[128];  /* Sent as X-Apple-Client-Name    */
    char dacp_id[17];       /* 16-char hex, sent as DACP-ID + Client-Instance */
    uint32_t active_remote; /* Sent as Active-Remote (decimal) */

    /* ---- Audio format constants ---- */
    int sample_rate;
    int sample_size;
    int channels;
    int spf;                /* Samples per frame (352)        */
    float volume;
} ap2_session_t;

/* Ring buffer constants. */
#define AP2_DEFAULT_SPF         352
#define AP2_DEFAULT_SAMPLE_RATE 44100
#define AP2_DEFAULT_SAMPLE_SIZE 16
#define AP2_DEFAULT_CHANNELS    2
#define AP2_CHUNK_BYTES(s) ((s)->spf * (s)->channels * ((s)->sample_size / 8))
#define AP2_DEFAULT_RING_CHUNKS 128

/**
 * Allocate and zero-initialise a session.  All sockets set to -1,
 * mutexes initialised.  Returns NULL on allocation failure.
 */
ap2_session_t *ap2_session_create(const char *host, int port,
                                  int sample_rate, int sample_size,
                                  int channels, float volume);

/**
 * Tear down everything: join threads, close sockets, free ring buffer
 * and ALAC encoder, destroy mutexes.  Safe to call on a partially
 * initialised session.  Sets the pointer to NULL via the double-pointer.
 */
void ap2_session_destroy(ap2_session_t **session);

/**
 * Return a human-readable label for a state enum value.
 */
const char *ap2_state_name(enum ap2_state s);

/**
 * Upcall from native → Kotlin when the drain thread determines the session
 * is dead (e.g. repeated /feedback failures).  Implemented in airplay2_jni.cpp;
 * declared here so it can be called from audio/control .c files.
 * @param reason  1 = feedback timeout (more codes may be added later)
 */
void ap2_notify_session_died(ap2_session_t *s, int reason);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_SESSION_H */
