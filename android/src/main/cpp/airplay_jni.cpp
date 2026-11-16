#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <atomic>

extern "C" {
#include "cross_log.h"
#include "cross_ssl.h"
#include "raop_client.h"
}

// platform.h defines min/max macros — avoid MIN collision
#ifndef MIN
#define MIN(a, b) (((a) < (b)) ? (a) : (b))
#endif

#define TAG "RaopJNI"
#define DRAIN_TAG "RaopDrain"

// Log-level globals normally defined in the CLI app (cliraop.c).
log_level raop_loglevel = lDEBUG;
log_level util_loglevel = lDEBUG;

// ---------------------------------------------------------------------------
// Ring buffer + drain thread session wrapper
// ---------------------------------------------------------------------------
// ExoPlayer pushes variable-size PCM buffers on its render thread.
// libraop expects a dedicated thread polling accept_frames() and pushing
// exactly chunk_len frames per send_chunk(). This wrapper bridges the two
// models with a lock-free-ish ring buffer and a background drain thread.

struct JniSession {
    struct raopcl_s *raop;

    // -- Ring buffer --
    // Standard RAOP chunk: 352 stereo 16-bit frames = 1408 bytes
    static constexpr int CHUNK_LEN   = 352;
    static constexpr int CHUNK_BYTES = CHUNK_LEN * 4;   // stereo S16LE
    static constexpr int RING_CHUNKS = 128;              // ~1s buffer
    static constexpr size_t RING_SIZE = CHUNK_BYTES * RING_CHUNKS;

    uint8_t ring[RING_SIZE];
    size_t  write_pos;   // monotonically increasing
    size_t  read_pos;    // monotonically increasing
    pthread_mutex_t mutex;

    size_t available() const { return write_pos - read_pos; }
    size_t free_space() const { return RING_SIZE - available(); }

    void ring_write(const uint8_t *data, size_t len) {
        size_t idx = write_pos % RING_SIZE;
        size_t first = MIN(len, RING_SIZE - idx);
        memcpy(ring + idx, data, first);
        if (first < len) memcpy(ring, data + first, len - first);
        write_pos += len;
    }

    void ring_read(uint8_t *data, size_t len) {
        size_t idx = read_pos % RING_SIZE;
        size_t first = MIN(len, RING_SIZE - idx);
        memcpy(data, ring + idx, first);
        if (first < len) memcpy(data + first, ring, len - first);
        read_pos += len;
    }

    // -- Drain thread --
    pthread_t thread;
    // Stop flag: set on the JNI/control thread, polled in the drain thread's
    // loop. Atomic so the stop is a data-race-free, promptly-visible read
    // (the ring buffer has its own mutex; this only gates the loop).
    std::atomic<bool> drain_running{false};

    // -- Tracking --
    std::atomic<int64_t> bytes_sent{0};    // total PCM bytes sent to RAOP
    std::atomic<int64_t> chunks_sent{0};   // total chunks sent

    JniSession() : raop(nullptr), write_pos(0), read_pos(0),
                   thread{} {
        memset(ring, 0, sizeof(ring));
        pthread_mutex_init(&mutex, nullptr);
    }

    ~JniSession() {
        pthread_mutex_destroy(&mutex);
    }
};

