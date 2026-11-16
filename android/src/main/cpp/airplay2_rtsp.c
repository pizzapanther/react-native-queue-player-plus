/**
 * Blocking RTSP client for AirPlay 2.
 */

#include "airplay2_rtsp.h"
#include "airplay2_crypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <openssl/evp.h>

#define TAG "AP2Rtsp"
#include "airplay2_filelog.h"

/* Max response size we'll read (256 KB). */
#define MAX_RESPONSE_SIZE (256 * 1024)

/* ---- TCP helpers ---- */

static int send_all(int fd, const uint8_t *data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, data + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return -1;
        sent += (size_t)n;
    }
    return 0;
}

static int recv_exact(int fd, uint8_t *buf, size_t len) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = recv(fd, buf + got, len - got, 0);
        if (n <= 0) return -1;
        got += (size_t)n;
    }
    return 0;
}

/* Read until we find "\r\n\r\n" (end of RTSP headers).
 * Returns total bytes read (headers + possibly some body). */
static int recv_headers(int fd, uint8_t *buf, size_t buf_size, size_t *total) {
    *total = 0;
    while (*total < buf_size - 1) {
        ssize_t n = recv(fd, buf + *total, 1, 0);
        if (n <= 0) return -1;
        (*total)++;
        if (*total >= 4 &&
            buf[*total-4] == '\r' && buf[*total-3] == '\n' &&
            buf[*total-2] == '\r' && buf[*total-1] == '\n') {
            return 0;
        }
    }
    return -1; /* Headers too large */
}

/* ---- Encrypted I/O ---- */

/**
 * Send an encrypted frame: [2-byte LE length][encrypted data][16-byte tag].
 * Max payload per frame: 1024 bytes.
 */
static int send_encrypted(ap2_session_t *s, const uint8_t *data, size_t len) {
    size_t offset = 0;
    int frame_idx = 0;
    LOGI("send_encrypted: total=%zu bytes, write_nonce=%llu",
         len, (unsigned long long)s->write_nonce);
    while (offset < len) {
        size_t chunk = len - offset;
        if (chunk > 1024) chunk = 1024;

        /* Build nonce: 64-bit LE counter, zero-padded to 96 bits. */
        uint8_t nonce[12];
        memset(nonce, 0, 12);
        memcpy(nonce + 4, &s->write_nonce, 8);
        s->write_nonce++;

        /* AAD is the 2-byte LE length. */
        uint8_t aad[2];
        aad[0] = (uint8_t)(chunk & 0xff);
        aad[1] = (uint8_t)((chunk >> 8) & 0xff);

        uint8_t *cipher = (uint8_t *)malloc(chunk);
        uint8_t tag[16];

        if (ap2_chacha20_encrypt(s->write_key, nonce,
                                  aad, 2,
                                  data + offset, chunk,
                                  cipher, tag) != 0) {
            LOGE("send_encrypted: encrypt failed frame=%d chunk=%zu", frame_idx, chunk);
            free(cipher);
            return -1;
        }

        /* Send: [2-byte LE length][cipher][tag] */
        if (send_all(s->rtsp_fd, aad, 2) != 0 ||
            send_all(s->rtsp_fd, cipher, chunk) != 0 ||
            send_all(s->rtsp_fd, tag, 16) != 0) {
            LOGE("send_encrypted: send failed frame=%d errno=%d (%s)",
                 frame_idx, errno, strerror(errno));
            free(cipher);
            return -1;
        }

        /* Log first 20 wire bytes for offline verification. */
        if (frame_idx == 0) {
            char hex[61];
            int hx = 0;
            hex[hx++] = aad[0] >= 0x10 ? "0123456789abcdef"[aad[0]>>4] : '0';
            hex[hx++] = "0123456789abcdef"[aad[0]&0xf];
            hex[hx++] = aad[1] >= 0x10 ? "0123456789abcdef"[aad[1]>>4] : '0';
            hex[hx++] = "0123456789abcdef"[aad[1]&0xf];
            for (size_t b = 0; b < 16 && b < chunk; b++) {
                hex[hx++] = "0123456789abcdef"[cipher[b]>>4];
                hex[hx++] = "0123456789abcdef"[cipher[b]&0xf];
            }
            hex[hx] = '\0';
            LOGI("send_encrypted: wire[0..%d]=%s nonce=%llu",
                 hx/2 - 1, hex, (unsigned long long)(s->write_nonce - 1));
        }

        LOGD("send_encrypted: frame=%d chunk=%zu sent=%zu+18",
             frame_idx, chunk, chunk);
        free(cipher);
        offset += chunk;
        frame_idx++;
    }
    LOGI("send_encrypted: done, %d frames, write_nonce now=%llu",
         frame_idx, (unsigned long long)s->write_nonce);
    return 0;
}

