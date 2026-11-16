/**
 * AirPlay 2 cryptographic primitives — implementation.
 *
 * SRP6a: Custom implementation using OpenSSL BN (BigNum) arithmetic,
 *   following RFC 5054 with 3072-bit prime, g=5, SHA-512.
 *   Based on the same approach as OwnTone's csrp adaptation.
 *
 * HKDF: OpenSSL EVP_PKEY HKDF (extract+expand, SHA-512).
 * ChaCha20-Poly1305: OpenSSL EVP_CIPHER.
 * Ed25519/X25519: libsodium (RFC 8032 compliant, Apple-compatible).
 * AES-128-CTR: OpenSSL EVP_CIPHER.
 */

#include "airplay2_crypto.h"

#include <stdlib.h>
#include <string.h>

#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/rand.h>
#include <openssl/kdf.h>

#include <sodium.h>

/* ====================================================================
 * SRP6a constants — RFC 5054 3072-bit group (N, g=5, SHA-512)
 *
 * These are the exact parameters used by AirPlay 2.
 * ==================================================================== */

/* RFC 5054 3072-bit prime N (big-endian hex). */
static const char *SRP_N_HEX =
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C"
    "7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A08"
    "64D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946"
    "E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

/* Generator g = 5. */
static const unsigned long SRP_G = 5;

/* ---- SRP6a client context ---- */
struct ap2_srp {
    BIGNUM *N;      /* Prime */
    BIGNUM *g;      /* Generator */
    BIGNUM *a;      /* Client private ephemeral */
    BIGNUM *A;      /* Client public ephemeral */
    BIGNUM *B;      /* Server public ephemeral */
    BIGNUM *S;      /* Shared secret */
    BIGNUM *k;      /* k = H(N, g)  */
    BIGNUM *x;      /* x = H(salt, H(user:pass)) */
    BIGNUM *u;      /* u = H(A, B) */
    uint8_t *K;     /* Session key = H(S) */
    size_t K_len;
    uint8_t *M1;    /* Client proof */
    size_t M1_len;
    uint8_t *M2;    /* Expected server proof */
    size_t M2_len;
    char *username;
    char *pin;
};

/* ---- SHA-512 utility for BN ---- */
static void hash_bn(uint8_t out[64], int count, ...) {
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    EVP_DigestInit_ex(ctx, EVP_sha512(), NULL);

    va_list ap;
    va_start(ap, count);
    for (int i = 0; i < count; i++) {
        BIGNUM *bn = va_arg(ap, BIGNUM *);
        int len = BN_num_bytes(bn);
        uint8_t *buf = (uint8_t *)malloc(len);
        BN_bn2bin(bn, buf);
        EVP_DigestUpdate(ctx, buf, len);
        free(buf);
    }
    va_end(ap);

    unsigned int olen;
    EVP_DigestFinal_ex(ctx, out, &olen);
    EVP_MD_CTX_free(ctx);
}

/* Hash raw bytes. */
static void hash_bytes(uint8_t out[64], const uint8_t *data, size_t len) {
    SHA512(data, len, out);
}

/* Hash of N zero-padded BN to match RFC 5054 PAD(). */
static void hash_bn_padded(uint8_t out[64], int pad_len,
                           int count, ...) {
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    EVP_DigestInit_ex(ctx, EVP_sha512(), NULL);

    va_list ap;
    va_start(ap, count);
    for (int i = 0; i < count; i++) {
        BIGNUM *bn = va_arg(ap, BIGNUM *);
        int bn_len = BN_num_bytes(bn);
        uint8_t *buf = (uint8_t *)calloc(1, pad_len);
        /* Right-justify in pad_len-byte buffer. */
        BN_bn2bin(bn, buf + (pad_len - bn_len));
        EVP_DigestUpdate(ctx, buf, pad_len);
        free(buf);
    }
    va_end(ap);

    unsigned int olen;
    EVP_DigestFinal_ex(ctx, out, &olen);
    EVP_MD_CTX_free(ctx);
}

#include <stdarg.h>

ap2_srp_t *ap2_srp_new(const char *username, const char *pin) {
    ap2_srp_t *srp = (ap2_srp_t *)calloc(1, sizeof(*srp));
    if (!srp) return NULL;

    srp->username = strdup(username);
    srp->pin = strdup(pin);

    /* Set up N and g. */
    srp->N = BN_new();
    BN_hex2bn(&srp->N, SRP_N_HEX);
    srp->g = BN_new();
    BN_set_word(srp->g, SRP_G);

    /* Generate random private ephemeral a (256 bits). */
    srp->a = BN_new();
    BN_rand(srp->a, 256, BN_RAND_TOP_ONE, BN_RAND_BOTTOM_ANY);

    /* Compute A = g^a mod N. */
    srp->A = BN_new();
    BN_CTX *ctx = BN_CTX_new();
    BN_mod_exp(srp->A, srp->g, srp->a, srp->N, ctx);
    BN_CTX_free(ctx);

    /* Compute k = H(N, g) — pad g to same length as N. */
    int N_len = BN_num_bytes(srp->N);
    uint8_t h_k[64];
    hash_bn_padded(h_k, N_len, 2, srp->N, srp->g);
    srp->k = BN_bin2bn(h_k, 64, NULL);

    return srp;
}

