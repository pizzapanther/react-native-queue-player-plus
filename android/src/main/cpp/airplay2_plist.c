/**
 * Minimal binary plist encoder/decoder for AirPlay 2.
 *
 * Binary plist format ("bplist00"):
 *   - 8-byte magic: "bplist00"
 *   - Objects (various types, variable length)
 *   - Offset table (array of offsets to each object)
 *   - 32-byte trailer (offset table offset, object count, etc.)
 *
 * Supported object types:
 *   0x0 — bool/nil (0x00=null, 0x08=false, 0x09=true)
 *   0x1 — int (1,2,4,8 bytes big-endian)
 *   0x2 — real (4 or 8 bytes big-endian IEEE)
 *   0x4 — data (byte array)
 *   0x5 — ASCII string
 *   0x6 — Unicode string (UTF-16BE)
 *   0xA — array
 *   0xD — dict
 */

#include "airplay2_plist.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>

/* ---- Big-endian read helpers ---- */

static uint64_t read_be(const uint8_t *p, int n) {
    uint64_t v = 0;
    for (int i = 0; i < n; i++) v = (v << 8) | p[i];
    return v;
}

static double read_be_double(const uint8_t *p) {
    uint64_t bits = read_be(p, 8);
    double d;
    memcpy(&d, &bits, 8);
    return d;
}

static float read_be_float(const uint8_t *p) {
    uint32_t bits = (uint32_t)read_be(p, 4);
    float f;
    memcpy(&f, &bits, 4);
    return f;
}

/* ---- Allocation helpers ---- */

static ap2_plist_value_t *pval_new(enum ap2_plist_type type) {
    ap2_plist_value_t *v = (ap2_plist_value_t *)calloc(1, sizeof(*v));
    if (v) v->type = type;
    return v;
}

void ap2_plist_free(ap2_plist_value_t *val) {
    if (!val) return;
    switch (val->type) {
        case AP2_PLIST_STRING:
            free(val->string_val);
            break;
        case AP2_PLIST_DATA:
            free(val->data_val.bytes);
            break;
        case AP2_PLIST_DICT: {
            ap2_plist_dict_entry_t *e = val->dict_val;
            while (e) {
                ap2_plist_dict_entry_t *next = e->next;
                free(e->key);
                ap2_plist_free(e->value);
                free(e);
                e = next;
            }
            break;
        }
        case AP2_PLIST_ARRAY: {
            ap2_plist_array_entry_t *e = val->array_val;
            while (e) {
                ap2_plist_array_entry_t *next = e->next;
                ap2_plist_free(e->value);
                free(e);
                e = next;
            }
            break;
        }
        default:
            break;
    }
    free(val);
}

/* ---- Decoder ---- */

/* Parser context. */
typedef struct {
    const uint8_t *data;
    size_t len;
    uint64_t num_objects;
    int offset_size;
    int ref_size;
    const uint8_t *offsets;     /* Offset table pointer */
} plist_ctx_t;

static ap2_plist_value_t *parse_object(plist_ctx_t *ctx, uint64_t idx);

static uint64_t get_offset(plist_ctx_t *ctx, uint64_t idx) {
    return read_be(ctx->offsets + idx * ctx->offset_size, ctx->offset_size);
}

static uint64_t get_ref(const uint8_t *p, int ref_size) {
    return read_be(p, ref_size);
}