// ---------------------------------------------------------------------------
// Drain thread
// ---------------------------------------------------------------------------
static void *drain_thread_func(void *arg) {
    auto *s = static_cast<JniSession *>(arg);

    __android_log_print(ANDROID_LOG_INFO, DRAIN_TAG,
        "Drain thread started, chunk_len=%d chunk_bytes=%d ring_size=%zu",
        JniSession::CHUNK_LEN, JniSession::CHUNK_BYTES, JniSession::RING_SIZE);

    uint8_t chunk[JniSession::CHUNK_BYTES];
    int accept_spins = 0;
    int drain_cycles = 0;

    while (s->drain_running) {
        // Phase 1: Wait for enough data in the ring buffer
        pthread_mutex_lock(&s->mutex);
        size_t avail = s->available();
        if (avail < JniSession::CHUNK_BYTES) {
            pthread_mutex_unlock(&s->mutex);
            usleep(2000);  // 2ms — wait for producer
            continue;
        }
        // Dequeue one chunk
        s->ring_read(chunk, JniSession::CHUNK_BYTES);
        pthread_mutex_unlock(&s->mutex);

        // Phase 2: Wait for libraop to accept (pacing)
        accept_spins = 0;
        while (s->drain_running && !raopcl_accept_frames(s->raop)) {
            accept_spins++;
            if (accept_spins == 10 || accept_spins == 100 ||
                accept_spins % 5000 == 0) {
                __android_log_print(ANDROID_LOG_DEBUG, DRAIN_TAG,
                    "accept_frames spinning: %d iterations, state=%d",
                    accept_spins, raopcl_state(s->raop));
            }
            usleep(1000);  // 1ms between polls
        }
        if (!s->drain_running) break;

        // Phase 3: Send the chunk
        uint64_t playtime = 0;
        bool ok = raopcl_send_chunk(s->raop, chunk, JniSession::CHUNK_LEN, &playtime);

        if (ok) {
            s->bytes_sent.fetch_add(JniSession::CHUNK_BYTES, std::memory_order_relaxed);
            int64_t total_chunks = s->chunks_sent.fetch_add(1, std::memory_order_relaxed) + 1;

            if (total_chunks <= 5 || total_chunks % 500 == 0) {
                __android_log_print(ANDROID_LOG_INFO, DRAIN_TAG,
                    "chunk #%lld sent (%d accept spins), bytes_sent=%lld",
                    (long long)total_chunks, accept_spins,
                    (long long)s->bytes_sent.load(std::memory_order_relaxed));
            }
        } else {
            __android_log_print(ANDROID_LOG_ERROR, DRAIN_TAG,
                "send_chunk failed at chunk #%lld",
                (long long)s->chunks_sent.load(std::memory_order_relaxed));
        }

        drain_cycles++;
    }

    __android_log_print(ANDROID_LOG_INFO, DRAIN_TAG,
        "Drain thread exiting after %d cycles, %lld chunks sent",
        drain_cycles, (long long)s->chunks_sent.load(std::memory_order_relaxed));

    return nullptr;
}

// ---------------------------------------------------------------------------
// Handle helpers
// ---------------------------------------------------------------------------
static JniSession *toJni(jlong handle) {
    return reinterpret_cast<JniSession *>(handle);
}

static struct raopcl_s *toRaop(jlong handle) {
    auto *js = toJni(handle);
    return js ? js->raop : nullptr;
}

// ---------------------------------------------------------------------------
// JNI methods
// ---------------------------------------------------------------------------

/**
 * Open a RAOP session.  Returns a handle (pointer-as-long) or 0 on failure.
 *
 * @param codec  0=PCM, 1=ALAC_RAW, 2=ALAC, 3=AAC, 4=AAC_ELC
 * @param crypto 0=CLEAR, 1=RSA, 2=FAIRPLAY, 3=MFISAP, 4=FAIRPLAYSAP
 */
static jlong nativeOpen(JNIEnv *env, jclass,
                        jstring jHost, jint port,
                        jint codec, jint frameLen, jint latencyFrames,
                        jint crypto, jboolean auth,
                        jint sampleRate, jint sampleSize, jint channels,
                        jfloat volume) {
    (void) jHost;
    (void) port;

    struct in_addr local_addr{};
    local_addr.s_addr = INADDR_ANY;

    struct raopcl_s *raop = raopcl_create(
        local_addr,
        0, 0,
        nullptr, nullptr,
        static_cast<raop_codec_t>(codec),
        frameLen,
        latencyFrames,
        static_cast<raop_crypto_t>(crypto),
        auth == JNI_TRUE,
        nullptr, nullptr,
        nullptr, (char *) "0,1,2",
        sampleRate, sampleSize, channels,
        volume
    );

    if (!raop) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "raopcl_create failed");
        return 0;
    }

    auto *session = new JniSession();
    session->raop = raop;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "Session created: raop=%p session=%p", raop, session);

    return reinterpret_cast<jlong>(session);
}

