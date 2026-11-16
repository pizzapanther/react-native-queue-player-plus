/**
 * AirPlay 2 NTP timing service — responds to receiver timing requests.
 *
 * Protocol (per OwnTone airplay.c timing_svc_cb):
 *
 * REQUEST (32 bytes, receiver → us):
 *   [0]     0x80
 *   [1]     0xD2  (PT 210 = timing request)
 *   [2]     sequence number
 *   [3-7]   reserved (zero)
 *   [8-15]  origin timestamp (ignored)
 *   [16-23] receive timestamp (ignored)
 *   [24-31] transmit timestamp (T1 — receiver's send time)
 *
 * RESPONSE (32 bytes, us → receiver):
 *   [0]     0x80
 *   [1]     0xD3  (PT 211 = timing response)
 *   [2]     sequence number (echoed from request)
 *   [3-7]   reserved (zero)
 *   [8-15]  origin timestamp (echoed from request[24:32])
 *   [16-23] receive timestamp (T2 — our receive time, NTP format)
 *   [24-31] transmit timestamp (T3 — our send time, NTP format)
 *
 * NTP timestamp = 32-bit seconds since 1900-01-01 + 32-bit fraction,
 * both big-endian.  We use CLOCK_MONOTONIC + NTP epoch delta.
 */

#include "airplay2_ntp.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define TAG "AP2NTP"
#include "airplay2_filelog.h"

/* NTP epoch delta: seconds between 1900-01-01 and 1970-01-01. */
#define NTP_EPOCH_DELTA 2208988800UL

/* Convert CLOCK_MONOTONIC timespec to NTP timestamp (sec + frac). */
static void timespec_to_ntp(const struct timespec *ts,
                            uint32_t *out_sec, uint32_t *out_frac) {
    *out_sec  = (uint32_t)(ts->tv_sec + NTP_EPOCH_DELTA);
    *out_frac = (uint32_t)((double)ts->tv_nsec * 1e-9 * 4294967296.0);
}

/* ---- Timing thread ---- */

static void *ntp_thread_fn(void *arg) {
    ap2_session_t *s = (ap2_session_t *)arg;
    uint8_t req[32];
    uint8_t res[32];
    uint32_t req_count = 0;

    LOGI("NTP timing thread started on port %d", s->timing_port);

    while (s->timing_running) {
        struct sockaddr_in peer_addr;
        socklen_t peer_len = sizeof(peer_addr);

        ssize_t n = recvfrom(s->timing_fd, req, sizeof(req), 0,
                             (struct sockaddr *)&peer_addr, &peer_len);

        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
            if (!s->timing_running) break;
            LOGE("NTP recvfrom failed: %s", strerror(errno));
            continue;
        }

        /* Capture receive timestamp immediately. */
        struct timespec recv_ts;
        clock_gettime(CLOCK_MONOTONIC, &recv_ts);

        if (n != 32) {
            LOGD("NTP: ignoring %zd-byte packet (expected 32)", n);
            continue;
        }

        if (req[0] != 0x80 || req[1] != 0xD2) {
            LOGD("NTP: ignoring packet with header %02x%02x (expected 80D2)",
                 req[0], req[1]);
            continue;
        }

        /* Build response. */
        memset(res, 0, sizeof(res));
        res[0] = 0x80;
        res[1] = 0xD3;
        res[2] = req[2];  /* Echo sequence number */

        /* Origin timestamp: echo client's transmit time (req[24:32]). */
        memcpy(res + 8, req + 24, 8);

        /* Receive timestamp (T2). */
        uint32_t recv_sec, recv_frac;
        timespec_to_ntp(&recv_ts, &recv_sec, &recv_frac);
        recv_sec  = htonl(recv_sec);
        recv_frac = htonl(recv_frac);
        memcpy(res + 16, &recv_sec, 4);
        memcpy(res + 20, &recv_frac, 4);

        /* Transmit timestamp (T3). */
        struct timespec xmit_ts;
        clock_gettime(CLOCK_MONOTONIC, &xmit_ts);
        uint32_t xmit_sec, xmit_frac;
        timespec_to_ntp(&xmit_ts, &xmit_sec, &xmit_frac);
        xmit_sec  = htonl(xmit_sec);
        xmit_frac = htonl(xmit_frac);
        memcpy(res + 24, &xmit_sec, 4);
        memcpy(res + 28, &xmit_frac, 4);

        /* Send response back to the peer. */
        ssize_t sent = sendto(s->timing_fd, res, sizeof(res), 0,
                              (struct sockaddr *)&peer_addr, peer_len);
        if (sent < 0) {
            LOGE("NTP sendto failed: %s", strerror(errno));
        }

        req_count++;
        if (req_count == 1) {
            LOGI("NTP: first timing request handled (seq=%u)", req[2]);
        }
    }

    LOGI("NTP timing thread stopped (%u requests handled)", req_count);
    return NULL;
}

/* ---- Public API ---- */

int ap2_ntp_start(ap2_session_t *session) {
    if (session->timing_running) {
        LOGI("NTP timing already running");
        return 0;
    }

    /* Create UDP socket. */
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGE("NTP socket() failed: %s", strerror(errno));
        return -1;
    }

    /* Bind to ephemeral port. */
    struct sockaddr_in bind_addr;
    memset(&bind_addr, 0, sizeof(bind_addr));
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_port = 0;
    bind_addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(fd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) != 0) {
        LOGE("NTP bind() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    /* Get the assigned port. */
    socklen_t alen = sizeof(bind_addr);
    getsockname(fd, (struct sockaddr *)&bind_addr, &alen);
    int port = ntohs(bind_addr.sin_port);

    /* Set receive timeout so the thread can check timing_running. */
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    session->timing_fd = fd;
    session->timing_port = port;
    session->timing_running = 1;

    int ret = pthread_create(&session->timing_thread, NULL,
                             ntp_thread_fn, session);
    if (ret != 0) {
        LOGE("NTP pthread_create failed: %s", strerror(ret));
        session->timing_running = 0;
        close(fd);
        session->timing_fd = -1;
        return -1;
    }

    LOGI("NTP timing service started on port %d", port);
    return 0;
}

void ap2_ntp_stop(ap2_session_t *session) {
    if (!session->timing_running) return;

    session->timing_running = 0;
    pthread_join(session->timing_thread, NULL);

    if (session->timing_fd >= 0) {
        close(session->timing_fd);
        session->timing_fd = -1;
    }

    LOGI("NTP timing service stopped");
}