static ap2_plist_value_t *parse_object(plist_ctx_t *ctx, uint64_t idx) {
    if (idx >= ctx->num_objects) return NULL;

    uint64_t offset = get_offset(ctx, idx);
    if (offset >= ctx->len) return NULL;

    const uint8_t *p = ctx->data + offset;
    uint8_t marker = p[0];
    uint8_t type_nibble = (marker >> 4) & 0x0f;
    uint8_t size_nibble = marker & 0x0f;

    switch (type_nibble) {
        case 0x0: {
            /* Singleton: null(0), false(8), true(9) */
            ap2_plist_value_t *v = pval_new(AP2_PLIST_BOOL);
            if (!v) return NULL;
            v->bool_val = (marker == 0x09) ? 1 : 0;
            return v;
        }

        case 0x1: {
            /* Integer: 2^size_nibble bytes big-endian. */
            int byte_count = 1 << size_nibble;
            if (offset + 1 + byte_count > ctx->len) return NULL;
            ap2_plist_value_t *v = pval_new(AP2_PLIST_INT);
            if (!v) return NULL;
            v->int_val = (int64_t)read_be(p + 1, byte_count);
            return v;
        }

        case 0x2: {
            /* Real: 4 or 8 bytes. */
            int byte_count = 1 << size_nibble;
            ap2_plist_value_t *v = pval_new(AP2_PLIST_REAL);
            if (!v) return NULL;
            if (byte_count == 4)
                v->real_val = (double)read_be_float(p + 1);
            else
                v->real_val = read_be_double(p + 1);
            return v;
        }

        case 0x4: {
            /* Data (byte array). */
            uint64_t data_len = size_nibble;
            const uint8_t *data_start = p + 1;
            if (size_nibble == 0x0f) {
                /* Extended length: next byte is int marker. */
                uint8_t int_marker = p[1];
                int int_bytes = 1 << (int_marker & 0x0f);
                data_len = read_be(p + 2, int_bytes);
                data_start = p + 2 + int_bytes;
            }
            ap2_plist_value_t *v = pval_new(AP2_PLIST_DATA);
            if (!v) return NULL;
            v->data_val.length = (size_t)data_len;
            v->data_val.bytes = (uint8_t *)malloc(data_len);
            if (!v->data_val.bytes && data_len > 0) { free(v); return NULL; }
            memcpy(v->data_val.bytes, data_start, data_len);
            return v;
        }

        case 0x5: {
            /* ASCII string. */
            uint64_t str_len = size_nibble;
            const uint8_t *str_start = p + 1;
            if (size_nibble == 0x0f) {
                uint8_t int_marker = p[1];
                int int_bytes = 1 << (int_marker & 0x0f);
                str_len = read_be(p + 2, int_bytes);
                str_start = p + 2 + int_bytes;
            }
            ap2_plist_value_t *v = pval_new(AP2_PLIST_STRING);
            if (!v) return NULL;
            v->string_val = (char *)malloc(str_len + 1);
            if (!v->string_val) { free(v); return NULL; }
            memcpy(v->string_val, str_start, str_len);
            v->string_val[str_len] = '\0';
            return v;
        }

        case 0x6: {
            /* Unicode string (UTF-16BE) — convert to UTF-8. */
            uint64_t char_count = size_nibble;
            const uint8_t *u16_start = p + 1;
            if (size_nibble == 0x0f) {
                uint8_t int_marker = p[1];
                int int_bytes = 1 << (int_marker & 0x0f);
                char_count = read_be(p + 2, int_bytes);
                u16_start = p + 2 + int_bytes;
            }
            /* Simple UTF-16BE → ASCII (BMP only, good enough for AP2 keys). */
            ap2_plist_value_t *v = pval_new(AP2_PLIST_STRING);
            if (!v) return NULL;
            v->string_val = (char *)malloc(char_count * 3 + 1);
            if (!v->string_val) { free(v); return NULL; }
            size_t out_pos = 0;
            for (uint64_t i = 0; i < char_count; i++) {
                uint16_t ch = (uint16_t)read_be(u16_start + i * 2, 2);
                if (ch < 0x80) {
                    v->string_val[out_pos++] = (char)ch;
                } else if (ch < 0x800) {
                    v->string_val[out_pos++] = (char)(0xC0 | (ch >> 6));
                    v->string_val[out_pos++] = (char)(0x80 | (ch & 0x3F));
                } else {
                    v->string_val[out_pos++] = (char)(0xE0 | (ch >> 12));
                    v->string_val[out_pos++] = (char)(0x80 | ((ch >> 6) & 0x3F));
                    v->string_val[out_pos++] = (char)(0x80 | (ch & 0x3F));
                }
            }
            v->string_val[out_pos] = '\0';
            return v;
        }

        case 0xA: {
            /* Array. */
            uint64_t count = size_nibble;
            const uint8_t *refs_start = p + 1;
            if (size_nibble == 0x0f) {
                uint8_t int_marker = p[1];
                int int_bytes = 1 << (int_marker & 0x0f);
                count = read_be(p + 2, int_bytes);
                refs_start = p + 2 + int_bytes;
            }
            ap2_plist_value_t *v = pval_new(AP2_PLIST_ARRAY);
            if (!v) return NULL;
            ap2_plist_array_entry_t **tail = &v->array_val;
            for (uint64_t i = 0; i < count; i++) {
                uint64_t ref = get_ref(refs_start + i * ctx->ref_size,
                                       ctx->ref_size);
                ap2_plist_value_t *child = parse_object(ctx, ref);
                if (!child) continue;
                ap2_plist_array_entry_t *e =
                    (ap2_plist_array_entry_t *)calloc(1, sizeof(*e));
                e->value = child;
                *tail = e;
                tail = &e->next;
            }
            return v;
        }

        case 0xD: {
            /* Dict. */
            uint64_t count = size_nibble;
            const uint8_t *refs_start = p + 1;
            if (size_nibble == 0x0f) {
                uint8_t int_marker = p[1];
                int int_bytes = 1 << (int_marker & 0x0f);
                count = read_be(p + 2, int_bytes);
                refs_start = p + 2 + int_bytes;
            }
            /* Keys first, then values, each is a ref. */
            const uint8_t *key_refs = refs_start;
            const uint8_t *val_refs = refs_start + count * ctx->ref_size;

            ap2_plist_value_t *v = pval_new(AP2_PLIST_DICT);
            if (!v) return NULL;
            ap2_plist_dict_entry_t **tail = &v->dict_val;
            for (uint64_t i = 0; i < count; i++) {
                uint64_t kref = get_ref(key_refs + i * ctx->ref_size,
                                        ctx->ref_size);
                uint64_t vref = get_ref(val_refs + i * ctx->ref_size,
                                        ctx->ref_size);
                ap2_plist_value_t *kval = parse_object(ctx, kref);
                ap2_plist_value_t *vval = parse_object(ctx, vref);
                if (!kval || kval->type != AP2_PLIST_STRING) {
                    ap2_plist_free(kval);
                    ap2_plist_free(vval);
                    continue;
                }
                ap2_plist_dict_entry_t *e =
                    (ap2_plist_dict_entry_t *)calloc(1, sizeof(*e));
                e->key = kval->string_val;
                kval->string_val = NULL;  /* Transferred ownership */
                ap2_plist_free(kval);
                e->value = vval;
                *tail = e;
                tail = &e->next;
            }
            return v;
        }

        default:
            return NULL;
    }
}