int ap2_srp_start(ap2_srp_t *srp,
                  const uint8_t *salt, size_t salt_len,
                  const uint8_t *B_bytes, size_t B_len,
                  uint8_t **A_out, size_t *A_len,
                  uint8_t **M1_out, size_t *M1_len) {
    if (!srp) return -1;

    BN_CTX *ctx = BN_CTX_new();
    int N_len = BN_num_bytes(srp->N);

    /* Store B. */
    srp->B = BN_bin2bn(B_bytes, (int)B_len, NULL);

    /* Safety check: B mod N != 0. */
    BIGNUM *tmp = BN_new();
    BN_nnmod(tmp, srp->B, srp->N, ctx);
    if (BN_is_zero(tmp)) {
        BN_free(tmp);
        BN_CTX_free(ctx);
        return -1;
    }
    BN_free(tmp);

    /* x = H(salt || H(username:pin)). */
    {
        /* inner = H(username:pin) */
        EVP_MD_CTX *md = EVP_MD_CTX_new();
        uint8_t inner[64];
        unsigned int olen;
        EVP_DigestInit_ex(md, EVP_sha512(), NULL);
        EVP_DigestUpdate(md, srp->username, strlen(srp->username));
        EVP_DigestUpdate(md, ":", 1);
        EVP_DigestUpdate(md, srp->pin, strlen(srp->pin));
        EVP_DigestFinal_ex(md, inner, &olen);

        /* x = H(salt || inner) */
        uint8_t h_x[64];
        EVP_DigestInit_ex(md, EVP_sha512(), NULL);
        EVP_DigestUpdate(md, salt, salt_len);
        EVP_DigestUpdate(md, inner, 64);
        EVP_DigestFinal_ex(md, h_x, &olen);
        EVP_MD_CTX_free(md);

        srp->x = BN_bin2bn(h_x, 64, NULL);
    }

    /* u = H(PAD(A) || PAD(B)). */
    {
        uint8_t h_u[64];
        hash_bn_padded(h_u, N_len, 2, srp->A, srp->B);
        srp->u = BN_bin2bn(h_u, 64, NULL);
    }

    /* S = (B - k * g^x)^(a + u*x) mod N. */
    {
        BIGNUM *gx = BN_new();
        BIGNUM *kgx = BN_new();
        BIGNUM *diff = BN_new();
        BIGNUM *ux = BN_new();
        BIGNUM *aux = BN_new();

        /* g^x mod N */
        BN_mod_exp(gx, srp->g, srp->x, srp->N, ctx);
        /* k * g^x mod N */
        BN_mod_mul(kgx, srp->k, gx, srp->N, ctx);
        /* B - k*g^x mod N */
        BN_mod_sub(diff, srp->B, kgx, srp->N, ctx);
        /* u * x */
        BN_mul(ux, srp->u, srp->x, ctx);
        /* a + u*x */
        BN_add(aux, srp->a, ux);
        /* S = diff ^ aux mod N */
        srp->S = BN_new();
        BN_mod_exp(srp->S, diff, aux, srp->N, ctx);

        BN_free(gx);
        BN_free(kgx);
        BN_free(diff);
        BN_free(ux);
        BN_free(aux);
    }

    /* K = H(S) — the session key. */
    {
        uint8_t h_s[64];
        hash_bn(h_s, 1, srp->S);
        srp->K_len = 64;
        srp->K = (uint8_t *)malloc(64);
        memcpy(srp->K, h_s, 64);
    }

    /* M1 = H(H(N) XOR H(g) || H(username) || salt || A || B || K). */
    {
        uint8_t h_N[64], h_g[64], h_xor[64], h_user[64];

        /* H(N) */
        hash_bn(h_N, 1, srp->N);
        /* H(g) */
        hash_bn(h_g, 1, srp->g);
        /* H(N) XOR H(g) */
        for (int i = 0; i < 64; i++) h_xor[i] = h_N[i] ^ h_g[i];
        /* H(username) */
        hash_bytes(h_user, (const uint8_t *)srp->username,
                   strlen(srp->username));

        EVP_MD_CTX *md = EVP_MD_CTX_new();
        unsigned int olen;
        EVP_DigestInit_ex(md, EVP_sha512(), NULL);
        EVP_DigestUpdate(md, h_xor, 64);
        EVP_DigestUpdate(md, h_user, 64);
        EVP_DigestUpdate(md, salt, salt_len);

        /* PAD(A) */
        int a_len = BN_num_bytes(srp->A);
        uint8_t *a_buf = (uint8_t *)calloc(1, N_len);
        BN_bn2bin(srp->A, a_buf + (N_len - a_len));
        EVP_DigestUpdate(md, a_buf, N_len);
        free(a_buf);

        /* PAD(B) */
        int b_len = BN_num_bytes(srp->B);
        uint8_t *b_buf = (uint8_t *)calloc(1, N_len);
        BN_bn2bin(srp->B, b_buf + (N_len - b_len));
        EVP_DigestUpdate(md, b_buf, N_len);
        free(b_buf);

        EVP_DigestUpdate(md, srp->K, srp->K_len);

        uint8_t m1[64];
        EVP_DigestFinal_ex(md, m1, &olen);
        EVP_MD_CTX_free(md);

        srp->M1_len = 64;
        srp->M1 = (uint8_t *)malloc(64);
        memcpy(srp->M1, m1, 64);
    }

    /* M2 = H(A || M1 || K) — expected server proof. */
    {
        EVP_MD_CTX *md = EVP_MD_CTX_new();
        unsigned int olen;
        EVP_DigestInit_ex(md, EVP_sha512(), NULL);

        int a_len = BN_num_bytes(srp->A);
        uint8_t *a_buf = (uint8_t *)calloc(1, N_len);
        BN_bn2bin(srp->A, a_buf + (N_len - a_len));
        EVP_DigestUpdate(md, a_buf, N_len);
        free(a_buf);

        EVP_DigestUpdate(md, srp->M1, srp->M1_len);
        EVP_DigestUpdate(md, srp->K, srp->K_len);

        uint8_t m2[64];
        EVP_DigestFinal_ex(md, m2, &olen);
        EVP_MD_CTX_free(md);

        srp->M2_len = 64;
        srp->M2 = (uint8_t *)malloc(64);
        memcpy(srp->M2, m2, 64);
    }

    BN_CTX_free(ctx);

    /* Output A. */
    *A_len = BN_num_bytes(srp->A);
    *A_out = (uint8_t *)malloc(*A_len);
    BN_bn2bin(srp->A, *A_out);

    /* Output M1. */
    *M1_len = srp->M1_len;
    *M1_out = (uint8_t *)malloc(*M1_len);
    memcpy(*M1_out, srp->M1, *M1_len);

    return 0;
}

