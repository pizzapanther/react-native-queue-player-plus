/**
 * DMAP metadata encoding for AirPlay 2.
 */

#include "airplay2_dmap.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Write a 4-byte big-endian integer. */
static void write_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)(v);
}

/* Append a DMAP string item: [4-byte tag][4-byte BE length][string]. */
static size_t dmap_item_size(const char *tag, const char *value) {
    (void)tag;
    return 4 + 4 + (value ? strlen(value) : 0);
}

static void dmap_write_item(uint8_t *p, const char *tag, const char *value) {
    size_t vlen = value ? strlen(value) : 0;
    memcpy(p, tag, 4);
    write_be32(p + 4, (uint32_t)vlen);
    if (vlen > 0) memcpy(p + 8, value, vlen);
}

int ap2_dmap_encode_metadata(const char *title, const char *artist,
                             const char *album,
                             uint8_t **out_buf, size_t *out_len) {
    /* Calculate total size of inner items. */
    size_t inner = 0;
    if (title)  inner += dmap_item_size("minm", title);
    if (artist) inner += dmap_item_size("asar", artist);
    if (album)  inner += dmap_item_size("asal", album);

    /* Wrap in mlit container: [mlit][length][inner items]. */
    size_t total = 4 + 4 + inner;
    uint8_t *buf = (uint8_t *)malloc(total);
    if (!buf) return -1;

    memcpy(buf, "mlit", 4);
    write_be32(buf + 4, (uint32_t)inner);

    size_t pos = 8;
    if (title) {
        dmap_write_item(buf + pos, "minm", title);
        pos += dmap_item_size("minm", title);
    }
    if (artist) {
        dmap_write_item(buf + pos, "asar", artist);
        pos += dmap_item_size("asar", artist);
    }
    if (album) {
        dmap_write_item(buf + pos, "asal", album);
        pos += dmap_item_size("asal", album);
    }

    *out_buf = buf;
    *out_len = total;
    return 0;
}

int ap2_dmap_encode_progress(uint32_t start_rtp, uint32_t current_rtp,
                             uint32_t end_rtp,
                             uint8_t **out_buf, size_t *out_len) {
    /* Format: "progress: START/CURRENT/END\r\n" */
    char tmp[128];
    int n = snprintf(tmp, sizeof(tmp), "progress: %u/%u/%u\r\n",
                     start_rtp, current_rtp, end_rtp);
    if (n < 0) return -1;

    *out_buf = (uint8_t *)malloc(n);
    if (!*out_buf) return -1;
    memcpy(*out_buf, tmp, n);
    *out_len = (size_t)n;
    return 0;
}