ap2_plist_value_t *ap2_plist_parse(const uint8_t *data, size_t len) {
    if (len < 40) return NULL;  /* Magic(8) + at least one obj + trailer(32) */
    if (memcmp(data, "bplist00", 8) != 0) return NULL;

    /* Trailer: last 32 bytes.
     * [6 unused][1 offset_size][1 ref_size][8 num_objects][8 top_object][8 offset_table_offset] */
    const uint8_t *trailer = data + len - 32;
    int offset_size = trailer[6];
    int ref_size = trailer[7];
    uint64_t num_objects = read_be(trailer + 8, 8);
    uint64_t top_object = read_be(trailer + 16, 8);
    uint64_t offset_table_offset = read_be(trailer + 24, 8);

    if (offset_size < 1 || offset_size > 8) return NULL;
    if (ref_size < 1 || ref_size > 8) return NULL;
    if (offset_table_offset + num_objects * offset_size > len - 32) return NULL;

    plist_ctx_t ctx;
    ctx.data = data;
    ctx.len = len;
    ctx.num_objects = num_objects;
    ctx.offset_size = offset_size;
    ctx.ref_size = ref_size;
    ctx.offsets = data + offset_table_offset;

    return parse_object(&ctx, top_object);
}

/* ---- Lookup helpers ---- */

static ap2_plist_dict_entry_t *find_entry(const ap2_plist_value_t *dict,
                                           const char *key) {
    if (!dict || dict->type != AP2_PLIST_DICT) return NULL;
    ap2_plist_dict_entry_t *e = dict->dict_val;
    while (e) {
        if (e->key && strcmp(e->key, key) == 0) return e;
        e = e->next;
    }
    return NULL;
}

const char *ap2_plist_dict_get_string(const ap2_plist_value_t *dict,
                                     const char *key) {
    ap2_plist_dict_entry_t *e = find_entry(dict, key);
    if (!e || !e->value || e->value->type != AP2_PLIST_STRING) return NULL;
    return e->value->string_val;
}

int64_t ap2_plist_dict_get_int(const ap2_plist_value_t *dict,
                               const char *key, int64_t dflt) {
    ap2_plist_dict_entry_t *e = find_entry(dict, key);
    if (!e || !e->value || e->value->type != AP2_PLIST_INT) return dflt;
    return e->value->int_val;
}