static jboolean nativeConnect(JNIEnv *env, jclass,
                              jlong handle, jstring jHost, jint port) {
    auto *s = toJni(handle);
    if (!s || !s->raop) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeConnect: null session");
        return JNI_FALSE;
    }

    const char *host = env->GetStringUTFChars(jHost, nullptr);
    if (!host) return JNI_FALSE;

    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeConnect: %s:%d", host, port);

    struct in_addr addr{};
    inet_pton(AF_INET, host, &addr);
    env->ReleaseStringUTFChars(jHost, host);

    bool ok = raopcl_connect(s->raop, addr, static_cast<uint16_t>(port), true);
    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
        "raopcl_connect returned %s", ok ? "true" : "false");

    if (ok) {
        // Prime libraop state machine: accept_frames needs flushing=true
        // to transition FLUSHED→STREAMING. nativeStop sets flushing=true.
        raopcl_stop(s->raop);

        __android_log_print(ANDROID_LOG_INFO, TAG,
            "Starting drain thread, state=%d", raopcl_state(s->raop));

        // Reset ring buffer and counters
        pthread_mutex_lock(&s->mutex);
        s->write_pos = 0;
        s->read_pos = 0;
        s->bytes_sent.store(0, std::memory_order_relaxed);
        s->chunks_sent.store(0, std::memory_order_relaxed);
        pthread_mutex_unlock(&s->mutex);

        // Start drain thread
        s->drain_running = true;
        pthread_create(&s->thread, nullptr, drain_thread_func, s);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeDisconnect(JNIEnv *, jclass, jlong handle) {
    auto *s = toJni(handle);
    if (!s || !s->raop) return JNI_FALSE;

    // Stop drain thread first
    if (s->drain_running) {
        s->drain_running = false;
        pthread_join(s->thread, nullptr);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Drain thread joined");
    }

    return raopcl_disconnect(s->raop) ? JNI_TRUE : JNI_FALSE;
}

static void nativeDestroy(JNIEnv *, jclass, jlong handle) {
    auto *s = toJni(handle);
    if (!s) return;

    // Ensure drain thread is stopped
    if (s->drain_running) {
        s->drain_running = false;
        pthread_join(s->thread, nullptr);
    }

    if (s->raop) raopcl_destroy(s->raop);
    delete s;
}

/**
 * Feed PCM audio data into the ring buffer. The drain thread will
 * pace and send it via accept_frames/send_chunk.
 *
 * @return number of bytes consumed (== length if accepted, 0 if buffer full)
 */
static jint nativeFeedPcm(JNIEnv *env, jclass,
                          jlong handle,
                          jbyteArray data, jint offset, jint length) {
    auto *s = toJni(handle);
    if (!s || !s->drain_running) return 0;

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (!buf) return 0;

    pthread_mutex_lock(&s->mutex);

    size_t free = s->free_space();
    if (free < static_cast<size_t>(length)) {
        pthread_mutex_unlock(&s->mutex);
        env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
        return 0;  // Back-pressure: ring buffer full
    }

    s->ring_write(reinterpret_cast<uint8_t *>(buf + offset), length);
    pthread_mutex_unlock(&s->mutex);

    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return length;
}

/**
 * Total PCM bytes successfully sent to the RAOP receiver.
 * Used by AirPlaySink for position tracking.
 */
static jlong nativeBytesSent(JNIEnv *, jclass, jlong handle) {
    auto *s = toJni(handle);
    return s ? s->bytes_sent.load(std::memory_order_relaxed) : 0;
}

/**
 * Bytes waiting in the ring buffer (not yet sent to the RAOP receiver).
 * Used by AirPlaySink.hasPendingData() so ExoPlayer knows when a track
 * has truly finished rendering before advancing to the next one.
 */
static jint nativeRingAvailable(JNIEnv *, jclass, jlong handle) {
    auto *s = toJni(handle);
    if (!s) return 0;
    pthread_mutex_lock(&s->mutex);
    size_t avail = s->available();
    pthread_mutex_unlock(&s->mutex);
    return static_cast<jint>(avail);
}

// -- Transport control --

static void nativePause(JNIEnv *, jclass, jlong handle) {
    auto *r = toRaop(handle);
    if (r) raopcl_pause(r);
}

static void nativeStop(JNIEnv *, jclass, jlong handle) {
    auto *s = toJni(handle);
    if (!s || !s->raop) return;

    // Stop drain thread
    if (s->drain_running) {
        s->drain_running = false;
        pthread_join(s->thread, nullptr);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Drain thread stopped via nativeStop");
    }

    raopcl_stop(s->raop);
}

static jboolean nativeFlush(JNIEnv *, jclass, jlong handle) {
    auto *r = toRaop(handle);
    return (r && raopcl_flush(r)) ? JNI_TRUE : JNI_FALSE;
}

// -- Volume & progress --

static jboolean nativeSetVolume(JNIEnv *, jclass, jlong handle, jfloat vol) {
    auto *r = toRaop(handle);
    return (r && raopcl_set_volume(r, vol)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeSetProgressMs(JNIEnv *, jclass, jlong handle,
                                    jint elapsed, jint duration) {
    auto *r = toRaop(handle);
    return (r && raopcl_set_progress_ms(r,
        static_cast<uint32_t>(elapsed),
        static_cast<uint32_t>(duration))) ? JNI_TRUE : JNI_FALSE;
}

// -- State queries --

static jboolean nativeIsConnected(JNIEnv *, jclass, jlong handle) {
    auto *r = toRaop(handle);
    return (r && raopcl_is_connected(r)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeKeepalive(JNIEnv *, jclass, jlong handle) {
    auto *r = toRaop(handle);
    return (r && raopcl_keepalive(r)) ? JNI_TRUE : JNI_FALSE;
}

// -- Configuration --

static jint nativeGetLastRtspStatus(JNIEnv *, jclass, jlong handle) {
    auto *r = toRaop(handle);
    return r ? static_cast<jint>(raopcl_last_rtsp_status(r)) : 0;
}

static void nativeSetDeviceName(JNIEnv *env, jclass, jlong handle, jstring jName) {
    auto *r = toRaop(handle);
    if (!r || !jName) return;

    const char *name = env->GetStringUTFChars(jName, nullptr);
    raopcl_set_device_name(r, name);
    env->ReleaseStringUTFChars(jName, name);
}

static void nativeSetPassword(JNIEnv *env, jclass, jlong handle, jstring jPasswd) {
    auto *r = toRaop(handle);
    if (!r) return;

    if (jPasswd) {
        const char *passwd = env->GetStringUTFChars(jPasswd, nullptr);
        raopcl_set_password(r, passwd);
        env->ReleaseStringUTFChars(jPasswd, passwd);
    } else {
        raopcl_set_password(r, nullptr);
    }
}

// -- Metadata --

static jboolean nativeSetDaap(JNIEnv *env, jclass, jlong handle,
                              jstring jTitle, jstring jArtist, jstring jAlbum) {
    auto *r = toRaop(handle);
    if (!r) return JNI_FALSE;

    const char *title  = jTitle  ? env->GetStringUTFChars(jTitle, nullptr) : nullptr;
    const char *artist = jArtist ? env->GetStringUTFChars(jArtist, nullptr) : nullptr;
    const char *album  = jAlbum  ? env->GetStringUTFChars(jAlbum, nullptr) : nullptr;

    bool ok = raopcl_set_daap(r, 3,
        "minm", 's', title  ? title  : "",
        "asar", 's', artist ? artist : "",
        "asal", 's', album  ? album  : "");

    if (title)  env->ReleaseStringUTFChars(jTitle, title);
    if (artist) env->ReleaseStringUTFChars(jArtist, artist);
    if (album)  env->ReleaseStringUTFChars(jAlbum, album);

    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeSetArtwork(JNIEnv *env, jclass, jlong handle,
                                 jbyteArray jImageData, jint length,
                                 jstring jContentType) {
    auto *r = toRaop(handle);
    if (!r || !jImageData) return JNI_FALSE;

    const char *ct = jContentType
        ? env->GetStringUTFChars(jContentType, nullptr) : nullptr;
    jbyte *data = env->GetByteArrayElements(jImageData, nullptr);
    if (!data) {
        if (ct) env->ReleaseStringUTFChars(jContentType, ct);
        return JNI_FALSE;
    }

    bool ok = raopcl_set_artwork(r,
        const_cast<char *>(ct ? ct : "image/jpeg"),
        static_cast<int>(length),
        reinterpret_cast<char *>(data));

    env->ReleaseByteArrayElements(jImageData, data, JNI_ABORT);
    if (ct) env->ReleaseStringUTFChars(jContentType, ct);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// RegisterNatives table
// ---------------------------------------------------------------------------
static const JNINativeMethod sMethods[] = {
    {"nativeOpen",          "(Ljava/lang/String;IIIIIZIIIF)J",      reinterpret_cast<void *>(nativeOpen)},
    {"nativeConnect",       "(JLjava/lang/String;I)Z",              reinterpret_cast<void *>(nativeConnect)},
    {"nativeDisconnect",    "(J)Z",                                 reinterpret_cast<void *>(nativeDisconnect)},
    {"nativeDestroy",       "(J)V",                                 reinterpret_cast<void *>(nativeDestroy)},
    {"nativeFeedPcm",       "(J[BII)I",                             reinterpret_cast<void *>(nativeFeedPcm)},
    {"nativeBytesSent",     "(J)J",                                 reinterpret_cast<void *>(nativeBytesSent)},
    {"nativeRingAvailable", "(J)I",                                 reinterpret_cast<void *>(nativeRingAvailable)},
    {"nativePause",         "(J)V",                                 reinterpret_cast<void *>(nativePause)},
    {"nativeStop",          "(J)V",                                 reinterpret_cast<void *>(nativeStop)},
    {"nativeFlush",         "(J)Z",                                 reinterpret_cast<void *>(nativeFlush)},
    {"nativeSetVolume",     "(JF)Z",                                reinterpret_cast<void *>(nativeSetVolume)},
    {"nativeSetProgressMs", "(JII)Z",                               reinterpret_cast<void *>(nativeSetProgressMs)},
    {"nativeIsConnected",   "(J)Z",                                 reinterpret_cast<void *>(nativeIsConnected)},
    {"nativeKeepalive",     "(J)Z",                                 reinterpret_cast<void *>(nativeKeepalive)},
    {"nativeGetLastRtspStatus", "(J)I",                              reinterpret_cast<void *>(nativeGetLastRtspStatus)},
    {"nativeSetPassword",   "(JLjava/lang/String;)V",               reinterpret_cast<void *>(nativeSetPassword)},
    {"nativeSetDeviceName", "(JLjava/lang/String;)V",               reinterpret_cast<void *>(nativeSetDeviceName)},
    {"nativeSetDaap",       "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z", reinterpret_cast<void *>(nativeSetDaap)},
    {"nativeSetArtwork",    "(J[BILjava/lang/String;)Z",            reinterpret_cast<void *>(nativeSetArtwork)},
};

static const char *sClassName =
    "com/margelo/nitro/queueplayer/cast/airplay/AirplayJNI";

jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    cross_ssl_load();

    jclass clazz = env->FindClass(sClassName);
    if (clazz == nullptr) {
        /* Leaves a pending NoClassDefFoundError. Clear it and fail the load
         * outright: nothing retries registration, so reporting success here
         * would leave the Kotlin object reporting AirPlay 1 as available with
         * no natives bound, and the first call would throw
         * UnsatisfiedLinkError. Failing makes System.loadLibrary throw, and
         * AirplayJNI's initialiser catches that and sets isAvailable false. */
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "AirplayJNI class not found — AirPlay 1 unavailable");
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, sMethods,
            sizeof(sMethods) / sizeof(sMethods[0])) < 0) {
        /* An unresolved table entry leaves a pending NoSuchMethodError. */
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "RegisterNatives failed");
        return JNI_ERR;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "libraop native loaded (v0.2.0 ring-buffer)");
    return JNI_VERSION_1_6;
}