int ap2_srp_verify(ap2_srp_t *srp, const uint8_t *M2, size_t M2_len) {
    if (!srp || !srp->M2 || M2_len != srp->M2_len)
        return -1;
    return memcmp(M2, srp->M2, M2_len) == 0 ? 0 : -1;
}

int ap2_srp_session_key(ap2_srp_t *srp, uint8_t **key_out, size_t *key_len) {
    if (!srp || !srp->K) return -1;
    *key_len = srp->K_len;
    *key_out = (uint8_t *)malloc(*key_len);
    memcpy(*key_out, srp->K, *key_len);
    return 0;
}

void ap2_srp_free(ap2_srp_t *srp) {
    if (!srp) return;
    BN_free(srp->N);
    BN_free(srp->g);
    BN_free(srp->a);
    BN_free(srp->A);
    BN_free(srp->B);
    BN_free(srp->S);
    BN_free(srp->k);
    BN_free(srp->x);
    BN_free(srp->u);
    free(srp->K);
    free(srp->M1);
    free(srp->M2);
    free(srp->username);
    free(srp->pin);
    free(srp);
}

/* ====================================================================
 * HKDF-SHA-512
 * ==================================================================== */

int ap2_hkdf_sha512(const uint8_t *ikm, size_t ikm_len,
                    const char *salt, const char *info,
                    uint8_t *out, size_t out_len) {
    EVP_PKEY_CTX *pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, NULL);
    if (!pctx) return -1;

    int ret = -1;
    if (EVP_PKEY_derive_init(pctx) <= 0) goto done;
    if (EVP_PKEY_CTX_set_hkdf_md(pctx, EVP_sha512()) <= 0) goto done;
    if (EVP_PKEY_CTX_set1_hkdf_salt(pctx,
            (const unsigned char *)salt, strlen(salt)) <= 0) goto done;
    if (EVP_PKEY_CTX_set1_hkdf_key(pctx, ikm, ikm_len) <= 0) goto done;
    if (EVP_PKEY_CTX_add1_hkdf_info(pctx,
            (const unsigned char *)info, strlen(info)) <= 0) goto done;
    if (EVP_PKEY_derive(pctx, out, &out_len) <= 0) goto done;
    ret = 0;

