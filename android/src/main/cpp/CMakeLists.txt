cmake_minimum_required(VERSION 3.22.1)

project(raop LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- Paths ---
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(OPENSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/openssl/${ANDROID_ABI})

# --- OpenSSL (pre-built static libs) ---
add_library(openssl_crypto STATIC IMPORTED)
set_target_properties(openssl_crypto PROPERTIES
    IMPORTED_LOCATION ${OPENSSL_DIR}/lib/libcrypto.a)

add_library(openssl_ssl STATIC IMPORTED)
set_target_properties(openssl_ssl PROPERTIES
    IMPORTED_LOCATION ${OPENSSL_DIR}/lib/libssl.a)

# --- ALAC codec (Apple reference + wrapper) ---
add_library(alac STATIC
    ${THIRD_PARTY}/alac/codec/ALACBitUtilities.c
    ${THIRD_PARTY}/alac/codec/ALACDecoder.cpp
    ${THIRD_PARTY}/alac/codec/ALACEncoder.cpp
    ${THIRD_PARTY}/alac/codec/EndianPortable.c
    ${THIRD_PARTY}/alac/codec/ag_dec.c
    ${THIRD_PARTY}/alac/codec/ag_enc.c
    ${THIRD_PARTY}/alac/codec/dp_dec.c
    ${THIRD_PARTY}/alac/codec/dp_enc.c
    ${THIRD_PARTY}/alac/codec/matrix_dec.c
    ${THIRD_PARTY}/alac/codec/matrix_enc.c
    ${THIRD_PARTY}/alac/addons/alac_wrapper.cpp
)
target_include_directories(alac PUBLIC
    ${THIRD_PARTY}/alac/codec
    ${THIRD_PARTY}/alac/addons
)
# Apple's vendored ALAC reference codec carries benign unused-variable noise we
# don't patch upstream; silence just those two categories for this third-party
# target so a consumer's release build stays quiet. Our own code keeps -Wall.
target_compile_options(alac PRIVATE
    -Wno-unused-const-variable
    -Wno-unused-but-set-variable
)

# --- Curve25519 / Ed25519 (retained for libraop / AP1 only) ---
add_library(curve25519 STATIC
    ${THIRD_PARTY}/curve25519/source/curve25519_dh.c
    ${THIRD_PARTY}/curve25519/source/curve25519_mehdi.c
    ${THIRD_PARTY}/curve25519/source/curve25519_order.c
    ${THIRD_PARTY}/curve25519/source/curve25519_utils.c
    ${THIRD_PARTY}/curve25519/source/custom_blind.c
    ${THIRD_PARTY}/curve25519/source/ed25519_sign.c
    ${THIRD_PARTY}/curve25519/source/ed25519_verify.c
    # sha512.c excluded — use OpenSSL's SHA512 to avoid duplicate symbols
)
target_include_directories(curve25519 PUBLIC
    ${THIRD_PARTY}/curve25519/source
    ${THIRD_PARTY}/curve25519/include
    ${THIRD_PARTY}/openssl/include
)

# --- libsodium (pre-built static lib for Ed25519/X25519) ---
set(SODIUM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/libsodium/${ANDROID_ABI})
add_library(sodium STATIC IMPORTED)
set_target_properties(sodium PROPERTIES
    IMPORTED_LOCATION ${SODIUM_DIR}/lib/libsodium.a)

# --- OwnTone pair_ap (vendored pairing/crypto library) ---
set(PAIR_AP_DIR ${THIRD_PARTY}/pair_ap)
add_library(pair_ap STATIC
    ${PAIR_AP_DIR}/pair.c
    ${PAIR_AP_DIR}/pair-tlv.c
    ${PAIR_AP_DIR}/pair_homekit.c
    ${PAIR_AP_DIR}/pair_fruit_stub.c
)
target_compile_definitions(pair_ap PRIVATE
    CONFIG_OPENSSL=1
    # pair.c uses OpenSSL's legacy SHA*_Final API, deprecated in OpenSSL 3.0.
    # OPENSSL_SUPPRESS_DEPRECATED is OpenSSL's own opt-in for intentional
    # legacy-API use; the raop + airplay2 targets already set it.
    OPENSSL_SUPPRESS_DEPRECATED
)
target_include_directories(pair_ap PUBLIC
    ${PAIR_AP_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/libsodium/include
    ${THIRD_PARTY}/openssl/include
)
target_link_libraries(pair_ap
    sodium
    openssl_crypto
)

# --- libraop (client-only) + crosstools + dmap-parser ---
add_library(raop SHARED
    # JNI entry point
    airplay_jni.cpp
    # libraop client
    ${THIRD_PARTY}/libraop/raop_client.c
    ${THIRD_PARTY}/libraop/rtsp_client.c
    ${THIRD_PARTY}/libraop/aes.c
    ${THIRD_PARTY}/libraop/aes_ctr.c
    ${THIRD_PARTY}/libraop/alac.c
    # crosstools
    ${THIRD_PARTY}/crosstools/cross_log.c
    ${THIRD_PARTY}/crosstools/cross_net.c
    ${THIRD_PARTY}/crosstools/cross_ssl.c
    ${THIRD_PARTY}/crosstools/cross_util.c
    ${THIRD_PARTY}/crosstools/cross_thread.c
    ${THIRD_PARTY}/crosstools/platform.c
    # dmap-parser
    ${THIRD_PARTY}/dmap-parser/dmap_parser.c
)

target_compile_definitions(raop PRIVATE
    NDEBUG
    _GNU_SOURCE
    SSL_STATIC_LIB
    OPENSSL_SUPPRESS_DEPRECATED
)

target_include_directories(raop PRIVATE
    ${THIRD_PARTY}/libraop
    ${THIRD_PARTY}/crosstools
    ${THIRD_PARTY}/dmap-parser
    ${THIRD_PARTY}/alac/addons
    ${THIRD_PARTY}/alac/codec
    ${THIRD_PARTY}/curve25519/include
    ${THIRD_PARTY}/curve25519/source
    ${THIRD_PARTY}/openssl/include
)

# 16KB ELF page alignment (Android 15+)
target_link_options(raop PRIVATE
    "-Wl,-z,max-page-size=16384"
)

find_library(log-lib log)

target_link_libraries(raop
    alac
    curve25519
    openssl_crypto
    openssl_ssl
    ${log-lib}
)

# --- libairplay2 (AirPlay 2 native sender) ---
add_library(airplay2 SHARED
    # JNI entry point
    airplay2_jni.cpp
    # Session lifecycle
    airplay2_session.c
    # Crypto primitives
    airplay2_crypto.c
    # TLV8 encoding
    airplay2_tlv.c
    # Binary plist
    airplay2_plist.c
    # DMAP metadata
    airplay2_dmap.c
    # RTSP client
    airplay2_rtsp.c
    # Pairing + connection establishment
    airplay2_pair.c
    # Session control (SETUP, RECORD, volume)
    airplay2_control.c
    # Audio pipeline (drain thread, ALAC encode, encrypt, RTP send)
    airplay2_audio.c
    # PTP master clock (timing sync for receiver)
    airplay2_ptp.c
    # NTP timing service (alternative to PTP, works without privileged ports)
    airplay2_ntp.c
)

target_compile_definitions(airplay2 PRIVATE
    NDEBUG
    _GNU_SOURCE
    SSL_STATIC_LIB
    OPENSSL_SUPPRESS_DEPRECATED
)

target_include_directories(airplay2 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${THIRD_PARTY}/alac/addons
    ${THIRD_PARTY}/alac/codec
    ${THIRD_PARTY}/pair_ap
    ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/libsodium/include
    ${THIRD_PARTY}/openssl/include
    ${THIRD_PARTY}/dmap-parser
)

# 16KB ELF page alignment (Android 15+)
target_link_options(airplay2 PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(airplay2
    alac
    pair_ap
    sodium
    openssl_crypto
    openssl_ssl
    ${log-lib}
)

# --- Signalsmith Stretch (music-mode time-stretch JNI) ---
# Header-only float DSP. Compiled -O3 PER TARGET regardless of the app build
# type: upstream warns Debug builds are ~10x slower, which for a real-time audio
# callback is the difference between meeting and missing the deadline. The
# per-target -O3 is appended after the gradle-level cppFlags, so the last -O
# wins. We deliberately do NOT add -ffast-math: it reorders FP ops + relaxes
# denormal handling, which can subtly alter the phase-vocoder FFT output, and
# upstream asks only for -O3.
add_library(signalsmith_stretch SHARED
    stretch_jni.cpp
)
target_include_directories(signalsmith_stretch PRIVATE
    ${THIRD_PARTY}/signalsmith/include
)
target_compile_options(signalsmith_stretch PRIVATE
    -O3
)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(signalsmith_stretch PRIVATE -mfpu=neon)
endif()
# 16KB ELF page alignment (Android 15+), matching the other native libs.
target_link_options(signalsmith_stretch PRIVATE
    "-Wl,-z,max-page-size=16384"
)
target_link_libraries(signalsmith_stretch ${log-lib})
