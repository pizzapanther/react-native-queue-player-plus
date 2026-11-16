/**
 * AirPlay 2 audio pipeline — drain thread + encrypted RTP send.
 *
 * Architecture:
 *   JNI feedPcm → ring buffer → drain thread → ALAC encode →
 *   ChaCha20-Poly1305 encrypt → RTP/UDP send
 *
 * Packet format (per OwnTone airplay.c packet_encrypt):
 *   [RTP header (12 bytes)]
 *   [encrypted ALAC payload]
 *   [16-byte Poly1305 auth tag]
 *   [8-byte nonce (low 8 bytes)]
 *
 * Encryption:
 *   key   = session->audio_key (shk, 32 bytes)
 *   nonce = [4 zero bytes][seqnum as 2-byte native LE][6 zero bytes] (12 bytes)
 *   AAD   = RTP header bytes 4-11 (timestamp + SSRC)
 */

#include "airplay2_audio.h"
#include "airplay2_crypto.h"
#include "airplay2_rtsp.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <arpa/inet.h>

/* ALAC encoder wrapper. */
#include "alac_wrapper.h"

#define TAG "AP2Audio"
#include "airplay2_filelog.h"

/* RTP header constants. */
#define RTP_VERSION  2
#define RTP_PT       96   /* Realtime audio */
#define RTP_HEADER_SIZE 12

/* Pacing: nanoseconds per 352-sample chunk at 44100 Hz. */
#define CHUNK_NS(session) \
    ((int64_t)(session)->spf * 1000000000LL / (session)->sample_rate)

/* RTCP sync packet constants. */
#define RTCP_SYNC_PTP_LEN  28
#define RTCP_SYNC_PTP_PT   0xD7  /* Payload type 215: PTP Time Announce */
#define RTCP_SYNC_NTP_LEN  20
#define RTCP_SYNC_NTP_PT   0xD4  /* Payload type 212: NTP Time Announce */
#define RTCP_SYNC_LATENCY  11025 /* 250ms at 44100 Hz */

/* NTP epoch delta: seconds between 1900-01-01 and 1970-01-01. */
#define NTP_EPOCH_DELTA 2208988800UL

/* Keep-alive: POST /feedback every 20 seconds keeps the receiver connected.
 * OwnTone uses 25s interval — we use 20s for safety margin.
 * Without this, macOS receivers close the data port after ~30 seconds. */
#define FEEDBACK_INTERVAL_SAMPLES (20 * 44100)  /* 20 seconds in samples */

/* ---- RTP header ---- */

static void build_rtp_header(uint8_t hdr[RTP_HEADER_SIZE],
                             uint16_t seq, uint32_t timestamp,
                             uint32_t ssrc) {
    hdr[0] = (RTP_VERSION << 6);   /* V=2, P=0, X=0, CC=0 */
    hdr[1] = RTP_PT;               /* PT=96, marker bit set separately */
    hdr[2] = (uint8_t)(seq >> 8);
    hdr[3] = (uint8_t)(seq);
    hdr[4] = (uint8_t)(timestamp >> 24);
    hdr[5] = (uint8_t)(timestamp >> 16);
    hdr[6] = (uint8_t)(timestamp >>  8);
    hdr[7] = (uint8_t)(timestamp);
    hdr[8] = (uint8_t)(ssrc >> 24);
    hdr[9] = (uint8_t)(ssrc >> 16);
    hdr[10] = (uint8_t)(ssrc >>  8);
    hdr[11] = (uint8_t)(ssrc);
}

/* ---- Encrypt and send one audio packet ---- */

