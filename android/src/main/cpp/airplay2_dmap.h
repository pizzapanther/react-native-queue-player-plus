/**
 * DMAP metadata encoding for AirPlay 2 SET_PARAMETER requests.
 *
 * DMAP is a TLV format: [4-byte tag][4-byte BE length][value].
 * For metadata, we wrap individual items (minm, asar, asal) inside
 * a "mlit" container.
 */
#ifndef AIRPLAY2_DMAP_H
#define AIRPLAY2_DMAP_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Encode metadata as DMAP tagged bytes for SET_PARAMETER.
 * Content-Type: application/x-dmap-tagged
 *
 * @param title   Track title (may be NULL)
 * @param artist  Artist name (may be NULL)
 * @param album   Album name (may be NULL)
 * @param out_buf OUT: heap-allocated DMAP bytes (caller frees)
 * @param out_len OUT: output length
 * @return 0 on success
 */
int ap2_dmap_encode_metadata(const char *title, const char *artist,
                             const char *album,
                             uint8_t **out_buf, size_t *out_len);

/**
 * Encode progress as text/parameters body for SET_PARAMETER.
 * Format: "progress: START/CURRENT/END\r\n"
 * where values are RTP timestamps.
 *
 * @param start_rtp   Start RTP timestamp
 * @param current_rtp Current RTP timestamp
 * @param end_rtp     End RTP timestamp
 * @param out_buf     OUT: heap-allocated string (caller frees)
 * @param out_len     OUT: output length
 * @return 0 on success
 */
int ap2_dmap_encode_progress(uint32_t start_rtp, uint32_t current_rtp,
                             uint32_t end_rtp,
                             uint8_t **out_buf, size_t *out_len);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_DMAP_H */