/**
 * Receive and decrypt one frame.  Returns decrypted length, or -1 on error.
 */
static int recv_decrypt_frame(ap2_session_t *s, uint8_t *out, size_t out_cap) {
    /* Read 2-byte LE length. */
    uint8_t aad[2];
    if (recv_exact(s->rtsp_fd, aad, 2) != 0) {
        LOGE("recv_decrypt: failed to read 2-byte length, errno=%d (%s)",
             errno, strerror(errno));
        return -1;
    }
    size_t frame_len = aad[0] | ((size_t)aad[1] << 8);
    LOGD("recv_decrypt: frame_len=%zu read_nonce=%llu",
         frame_len, (unsigned long long)s->read_nonce);
    if (frame_len > out_cap || frame_len > 1024) {
        /* If the first 2 bytes look like ASCII, the server may be
         * responding in cleartext (not encrypted).  Log to diagnose. */
        LOGE("recv_decrypt: frame_len=%zu (0x%02x%02x) exceeds cap=%zu or max 1024",
             frame_len, aad[0], aad[1], out_cap);
        if (aad[0] >= 0x20 && aad[0] < 0x7f &&
            aad[1] >= 0x20 && aad[1] < 0x7f) {
            /* Read up to 256 more bytes to see what the cleartext says. */
            char peek[258];
            peek[0] = (char)aad[0];
            peek[1] = (char)aad[1];
            ssize_t n = recv(s->rtsp_fd, peek + 2, sizeof(peek) - 3, MSG_DONTWAIT);
            if (n > 0) peek[2 + n] = '\0'; else peek[2] = '\0';
            LOGE("recv_decrypt: CLEARTEXT response detected: %.200s", peek);
        }
        return -1;
    }

    /* Read encrypted data + tag. */
    uint8_t *cipher = (uint8_t *)malloc(frame_len);
    uint8_t tag[16];
    if (recv_exact(s->rtsp_fd, cipher, frame_len) != 0) {
        LOGE("recv_decrypt: failed to read %zu cipher bytes", frame_len);
        free(cipher);
        return -1;
    }
    if (recv_exact(s->rtsp_fd, tag, 16) != 0) {
        LOGE("recv_decrypt: failed to read 16-byte tag");
        free(cipher);
        return -1;
    }

    /* Build nonce. */
    uint8_t nonce[12];
    memset(nonce, 0, 12);
    memcpy(nonce + 4, &s->read_nonce, 8);
    s->read_nonce++;

    int ret = ap2_chacha20_decrypt(s->read_key, nonce,
                                    aad, 2,
                                    cipher, frame_len, tag,
                                    out);
    free(cipher);
    if (ret != 0) {
        LOGE("recv_decrypt: decryption failed at nonce=%llu frame_len=%zu",
             (unsigned long long)(s->read_nonce - 1), frame_len);
    }
    return ret == 0 ? (int)frame_len : -1;
}

/* ---- HTTP Digest auth (OwnTone airplay.c) ---- */

/**
 * Compute MD5 hex digest of the concatenated inputs separated by ':'.
 * Returns 32-char lowercase hex string in `out` (caller provides 33-byte buf).
 */
static void md5_hex(const char *a, const char *b, const char *c, char out[33]) {
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    unsigned char hash[16];
    unsigned int hash_len = 16;
    EVP_DigestInit_ex(ctx, EVP_md5(), NULL);
    EVP_DigestUpdate(ctx, a, strlen(a));
    EVP_DigestUpdate(ctx, ":", 1);
    EVP_DigestUpdate(ctx, b, strlen(b));
    if (c) {
        EVP_DigestUpdate(ctx, ":", 1);
        EVP_DigestUpdate(ctx, c, strlen(c));
    }
    EVP_DigestFinal_ex(ctx, hash, &hash_len);
    EVP_MD_CTX_free(ctx);
    for (int i = 0; i < 16; i++)
        sprintf(out + i * 2, "%02x", hash[i]);
    out[32] = '\0';
}