const uint8_t *ap2_plist_dict_get_data(const ap2_plist_value_t *dict,
                                       const char *key, size_t *out_len) {
    ap2_plist_dict_entry_t *e = find_entry(dict, key);
    if (!e || !e->value || e->value->type != AP2_PLIST_DATA) {
        if (out_len) *out_len = 0;
        return NULL;
    }
    if (out_len) *out_len = e->value->data_val.length;
    return e->value->data_val.bytes;
}

const ap2_plist_value_t *ap2_plist_dict_get_dict(const ap2_plist_value_t *dict,
                                                  const char *key) {
    ap2_plist_dict_entry_t *e = find_entry(dict, key);
    if (!e || !e->value || e->value->type != AP2_PLIST_DICT) return NULL;
    return e->value;
}

const ap2_plist_value_t *ap2_plist_dict_get_array(const ap2_plist_value_t *dict,
                                                   const char *key) {
    ap2_plist_dict_entry_t *e = find_entry(dict, key);
    if (!e || !e->value || e->value->type != AP2_PLIST_ARRAY) return NULL;
    return e->value;
}

/* ====================================================================
 * Encoder — simple buffer builder for known AP2 structures
 * ====================================================================
 *
 * Strategy: build objects into a flat array, track offsets, then write
 * the offset table and trailer.  Object types we need:
 *   - bool, int, real, string, data, array, dict
 *
 * We use a growable buffer approach.
 */

typedef struct {
    uint8_t *buf;
    size_t len;
    size_t cap;
    uint64_t *offsets;
    size_t obj_count;
    size_t obj_cap;
} plist_builder_t;

static void pb_init(plist_builder_t *pb) {
    pb->cap = 1024;
    pb->buf = (uint8_t *)malloc(pb->cap);
    pb->len = 8;  /* "bplist00" */
    memcpy(pb->buf, "bplist00", 8);
    pb->obj_cap = 64;
    pb->offsets = (uint64_t *)malloc(pb->obj_cap * sizeof(uint64_t));
    pb->obj_count = 0;
}

static void pb_grow(plist_builder_t *pb, size_t need) {
    while (pb->len + need > pb->cap) {
        pb->cap *= 2;
        pb->buf = (uint8_t *)realloc(pb->buf, pb->cap);
    }
}

static void pb_write(plist_builder_t *pb, const void *data, size_t n) {
    pb_grow(pb, n);
    memcpy(pb->buf + pb->len, data, n);
    pb->len += n;
}

static void pb_write_byte(plist_builder_t *pb, uint8_t b) {
    pb_write(pb, &b, 1);
}

static uint64_t pb_add_obj(plist_builder_t *pb) {
    if (pb->obj_count >= pb->obj_cap) {
        pb->obj_cap *= 2;
        pb->offsets = (uint64_t *)realloc(pb->offsets,
                                           pb->obj_cap * sizeof(uint64_t));
    }
    uint64_t idx = pb->obj_count++;
    pb->offsets[idx] = pb->len;
    return idx;
}

/* Write big-endian bytes. */
static void pb_write_be(plist_builder_t *pb, uint64_t val, int n) {
    uint8_t tmp[8];
    for (int i = n - 1; i >= 0; i--) {
        tmp[i] = (uint8_t)(val & 0xff);
        val >>= 8;
    }
    pb_write(pb, tmp, n);
}

/* Add a bool object. */
static uint64_t pb_bool(plist_builder_t *pb, int val) {
    uint64_t idx = pb_add_obj(pb);
    pb_write_byte(pb, val ? 0x09 : 0x08);
    return idx;
}

/* Add an integer object. */
static uint64_t pb_int(plist_builder_t *pb, int64_t val) {
    uint64_t idx = pb_add_obj(pb);
    if (val >= 0 && val <= 0xff) {
        pb_write_byte(pb, 0x10); /* int, 1 byte */
        pb_write_byte(pb, (uint8_t)val);
    } else if (val >= 0 && val <= 0xffff) {
        pb_write_byte(pb, 0x11); /* int, 2 bytes */
        pb_write_be(pb, val, 2);
    } else if (val >= 0 && val <= 0xffffffffLL) {
        pb_write_byte(pb, 0x12); /* int, 4 bytes */
        pb_write_be(pb, val, 4);
    } else {
        pb_write_byte(pb, 0x13); /* int, 8 bytes */
        pb_write_be(pb, (uint64_t)val, 8);
    }
    return idx;
}

/* Add a real (double) object. */
static uint64_t pb_real(plist_builder_t *pb, double val) {
    uint64_t idx = pb_add_obj(pb);
    pb_write_byte(pb, 0x23); /* real, 8 bytes */
    uint64_t bits;
    memcpy(&bits, &val, 8);
    pb_write_be(pb, bits, 8);
    return idx;
}

