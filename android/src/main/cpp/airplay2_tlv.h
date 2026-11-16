/**
 * TLV8 encoding/decoding for AirPlay 2 pairing messages.
 *
 * Format: [1-byte tag][1-byte length (0-255)][0-255 bytes value]
 * Values >255 bytes: fragmented across multiple consecutive entries
 * with the same tag.  Decoder concatenates all consecutive same-tag
 * entries.
 */
#ifndef AIRPLAY2_TLV_H
#define AIRPLAY2_TLV_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Known TLV8 tags used in AirPlay 2 pairing. */
#define AP2_TLV_METHOD       0x00
#define AP2_TLV_IDENTIFIER   0x01
#define AP2_TLV_SALT         0x02
#define AP2_TLV_PUBLIC_KEY   0x03
#define AP2_TLV_PROOF        0x04
#define AP2_TLV_ENCRYPTED    0x05
#define AP2_TLV_STATE        0x06
#define AP2_TLV_ERROR        0x07
#define AP2_TLV_SIGNATURE    0x0A
#define AP2_TLV_FLAGS        0x13

/* Decoded TLV entry (linked list). */
typedef struct ap2_tlv {
    uint8_t tag;
    uint8_t *value;     /* Heap-allocated, concatenated from fragments. */
    size_t length;
    struct ap2_tlv *next;
} ap2_tlv_t;

/**
 * Encode a list of TLV entries into a byte stream.
 * Handles fragmentation for values >255 bytes.
 *
 * @param list      Linked list of TLV entries to encode
 * @param out_buf   OUT: heap-allocated output buffer (caller frees)
 * @param out_len   OUT: output length
 * @return 0 on success
 */
int ap2_tlv_encode(const ap2_tlv_t *list, uint8_t **out_buf, size_t *out_len);

/**
 * Decode a TLV8 byte stream into a linked list.
 * Concatenates consecutive same-tag fragments.
 *
 * @param data  Input TLV8 bytes
 * @param len   Input length
 * @return Linked list of decoded entries, or NULL on failure.
 *         Caller must call ap2_tlv_free().
 */
ap2_tlv_t *ap2_tlv_decode(const uint8_t *data, size_t len);

/**
 * Find the first entry with the given tag.
 * @return Pointer to entry, or NULL if not found.
 */
ap2_tlv_t *ap2_tlv_find(ap2_tlv_t *list, uint8_t tag);

/**
 * Create a single TLV entry (convenience for building encode lists).
 * The value is copied.
 */
ap2_tlv_t *ap2_tlv_new(uint8_t tag, const uint8_t *value, size_t length);

/**
 * Append an entry to the end of a list.  Returns the new head.
 */
ap2_tlv_t *ap2_tlv_append(ap2_tlv_t *list, ap2_tlv_t *entry);

/**
 * Free a linked list of TLV entries.
 */
void ap2_tlv_free(ap2_tlv_t *list);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_TLV_H */
