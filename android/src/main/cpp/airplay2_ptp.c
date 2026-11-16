/**
 * AirPlay 2 PTP master clock — minimal IEEE 1588v2 unicast sender.
 *
 * Implements the minimum PTP messages required for an AirPlay 2 receiver
 * to accept and play audio:
 *   - ANNOUNCE  every 1000 ms  → receiver port 320 (general)
 *   - SYNC      every  125 ms  → receiver port 319 (event)
 *   - FOLLOW_UP right after    → receiver port 320 (general)
 *   - SIGNALING every 1000 ms  → receiver port 320 (general)
 *
 * Packet formats match OwnTone's libairptp exactly.  All fields are
 * big-endian (network order) per IEEE 1588.
 */

#include "airplay2_ptp.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
/* htobe64: Android/Linux has <endian.h>, macOS has <libkern/OSByteOrder.h>. */
#ifdef __ANDROID__
#include <endian.h>
#elif defined(__APPLE__)
#include <libkern/OSByteOrder.h>
#define htobe64(x) OSSwapHostToBigInt64(x)
#else
#include <endian.h>
#endif
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define TAG "AP2PTP"
#include "airplay2_filelog.h"

/* ---- IEEE 1588v2 constants ---- */

/* Message types (lower nibble). */
#define PTP_MSG_SYNC       0x00
#define PTP_MSG_FOLLOW_UP  0x08
#define PTP_MSG_DELAY_RESP 0x09
#define PTP_MSG_ANNOUNCE   0x0B
#define PTP_MSG_SIGNALING  0x0C

/* Flags. */
#define PTP_FLAG_TWO_STEP  0x0200
#define PTP_FLAG_UNICAST   0x0400
#define PTP_FLAG_TIMESCALE 0x0008

/* Ports. */
#define PTP_EVENT_PORT   319
#define PTP_GENERAL_PORT 320

/* Timer intervals. */
#define SYNC_INTERVAL_MS     125   /* 8 Hz  (logMessageInterval = -3) */
#define ANNOUNCE_INTERVAL_MS 1000  /* 1 Hz  (logMessageInterval =  0) */

/* Log message interval values (signed int8). */
#define LOG_INTERVAL_SYNC      (-3)
#define LOG_INTERVAL_ANNOUNCE  0
#define LOG_INTERVAL_SIGNALING (-128)  /* Apple-specific */

/* TLV types. */
#define TLV_ORG_EXTENSION 0x0003
#define TLV_PATH_TRACE    0x0008

/* Organization codes. */
static const uint8_t ORG_IEEE[3]  = { 0x00, 0x80, 0xC2 };
static const uint8_t ORG_APPLE[3] = { 0x00, 0x0D, 0x93 };

/* Apple subtype codes. */
static const uint8_t SUB_FOLLOW_UP_INFO[3] = { 0x00, 0x00, 0x01 };
static const uint8_t SUB_APPLE_UNKNOWN1[3] = { 0x00, 0x00, 0x01 };
static const uint8_t SUB_APPLE_CLOCK_ID[3] = { 0x00, 0x00, 0x04 };
static const uint8_t SUB_APPLE_UNKNOWN5[3] = { 0x00, 0x00, 0x05 };

/* Fixed Apple data in signaling TLVs. */
static const uint8_t APPLE_UNKNOWN_DATA[4] = { 0x00, 0x00, 0x03, 0x01 };

/* Source port identity: clock_id (8) + port (2) = fixed 0x8005. */
#define PORT_ID_PORT_HI  0x80
#define PORT_ID_PORT_LO  0x05

/* ---- PTP header (34 bytes) ---- */

#pragma pack(push, 1)

struct ptp_header {
    uint8_t  msg_type;            /* transportSpecific(4) | messageType(4) */
    uint8_t  version;             /* reserved(4) | versionPTP(4) */
    uint16_t msg_length;          /* Big-endian */
    uint8_t  domain;
    uint8_t  reserved1;
    uint16_t flags;               /* Big-endian */
    uint8_t  correction[8];       /* Big-endian int64 */
    uint8_t  reserved2[4];
    uint8_t  src_port_id[10];     /* 8-byte clock ID + 2-byte port */
    uint16_t seq_id;              /* Big-endian */
    uint8_t  control;
    int8_t   log_msg_interval;
};