/* Add an ASCII string object. */
static uint64_t pb_string(plist_builder_t *pb, const char *s) {
    uint64_t idx = pb_add_obj(pb);
    size_t slen = strlen(s);
    if (slen < 15) {
        pb_write_byte(pb, (uint8_t)(0x50 | slen));
    } else {
        pb_write_byte(pb, 0x5f);
        /* Extended length as int object inline. */
        if (slen <= 0xff) {
            pb_write_byte(pb, 0x10);
            pb_write_byte(pb, (uint8_t)slen);
        } else {
            pb_write_byte(pb, 0x11);
            pb_write_be(pb, slen, 2);
        }
    }
    pb_write(pb, s, slen);
    return idx;
}

/* Add a data (byte array) object. */
static uint64_t pb_data(plist_builder_t *pb, const uint8_t *data, size_t dlen) {
    uint64_t idx = pb_add_obj(pb);
    if (dlen < 15) {
        pb_write_byte(pb, (uint8_t)(0x40 | dlen));
    } else {
        pb_write_byte(pb, 0x4f);
        if (dlen <= 0xff) {
            pb_write_byte(pb, 0x10);
            pb_write_byte(pb, (uint8_t)dlen);
        } else {
            pb_write_byte(pb, 0x11);
            pb_write_be(pb, dlen, 2);
        }
    }
    pb_write(pb, data, dlen);
    return idx;
}

/* Determine ref_size needed for obj_count objects. */
static int ref_size_for(size_t count) {
    if (count <= 0xff) return 1;
    if (count <= 0xffff) return 2;
    return 4;
}

/* Finalize: write offset table + trailer, output the buffer. */
static int pb_finish(plist_builder_t *pb, uint64_t top_object,
                     uint8_t **out_buf, size_t *out_len) {
    /* Offset table. */
    int offset_size = 1;
    if (pb->len > 0xff) offset_size = 2;
    if (pb->len > 0xffff) offset_size = 4;

    uint64_t offset_table_offset = pb->len;
    for (size_t i = 0; i < pb->obj_count; i++) {
        pb_write_be(pb, pb->offsets[i], offset_size);
    }

    /* 32-byte trailer. */
    uint8_t trailer[32];
    memset(trailer, 0, 32);
    trailer[6] = (uint8_t)offset_size;
    trailer[7] = (uint8_t)ref_size_for(pb->obj_count);
    /* num_objects at offset 8 (8 bytes BE). */
    for (int i = 7; i >= 0; i--) {
        trailer[8 + (7 - i)] = (uint8_t)((pb->obj_count >> (i * 8)) & 0xff);
    }
    /* top_object at offset 16 (8 bytes BE). */
    for (int i = 7; i >= 0; i--) {
        trailer[16 + (7 - i)] = (uint8_t)((top_object >> (i * 8)) & 0xff);
    }
    /* offset_table_offset at offset 24 (8 bytes BE). */
    for (int i = 7; i >= 0; i--) {
        trailer[24 + (7 - i)] = (uint8_t)((offset_table_offset >> (i * 8)) & 0xff);
    }
    pb_write(pb, trailer, 32);

    *out_buf = pb->buf;
    *out_len = pb->len;
    free(pb->offsets);
    /* Don't free pb->buf — it's transferred to caller. */
    return 0;
}

/* Helper: add a dict with N key-value pairs.  Keys/values are object indices. */
static uint64_t pb_dict(plist_builder_t *pb,
                        const uint64_t *keys, const uint64_t *vals,
                        size_t count) {
    uint64_t idx = pb_add_obj(pb);
    int rs = ref_size_for(pb->obj_count + count * 2 + 16);
    if (count < 15) {
        pb_write_byte(pb, (uint8_t)(0xD0 | count));
    } else {
        pb_write_byte(pb, 0xDF);
        pb_write_byte(pb, 0x10);
        pb_write_byte(pb, (uint8_t)count);
    }
    /* Key refs, then value refs. */
    for (size_t i = 0; i < count; i++) pb_write_be(pb, keys[i], rs);
    for (size_t i = 0; i < count; i++) pb_write_be(pb, vals[i], rs);
    return idx;
}