/**
 * Parse WWW-Authenticate: Digest realm="...", nonce="..." from response.
 * OwnTone airplay.c.
 */
static int parse_www_authenticate(ap2_session_t *s, const char *resp_buf) {
    const char *hdr = strstr(resp_buf, "WWW-Authenticate:");
    if (!hdr) hdr = strstr(resp_buf, "www-authenticate:");
    if (!hdr) return -1;

    hdr += 17;
    while (*hdr == ' ') hdr++;

    if (strncmp(hdr, "Digest ", 7) != 0) return -1;
    hdr += 7;

    /* Extract realm="..." and nonce="..." */
    s->realm[0] = '\0';
    s->nonce[0] = '\0';

    const char *p = hdr;
    while (*p) {
        while (*p == ' ' || *p == ',') p++;
        if (strncmp(p, "realm=\"", 7) == 0) {
            p += 7;
            int i = 0;
            while (*p && *p != '"' && i < (int)sizeof(s->realm) - 1)
                s->realm[i++] = *p++;
            s->realm[i] = '\0';
            if (*p == '"') p++;
        } else if (strncmp(p, "nonce=\"", 7) == 0) {
            p += 7;
            int i = 0;
            while (*p && *p != '"' && i < (int)sizeof(s->nonce) - 1)
                s->nonce[i++] = *p++;
            s->nonce[i] = '\0';
            if (*p == '"') p++;
        } else {
            /* Skip unknown key=value */
            while (*p && *p != ',' && *p != '\r') p++;
        }
    }

    if (s->realm[0] && s->nonce[0]) {
        s->has_digest_auth = 1;
        LOGI("Digest auth parsed: realm=%s", s->realm);
        return 0;
    }
    return -1;
}

/**
 * Build Authorization: Digest ... header string.
 * OwnTone airplay.c — HA1=MD5("":realm:password), HA2=MD5(method:uri).
 * Returns heap-allocated string like "Authorization: Digest ...\r\n", or NULL.
 */
static char *build_digest_header(ap2_session_t *s, const char *method, const char *uri) {
    if (!s->has_digest_auth || !s->password[0]) return NULL;

    /* HA1 = MD5(username:realm:password) — username is empty per OwnTone */
    char ha1[33];
    md5_hex("", s->realm, s->password, ha1);

    /* HA2 = MD5(method:uri) */
    char ha2[33];
    md5_hex(method, uri, NULL, ha2);

    /* response = MD5(HA1:nonce:HA2) */
    char response[33];
    md5_hex(ha1, s->nonce, ha2, response);

    char *hdr = (char *)malloc(512);
    snprintf(hdr, 512,
        "Authorization: Digest username=\"\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\"\r\n",
        s->realm, s->nonce, uri, response);
    return hdr;
}

/* ---- Public API ---- */

int ap2_rtsp_connect(ap2_session_t *session) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return -1;
    }

    /* Set TCP_NODELAY for low-latency RTSP. */
    int flag = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)session->rtsp_port);
    if (inet_pton(AF_INET, session->host, &addr.sin_addr) != 1) {
        LOGE("inet_pton failed for %s", session->host);
        close(fd);
        return -1;
    }

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        LOGE("connect(%s:%d) failed: %s",
             session->host, session->rtsp_port, strerror(errno));
        close(fd);
        return -1;
    }

    /* 10-second receive timeout — prevents hanging on unresponsive
     * receivers or endpoints that silently drop responses. */
    struct timeval tv = { .tv_sec = 10, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    session->rtsp_fd = fd;
    session->cseq = 0;
    LOGI("RTSP connected to %s:%d fd=%d",
         session->host, session->rtsp_port, fd);
    return 0;
}