static int send_audio_packet(ap2_session_t *s,
                             const uint8_t *alac_data, int alac_len,
                             int marker) {
    uint8_t rtp_hdr[RTP_HEADER_SIZE];
    build_rtp_header(rtp_hdr, s->rtp_seq, s->rtp_timestamp, s->ssrc);
    if (marker) rtp_hdr[1] |= 0x80; /* Set marker bit (M=1) */

    /* Nonce: [4 zero bytes][2-byte seqnum native LE][6 zero bytes] = 12 bytes.
     * Matches OwnTone: memcpy(nonce + 4, &pkt->seqnum, sizeof(seqnum))
     * where pkt->seqnum is a native uint16_t (little-endian on ARM/x86). */
    uint8_t nonce[12];
    memset(nonce, 0, 12);
    memcpy(nonce + 4, &s->rtp_seq, sizeof(s->rtp_seq));

    /* AAD = RTP header bytes 4-11 (timestamp + SSRC). */
    const uint8_t *aad = rtp_hdr + 4;
    size_t aad_len = 8;

    /* Encrypt ALAC payload. */
    uint8_t *cipher = (uint8_t *)malloc(alac_len);
    uint8_t tag[16];
    if (!cipher) return -1;

    if (ap2_chacha20_encrypt(s->audio_key, nonce,
                              aad, aad_len,
                              alac_data, alac_len,
                              cipher, tag) != 0) {
        free(cipher);
        LOGE("Audio encrypt failed seq=%u", s->rtp_seq);
        return -1;
    }

    /* Diagnostic: dump first packet's encryption parameters. */
    if (marker) {
        LOGI("DIAG pkt0: seq=%u ts=%u ssrc=%u alac_len=%d pkt_len=%zu",
             s->rtp_seq, s->rtp_timestamp, s->ssrc, alac_len,
             (size_t)(RTP_HEADER_SIZE + alac_len + 16 + 8));
        LOGI("DIAG key: %02x%02x%02x%02x %02x%02x%02x%02x",
             s->audio_key[0], s->audio_key[1], s->audio_key[2], s->audio_key[3],
             s->audio_key[4], s->audio_key[5], s->audio_key[6], s->audio_key[7]);
        LOGI("DIAG nonce: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
             nonce[0], nonce[1], nonce[2], nonce[3],
             nonce[4], nonce[5], nonce[6], nonce[7],
             nonce[8], nonce[9], nonce[10], nonce[11]);
        LOGI("DIAG aad: %02x%02x%02x%02x %02x%02x%02x%02x",
             aad[0], aad[1], aad[2], aad[3], aad[4], aad[5], aad[6], aad[7]);
        LOGI("DIAG hdr: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
             rtp_hdr[0], rtp_hdr[1], rtp_hdr[2], rtp_hdr[3],
             rtp_hdr[4], rtp_hdr[5], rtp_hdr[6], rtp_hdr[7],
             rtp_hdr[8], rtp_hdr[9], rtp_hdr[10], rtp_hdr[11]);
        LOGI("DIAG alac[0:4]: %02x%02x%02x%02x tag[0:4]: %02x%02x%02x%02x",
             alac_data[0], alac_data[1], alac_data[2], alac_data[3],
             tag[0], tag[1], tag[2], tag[3]);
    }

    /* Build final packet: [rtp_hdr][cipher][tag][nonce_low8]. */
    size_t pkt_len = RTP_HEADER_SIZE + alac_len + 16 + 8;
    uint8_t *pkt = (uint8_t *)malloc(pkt_len);
    if (!pkt) { free(cipher); return -1; }

    uint8_t *p = pkt;
    memcpy(p, rtp_hdr, RTP_HEADER_SIZE); p += RTP_HEADER_SIZE;
    memcpy(p, cipher, alac_len);         p += alac_len;
    memcpy(p, tag, 16);                  p += 16;
    memcpy(p, nonce + 4, 8);            /* nonce low 8 bytes */

    free(cipher);

    /* UDP send. */
    ssize_t sent = send(s->data_fd, pkt, pkt_len, 0);
    free(pkt);

    if (sent < 0) {
        LOGE("Audio send failed: %s", strerror(errno));
        return -1;
    }

    /* Advance RTP state. */
    s->rtp_seq++;
    s->rtp_timestamp += s->spf;

    return 0;
}

/* ---- RTCP sync packets ---- */

/**
 * Send a PTP sync packet (28 bytes) to the receiver's control port.
 *
 * PTP sync packet:
 *   [0]     0x90 (first) or 0x80 (regular) — V=2, marker bit
 *   [1]     0xD7 — PT 215 (Time Announce)
 *   [2-3]   0x0006
 *   [4-7]   RTP timestamp - latency (big-endian)
 *   [8-15]  wall clock nanoseconds (big-endian)
 *   [16-19] earliest play RTP time (big-endian)
 *   [20-27] PTP clock ID (big-endian)
 */