/* 10-byte PTP timestamp. */
struct ptp_timestamp {
    uint16_t seconds_hi;          /* Big-endian upper 16 bits */
    uint32_t seconds_lo;          /* Big-endian lower 32 bits */
    uint32_t nanoseconds;         /* Big-endian */
};

/* ---- Message structures (packed) ---- */

/* ANNOUNCE: 34 + 10 + 6 + 8 + 2 + 1 + 12 = 78 bytes (with PATH_TRACE TLV). */
struct ptp_announce {
    struct ptp_header hdr;        /* 34 */
    struct ptp_timestamp origin;  /* 10 */
    uint16_t utc_offset;          /*  2 */
    uint8_t  reserved;            /*  1 */
    uint8_t  gm_priority1;       /*  1 */
    uint32_t gm_clock_quality;    /*  4 */
    uint8_t  gm_priority2;       /*  1 */
    uint8_t  gm_identity[8];     /*  8 */
    uint16_t steps_removed;       /*  2 */
    uint8_t  time_source;         /*  1 */
    /* PATH_TRACE TLV: type(2) + len(2) + clock_id(8) = 12 */
    uint8_t  tlv_path[12];
};

/* SYNC: 34 + 10 = 44 bytes. */
struct ptp_sync {
    struct ptp_header hdr;
    struct ptp_timestamp origin;
};

/* FOLLOW_UP: 34 + 10 + 32 + 20 = 98 bytes (with two TLVs). */
struct ptp_follow_up {
    struct ptp_header hdr;
    struct ptp_timestamp precise_origin;
    uint8_t  tlv_ieee[32];       /* IEEE Follow-Up Info */
    uint8_t  tlv_apple[20];      /* Apple Clock ID */
};

/* SIGNALING: 34 + 10 + 26 + 36 = 106 bytes. */
struct ptp_signaling {
    struct ptp_header hdr;
    uint8_t  target_port_id[10];
    uint8_t  tlv1[26];           /* Apple Unknown1 */
    uint8_t  tlv2[36];           /* Apple Unknown5 */
};

#pragma pack(pop)

/* ---- Helper: write PTP header ---- */

static void header_init(struct ptp_header *h, uint8_t type, uint16_t total_len,
                        uint64_t clock_id, uint16_t seq, int8_t log_interval,
                        uint16_t flags) {
    memset(h, 0, sizeof(*h));

    /* transportSpecific = 1 (upper nibble) | messageType (lower nibble). */
    h->msg_type = 0x10 | (type & 0x0F);
    h->version  = 0x02;  /* PTPv2 */
    h->msg_length = htons(total_len);
    h->domain   = 0;     /* AirPlay domain */
    h->flags    = htons(flags);

    /* sourcePortIdentity: 8-byte BE clock_id + 2-byte port 0x8005. */
    uint64_t be_clk = htobe64(clock_id);
    memcpy(h->src_port_id, &be_clk, 8);
    h->src_port_id[8] = PORT_ID_PORT_HI;
    h->src_port_id[9] = PORT_ID_PORT_LO;

    h->seq_id = htons(seq);
    h->log_msg_interval = log_interval;
}

/* ---- Helper: write a TLV ---- */

static void tlv_write(uint8_t *dst, uint16_t type, uint16_t data_len,
                      const void *data) {
    uint16_t be;
    be = htons(type);     memcpy(dst, &be, 2);
    be = htons(data_len); memcpy(dst + 2, &be, 2);
    if (data_len > 0 && data) memcpy(dst + 4, data, data_len);
}

/* ---- Helper: get current time as PTP timestamp ---- */

static struct ptp_timestamp now_ptp(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    struct ptp_timestamp p;
    uint64_t sec = (uint64_t)ts.tv_sec;
    p.seconds_hi  = htons((uint16_t)(sec >> 32));
    p.seconds_lo  = htonl((uint32_t)sec);
    p.nanoseconds = htonl((uint32_t)ts.tv_nsec);
    return p;
}

static struct ptp_timestamp zero_ts(void) {
    struct ptp_timestamp p = {0, 0, 0};
    return p;
}

/* ---- Message builders ---- */

