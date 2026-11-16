/**
 * TLV8 encoding/decoding for AirPlay 2 pairing messages.
 */

#include "airplay2_tlv.h"
#include <stdlib.h>
#include <string.h>

ap2_tlv_t *ap2_tlv_new(uint8_t tag, const uint8_t *value, size_t length) {
    ap2_tlv_t *t = (ap2_tlv_t *)calloc(1, sizeof(*t));
    if (!t) return NULL;
    t->tag = tag;
    t->length = length;
    if (length > 0 && value) {
        t->value = (uint8_t *)malloc(length);
        if (!t->value) { free(t); return NULL; }
        memcpy(t->value, value, length);
    }
    return t;
}

ap2_tlv_t *ap2_tlv_append(ap2_tlv_t *list, ap2_tlv_t *entry) {
    if (!entry) return list;
    if (!list) return entry;
    ap2_tlv_t *tail = list;
    while (tail->next) tail = tail->next;
    tail->next = entry;
    return list;
}

void ap2_tlv_free(ap2_tlv_t *list) {
    while (list) {
        ap2_tlv_t *next = list->next;
        free(list->value);
        free(list);
        list = next;
    }
}

ap2_tlv_t *ap2_tlv_find(ap2_tlv_t *list, uint8_t tag) {
    while (list) {
        if (list->tag == tag) return list;
        list = list->next;
    }
    return NULL;
}

int ap2_tlv_encode(const ap2_tlv_t *list, uint8_t **out_buf, size_t *out_len) {
    /* First pass: compute total output size. */
    size_t total = 0;
    for (const ap2_tlv_t *t = list; t; t = t->next) {
        if (t->length == 0) {
            total += 2; /* tag + length=0 */
        } else {
            /* Number of 255-byte fragments + possible remainder. */
            size_t full = t->length / 255;
            size_t rem = t->length % 255;
            total += full * (2 + 255);
            if (rem > 0) total += 2 + rem;
        }
    }

    uint8_t *buf = (uint8_t *)malloc(total);
    if (!buf) return -1;

    /* Second pass: write. */
    size_t pos = 0;
    for (const ap2_tlv_t *t = list; t; t = t->next) {
        size_t remaining = t->length;
        size_t offset = 0;

        if (remaining == 0) {
            buf[pos++] = t->tag;
            buf[pos++] = 0;
            continue;
        }

        while (remaining > 0) {
            size_t chunk = remaining > 255 ? 255 : remaining;
            buf[pos++] = t->tag;
            buf[pos++] = (uint8_t)chunk;
            if (t->value) memcpy(buf + pos, t->value + offset, chunk);
            pos += chunk;
            offset += chunk;
            remaining -= chunk;
        }
    }

    *out_buf = buf;
    *out_len = total;
    return 0;
}

ap2_tlv_t *ap2_tlv_decode(const uint8_t *data, size_t len) {
    ap2_tlv_t *head = NULL;
    size_t pos = 0;

    while (pos + 2 <= len) {
        uint8_t tag = data[pos];
        uint8_t frag_len = data[pos + 1];
        pos += 2;

        if (pos + frag_len > len) {
            ap2_tlv_free(head);
            return NULL; /* Truncated */
        }

        /* Check if the previous entry has the same tag (fragmentation). */
        ap2_tlv_t *prev = NULL;
        if (head) {
            ap2_tlv_t *t = head;
            while (t->next) t = t->next;
            if (t->tag == tag) prev = t;
        }

        if (prev) {
            /* Extend the previous entry. */
            size_t new_len = prev->length + frag_len;
            uint8_t *new_val = (uint8_t *)realloc(prev->value, new_len);
            if (!new_val) { ap2_tlv_free(head); return NULL; }
            memcpy(new_val + prev->length, data + pos, frag_len);
            prev->value = new_val;
            prev->length = new_len;
        } else {
            /* New entry. */
            ap2_tlv_t *entry = ap2_tlv_new(tag, data + pos, frag_len);
            if (!entry) { ap2_tlv_free(head); return NULL; }
            head = ap2_tlv_append(head, entry);
        }

        pos += frag_len;
    }

    return head;
}
