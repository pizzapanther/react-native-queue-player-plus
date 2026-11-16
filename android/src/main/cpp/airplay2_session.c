/**
 * AirPlay 2 session lifecycle — create / destroy.
 */

#include "airplay2_session.h"
#include "airplay2_filelog.h"

/* File-logging globals — single definition, declared extern in airplay2_filelog.h. */
FILE *g_ap2_logfile = NULL;
pthread_mutex_t g_ap2_logfile_mutex = PTHREAD_MUTEX_INITIALIZER;

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sodium.h>

/* Generate a random UUID v4. */
static void make_uuid(char out[37]) {
    uint8_t b[16];
    /* Simple PRNG seed — good enough for session IDs. */
    for (int i = 0; i < 16; i++) b[i] = (uint8_t)(rand() & 0xff);
    b[6] = (b[6] & 0x0f) | 0x40;  /* version 4 */
    b[8] = (b[8] & 0x3f) | 0x80;  /* variant 1 */
    snprintf(out, 37,
        "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        b[0],b[1],b[2],b[3], b[4],b[5], b[6],b[7],
        b[8],b[9], b[10],b[11],b[12],b[13],b[14],b[15]);
}

ap2_session_t *ap2_session_create(const char *host, int port,
                                  int sample_rate, int sample_size,
                                  int channels, float volume) {
    /* Idempotent — safe to call multiple times.  Must happen before any
     * libsodium crypto (Ed25519, X25519, randombytes_buf). */
    if (sodium_init() < -1) return NULL;

    ap2_session_t *s = (ap2_session_t *)calloc(1, sizeof(*s));
    if (!s) return NULL;

    /* Seed PRNG once per session — ensures random RTP fields, UUIDs, etc.
     * XOR with pointer address for extra entropy between sessions. */
    srand((unsigned)(time(NULL) ^ (uintptr_t)s));

    /* Network — all sockets invalid until connect. */
    s->rtsp_fd       = -1;
    s->data_fd       = -1;
    s->control_fd    = -1;
    s->event_fd      = -1;
    s->ptp_event_fd  = -1;
    s->ptp_general_fd = -1;
    s->timing_fd     = -1;

    if (host) {
        strncpy(s->host, host, sizeof(s->host) - 1);
    }
    s->rtsp_port = port;

    /* State machine. */
    s->state = AP2_STATE_INIT;
    pthread_mutex_init(&s->mutex, NULL);

    /* Audio format. */
    s->sample_rate = sample_rate > 0 ? sample_rate : AP2_DEFAULT_SAMPLE_RATE;
    s->sample_size = sample_size > 0 ? sample_size : AP2_DEFAULT_SAMPLE_SIZE;
    s->channels    = channels > 0    ? channels    : AP2_DEFAULT_CHANNELS;
    s->spf         = AP2_DEFAULT_SPF;
    s->volume      = volume;

    /* Ring buffer. */
    int chunk_bytes = AP2_CHUNK_BYTES(s);
    s->ring_size = chunk_bytes * AP2_DEFAULT_RING_CHUNKS;
    s->ring = (uint8_t *)calloc(1, s->ring_size);
    if (!s->ring) {
        pthread_mutex_destroy(&s->mutex);
        free(s);
        return NULL;
    }
    pthread_mutex_init(&s->ring_mutex, NULL);

    /* DACP-ID / Client-Instance — 16-char uppercase hex.
     * Active-Remote — 32-bit decimal. Per OwnTone airplay.c. */
    {
        uint8_t id_bytes[8];
        randombytes_buf(id_bytes, sizeof(id_bytes));
        for (int i = 0; i < 8; i++)
            snprintf(s->dacp_id + i * 2, 3, "%02X", id_bytes[i]);
        uint32_t ar;
        randombytes_buf(&ar, sizeof(ar));
        s->active_remote = ar ? ar : 1;
    }

    /* Session UUID. */
    make_uuid(s->session_uuid);

    /* Random SSRC. */
    s->ssrc = (uint32_t)rand();

    /* Random initial RTP timestamp and sequence number (per OwnTone).
     * Avoids wrap-around in the first RTCP sync packet. */
    s->rtp_timestamp = (uint32_t)rand();
    s->rtp_seq = (uint16_t)(rand() & 0xFFFF);

    /* PTP clock ID — random 64-bit identifier for timing sync. */
    s->ptp_clock_id = ((uint64_t)rand() << 32) | (uint64_t)rand();

    /* Random RTSP session ID — per OwnTone airplay.c.
     * Used in the RTSP URL: rtsp://<host>/<session_id>. */
    s->session_id = (uint32_t)rand();
    snprintf(s->session_url, sizeof(s->session_url),
             "rtsp://%s/%u", s->host, s->session_id);

    /* Default to NTP timing — works on Android without privileged ports.
     * PTP requires binding to 319/320 which Android blocks. */
    s->use_ntp = 1;

    return s;
}

static void close_fd(int *fd) {
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

void ap2_session_destroy(ap2_session_t **pp) {
    if (!pp || !*pp) return;
    ap2_session_t *s = *pp;

    /* Stop drain thread if running. */
    if (s->drain_running > 0) {
        s->drain_running = -1;
        pthread_join(s->drain_thread, NULL);
    }

    /* Stop PTP thread if running. */
    if (s->ptp_running) {
        s->ptp_running = 0;
        pthread_join(s->ptp_thread, NULL);
    }

    /* Stop NTP timing thread if running. */
    if (s->timing_running) {
        s->timing_running = 0;
        pthread_join(s->timing_thread, NULL);
    }

    /* Close all sockets. */
    close_fd(&s->rtsp_fd);
    close_fd(&s->data_fd);
    close_fd(&s->control_fd);
    close_fd(&s->event_fd);
    /* PTP may share one socket (ClockPorts mode). Avoid double-close. */
    if (s->ptp_general_fd == s->ptp_event_fd)
        s->ptp_general_fd = -1;
    close_fd(&s->ptp_event_fd);
    close_fd(&s->ptp_general_fd);
    close_fd(&s->timing_fd);

    /* Free ring buffer. */
    free(s->ring);
    s->ring = NULL;

    /* Destroy mutexes. */
    pthread_mutex_destroy(&s->ring_mutex);
    pthread_mutex_destroy(&s->mutex);

    /* Zero sensitive key material. */
    memset(s->shared_secret, 0, sizeof(s->shared_secret));
    memset(s->write_key, 0, sizeof(s->write_key));
    memset(s->read_key, 0, sizeof(s->read_key));
    memset(s->audio_key, 0, sizeof(s->audio_key));
    memset(s->ed25519_sk, 0, sizeof(s->ed25519_sk));
    memset(s->password, 0, sizeof(s->password));

    free(s);
    *pp = NULL;
}

const char *ap2_state_name(enum ap2_state st) {
    switch (st) {
        case AP2_STATE_INIT:          return "INIT";
        case AP2_STATE_INFO:          return "INFO";
        case AP2_STATE_AUTH_SETUP:    return "AUTH_SETUP";
        case AP2_STATE_PAIR:          return "PAIR";
        case AP2_STATE_ENCRYPTED:     return "ENCRYPTED";
        case AP2_STATE_SETUP_SESSION: return "SETUP_SESSION";
        case AP2_STATE_SETUP_STREAM:  return "SETUP_STREAM";
        case AP2_STATE_RECORD:        return "RECORD";
        case AP2_STATE_STREAMING:     return "STREAMING";
        case AP2_STATE_TEARDOWN:      return "TEARDOWN";
        default:                      return "UNKNOWN";
    }
}