static void build_announce(struct ptp_announce *msg, uint64_t clock_id,
                           uint16_t seq) {
    memset(msg, 0, sizeof(*msg));

    header_init(&msg->hdr, PTP_MSG_ANNOUNCE, sizeof(*msg),
                clock_id, seq, LOG_INTERVAL_ANNOUNCE,
                PTP_FLAG_UNICAST | PTP_FLAG_TIMESCALE);

    msg->origin = zero_ts();
    msg->utc_offset = 0;
    msg->gm_priority1 = 128;
    /* Clock quality: class=6(GPS), accuracy=0x21(100ns), variance=0x436A. */
    msg->gm_clock_quality = htonl(0x0621436A);
    msg->gm_priority2 = 128;

    uint64_t be_clk = htobe64(clock_id);
    memcpy(msg->gm_identity, &be_clk, 8);

    msg->steps_removed = 0;
    msg->time_source = 0x20; /* GPS */

    /* PATH_TRACE TLV: our clock ID. */
    tlv_write(msg->tlv_path, TLV_PATH_TRACE, 8, &be_clk);
}

static void build_sync(struct ptp_sync *msg, uint64_t clock_id, uint16_t seq) {
    memset(msg, 0, sizeof(*msg));

    header_init(&msg->hdr, PTP_MSG_SYNC, sizeof(*msg),
                clock_id, seq, LOG_INTERVAL_SYNC,
                PTP_FLAG_UNICAST | PTP_FLAG_TIMESCALE | PTP_FLAG_TWO_STEP);

    /* Two-step: origin timestamp is zero; precise time goes in Follow-Up. */
    msg->origin = zero_ts();
}

static void build_follow_up(struct ptp_follow_up *msg, uint64_t clock_id,
                             uint16_t seq, struct ptp_timestamp ts) {
    memset(msg, 0, sizeof(*msg));

    header_init(&msg->hdr, PTP_MSG_FOLLOW_UP, sizeof(*msg),
                clock_id, seq, LOG_INTERVAL_SYNC,
                PTP_FLAG_UNICAST | PTP_FLAG_TIMESCALE);

    msg->precise_origin = ts;

    /* TLV 1: IEEE 802.1 Follow-Up Information (28 bytes payload). */
    uint8_t ieee_data[28];
    memset(ieee_data, 0, sizeof(ieee_data));
    memcpy(ieee_data,     ORG_IEEE, 3);
    memcpy(ieee_data + 3, SUB_FOLLOW_UP_INFO, 3);
    /* Remaining 22 bytes are zero (cumulativeScaledRateOffset etc). */
    tlv_write(msg->tlv_ieee, TLV_ORG_EXTENSION, 28, ieee_data);

    /* TLV 2: Apple Clock ID (16 bytes payload). */
    uint8_t apple_data[16];
    memset(apple_data, 0, sizeof(apple_data));
    memcpy(apple_data,     ORG_APPLE, 3);
    memcpy(apple_data + 3, SUB_APPLE_CLOCK_ID, 3);
    uint64_t be_clk = htobe64(clock_id);
    memcpy(apple_data + 6, &be_clk, 8);
    /* Bytes 14-15 are zero padding. */
    tlv_write(msg->tlv_apple, TLV_ORG_EXTENSION, 16, apple_data);
}

static void build_signaling(struct ptp_signaling *msg, uint64_t clock_id,
                             uint16_t seq) {
    memset(msg, 0, sizeof(*msg));

    header_init(&msg->hdr, PTP_MSG_SIGNALING, sizeof(*msg),
                clock_id, seq, LOG_INTERVAL_SIGNALING,
                PTP_FLAG_UNICAST | PTP_FLAG_TIMESCALE);

    msg->hdr.control = 0x05; /* "Other Message" */

    /* targetPortIdentity: all zeros (broadcast-like). */
    memset(msg->target_port_id, 0, 10);

    /* TLV 1: Apple Unknown1 (22 bytes payload). */
    uint8_t data1[22];
    memset(data1, 0, sizeof(data1));
    memcpy(data1,     ORG_APPLE, 3);
    memcpy(data1 + 3, SUB_APPLE_UNKNOWN1, 3);
    memcpy(data1 + 6, APPLE_UNKNOWN_DATA, 4);
    tlv_write(msg->tlv1, TLV_ORG_EXTENSION, 22, data1);

    /* TLV 2: Apple Unknown5 (32 bytes payload). */
    uint8_t data2[32];
    memset(data2, 0, sizeof(data2));
    memcpy(data2,     ORG_APPLE, 3);
    memcpy(data2 + 3, SUB_APPLE_UNKNOWN5, 3);
    memcpy(data2 + 6, APPLE_UNKNOWN_DATA, 4);
    tlv_write(msg->tlv2, TLV_ORG_EXTENSION, 32, data2);
}

