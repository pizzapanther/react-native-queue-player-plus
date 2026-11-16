#!/usr/bin/env bash
# Cross-compile OpenSSL for Android NDK (arm64-v8a + x86_64).
# Run once; outputs go to prebuilt/openssl/<abi>/
set -euo pipefail

OPENSSL_VERSION="3.5.0"
OPENSSL_SHA256="344d0a79f1a9b08029b0744e2cc401a43f9c90acd1044d09a530b4885a8e9fc0"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PREBUILT_DIR="$SCRIPT_DIR/prebuilt/openssl"
WORK_DIR="/tmp/openssl-android-build"

# Detect NDK
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    # Try common locations
    for candidate in \
        "$HOME/Library/Android/sdk/ndk/27.1.12297006" \
        "$HOME/Library/Android/sdk/ndk/"* \
        "/usr/local/lib/android/sdk/ndk/"*; do
        if [[ -d "$candidate/toolchains" ]]; then
            ANDROID_NDK_ROOT="$candidate"
            break
        fi
    done
fi

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    echo "ERROR: ANDROID_NDK_ROOT not set and not found in standard locations"
    exit 1
fi

export ANDROID_NDK_ROOT
echo "Using NDK: $ANDROID_NDK_ROOT"

ANDROID_API=29  # matches project minSdkVersion

# Map our ABI names to OpenSSL target names
declare -A TARGETS=(
    [arm64-v8a]="android-arm64"
    [x86_64]="android-x86_64"
)

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# Download
TARBALL="openssl-${OPENSSL_VERSION}.tar.gz"
if [[ ! -f "$TARBALL" ]]; then
    echo "Downloading OpenSSL $OPENSSL_VERSION..."
    curl -fsSL "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/${TARBALL}" -o "$TARBALL"
fi

# Verify
echo "$OPENSSL_SHA256  $TARBALL" | shasum -a 256 -c -

for ABI in "${!TARGETS[@]}"; do
    TARGET="${TARGETS[$ABI]}"
    PREFIX="$PREBUILT_DIR/$ABI"
    echo ""
    echo "=== Building OpenSSL for $ABI ($TARGET) ==="

    rm -rf "openssl-${OPENSSL_VERSION}"
    tar xzf "$TARBALL"
    cd "openssl-${OPENSSL_VERSION}"

    export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"

    ./Configure "$TARGET" \
        -D__ANDROID_API__=$ANDROID_API \
        -DOPENSSL_SUPPRESS_DEPRECATED \
        no-shared \
        no-tests \
        no-ui-console \
        no-engine \
        no-comp \
        no-dso \
        no-asm \
        --prefix="$PREFIX" \
        --openssldir="$PREFIX"

    make -j"$(sysctl -n hw.ncpu)" build_libs 2>&1 | tail -5

    mkdir -p "$PREFIX/lib" "$PREFIX/include"
    cp libcrypto.a libssl.a "$PREFIX/lib/"
    cp -R include/openssl "$PREFIX/include/"

    echo "Installed $ABI → $PREFIX"
    cd "$WORK_DIR"
done

echo ""
echo "=== Done. Prebuilt OpenSSL at: $PREBUILT_DIR ==="
ls -la "$PREBUILT_DIR"/*/lib/*.a
