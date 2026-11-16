/**
 * AirPlay 2 session control — SETUP, RECORD, volume, teardown.
 */

#include "airplay2_control.h"
#include "airplay2_rtsp.h"
#include "airplay2_plist.h"
#include "airplay2_ptp.h"
#include "airplay2_ntp.h"
#include "airplay2_dmap.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define TAG "AP2Control"
#include "airplay2_filelog.h"

/* Content types. */
#define CT_BPLIST  "application/x-apple-binary-plist"
#define CT_PARAMS  "text/parameters"

/* ---- Helpers ---- */

/**
 * Get our local IP address on the connected RTSP socket.
 * Used for PTP timing peer info.
 */
static int get_local_address(int fd, char *buf, size_t buf_len) {
    struct sockaddr_in addr;
    socklen_t alen = sizeof(addr);
    if (getsockname(fd, (struct sockaddr *)&addr, &alen) != 0) return -1;
    if (!inet_ntop(AF_INET, &addr.sin_addr, buf, buf_len)) return -1;
    return 0;
}

/* ---- SETUP (session) ---- */

int ap2_setup_session(ap2_session_t *session) {
    /* Build our device ID from local MAC-like string.
     * For now use a synthetic one based on session UUID. */
    char our_device_id[18];
    snprintf(our_device_id, sizeof(our_device_id),
        "%02X:%02X:%02X:%02X:%02X:%02X",
        (unsigned)(session->ssrc >> 24) & 0xFF,
        (unsigned)(session->ssrc >> 16) & 0xFF,
        (unsigned)(session->ssrc >>  8) & 0xFF,
        (unsigned)(session->ssrc)       & 0xFF,
        (unsigned)(session->rtp_seq >> 8) & 0xFF,
        (unsigned)(session->rtp_seq)      & 0xFF);

    uint8_t *plist_buf = NULL;
    size_t plist_len = 0;

    if (session->use_ntp) {
        /* NTP mode: start timing service first to get our port number. */
        if (session->timing_port == 0) {
            if (ap2_ntp_start(session) != 0) {
                LOGE("NTP timing service failed to start");
                return -1;
            }
        }

        if (ap2_plist_build_setup_session_ntp(our_device_id,
                                               session->session_uuid,
                                               session->device_name,
                                               session->timing_port,
                                               &plist_buf, &plist_len) != 0) {
            LOGE("Failed to build NTP SETUP session plist");
            return -1;
        }
        LOGI("SETUP session: NTP mode, timingPort=%d", session->timing_port);
    } else {
        /* PTP mode: include timingPeerInfo with clock ID. */
        char local_addr[64] = {0};
        get_local_address(session->rtsp_fd, local_addr, sizeof(local_addr));

        if (ap2_plist_build_setup_session(our_device_id, session->session_uuid,
                                          "PTP",
                                          session->device_name,
                                          local_addr,
                                          session->ptp_clock_id,
                                          &plist_buf, &plist_len) != 0) {
            LOGE("Failed to build PTP SETUP session plist");
            return -1;
        }
    }

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request(session, "SETUP", session->session_url,
                               CT_BPLIST, plist_buf, plist_len, &resp);

    /* 401 → enable encryption and retry. Transient pairing derives
     * control-channel keys but keeps encrypted=0 (shairport-sync
     * expects cleartext). macOS/HomePod return 401 to signal that
     * encrypted RTSP is required. */
    if ((ret == 0 && resp.status == 401) &&
        !session->encrypted && session->has_control_keys) {
        LOGI("SETUP session: 401 — enabling encryption and retrying");
        ap2_rtsp_response_free(&resp);
        session->encrypted = 1;

        ret = ap2_rtsp_request(session, "SETUP", session->session_url,
                               CT_BPLIST, plist_buf, plist_len, &resp);
    }
    free(plist_buf);

    if (ret != 0 || resp.status != 200) {
        int orig_status = resp.status;
        ap2_rtsp_response_free(&resp);

        /* NTP→PTP fallback: if the receiver rejected NTP (400/403),
         * stop NTP service and retry with PTP timing.  shairport-sync
         * only supports PTP for AP2; real Apple receivers accept NTP. */
        if (session->use_ntp && (orig_status == 400 || orig_status == 403)) {
            LOGI("SETUP session: NTP rejected (status=%d), falling back to PTP",
                 orig_status);

            ap2_ntp_stop(session);
            session->use_ntp = 0;

            char local_addr[64] = {0};
            get_local_address(session->rtsp_fd, local_addr, sizeof(local_addr));

            plist_buf = NULL;
            plist_len = 0;
            if (ap2_plist_build_setup_session(our_device_id, session->session_uuid,
                                              "PTP",
                                              session->device_name,
                                              local_addr,
                                              session->ptp_clock_id,
                                              &plist_buf, &plist_len) != 0) {
                LOGE("Failed to build PTP SETUP session plist");
                return -1;
            }

            ret = ap2_rtsp_request(session, "SETUP", session->session_url,
                                   CT_BPLIST, plist_buf, plist_len, &resp);
            free(plist_buf);

            if (ret != 0 || resp.status != 200) {
                LOGE("SETUP session (PTP fallback) failed: ret=%d status=%d",
                     ret, resp.status);
                ap2_rtsp_response_free(&resp);
                return -1;
            }
            LOGI("SETUP session: PTP fallback succeeded");
        } else {
            LOGE("SETUP session failed: ret=%d status=%d", ret, orig_status);
            return -1;
        }
    }

    /* Parse response plist. */
    ap2_plist_value_t *root = ap2_plist_parse(resp.body, resp.body_len);
    ap2_rtsp_response_free(&resp);

    if (!root) {
        LOGE("SETUP session: failed to parse response plist");
        return -1;
    }

    /* Debug: dump all top-level keys to diagnose eventPort lookup failures. */
    if (root->type == AP2_PLIST_DICT) {
        ap2_plist_dict_entry_t *e = root->dict_val;
        while (e) {
            LOGD("SETUP resp key: '%s' type=%d", e->key ? e->key : "(null)",
                 e->value ? e->value->type : -1);
            if (e->value && e->value->type == AP2_PLIST_INT) {
                LOGD("  int val: %lld", (long long)e->value->int_val);
            }
            e = e->next;
        }
    } else {
        LOGE("SETUP response root is type %d, expected dict", root->type);
    }

    /* Extract PTP port from timingPeerInfo → ClockPorts dict.
     * The receiver maps our session UUID to the port we should send PTP to. */
    const ap2_plist_value_t *tpi = ap2_plist_dict_get_dict(root, "timingPeerInfo");
    if (tpi) {
        const ap2_plist_value_t *clock_ports = ap2_plist_dict_get_dict(tpi, "ClockPorts");
        if (clock_ports) {
            /* Look up our session UUID in ClockPorts. */
            int64_t raw = ap2_plist_dict_get_int(clock_ports,
                                                  session->session_uuid, 0);
            uint16_t ptp_port = (uint16_t)(raw & 0xFFFF);
            if (ptp_port > 0) {
                session->ptp_port = (int)ptp_port;
                LOGI("ClockPorts: PTP port=%d for UUID=%s",
                     ptp_port, session->session_uuid);
            }
        }
    }

    int64_t event_port_raw = ap2_plist_dict_get_int(root, "eventPort", 0);
    /* macOS returns eventPort as unsigned — may appear negative in signed int64.
     * Mask to uint16 to get the real port number. */
    uint16_t event_port = (uint16_t)(event_port_raw & 0xFFFF);
    if (event_port == 0) {
        LOGE("SETUP session: missing or zero eventPort in response");
        ap2_plist_free(root);
        return -1;
    }
    session->event_port = (int)event_port;
    LOGI("SETUP session: eventPort=%d (raw=%lld)", event_port, (long long)event_port_raw);

    /* Extract timing port if present (some receivers return it). */
    int64_t timing_port = ap2_plist_dict_get_int(root, "timingPort", 0);
    if (timing_port > 0) {
        LOGD("SETUP session: timingPort=%d", (int)timing_port);
    }

    ap2_plist_free(root);

    /* Open event TCP connection. */
    int efd = socket(AF_INET, SOCK_STREAM, 0);
    if (efd < 0) {
        LOGE("Event socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_in eaddr;
    memset(&eaddr, 0, sizeof(eaddr));
    eaddr.sin_family = AF_INET;
    eaddr.sin_port = htons((uint16_t)session->event_port);
    inet_pton(AF_INET, session->host, &eaddr.sin_addr);

    if (connect(efd, (struct sockaddr *)&eaddr, sizeof(eaddr)) != 0) {
        LOGE("Event connect(%s:%d) failed: %s",
             session->host, session->event_port, strerror(errno));
        close(efd);
        return -1;
    }
    session->event_fd = efd;

    session->state = AP2_STATE_SETUP_SESSION;
    LOGI("SETUP session ok: eventPort=%d", session->event_port);
    return 0;
}

/* ---- SETUP (stream) ---- */

int ap2_setup_stream(ap2_session_t *session) {
    /* Audio key (shk) = first 32 bytes of shared secret.
     * OwnTone does exactly this: chacha_open(shared_secret, 32). */
    memcpy(session->audio_key, session->shared_secret, 32);

    /* Verify key is non-zero (catches missing shared_secret bug). */
    int key_nonzero = 0;
    for (int i = 0; i < 32; i++) key_nonzero |= session->audio_key[i];
    LOGI("Audio key (shk): %s, first4=%02x%02x%02x%02x",
         key_nonzero ? "OK" : "ALL ZEROS",
         session->audio_key[0], session->audio_key[1],
         session->audio_key[2], session->audio_key[3]);

    /* Generate stream connection ID if not set. */
    if (session->stream_conn_id == 0) {
        /* Simple pseudo-random from session pointer + time. */
        session->stream_conn_id = (uint32_t)((uintptr_t)session ^ (uint32_t)time(NULL));
    }

    /* SSRC = 0 for PTP timing mode (per OwnTone). */
    session->ssrc = 0;

    /* We need a local control port. Bind an ephemeral UDP socket. */
    int ctl_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (ctl_fd < 0) {
        LOGE("Control socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_in ctl_addr;
    memset(&ctl_addr, 0, sizeof(ctl_addr));
    ctl_addr.sin_family = AF_INET;
    ctl_addr.sin_port = 0; /* Let OS pick. */
    ctl_addr.sin_addr.s_addr = INADDR_ANY;
    if (bind(ctl_fd, (struct sockaddr *)&ctl_addr, sizeof(ctl_addr)) != 0) {
        LOGE("Control bind() failed: %s", strerror(errno));
        close(ctl_fd);
        return -1;
    }

    /* Get our assigned port. */
    socklen_t alen = sizeof(ctl_addr);
    getsockname(ctl_fd, (struct sockaddr *)&ctl_addr, &alen);
    int our_control_port = ntohs(ctl_addr.sin_port);

    /* Build SETUP stream plist — 13 keys matching OwnTone. */
    uint8_t *plist_buf = NULL;
    size_t plist_len = 0;
    if (ap2_plist_build_setup_stream(
            0x40000,             /* audioFormat: ALAC/44100/16/2 */
            2,                   /* ct: ALAC */
            session->sample_rate,
            session->spf,
            session->audio_key,  /* shk */
            32,
            our_control_port,
            session->stream_conn_id,
            &plist_buf, &plist_len) != 0) {
        LOGE("Failed to build SETUP stream plist");
        close(ctl_fd);
        return -1;
    }

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request(session, "SETUP", session->session_url,
                               CT_BPLIST, plist_buf, plist_len, &resp);
    free(plist_buf);

    if (ret != 0 || resp.status != 200) {
        LOGE("SETUP stream failed: ret=%d status=%d", ret, resp.status);
        ap2_rtsp_response_free(&resp);
        close(ctl_fd);
        return -1;
    }

    /* Parse response: extract dataPort, controlPort from streams[0]. */
    ap2_plist_value_t *root = ap2_plist_parse(resp.body, resp.body_len);
    ap2_rtsp_response_free(&resp);

    if (!root) {
        LOGE("SETUP stream: failed to parse response plist");
        close(ctl_fd);
        return -1;
    }

    /* Navigate: root -> "streams" -> [0] -> dataPort, controlPort. */
    const ap2_plist_value_t *streams_arr = ap2_plist_dict_get_array(root, "streams");
    const ap2_plist_value_t *stream0 = NULL;
    if (streams_arr && streams_arr->type == AP2_PLIST_ARRAY &&
        streams_arr->array_val) {
        stream0 = streams_arr->array_val->value;
    }

    if (!stream0 || stream0->type != AP2_PLIST_DICT) {
        LOGE("SETUP stream: missing streams[0] in response");
        ap2_plist_free(root);
        close(ctl_fd);
        return -1;
    }

    int64_t data_port_raw = ap2_plist_dict_get_int(stream0, "dataPort", 0);
    int64_t ctl_port_raw = ap2_plist_dict_get_int(stream0, "controlPort", 0);
    /* macOS returns ports as unsigned — mask to uint16 like eventPort. */
    uint16_t data_port = (uint16_t)(data_port_raw & 0xFFFF);
    uint16_t remote_ctl_port = (uint16_t)(ctl_port_raw & 0xFFFF);

    if (data_port == 0 || remote_ctl_port == 0) {
        LOGE("SETUP stream: missing ports (data=%d raw=%lld, control=%d raw=%lld)",
             data_port, (long long)data_port_raw,
             remote_ctl_port, (long long)ctl_port_raw);
        ap2_plist_free(root);
        close(ctl_fd);
        return -1;
    }

    session->data_port = (int)data_port;
    session->control_port = (int)remote_ctl_port;
    LOGI("SETUP stream ok: dataPort=%d controlPort=%d",
         session->data_port, session->control_port);

    ap2_plist_free(root);

    /* Create UDP socket for audio data. */
    int dfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (dfd < 0) {
        LOGE("Data socket() failed: %s", strerror(errno));
        close(ctl_fd);
        return -1;
    }

    struct sockaddr_in daddr;
    memset(&daddr, 0, sizeof(daddr));
    daddr.sin_family = AF_INET;
    daddr.sin_port = htons((uint16_t)session->data_port);
    inet_pton(AF_INET, session->host, &daddr.sin_addr);

    if (connect(dfd, (struct sockaddr *)&daddr, sizeof(daddr)) != 0) {
        LOGE("Data connect(%s:%d) failed: %s",
             session->host, session->data_port, strerror(errno));
        close(dfd);
        close(ctl_fd);
        return -1;
    }

    session->data_fd = dfd;
    /* Keep control socket alive for sync/retransmit packets. */
    session->control_fd = ctl_fd;

    session->state = AP2_STATE_SETUP_STREAM;
    LOGI("SETUP stream ok: dataPort=%d controlPort=%d",
         session->data_port, session->control_port);
    return 0;
}

/* ---- SETPEERS ---- */

int ap2_setpeers(ap2_session_t *session) {
    /* Get our local IP from the RTSP socket. */
    char local_addr[64];
    if (get_local_address(session->rtsp_fd, local_addr, sizeof(local_addr)) != 0) {
        LOGE("SETPEERS: failed to get local address");
        return -1;
    }

    /* OwnTone sends: [receiver_address, our_local_address]. */
    const char *addrs[2] = { session->host, local_addr };

    uint8_t *plist_buf = NULL;
    size_t plist_len = 0;
    if (ap2_plist_build_setpeers(addrs, 2, &plist_buf, &plist_len) != 0) {
        LOGE("Failed to build SETPEERS plist");
        return -1;
    }

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request(session, "SETPEERS", "/peer-list-changed",
                               CT_BPLIST, plist_buf, plist_len, &resp);
    free(plist_buf);

    if (ret != 0 || resp.status != 200) {
        LOGE("SETPEERS failed: ret=%d status=%d", ret, resp.status);
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    LOGI("SETPEERS ok: [%s, %s]", session->host, local_addr);
    return 0;
}

/* ---- RECORD ---- */

int ap2_record(ap2_session_t *session) {
    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request(session, "RECORD", session->session_url,
                               NULL, NULL, 0, &resp);
    if (ret != 0 || resp.status != 200) {
        LOGE("RECORD failed: ret=%d status=%d", ret, resp.status);
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    session->state = AP2_STATE_RECORD;
    LOGI("RECORD ok");
    return 0;
}

/* ---- FLUSH ---- */

int ap2_flush(ap2_session_t *session) {
    /* Build RTP-Info header per OwnTone airplay.c:
     * "RTP-Info: seq=<seqnum>;rtptime=<rtptime>" */
    char rtp_info[128];
    snprintf(rtp_info, sizeof(rtp_info),
             "RTP-Info: seq=%u;rtptime=%u\r\n",
             (unsigned)session->rtp_seq, (unsigned)session->rtp_timestamp);

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request_ex(session, "FLUSH", session->session_url,
                                   NULL, NULL, 0, rtp_info, &resp);
    if (ret != 0 || resp.status != 200) {
        LOGE("FLUSH failed: ret=%d status=%d", ret, resp.status);
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    LOGI("FLUSH ok: seq=%u rtptime=%u",
         (unsigned)session->rtp_seq, (unsigned)session->rtp_timestamp);
    return 0;
}

/* ---- Volume ---- */

int ap2_set_volume(ap2_session_t *session, float vol_db) {
    char body[64];
    int blen;

    /* Format: "volume: -X.XXXXXX\r\n"
     * Use negative sign handling to avoid locale issues with %f. */
    if (vol_db >= 0.0f) {
        blen = snprintf(body, sizeof(body), "volume: 0.000000\r\n");
    } else if (vol_db <= -144.0f) {
        blen = snprintf(body, sizeof(body), "volume: -144.000000\r\n");
    } else {
        float abs_vol = -vol_db;
        int whole = (int)abs_vol;
        int frac = (int)(1000000.0f * (abs_vol - (float)whole));
        blen = snprintf(body, sizeof(body), "volume: -%d.%06d\r\n",
                        whole, frac);
    }

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request(session, "SET_PARAMETER", session->session_url,
                               CT_PARAMS, (uint8_t *)body, (size_t)blen, &resp);
    if (ret != 0) {
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    LOGD("Volume set to %.1f dB", vol_db);
    return 0;
}

/* ---- Metadata ---- */

int ap2_set_metadata(ap2_session_t *session,
                     const char *title, const char *artist, const char *album) {
    uint8_t *dmap_buf = NULL;
    size_t dmap_len = 0;
    if (ap2_dmap_encode_metadata(title, artist, album, &dmap_buf, &dmap_len) != 0) {
        LOGE("Failed to encode DMAP metadata");
        return -1;
    }

    /* OwnTone airplay.c — adds RTP-Info header. */
    char rtp_info[128];
    snprintf(rtp_info, sizeof(rtp_info),
             "RTP-Info: rtptime=%u\r\n",
             (unsigned)session->rtp_timestamp);

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request_ex(session, "SET_PARAMETER", session->session_url,
                                   "application/x-dmap-tagged",
                                   dmap_buf, dmap_len, rtp_info, &resp);
    free(dmap_buf);

    if (ret != 0) {
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    LOGD("Metadata sent: %s / %s / %s",
         title ? title : "(null)",
         artist ? artist : "(null)",
         album ? album : "(null)");
    return 0;
}

/* ---- Artwork ---- */

int ap2_set_artwork(ap2_session_t *session,
                    const uint8_t *data, size_t len,
                    const char *content_type) {
    if (!data || len == 0) return 0;

    /* OwnTone airplay.c — raw image body with format-specific CT. */
    char rtp_info[128];
    snprintf(rtp_info, sizeof(rtp_info),
             "RTP-Info: rtptime=%u\r\n",
             (unsigned)session->rtp_timestamp);

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request_ex(session, "SET_PARAMETER", session->session_url,
                                   content_type,
                                   data, len, rtp_info, &resp);
    if (ret != 0) {
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    LOGD("Artwork sent: %zu bytes (%s)", len, content_type);
    return 0;
}

/* ---- Progress ---- */

int ap2_set_progress(ap2_session_t *session,
                     uint32_t elapsed_ms, uint32_t duration_ms) {
    /* Convert ms to RTP timestamps.
     * Per OwnTone airplay.c — "progress: start/current/end". */
    uint32_t rtp_start = session->rtp_timestamp;
    uint32_t rtp_pos = rtp_start + (uint32_t)((uint64_t)elapsed_ms * session->sample_rate / 1000);
    uint32_t rtp_end = rtp_start + (uint32_t)((uint64_t)duration_ms * session->sample_rate / 1000);

    uint8_t *progress_buf = NULL;
    size_t progress_len = 0;
    if (ap2_dmap_encode_progress(rtp_start, rtp_pos, rtp_end,
                                  &progress_buf, &progress_len) != 0) {
        LOGE("Failed to encode progress");
        return -1;
    }

    char rtp_info[128];
    snprintf(rtp_info, sizeof(rtp_info),
             "RTP-Info: rtptime=%u\r\n",
             (unsigned)session->rtp_timestamp);

    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request_ex(session, "SET_PARAMETER", session->session_url,
                                   CT_PARAMS,
                                   progress_buf, progress_len, rtp_info, &resp);
    free(progress_buf);

    if (ret != 0) {
        ap2_rtsp_response_free(&resp);
        return -1;
    }
    ap2_rtsp_response_free(&resp);

    LOGD("Progress sent: %u/%u/%u", rtp_start, rtp_pos, rtp_end);
    return 0;
}

/* ---- TEARDOWN ---- */

int ap2_teardown(ap2_session_t *session) {
    /* Stop timing services before sending TEARDOWN. */
    ap2_ptp_stop(session);
    ap2_ntp_stop(session);

    /* If the session is already known to be dead (WiFi loss, receiver gone)
     * skip the RTSP request — it will just block for SO_RCVTIMEO. */
    if (session->session_dead) {
        session->state = AP2_STATE_TEARDOWN;
        LOGI("TEARDOWN skipped — session dead");
        return 0;
    }

    /* Use a short 2s receive timeout for TEARDOWN so a late-dying receiver
     * cannot stall the disconnect path. */
    ap2_rtsp_response_t resp;
    int ret = ap2_rtsp_request_tmo(session, "TEARDOWN", session->session_url,
                                   NULL, NULL, 0, 2, &resp);
    ap2_rtsp_response_free(&resp);

    session->state = AP2_STATE_TEARDOWN;
    LOGI("TEARDOWN sent (ret=%d)", ret);
    return ret;
}

/* ---- Full startup sequence ---- */

int ap2_start_streaming(ap2_session_t *session) {
    /* Sequence: SETUP session → timing start → RECORD → SETPEERS → SETUP stream → Volume.
     * NTP timing is started inside ap2_setup_session() (before the RTSP request).
     * PTP timing is started after SETUP session (needs ClockPorts from response). */
    if (ap2_setup_session(session) != 0) return -1;

    if (!session->use_ntp) {
        /* PTP mode: start after SETUP session to get ClockPorts. */
        if (ap2_ptp_start(session) != 0) {
            LOGE("PTP start failed — continuing without timing (audio may not play)");
        }
    }
    /* NTP timing service was already started in ap2_setup_session(). */

    if (ap2_record(session)        != 0) return -1;
    if (ap2_setpeers(session)      != 0) return -1;
    if (ap2_setup_stream(session)  != 0) return -1;

    /* Send initial volume. */
    float vol_db;
    if (session->volume <= 0.0f) {
        vol_db = -144.0f;
    } else if (session->volume >= 1.0f) {
        vol_db = 0.0f;
    } else {
        vol_db = -30.0f + (session->volume * 30.0f);
    }
    ap2_set_volume(session, vol_db);

    session->state = AP2_STATE_STREAMING;
    LOGI("Streaming started: %s:%d data_fd=%d",
         session->host, session->data_port, session->data_fd);
    return 0;
}