/* ---- PTP thread ---- */

static void *ptp_thread_fn(void *arg) {
    ap2_session_t *s = (ap2_session_t *)arg;

    /* Resolve receiver address.  If the receiver returned a ClockPorts
     * port in the SETUP response, send ALL PTP to that single port.
     * Otherwise fall back to standard 319 (event) / 320 (general). */
    int event_port   = s->ptp_port > 0 ? s->ptp_port : PTP_EVENT_PORT;
    int general_port = s->ptp_port > 0 ? s->ptp_port : PTP_GENERAL_PORT;

    struct sockaddr_in event_addr, general_addr;
    memset(&event_addr, 0, sizeof(event_addr));
    event_addr.sin_family = AF_INET;
    event_addr.sin_port = htons((uint16_t)event_port);
    inet_pton(AF_INET, s->host, &event_addr.sin_addr);

    memset(&general_addr, 0, sizeof(general_addr));
    general_addr.sin_family = AF_INET;
    general_addr.sin_port = htons((uint16_t)general_port);
    inet_pton(AF_INET, s->host, &general_addr.sin_addr);

    uint16_t sync_seq = 0;
    uint16_t announce_seq = 0;
    uint16_t signaling_seq = 0;

    /* Counters: sync every 125ms, announce/signaling every 1000ms. */
    int loop_count = 0;         /* Each loop is ~125ms; 8 loops = 1000ms */

    LOGI("PTP thread started: clock_id=0x%016llx target=%s",
         (unsigned long long)s->ptp_clock_id, s->host);

    while (s->ptp_running) {
        /* ---- SYNC + FOLLOW_UP (every 125ms) ---- */
        struct ptp_sync sync_msg;
        build_sync(&sync_msg, s->ptp_clock_id, sync_seq);

        /* Capture precise timestamp right before sending. */
        struct ptp_timestamp precise_ts = now_ptp();

        ssize_t n = sendto(s->ptp_event_fd, &sync_msg, sizeof(sync_msg), 0,
                           (struct sockaddr *)&event_addr, sizeof(event_addr));
        if (n < 0 && errno != EAGAIN) {
            LOGE("PTP SYNC sendto failed: %s", strerror(errno));
        }

        /* Brief delay (~100µs) then Follow-Up with precise timestamp. */
        usleep(100);

        struct ptp_follow_up fu_msg;
        build_follow_up(&fu_msg, s->ptp_clock_id, sync_seq, precise_ts);

        n = sendto(s->ptp_general_fd, &fu_msg, sizeof(fu_msg), 0,
                   (struct sockaddr *)&general_addr, sizeof(general_addr));
        if (n < 0 && errno != EAGAIN) {
            LOGE("PTP FOLLOW_UP sendto failed: %s", strerror(errno));
        }

        sync_seq++;

        /* ---- ANNOUNCE + SIGNALING (every 1000ms = 8 loops) ---- */
        if (loop_count % 8 == 0) {
            struct ptp_announce ann_msg;
            build_announce(&ann_msg, s->ptp_clock_id, announce_seq++);

            n = sendto(s->ptp_general_fd, &ann_msg, sizeof(ann_msg), 0,
                       (struct sockaddr *)&general_addr, sizeof(general_addr));
            if (n < 0 && errno != EAGAIN) {
                LOGE("PTP ANNOUNCE sendto failed: %s", strerror(errno));
            }

            struct ptp_signaling sig_msg;
            build_signaling(&sig_msg, s->ptp_clock_id, signaling_seq++);

            n = sendto(s->ptp_general_fd, &sig_msg, sizeof(sig_msg), 0,
                       (struct sockaddr *)&general_addr, sizeof(general_addr));
            if (n < 0 && errno != EAGAIN) {
                LOGE("PTP SIGNALING sendto failed: %s", strerror(errno));
            }

            if (loop_count == 0) {
                LOGI("PTP first Announce+Sync+Follow_Up+Signaling sent");
            }
        }

        loop_count++;

        /* Sleep 125ms. Use nanosleep for precision. */
        struct timespec req = { .tv_sec = 0, .tv_nsec = 125000000L };
        nanosleep(&req, NULL);
    }

    LOGI("PTP thread stopped (sync_seq=%u, announce_seq=%u)",
         sync_seq, announce_seq);
    return NULL;
}

