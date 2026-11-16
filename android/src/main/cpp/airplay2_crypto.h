/**
 * AirPlay 2 cryptographic primitives.
 *
 * Wraps OpenSSL 3.5.0 + the vendored curve25519 library into
 * purpose-built functions for the AirPlay 2 pairing and streaming
 * protocols.
 *
 * Every function is self-contained and thread-safe (no global state).
 */
#ifndef AIRPLAY2_CRYPTO_H
#define AIRPLAY2_CRYPTO_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ====================================================================
 * SRP6a  (RFC 5054, 3072-bit prime, g=5, SHA-512)
 * ==================================================================== */

/**
 * Opaque SRP6a client context.  Holds BigNum state for the handshake.
 */
typedef struct ap2_srp ap2_srp_t;

/**
 * Create a new SRP6a client context.
 * @param username  SRP username (always "Pair-Setup")
 * @param pin       PIN string (e.g. "3939" for transient)
 * @return context, or NULL on failure.  Caller must call ap2_srp_free().
 */
ap2_srp_t *ap2_srp_new(const char *username, const char *pin);

/**
 * Process the server's salt + B (public ephemeral).
 * Computes A (our public ephemeral) and M1 (proof of password).
 *
 * @param srp       SRP context from ap2_srp_new()
 * @param salt      Server-provided salt bytes
 * @param salt_len  Salt length
 * @param B         Server's public ephemeral (big-endian)
 * @param B_len     B length
 * @param A_out     OUT: our public ephemeral A (caller frees)
 * @param A_len     OUT: A length
 * @param M1_out    OUT: proof M1 (caller frees)
 * @param M1_len    OUT: M1 length
 * @return 0 on success, -1 on failure
 */
int ap2_srp_start(ap2_srp_t *srp,
                  const uint8_t *salt, size_t salt_len,
                  const uint8_t *B, size_t B_len,
                  uint8_t **A_out, size_t *A_len,
                  uint8_t **M1_out, size_t *M1_len);

/**
 * Verify the server's proof M2.
 * @return 0 on success, -1 if verification fails
 */
int ap2_srp_verify(ap2_srp_t *srp, const uint8_t *M2, size_t M2_len);

/**
 * Get the SRP session key (K).  Valid only after a successful
 * ap2_srp_start().
 * @param key_out  OUT: session key bytes (caller frees)
 * @param key_len  OUT: key length (64 bytes for SHA-512)
 * @return 0 on success
 */
int ap2_srp_session_key(ap2_srp_t *srp, uint8_t **key_out, size_t *key_len);

/**
 * Free the SRP context and all associated BigNum state.
 */
void ap2_srp_free(ap2_srp_t *srp);

/* ====================================================================
 * HKDF-SHA-512
 * ==================================================================== */

/**
 * HKDF extract + expand (RFC 5869) with SHA-512.
 *
 * @param ikm       Input key material
 * @param ikm_len   IKM length
 * @param salt      Salt (typically an ASCII string like "Pair-Setup-Encrypt-Salt")
 * @param info      Info (typically an ASCII string)
 * @param out       Output buffer (caller-provided)
 * @param out_len   Desired output length
 * @return 0 on success, -1 on failure
 */
int ap2_hkdf_sha512(const uint8_t *ikm, size_t ikm_len,
                    const char *salt, const char *info,
                    uint8_t *out, size_t out_len);

/* ====================================================================
 * ChaCha20-Poly1305 (AEAD)
 * ==================================================================== */

/**
 * Encrypt with ChaCha20-Poly1305.
 *
 * @param key       256-bit key (32 bytes)
 * @param nonce     96-bit nonce (12 bytes)
 * @param aad       Additional authenticated data (may be NULL)
 * @param aad_len   AAD length
 * @param plain     Plaintext input
 * @param plain_len Plaintext length
 * @param cipher    Output buffer for ciphertext (plain_len bytes)
 * @param tag       Output buffer for authentication tag (16 bytes)
 * @return 0 on success, -1 on failure
 */
int ap2_chacha20_encrypt(const uint8_t *key, const uint8_t nonce[12],
                         const uint8_t *aad, size_t aad_len,
                         const uint8_t *plain, size_t plain_len,
                         uint8_t *cipher, uint8_t tag[16]);

/**
 * Decrypt with ChaCha20-Poly1305.
 * @return 0 on success, -1 on auth failure or other error
 */
int ap2_chacha20_decrypt(const uint8_t *key, const uint8_t nonce[12],
                         const uint8_t *aad, size_t aad_len,
                         const uint8_t *cipher, size_t cipher_len,
                         const uint8_t tag[16],
                         uint8_t *plain);

/* ====================================================================
 * Ed25519  (via vendored curve25519 lib)
 * ==================================================================== */

/**
 * Generate an Ed25519 keypair from a 32-byte secret seed.
 * @param sk  32-byte secret seed (input, will be clamped in-place)
 * @param pk  32-byte public key (output)
 * @param privkey  64-byte private key (output, = sk || pk)
 */
void ap2_ed25519_keygen(uint8_t sk[32], uint8_t pk[32],
                        uint8_t privkey[64]);

/**
 * Sign a message with Ed25519.
 * @param privkey  64-byte private key (from keygen)
 * @param msg      Message bytes
 * @param msg_len  Message length
 * @param sig      64-byte signature (output)
 */
void ap2_ed25519_sign(const uint8_t privkey[64],
                      const uint8_t *msg, size_t msg_len,
                      uint8_t sig[64]);

/**
 * Verify an Ed25519 signature.
 * @return 1 on success, 0 on failure
 */
int ap2_ed25519_verify(const uint8_t pk[32],
                       const uint8_t *msg, size_t msg_len,
                       const uint8_t sig[64]);

/* ====================================================================
 * X25519  (Curve25519 Diffie-Hellman, via vendored lib)
 * ==================================================================== */

/**
 * Generate an X25519 keypair.
 * @param sk  32-byte secret key (output, random)
 * @param pk  32-byte public key (output)
 */
void ap2_x25519_keygen(uint8_t sk[32], uint8_t pk[32]);

/**
 * Compute X25519 shared secret.
 * @param shared    32-byte shared secret (output)
 * @param my_sk     Our 32-byte secret key
 * @param their_pk  Their 32-byte public key
 */
void ap2_x25519_dh(uint8_t shared[32],
                   uint8_t my_sk[32],
                   const uint8_t their_pk[32]);

/* ====================================================================
 * AES-128-CTR
 * ==================================================================== */

/**
 * AES-128-CTR encrypt/decrypt in place.
 * @param key   16-byte AES key
 * @param iv    16-byte IV (modified in place as counter increments)
 * @param data  Data to encrypt/decrypt in place
 * @param len   Data length
 * @return 0 on success
 */
int ap2_aes128_ctr(const uint8_t key[16], uint8_t iv[16],
                   uint8_t *data, size_t len);

/* ====================================================================
 * Hash utilities
 * ==================================================================== */

/**
 * SHA-512 hash.
 * @param data  Input data
 * @param len   Data length
 * @param hash  64-byte output hash
 */
void ap2_sha512(const uint8_t *data, size_t len, uint8_t hash[64]);

#ifdef __cplusplus
}
#endif

#endif /* AIRPLAY2_CRYPTO_H */