done:
    EVP_PKEY_CTX_free(pctx);
    return ret;
}

/* ====================================================================
 * ChaCha20-Poly1305
 * ==================================================================== */

int ap2_chacha20_encrypt(const uint8_t *key, const uint8_t nonce[12],
                         const uint8_t *aad, size_t aad_len,
                         const uint8_t *plain, size_t plain_len,
                         uint8_t *cipher, uint8_t tag[16]) {
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int ret = -1;
    int len;

    if (EVP_EncryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, key, nonce) != 1)
        goto done;

    /* AAD — if present. */
    if (aad && aad_len > 0) {
        if (EVP_EncryptUpdate(ctx, NULL, &len, aad, (int)aad_len) != 1)
            goto done;
    }

    /* Encrypt plaintext. */
    if (EVP_EncryptUpdate(ctx, cipher, &len, plain, (int)plain_len) != 1)
        goto done;

    if (EVP_EncryptFinal_ex(ctx, cipher + len, &len) != 1)
        goto done;

    /* Extract tag. */
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, 16, tag) != 1)
        goto done;

    ret = 0;

done:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
}

int ap2_chacha20_decrypt(const uint8_t *key, const uint8_t nonce[12],
                         const uint8_t *aad, size_t aad_len,
                         const uint8_t *cipher, size_t cipher_len,
                         const uint8_t tag[16],
                         uint8_t *plain) {
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int ret = -1;
    int len;

    if (EVP_DecryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, key, nonce) != 1)
        goto done;

    /* Set tag before decryption. */
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, 16,
            (void *)tag) != 1)
        goto done;

    /* AAD. */
    if (aad && aad_len > 0) {
        if (EVP_DecryptUpdate(ctx, NULL, &len, aad, (int)aad_len) != 1)
            goto done;
    }

    /* Decrypt. */
    if (EVP_DecryptUpdate(ctx, plain, &len, cipher, (int)cipher_len) != 1)
        goto done;

    /* Verify tag. */
    if (EVP_DecryptFinal_ex(ctx, plain + len, &len) != 1)
        goto done;

    ret = 0;

done:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
}

/* ====================================================================
 * Ed25519 (libsodium — RFC 8032 compliant, Apple-compatible)
 * ==================================================================== */

void ap2_ed25519_keygen(uint8_t sk[32], uint8_t pk[32],
                        uint8_t privkey[64]) {
    /*
     * libsodium's "secret key" is 64 bytes: seed[32] || pk[32].
     * The 32-byte sk input is the seed.
     */
    crypto_sign_seed_keypair(pk, privkey, sk);
}

void ap2_ed25519_sign(const uint8_t privkey[64],
                      const uint8_t *msg, size_t msg_len,
                      uint8_t sig[64]) {
    crypto_sign_detached(sig, NULL, msg, msg_len, privkey);
}

int ap2_ed25519_verify(const uint8_t pk[32],
                       const uint8_t *msg, size_t msg_len,
                       const uint8_t sig[64]) {
    /* libsodium returns 0 on success; our API returns 1 on success. */
    return crypto_sign_verify_detached(sig, msg, msg_len, pk) == 0 ? 1 : 0;
}

/* ====================================================================
 * X25519 (libsodium — Curve25519 Diffie-Hellman)
 * ==================================================================== */

void ap2_x25519_keygen(uint8_t sk[32], uint8_t pk[32]) {
    randombytes_buf(sk, 32);
    crypto_scalarmult_base(pk, sk);
}

void ap2_x25519_dh(uint8_t shared[32],
                   uint8_t my_sk[32],
                   const uint8_t their_pk[32]) {
    (void)crypto_scalarmult(shared, my_sk, their_pk);
}

/* ====================================================================
 * AES-128-CTR
 * ==================================================================== */

int ap2_aes128_ctr(const uint8_t key[16], uint8_t iv[16],
                   uint8_t *data, size_t len) {
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int ret = -1;
    int outlen;

    if (EVP_EncryptInit_ex(ctx, EVP_aes_128_ctr(), NULL, key, iv) != 1)
        goto done;

    if (EVP_EncryptUpdate(ctx, data, &outlen, data, (int)len) != 1)
        goto done;

    if (EVP_EncryptFinal_ex(ctx, data + outlen, &outlen) != 1)
        goto done;

    ret = 0;

done:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
}

/* ====================================================================
 * SHA-512
 * ==================================================================== */

void ap2_sha512(const uint8_t *data, size_t len, uint8_t hash[64]) {
    SHA512(data, len, hash);
}