/* ---- Public API ---- */

int ap2_ptp_start(ap2_session_t *session) {
    if (session->ptp_running) {
        LOGI("PTP already running");
        return 0;
    }

    /* Create UDP sockets for sending PTP messages.
     * Try to bind to standard PTP ports (319/320) so the receiver
     * sees proper source ports.  Falls back to ephemeral if binding
     * fails (Android restricts privileged ports without root). */
    int efd = socket(AF_INET, SOCK_DGRAM, 0);
    if (efd < 0) {
        LOGE("PTP event socket() failed: %s", strerror(errno));
        return -1;
    }

    int gfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (gfd < 0) {
        LOGE("PTP general socket() failed: %s", strerror(errno));
        close(efd);
        return -1;
    }

    /* Bind PTP sockets so source port matches what the receiver expects.
     *
     * If ClockPorts gave us a single port, use ONE socket for all PTP
     * messages and bind it to that port (non-privileged, should succeed).
     * Otherwise use two sockets and try standard 319/320. */
    int reuse = 1;
    setsockopt(efd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in bind_addr;
    memset(&bind_addr, 0, sizeof(bind_addr));
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_addr.s_addr = INADDR_ANY;

    if (session->ptp_port > 0) {
        /* ClockPorts: single port — one socket handles everything.
         * Close the second socket, point both fds at the same one. */
        close(gfd);

        bind_addr.sin_port = htons((uint16_t)session->ptp_port);
        if (bind(efd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) == 0) {
            LOGI("PTP socket bound to ClockPorts port %d", session->ptp_port);
        } else {
            LOGI("PTP ClockPorts port %d bind failed (%s), using ephemeral",
                 session->ptp_port, strerror(errno));
        }

        session->ptp_event_fd   = efd;
        session->ptp_general_fd = efd;  /* Same socket for all messages */
    } else {
        /* No ClockPorts — two sockets, try standard PTP ports. */
        setsockopt(gfd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

        bind_addr.sin_port = htons(PTP_EVENT_PORT);
        if (bind(efd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) == 0) {
            LOGI("PTP event socket bound to port %d", PTP_EVENT_PORT);
        } else {
            LOGI("PTP event port %d unavailable (%s), using ephemeral",
                 PTP_EVENT_PORT, strerror(errno));
        }

        bind_addr.sin_port = htons(PTP_GENERAL_PORT);
        if (bind(gfd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) == 0) {
            LOGI("PTP general socket bound to port %d", PTP_GENERAL_PORT);
        } else {
            LOGI("PTP general port %d unavailable (%s), using ephemeral",
                 PTP_GENERAL_PORT, strerror(errno));
        }

        session->ptp_event_fd   = efd;
        session->ptp_general_fd = gfd;
    }
    session->ptp_running    = 1;

    int ret = pthread_create(&session->ptp_thread, NULL, ptp_thread_fn, session);
    if (ret != 0) {
        LOGE("PTP pthread_create failed: %s", strerror(ret));
        session->ptp_running = 0;
        close(efd);  session->ptp_event_fd = -1;
        close(gfd);  session->ptp_general_fd = -1;
        return -1;
    }

    LOGI("PTP master clock started for %s", session->host);
    return 0;
}

void ap2_ptp_stop(ap2_session_t *session) {
    if (!session->ptp_running) return;

    session->ptp_running = 0;
    pthread_join(session->ptp_thread, NULL);

    if (session->ptp_event_fd >= 0) {
        close(session->ptp_event_fd);
        session->ptp_event_fd = -1;
    }
    if (session->ptp_general_fd >= 0) {
        close(session->ptp_general_fd);
        session->ptp_general_fd = -1;
    }

    LOGI("PTP master clock stopped");
}