/* Helper: add an array with N elements (object indices). */
static uint64_t pb_array(plist_builder_t *pb,
                         const uint64_t *elems, size_t count) {
    uint64_t idx = pb_add_obj(pb);
    int rs = ref_size_for(pb->obj_count + count + 16);
    if (count < 15) {
        pb_write_byte(pb, (uint8_t)(0xA0 | count));
    } else {
        pb_write_byte(pb, 0xAF);
        pb_write_byte(pb, 0x10);
        pb_write_byte(pb, (uint8_t)count);
    }
    for (size_t i = 0; i < count; i++) pb_write_be(pb, elems[i], rs);
    return idx;
}

/* ---- Purpose-built encoders ---- */

int ap2_plist_build_setup_session(const char *device_id,
                                  const char *session_uuid,
                                  const char *timing,
                                  const char *name,
                                  const char *local_ip,
                                  uint64_t ptp_clock_id,
                                  uint8_t **out_buf, size_t *out_len) {
    plist_builder_t pb;
    pb_init(&pb);

    int has_name = (name && name[0]);

    /* ---- Build timingPeerInfo nested dict ---- */
    /* { Addresses: [local_ip], ID: session_uuid, ClockID: ptp_clock_id,
     *   DeviceType: 0, SupportsClockPortMatchingOverride: true } */
    uint64_t tp_k0 = pb_string(&pb, "Addresses");
    uint64_t tp_k1 = pb_string(&pb, "ClockID");
    uint64_t tp_k2 = pb_string(&pb, "DeviceType");
    uint64_t tp_k3 = pb_string(&pb, "ID");
    uint64_t tp_k4 = pb_string(&pb, "SupportsClockPortMatchingOverride");

    uint64_t addr_str = pb_string(&pb, local_ip ? local_ip : "0.0.0.0");
    uint64_t addr_arr = pb_array(&pb, &addr_str, 1);
    uint64_t tp_v0 = addr_arr;
    uint64_t tp_v1 = pb_int(&pb, (int64_t)ptp_clock_id);
    uint64_t tp_v2 = pb_int(&pb, 0);
    uint64_t tp_v3 = pb_string(&pb, session_uuid);
    uint64_t tp_v4 = pb_bool(&pb, 1);  /* SupportsClockPortMatchingOverride = true */

    uint64_t tpi_keys[5] = { tp_k0, tp_k1, tp_k2, tp_k3, tp_k4 };
    uint64_t tpi_vals[5] = { tp_v0, tp_v1, tp_v2, tp_v3, tp_v4 };
    uint64_t timing_peer_info = pb_dict(&pb, tpi_keys, tpi_vals, 5);

    /* timingPeerList: array containing the same dict.
     * We build a second copy since plist objects can't be shared. */
    uint64_t tp2_k0 = pb_string(&pb, "Addresses");
    uint64_t tp2_k1 = pb_string(&pb, "ClockID");
    uint64_t tp2_k2 = pb_string(&pb, "DeviceType");
    uint64_t tp2_k3 = pb_string(&pb, "ID");
    uint64_t tp2_k4 = pb_string(&pb, "SupportsClockPortMatchingOverride");

    uint64_t addr_str2 = pb_string(&pb, local_ip ? local_ip : "0.0.0.0");
    uint64_t addr_arr2 = pb_array(&pb, &addr_str2, 1);
    uint64_t tp2_v0 = addr_arr2;
    uint64_t tp2_v1 = pb_int(&pb, (int64_t)ptp_clock_id);
    uint64_t tp2_v2 = pb_int(&pb, 0);
    uint64_t tp2_v3 = pb_string(&pb, session_uuid);
    uint64_t tp2_v4 = pb_bool(&pb, 1);

    uint64_t tpl_keys[5] = { tp2_k0, tp2_k1, tp2_k2, tp2_k3, tp2_k4 };
    uint64_t tpl_vals[5] = { tp2_v0, tp2_v1, tp2_v2, tp2_v3, tp2_v4 };
    uint64_t tpl_dict = pb_dict(&pb, tpl_keys, tpl_vals, 5);
    uint64_t timing_peer_list = pb_array(&pb, &tpl_dict, 1);

    /* ---- Build root dict ---- */
    /* 8 keys: deviceID, groupContainsGroupLeader, groupUUID,
     *         name, sessionUUID, timingPeerInfo, timingPeerList,
     *         timingProtocol */
    int nkeys = has_name ? 8 : 7;

    uint64_t k0 = pb_string(&pb, "deviceID");
    uint64_t k1 = pb_string(&pb, "groupContainsGroupLeader");
    uint64_t k2 = pb_string(&pb, "groupUUID");
    uint64_t k3 = has_name ? pb_string(&pb, "name") : 0;
    uint64_t k4 = pb_string(&pb, "sessionUUID");
    uint64_t k5 = pb_string(&pb, "timingPeerInfo");
    uint64_t k6 = pb_string(&pb, "timingPeerList");
    uint64_t k7 = pb_string(&pb, "timingProtocol");

    uint64_t v0 = pb_string(&pb, device_id);
    uint64_t v1 = pb_bool(&pb, 0);  /* groupContainsGroupLeader = false */
    uint64_t v2 = pb_string(&pb, session_uuid);  /* groupUUID = sessionUUID */
    uint64_t v3 = has_name ? pb_string(&pb, name) : 0;
    uint64_t v4 = pb_string(&pb, session_uuid);
    uint64_t v5 = timing_peer_info;
    uint64_t v6 = timing_peer_list;
    uint64_t v7 = pb_string(&pb, timing);

    uint64_t keys[8], vals[8];
    int idx = 0;
    keys[idx] = k0; vals[idx] = v0; idx++;
    keys[idx] = k1; vals[idx] = v1; idx++;
    keys[idx] = k2; vals[idx] = v2; idx++;
    if (has_name) { keys[idx] = k3; vals[idx] = v3; idx++; }
    keys[idx] = k4; vals[idx] = v4; idx++;
    keys[idx] = k5; vals[idx] = v5; idx++;
    keys[idx] = k6; vals[idx] = v6; idx++;
    keys[idx] = k7; vals[idx] = v7; idx++;

    uint64_t root = pb_dict(&pb, keys, vals, nkeys);
    return pb_finish(&pb, root, out_buf, out_len);
}