static int send_sync_ptp(ap2_session_t *s, int is_first) {
    uint8_t pkt[RTCP_SYNC_PTP_LEN];
    memset(pkt, 0, sizeof(pkt));

    pkt[0] = is_first ? 0x90 : 0x80;
    pkt[1] = RTCP_SYNC_PTP_PT;
    pkt[2] = 0x00;
    pkt[3] = 0x06;

    uint32_t cur_pos_be = htonl(s->rtp_timestamp - 88200);
    memcpy(pkt + 4, &cur_pos_be, 4);

    struct timespec wall;
    clock_gettime(CLOCK_MONOTONIC, &wall);
    uint64_t wall_ns = (uint64_t)wall.tv_sec * 1000000000ULL + (uint64_t)wall.tv_nsec;
    uint64_t wall_ns_be;
    uint8_t *wp = (uint8_t *)&wall_ns_be;
    for (int i = 7; i >= 0; i--) {
        wp[7 - i] = (uint8_t)(wall_ns >> (i * 8));
    }
    memcpy(pkt + 8, &wall_ns_be, 8);

    uint32_t rtptime_be = htonl(s->rtp_timestamp - RTCP_SYNC_LATENCY);
    memcpy(pkt + 16, &rtptime_be, 4);

    uint64_t clock_id_be;
    uint8_t *cp = (uint8_t *)&clock_id_be;
    for (int i = 7; i >= 0; i--) {
        cp[7 - i] = (uint8_t)(s->ptp_clock_id >> (i * 8));
    }
    memcpy(pkt + 20, &clock_id_be, 8);

    struct sockaddr_in ctl_addr;
    memset(&ctl_addr, 0, sizeof(ctl_addr));
    ctl_addr.sin_family = AF_INET;
    ctl_addr.sin_port = htons((uint16_t)s->control_port);
    inet_pton(AF_INET, s->host, &ctl_addr.sin_addr);

    ssize_t sent = sendto(s->control_fd, pkt, sizeof(pkt), 0,
                          (struct sockaddr *)&ctl_addr, sizeof(ctl_addr));
    if (sent < 0) {
        LOGE("PTP sync send failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

/**
 * Send an NTP sync packet (20 bytes) to the receiver's control port.
 *
 * NTP sync packet (per OwnTone rtp_common.c sync_packet_ntp_make):
 *   [0]     0x90 (first) or 0x80 (regular) — V=2, marker bit
 *   [1]     0xD4 — PT 212 (NTP Time Announce)
 *   [2-3]   0x0007
 *   [4-7]   RTP timestamp position (big-endian)
 *   [8-11]  NTP seconds (big-endian)
 *   [12-15] NTP fractional seconds (big-endian)
 *   [16-19] RTP start time (big-endian)
 */
static int send_sync_ntp(ap2_session_t *s, int is_first) {
    uint8_t pkt[RTCP_SYNC_NTP_LEN];
    memset(pkt, 0, sizeof(pkt));

    pkt[0] = is_first ? 0x90 : 0x80;
    pkt[1] = RTCP_SYNC_NTP_PT;
    pkt[2] = 0x00;
    pkt[3] = 0x07;

    /* Current RTP position. */
    uint32_t cur_pos_be = htonl(s->rtp_timestamp);
    memcpy(pkt + 4, &cur_pos_be, 4);

    /* NTP timestamp: CLOCK_MONOTONIC + epoch delta. */
    struct timespec wall;
    clock_gettime(CLOCK_MONOTONIC, &wall);
    uint32_t ntp_sec  = htonl((uint32_t)(wall.tv_sec + NTP_EPOCH_DELTA));
    uint32_t ntp_frac = htonl((uint32_t)((double)wall.tv_nsec * 1e-9 * 4294967296.0));
    memcpy(pkt + 8, &ntp_sec, 4);
    memcpy(pkt + 12, &ntp_frac, 4);

    /* RTP start time — must match the value communicated in SETUP. */
    uint32_t rtptime_be = htonl(s->rtp_timestamp - RTCP_SYNC_LATENCY);
    memcpy(pkt + 16, &rtptime_be, 4);

    struct sockaddr_in ctl_addr;
    memset(&ctl_addr, 0, sizeof(ctl_addr));
    ctl_addr.sin_family = AF_INET;
    ctl_addr.sin_port = htons((uint16_t)s->control_port);
    inet_pton(AF_INET, s->host, &ctl_addr.sin_addr);

    ssize_t sent = sendto(s->control_fd, pkt, sizeof(pkt), 0,
                          (struct sockaddr *)&ctl_addr, sizeof(ctl_addr));
    if (sent < 0) {
        LOGE("NTP sync send failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

/**
 * Send the appropriate sync packet based on timing mode.
 */
static int send_sync_packet(ap2_session_t *s, int is_first) {
    if (s->control_fd < 0 || s->control_port == 0) return -1;

    int ret = s->use_ntp ? send_sync_ntp(s, is_first)
                         : send_sync_ptp(s, is_first);

    LOGD("Sync packet sent (%s, %s): rtp=%u",
         s->use_ntp ? "NTP" : "PTP",
         is_first ? "START" : "regular",
         s->rtp_timestamp);
    return ret;
}

/* ---- Drain thread ---- */

static int ring_read_chunk(ap2_session_t *s, uint8_t *buf, size_t chunk_bytes) {
    pthread_mutex_lock(&s->ring_mutex);
    size_t avail = s->write_pos - s->read_pos;
    if (avail < chunk_bytes) {
        pthread_mutex_unlock(&s->ring_mutex);
        return 0; /* Not enough data. */
    }
    size_t idx = s->read_pos % s->ring_size;
    size_t first = s->ring_size - idx;
    if (first > chunk_bytes) first = chunk_bytes;
    memcpy(buf, s->ring + idx, first);
    if (first < chunk_bytes)
        memcpy(buf + first, s->ring, chunk_bytes - first);
    s->read_pos += chunk_bytes;
    pthread_mutex_unlock(&s->ring_mutex);
    return 1; /* Got a chunk. */
}

static void *drain_thread_fn(void *arg) {
    ap2_session_t *s = (ap2_session_t *)arg;
    int chunk_bytes = AP2_CHUNK_BYTES(s);
    uint8_t *pcm_buf = (uint8_t *)malloc(chunk_bytes);
    if (!pcm_buf) {
        LOGE("Drain thread: malloc failed");
        return NULL;
    }
    int first_packet = 1;  /* Set marker bit on first packet */

    LOGI("Drain thread started: chunk=%d bytes, spf=%d", chunk_bytes, s->spf);

    struct timespec next_send;
    clock_gettime(CLOCK_MONOTONIC, &next_send);
    int64_t chunk_ns = CHUNK_NS(s);

    while (s->drain_running >= 0) {
        if (s->drain_running == 0) {
            /* Paused — sleep briefly and retry. */
            usleep(10000); /* 10ms */
            clock_gettime(CLOCK_MONOTONIC, &next_send);
            continue;
        }

        if (!ring_read_chunk(s, pcm_buf, chunk_bytes)) {
            /* Ring empty — sleep briefly. */
            usleep(2000); /* 2ms */
            continue;
        }

        /* ALAC encode. */
        uint8_t *alac_out = NULL;
        int alac_size = 0;
        if (!pcm_to_alac(s->alac, pcm_buf, s->spf, &alac_out, &alac_size)) {
            LOGE("ALAC encode failed");
            continue;
        }

        /* Send initial sync packet before first audio packet. */
        if (first_packet) {
            send_sync_packet(s, 1);
            s->sync_counter = 0;
        }

        /* Encrypt and send. Marker bit on first packet signals stream start. */
        if (send_audio_packet(s, alac_out, alac_size, first_packet) == 0) {
            s->bytes_sent += chunk_bytes;
            if (first_packet) {
                LOGI("First audio packet sent: seq=%u ts=%u size=%d",
                     (unsigned)(s->rtp_seq - 1), s->rtp_timestamp - s->spf, alac_size);
                first_packet = 0;
            }

            /* Periodic sync every ~1 second. */
            s->sync_counter += s->spf;
            if (s->sync_counter >= (uint32_t)s->sample_rate) {
                send_sync_packet(s, 0);
                s->sync_counter = 0;
            }

            /* Keep-alive: POST /feedback every ~20 seconds.
             * Uses a 3s receive timeout so a dead receiver is detected quickly.
             * Two consecutive failures mark the session dead and upcall Kotlin. */
            s->feedback_counter += s->spf;
            if (s->feedback_counter >= FEEDBACK_INTERVAL_SAMPLES) {
                ap2_rtsp_response_t fb_resp;
                if (ap2_rtsp_request_tmo(s, "POST", "/feedback",
                                         NULL, NULL, 0, 3, &fb_resp) == 0) {
                    LOGD("Feedback keep-alive sent: status=%d", fb_resp.status);
                    ap2_rtsp_response_free(&fb_resp);
                    s->consecutive_feedback_failures = 0;
                } else {
                    s->consecutive_feedback_failures++;
                    LOGE("Feedback keep-alive failed (consecutive=%d)",
                         s->consecutive_feedback_failures);
                    if (s->consecutive_feedback_failures >= 2) {
                        LOGE("Session declared dead — upcalling Kotlin");
                        s->session_dead = 1;
                        ap2_notify_session_died(s, 1 /* FEEDBACK_TIMEOUT */);
                        s->drain_running = -1;
                        free(alac_out);
                        break;
                    }
                }
                s->feedback_counter = 0;
            }
        }
        free(alac_out);

        /* Pace: wait until next_send time. */
        next_send.tv_nsec += chunk_ns;
        while (next_send.tv_nsec >= 1000000000L) {
            next_send.tv_nsec -= 1000000000L;
            next_send.tv_sec++;
        }

        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t delta_ns = (next_send.tv_sec - now.tv_sec) * 1000000000LL
                         + (next_send.tv_nsec - now.tv_nsec);
        if (delta_ns > 0) {
            struct timespec ts;
            ts.tv_sec = delta_ns / 1000000000LL;
            ts.tv_nsec = delta_ns % 1000000000LL;
            nanosleep(&ts, NULL);
        } else if (delta_ns < -chunk_ns * 5) {
            /* Fallen too far behind — reset timing. */
            clock_gettime(CLOCK_MONOTONIC, &next_send);
        }
    }

    free(pcm_buf);
    LOGI("Drain thread stopped, bytes_sent=%llu", (unsigned long long)s->bytes_sent);
    return NULL;
}

/* ---- Public API ---- */

int ap2_audio_init(ap2_session_t *session) {
    if (session->alac) return 0; /* Already initialised. */

    session->alac = alac_create_encoder(
        session->spf,
        session->sample_rate,
        session->sample_size,
        session->channels);

    if (!session->alac) {
        LOGE("ALAC encoder creation failed");
        return -1;
    }

    LOGI("ALAC encoder created: spf=%d sr=%d ss=%d ch=%d",
         session->spf, session->sample_rate,
         session->sample_size, session->channels);
    return 0;
}

int ap2_audio_start(ap2_session_t *session) {
    if (session->drain_running > 0) return 0; /* Already running. */

    if (!session->alac) {
        if (ap2_audio_init(session) != 0) return -1;
    }

    /* Reset ring buffer positions. */
    pthread_mutex_lock(&session->ring_mutex);
    session->write_pos = 0;
    session->read_pos = 0;
    pthread_mutex_unlock(&session->ring_mutex);

    /* Reset counters — keep rtp_seq/rtp_timestamp from session_create
     * (random starting values, required for correct sync timing). */
    session->bytes_sent = 0;
    session->sync_counter = 0;
    session->feedback_counter = 0;

    session->drain_running = 1;
    if (pthread_create(&session->drain_thread, NULL,
                       drain_thread_fn, session) != 0) {
        LOGE("Failed to create drain thread: %s", strerror(errno));
        session->drain_running = 0;
        return -1;
    }

    LOGI("Audio started");
    return 0;
}

void ap2_audio_pause(ap2_session_t *session) {
    session->drain_running = 0;
    LOGD("Audio paused");
}

void ap2_audio_resume(ap2_session_t *session) {
    if (session->drain_running == 0) {
        session->drain_running = 1;
        LOGD("Audio resumed");
    }
}

void ap2_audio_stop(ap2_session_t *session) {
    if (session->drain_running > 0 || session->drain_running == 0) {
        session->drain_running = -1;
        pthread_join(session->drain_thread, NULL);
        LOGI("Drain thread joined");
    }

    if (session->alac) {
        alac_delete_encoder(session->alac);
        session->alac = NULL;
    }
}

int ap2_audio_write(ap2_session_t *session, const uint8_t *pcm, size_t len) {
    if (!session->ring) return 0;

    pthread_mutex_lock(&session->ring_mutex);
    size_t avail = session->ring_size - (session->write_pos - session->read_pos);
    size_t to_write = len < avail ? len : avail;
    if (to_write > 0) {
        size_t idx = session->write_pos % session->ring_size;
        size_t first = session->ring_size - idx;
        if (first > to_write) first = to_write;
        memcpy(session->ring + idx, pcm, first);
        if (first < to_write)
            memcpy(session->ring, pcm + first, to_write - first);
        session->write_pos += to_write;
    }
    pthread_mutex_unlock(&session->ring_mutex);

    return (int)to_write;
}

size_t ap2_audio_ring_available(ap2_session_t *session) {
    if (!session->ring) return 0;
    pthread_mutex_lock(&session->ring_mutex);
    size_t avail = session->write_pos - session->read_pos;
    pthread_mutex_unlock(&session->ring_mutex);
    return avail;
}
