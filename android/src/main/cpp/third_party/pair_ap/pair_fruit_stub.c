/**
 * Stub for pair_fruit.c — provides the pair_client_fruit symbol
 * with NULL function pointers.  pair.c null-checks before calling,
 * so PAIR_CLIENT_FRUIT requests will fail gracefully.
 *
 * The real pair_fruit.c requires libplist which we don't vendor.
 * Fruit mode is only used for legacy Apple TV pairing (pre-HomeKit).
 */

#include "pair-internal.h"

struct pair_definition pair_client_fruit =
{
  .pair_setup_new        = NULL,
  .pair_setup_free       = NULL,
  .pair_setup_result     = NULL,
  .pair_setup_request1   = NULL,
  .pair_setup_request2   = NULL,
  .pair_setup_request3   = NULL,
  .pair_setup_response1  = NULL,
  .pair_setup_response2  = NULL,
  .pair_setup_response3  = NULL,
  .pair_verify_new       = NULL,
  .pair_verify_free      = NULL,
  .pair_verify_result    = NULL,
  .pair_verify_request1  = NULL,
  .pair_verify_request2  = NULL,
  .pair_verify_response1 = NULL,
  .pair_verify_response2 = NULL,
  .pair_cipher_new       = NULL,
  .pair_cipher_free      = NULL,
  .pair_encrypt          = NULL,
  .pair_decrypt          = NULL,
  .pair_state_get        = NULL,
  .pair_public_key_get   = NULL,
};