int ap2_plist_build_setup_session_ntp(const char *device_id,
                                      const char *session_uuid,
                                      const char *name,
                                      int timing_port,
                                      uint8_t **out_buf, size_t *out_len) {
    plist_builder_t pb;
    pb_init(&pb);

    int has_name = (name && name[0]);

    /* NTP mode: just deviceID, sessionUUID, timingPort, timingProtocol.
     * No timingPeerInfo or timingPeerList needed (per OwnTone). */
    int nkeys = has_name ? 4 : 3;

    uint64_t k0 = pb_string(&pb, "deviceID");
    uint64_t k1 = has_name ? pb_string(&pb, "name") : 0;
    uint64_t k2 = pb_string(&pb, "sessionUUID");
    uint64_t k3 = pb_string(&pb, "timingPort");
    uint64_t k4 = pb_string(&pb, "timingProtocol");

    uint64_t v0 = pb_string(&pb, device_id);
    uint64_t v1 = has_name ? pb_string(&pb, name) : 0;
    uint64_t v2 = pb_string(&pb, session_uuid);
    uint64_t v3 = pb_int(&pb, timing_port);
    uint64_t v4 = pb_string(&pb, "NTP");

    /* OwnTone also sends groupContainsGroupLeader and groupUUID
     * even in NTP mode — include them for compatibility. */
    uint64_t k5 = pb_string(&pb, "groupContainsGroupLeader");
    uint64_t k6 = pb_string(&pb, "groupUUID");
    uint64_t v5 = pb_bool(&pb, 0);
    uint64_t v6 = pb_string(&pb, session_uuid);

    nkeys += 2;  /* groupContainsGroupLeader + groupUUID */

    uint64_t keys[7], vals[7];
    int idx = 0;
    keys[idx] = k0; vals[idx] = v0; idx++;
    keys[idx] = k5; vals[idx] = v5; idx++;  /* groupContainsGroupLeader */
    keys[idx] = k6; vals[idx] = v6; idx++;  /* groupUUID */
    if (has_name) { keys[idx] = k1; vals[idx] = v1; idx++; }
    keys[idx] = k2; vals[idx] = v2; idx++;
    keys[idx] = k3; vals[idx] = v3; idx++;
    keys[idx] = k4; vals[idx] = v4; idx++;

    uint64_t root = pb_dict(&pb, keys, vals, nkeys);
    return pb_finish(&pb, root, out_buf, out_len);
}