int ap2_rtsp_request_ex(ap2_session_t *session,
                        const char *method, const char *path,
                        const char *ct, const uint8_t *body, size_t body_len,
                        const char *extra_headers,
                        ap2_rtsp_response_t *resp) {
    memset(resp, 0, sizeof(*resp));

    if (session->rtsp_fd < 0) return -1;

    session->cseq++;

    /* Build request.
     * Pair endpoints need X-Apple-HKP to tell the receiver which pairing
     * mode we're using: 4 = transient, 3 = HomeKit normal. Without it
     * macOS receivers return 400 on /pair-setup. */
    const char *hkp = NULL;
    if (strncmp(path, "/pair-", 6) == 0) {
        hkp = session->transient_ok ? "4" : "3";
    }

    /* Client name header — sent on all requests so the receiver can
     * display our identity (e.g. macOS shows this in the AirPlay popup).
     * AP2 uses X-Apple-Client-Name (per OwnTone); AP1 uses X-Apple-Device-Name. */
    const char *dn = session->device_name[0] ? session->device_name : NULL;

    /* Use session URL for RTSP methods, raw path for HTTP-style endpoints.
     * Per OwnTone airplay.c — session_url for SETUP/RECORD/etc,
     * raw path for /info, /pair-*, /feedback, /peer-list-changed. */
    const char *uri = path;
    if (strcmp(method, "SETUP") == 0 || strcmp(method, "RECORD") == 0 ||
        strcmp(method, "FLUSH") == 0 || strcmp(method, "TEARDOWN") == 0 ||
        strcmp(method, "SET_PARAMETER") == 0) {
        uri = session->session_url;
    }

    /* Auto-include Digest Authorization on all requests after first 401.
     * Per OwnTone airplay.c — auth_header_add on every request. */
    char *digest_hdr = NULL;
    char *merged_headers = NULL;
    if (session->has_digest_auth) {
        digest_hdr = build_digest_header(session, method, uri);
    }
    if (digest_hdr || extra_headers) {
        size_t dlen = digest_hdr ? strlen(digest_hdr) : 0;
        size_t elen = extra_headers ? strlen(extra_headers) : 0;
        merged_headers = (char *)malloc(dlen + elen + 1);
        merged_headers[0] = '\0';
        if (digest_hdr) strcat(merged_headers, digest_hdr);
        if (extra_headers) strcat(merged_headers, extra_headers);
        free(digest_hdr);
    }
    const char *all_extra = merged_headers ? merged_headers : "";

    /* DACP-ID / Client-Instance / Active-Remote — per OwnTone airplay.c.
     * These let the receiver persistently identify this client. */
    char dacp_hdr[192];
    if (session->dacp_id[0]) {
        snprintf(dacp_hdr, sizeof(dacp_hdr),
            "Client-Instance: %s\r\n"
            "DACP-ID: %s\r\n"
            "Active-Remote: %u\r\n",
            session->dacp_id, session->dacp_id, session->active_remote);
    } else {
        dacp_hdr[0] = '\0';
    }

    char header[4096];
    int hlen;
    if (body && body_len > 0 && ct) {
        hlen = snprintf(header, sizeof(header),
            "%s %s RTSP/1.0\r\n"
            "CSeq: %d\r\n"
            "%s%s%s"
            "%s%s%s"
            "%s"
            "%s"
            "Content-Type: %s\r\n"
            "Content-Length: %zu\r\n"
            "\r\n",
            method, uri, session->cseq,
            hkp ? "X-Apple-HKP: " : "", hkp ? hkp : "", hkp ? "\r\n" : "",
            dn ? "X-Apple-Client-Name: " : "", dn ? dn : "", dn ? "\r\n" : "",
            dacp_hdr,
            all_extra,
            ct, body_len);
    } else {
        hlen = snprintf(header, sizeof(header),
            "%s %s RTSP/1.0\r\n"
            "CSeq: %d\r\n"
            "%s%s%s"
            "%s%s%s"
            "%s"
            "%s"
            "\r\n",
            method, uri, session->cseq,
            hkp ? "X-Apple-HKP: " : "", hkp ? hkp : "", hkp ? "\r\n" : "",
            dn ? "X-Apple-Client-Name: " : "", dn ? dn : "", dn ? "\r\n" : "",
            dacp_hdr,
            all_extra);
    }
    free(merged_headers);

    LOGD(">>> %s %s CSeq=%d body=%zu encrypted=%d",
         method, path, session->cseq, body_len, session->encrypted);

    /* Send. */
    if (session->encrypted) {
        /* Diagnostic: log plaintext RTSP headers before encryption. */
        {
            char hdr_preview[256];
            int plen = hlen < 255 ? hlen : 255;
            memcpy(hdr_preview, header, plen);
            hdr_preview[plen] = '\0';
            /* Replace \r\n with | for single-line logging. */
            for (int i = 0; i < plen; i++) {
                if (hdr_preview[i] == '\r' || hdr_preview[i] == '\n')
                    hdr_preview[i] = '|';
            }
            LOGI("encrypted RTSP plaintext: %s", hdr_preview);
        }

        /* Diagnostic: check if socket has pending data before we send. */
        {
            struct pollfd pfd = { .fd = session->rtsp_fd, .events = POLLIN | POLLERR | POLLHUP };
            int pr = poll(&pfd, 1, 0);
            if (pr > 0) {
                LOGI("DIAG: socket readable BEFORE send! revents=0x%x", pfd.revents);
                /* Try peeking at what's there. */
                uint8_t peek[64];
                ssize_t pn = recv(session->rtsp_fd, peek, sizeof(peek), MSG_PEEK | MSG_DONTWAIT);
                if (pn > 0) {
                    char phex[129];
                    for (int i = 0; i < pn && i < 32; i++)
                        snprintf(phex + i*2, 3, "%02x", peek[i]);
                    LOGI("DIAG: pending data (%zd bytes): %s", pn, phex);
                }
            } else {
                LOGI("DIAG: socket clean before send (no pending data)");
            }
        }

        /* Combine header + body, encrypt as frames. */
        size_t total = hlen + body_len;
        uint8_t *combined = (uint8_t *)malloc(total);
        memcpy(combined, header, hlen);
        if (body_len > 0) memcpy(combined + hlen, body, body_len);
        int ret = send_encrypted(session, combined, total);
        free(combined);
        if (ret != 0) return -1;

        /* Diagnostic: check socket error + readability after send. */
        {
            int sockerr = 0;
            socklen_t errlen = sizeof(sockerr);
            getsockopt(session->rtsp_fd, SOL_SOCKET, SO_ERROR, &sockerr, &errlen);
            if (sockerr != 0)
                LOGE("DIAG: SO_ERROR after send: %d (%s)", sockerr, strerror(sockerr));

            /* Poll for 500ms to see if response starts arriving quickly. */
            struct pollfd pfd = { .fd = session->rtsp_fd, .events = POLLIN | POLLERR | POLLHUP };
            int pr = poll(&pfd, 1, 500);
            LOGI("DIAG: poll 500ms after send: ret=%d revents=0x%x", pr, pfd.revents);
        }
    } else {
        if (send_all(session->rtsp_fd, (uint8_t *)header, hlen) != 0)
            return -1;
        if (body && body_len > 0) {
            if (send_all(session->rtsp_fd, body, body_len) != 0)
                return -1;
        }
    }

    /* Receive response. */
    uint8_t *resp_buf;
    size_t resp_total;

    if (session->encrypted) {
        /* Read encrypted frames until we have a complete RTSP response. */
        resp_buf = (uint8_t *)malloc(MAX_RESPONSE_SIZE);
        resp_total = 0;
        int found_end = 0;
        while (!found_end && resp_total < MAX_RESPONSE_SIZE - 1024) {
            int n = recv_decrypt_frame(session, resp_buf + resp_total,
                                        MAX_RESPONSE_SIZE - resp_total);
            if (n < 0) { free(resp_buf); return -1; }
            resp_total += n;
            /* Check for end of headers. */
            for (size_t i = 3; i < resp_total; i++) {
                if (resp_buf[i-3] == '\r' && resp_buf[i-2] == '\n' &&
                    resp_buf[i-1] == '\r' && resp_buf[i] == '\n') {
                    found_end = 1;
                    break;
                }
            }
        }
    } else {
        resp_buf = (uint8_t *)malloc(MAX_RESPONSE_SIZE);
        if (recv_headers(session->rtsp_fd, resp_buf,
                         MAX_RESPONSE_SIZE, &resp_total) != 0) {
            free(resp_buf);
            return -1;
        }
    }

    /* Null-terminate so strstr doesn't read past headers into
     * uninitialized heap (which could contain stale Content-Length
     * from a previous response, causing phantom body reads). */
    resp_buf[resp_total] = '\0';

    /* Parse status line: "RTSP/1.0 200 OK\r\n" */
    resp->status = 0;
    if (resp_total >= 12) {
        resp->status = (int)strtol((char *)resp_buf + 9, NULL, 10);
    }

    /* 401 Unauthorized → parse Digest challenge and retry once.
     * Per OwnTone airplay.c. Must happen before freeing resp_buf. */
    if (resp->status == 401 && session->password[0] && !session->has_digest_auth) {
        if (parse_www_authenticate(session, (char *)resp_buf) == 0) {
            LOGI("Digest auth: realm=%s — retrying %s %s",
                 session->realm, method, uri);
            free(resp_buf);
            /* Retry with Authorization header (recursive, max 1 depth). */
            return ap2_rtsp_request_ex(session, method, path,
                                       ct, body, body_len,
                                       extra_headers, resp);
        }
    }

    /* Parse Content-Length. */
    size_t content_length = 0;
    const char *cl = strstr((char *)resp_buf, "Content-Length:");
    if (!cl) cl = strstr((char *)resp_buf, "content-length:");
    if (cl) {
        content_length = (size_t)strtoul(cl + 15, NULL, 10);
    }

    /* Parse Content-Type. */
    const char *ct_hdr = strstr((char *)resp_buf, "Content-Type:");
    if (!ct_hdr) ct_hdr = strstr((char *)resp_buf, "content-type:");
    if (ct_hdr) {
        ct_hdr += 13;
        while (*ct_hdr == ' ') ct_hdr++;
        const char *end = strstr(ct_hdr, "\r\n");
        if (end) {
            size_t ct_len = end - ct_hdr;
            resp->content_type = (char *)malloc(ct_len + 1);
            memcpy(resp->content_type, ct_hdr, ct_len);
            resp->content_type[ct_len] = '\0';
        }
    }

    /* Find where headers end. */
    const char *hdr_end = strstr((char *)resp_buf, "\r\n\r\n");
    size_t hdr_len = hdr_end ? (size_t)(hdr_end - (char *)resp_buf + 4) : resp_total;

    /* How much body we already have. */
    size_t body_already = resp_total - hdr_len;

    if (content_length > 0) {
        resp->body = (uint8_t *)malloc(content_length);
        resp->body_len = content_length;

        /* Copy what we have. */
        size_t to_copy = body_already < content_length ? body_already : content_length;
        memcpy(resp->body, resp_buf + hdr_len, to_copy);

        /* Read remaining body if needed. */
        if (to_copy < content_length) {
            if (session->encrypted) {
                /* Read more encrypted frames. */
                size_t got = to_copy;
                while (got < content_length) {
                    int n = recv_decrypt_frame(session,
                        resp->body + got, content_length - got);
                    if (n < 0) break;
                    got += n;
                }
            } else {
                recv_exact(session->rtsp_fd,
                           resp->body + to_copy,
                           content_length - to_copy);
            }
        }
    }

    free(resp_buf);

    LOGD("<<< %d %s body=%zu", resp->status,
         resp->content_type ? resp->content_type : "(none)",
         resp->body_len);

    return 0;
}

int ap2_rtsp_request(ap2_session_t *session,
                     const char *method, const char *path,
                     const char *ct, const uint8_t *body, size_t body_len,
                     ap2_rtsp_response_t *resp) {
    return ap2_rtsp_request_ex(session, method, path, ct, body, body_len,
                               NULL, resp);
}

int ap2_rtsp_request_tmo(ap2_session_t *session,
                         const char *method, const char *path,
                         const char *ct, const uint8_t *body, size_t body_len,
                         int timeout_sec,
                         ap2_rtsp_response_t *resp) {
    /* Save current timeout, apply the override, make the call, restore. */
    struct timeval old_tv = { 0 };
    socklen_t old_len = sizeof(old_tv);
    int have_old = 0;
    if (session->rtsp_fd >= 0 && timeout_sec > 0) {
        if (getsockopt(session->rtsp_fd, SOL_SOCKET, SO_RCVTIMEO,
                       &old_tv, &old_len) == 0) {
            have_old = 1;
        }
        struct timeval tv = { .tv_sec = timeout_sec, .tv_usec = 0 };
        setsockopt(session->rtsp_fd, SOL_SOCKET, SO_RCVTIMEO,
                   &tv, sizeof(tv));
    }

    int ret = ap2_rtsp_request_ex(session, method, path, ct, body, body_len,
                                  NULL, resp);

    if (have_old && session->rtsp_fd >= 0) {
        setsockopt(session->rtsp_fd, SOL_SOCKET, SO_RCVTIMEO,
                   &old_tv, sizeof(old_tv));
    }
    return ret;
}

void ap2_rtsp_response_free(ap2_rtsp_response_t *resp) {
    if (!resp) return;
    free(resp->content_type);
    free(resp->body);
    memset(resp, 0, sizeof(*resp));
}

void ap2_rtsp_close(ap2_session_t *session) {
    if (session->rtsp_fd >= 0) {
        close(session->rtsp_fd);
        session->rtsp_fd = -1;
    }
}