int ap2_plist_build_setup_stream(int64_t audio_format, int ct, int sr,
                                 int spf, const uint8_t *shk, size_t shk_len,
                                 int control_port, uint32_t stream_conn_id,
                                 uint8_t **out_buf, size_t *out_len) {
    plist_builder_t pb;
    pb_init(&pb);

    /* Stream dict — 13 keys matching OwnTone. */
    uint64_t sk0  = pb_string(&pb, "audioFormat");
    uint64_t sk1  = pb_string(&pb, "audioMode");
    uint64_t sk2  = pb_string(&pb, "controlPort");
    uint64_t sk3  = pb_string(&pb, "ct");
    uint64_t sk4  = pb_string(&pb, "isMedia");
    uint64_t sk5  = pb_string(&pb, "latencyMax");
    uint64_t sk6  = pb_string(&pb, "latencyMin");
    uint64_t sk7  = pb_string(&pb, "shk");
    uint64_t sk8  = pb_string(&pb, "spf");
    uint64_t sk9  = pb_string(&pb, "sr");
    uint64_t sk10 = pb_string(&pb, "streamConnectionID");
    uint64_t sk11 = pb_string(&pb, "supportsDynamicStreamID");
    uint64_t sk12 = pb_string(&pb, "type");

    uint64_t sv0  = pb_int(&pb, audio_format);
    uint64_t sv1  = pb_string(&pb, "default");
    uint64_t sv2  = pb_int(&pb, control_port);
    uint64_t sv3  = pb_int(&pb, ct);
    uint64_t sv4  = pb_bool(&pb, 1);            /* isMedia = true */
    uint64_t sv5  = pb_int(&pb, 88200);         /* 2s at 44100 */
    uint64_t sv6  = pb_int(&pb, 11025);         /* ~250ms at 44100 */
    uint64_t sv7  = pb_data(&pb, shk, shk_len);
    uint64_t sv8  = pb_int(&pb, spf);
    uint64_t sv9  = pb_int(&pb, sr);
    uint64_t sv10 = pb_int(&pb, stream_conn_id);
    uint64_t sv11 = pb_bool(&pb, 0);            /* supportsDynamicStreamID = false */
    uint64_t sv12 = pb_int(&pb, 96);            /* realtime */

    uint64_t skeys[] = { sk0, sk1, sk2, sk3, sk4, sk5, sk6, sk7, sk8, sk9, sk10, sk11, sk12 };
    uint64_t svals[] = { sv0, sv1, sv2, sv3, sv4, sv5, sv6, sv7, sv8, sv9, sv10, sv11, sv12 };
    uint64_t stream_dict = pb_dict(&pb, skeys, svals, 13);

    /* Wrap in "streams" array. */
    uint64_t stream_arr = pb_array(&pb, &stream_dict, 1);

    /* Root dict: { "streams": [...] } */
    uint64_t rk = pb_string(&pb, "streams");
    uint64_t root = pb_dict(&pb, &rk, &stream_arr, 1);

    return pb_finish(&pb, root, out_buf, out_len);
}

int ap2_plist_build_setpeers(const char **addresses, int count,
                             uint8_t **out_buf, size_t *out_len) {
    plist_builder_t pb;
    pb_init(&pb);

    uint64_t *elems = (uint64_t *)malloc(count * sizeof(uint64_t));
    for (int i = 0; i < count; i++) {
        elems[i] = pb_string(&pb, addresses[i]);
    }
    uint64_t root = pb_array(&pb, elems, count);
    free(elems);

    return pb_finish(&pb, root, out_buf, out_len);
}

int ap2_plist_build_setrateanchortime(double rate, uint32_t rtp_time,
                                      uint64_t net_secs, uint64_t net_frac,
                                      int timeline_id,
                                      uint8_t **out_buf, size_t *out_len) {
    plist_builder_t pb;
    pb_init(&pb);

    uint64_t k0 = pb_string(&pb, "rate");
    uint64_t k1 = pb_string(&pb, "rtpTime");
    uint64_t k2 = pb_string(&pb, "networkTimeSecs");
    uint64_t k3 = pb_string(&pb, "networkTimeFrac");
    uint64_t k4 = pb_string(&pb, "networkTimeTimelineID");

    uint64_t v0 = pb_real(&pb, rate);
    uint64_t v1 = pb_int(&pb, rtp_time);
    uint64_t v2 = pb_int(&pb, (int64_t)net_secs);
    uint64_t v3 = pb_int(&pb, (int64_t)net_frac);
    uint64_t v4 = pb_int(&pb, timeline_id);

    uint64_t keys[] = { k0, k1, k2, k3, k4 };
    uint64_t vals[] = { v0, v1, v2, v3, v4 };
    uint64_t root = pb_dict(&pb, keys, vals, 5);

    return pb_finish(&pb, root, out_buf, out_len);
}
