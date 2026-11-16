/**
 * JNI bridge for libairplay2.  Same RegisterNatives pattern as
 * airplay_jni.cpp (libraop), but registers against AirPlay2JNI.kt.
 *
 * The session handle is an ap2_session_t pointer cast to jlong.
 */
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>

extern "C" {
#include "airplay2_session.h"
#include "airplay2_rtsp.h"
#include "airplay2_pair.h"
#include "airplay2_control.h"
#include "airplay2_audio.h"
#include "airplay2_filelog.h"
}

#define TAG "AirPlay2JNI"

// ---------------------------------------------------------------------------
// Handle helpers
// ---------------------------------------------------------------------------
static ap2_session_t *toSession(jlong handle) {
    return reinterpret_cast<ap2_session_t *>(handle);
}

// ---------------------------------------------------------------------------
// Session-death upcall (drain thread → Kotlin)
// ---------------------------------------------------------------------------
static JavaVM *sJavaVM = nullptr;
static jclass sAp2JniClass = nullptr;
static jmethodID sOnNativeSessionDied = nullptr;

/**
 * Session-death reason codes passed to AirPlay2JNI.onNativeSessionDied.
 * Kept in sync with AirPlay2JNI.kt DIED_* constants.
 */
#define AP2_DIED_FEEDBACK_TIMEOUT 1

extern "C" void ap2_notify_session_died(ap2_session_t *s, int reason) {
    if (!sJavaVM || !sAp2JniClass || !sOnNativeSessionDied || !s) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    jint jres = sJavaVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (jres == JNI_EDETACHED) {
        if (sJavaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (jres != JNI_OK) {
        return;
    }

    env->CallStaticVoidMethod(sAp2JniClass, sOnNativeSessionDied,
                              reinterpret_cast<jlong>(s), (jint)reason);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) sJavaVM->DetachCurrentThread();
}

// ---------------------------------------------------------------------------
// Session lifecycle
// ---------------------------------------------------------------------------

static jlong nativeAp2Open(JNIEnv *env, jclass,
                           jstring jHost, jint port,
                           jint sampleRate, jint sampleSize,
                           jint channels, jfloat volume) {
    const char *host = env->GetStringUTFChars(jHost, nullptr);
    if (!host) return 0;

    ap2_session_t *s = ap2_session_create(host, port,
        sampleRate, sampleSize, channels, volume);

    env->ReleaseStringUTFChars(jHost, host);

    if (!s) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ap2_session_create failed");
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "Session created: %s:%d uuid=%s", s->host, s->rtsp_port, s->session_uuid);

    return reinterpret_cast<jlong>(s);
}

static void nativeAp2SetAuthKey(JNIEnv *env, jclass,
                                jlong handle, jstring jKey) {
    auto *s = toSession(handle);
    if (!s || !jKey) return;

    const char *key = env->GetStringUTFChars(jKey, nullptr);
    if (!key) return;

    int ret = ap2_set_auth_key(s, key);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "SetAuthKey: len=%zu valid=%d", strlen(key), ret == 0);

    env->ReleaseStringUTFChars(jKey, key);
}

static void nativeAp2SetDeviceName(JNIEnv *env, jclass, jlong handle, jstring jName) {
    auto *s = toSession(handle);
    if (!s || !jName) return;
    const char *name = env->GetStringUTFChars(jName, nullptr);
    if (!name) return;
    strncpy(s->device_name, name, sizeof(s->device_name) - 1);
    s->device_name[sizeof(s->device_name) - 1] = '\0';
    env->ReleaseStringUTFChars(jName, name);
}

static jboolean nativeAp2Connect(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return JNI_FALSE;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "Connect: %s:%d state=%s", s->host, s->rtsp_port,
        ap2_state_name(s->state));

    /* 1+2. TCP connect + pair (info → auth-setup → pair-verify/transient).
     * ap2_connect() calls ap2_rtsp_connect() internally. */
    if (ap2_connect(s) != 0) {
        /* If pairing required, keep TCP alive for subsequent pair-setup.
         * Per OwnTone airplay.c — socket reused across auth. */
        if (s->pairing_required) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "Connect: pairing required (auth_mode=%d), keeping TCP alive",
                s->auth_mode);
        } else {
            ap2_rtsp_close(s);
        }
        return JNI_FALSE;
    }

    /* 3. Session establishment (SETUP → RECORD → volume). */
    if (ap2_start_streaming(s) != 0) {
        ap2_rtsp_close(s);
        return JNI_FALSE;
    }

    /* 4. Start audio drain thread. */
    if (ap2_audio_start(s) != 0) {
        ap2_teardown(s);
        ap2_rtsp_close(s);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static void nativeAp2Disconnect(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "Disconnect: state=%s dead=%d", ap2_state_name(s->state), s->session_dead);

    /* If session is dead (WiFi loss / receiver gone), shutdown sockets first so
     * any blocking reads on the drain thread return immediately. Skip TEARDOWN
     * because the socket is a dead-end and would block for SO_RCVTIMEO.       */
    if (s->session_dead) {
        if (s->rtsp_fd    >= 0) shutdown(s->rtsp_fd,    SHUT_RDWR);
        if (s->data_fd    >= 0) shutdown(s->data_fd,    SHUT_RDWR);
        if (s->control_fd >= 0) shutdown(s->control_fd, SHUT_RDWR);
        if (s->event_fd   >= 0) shutdown(s->event_fd,   SHUT_RDWR);
    }

    /* Stop audio drain thread. */
    ap2_audio_stop(s);

    /* Send TEARDOWN (best-effort) only when the session is still alive. */
    if (!s->session_dead && s->rtsp_fd >= 0 && s->state >= AP2_STATE_SETUP_SESSION) {
        ap2_teardown(s);
    }

    /* Close network sockets. */
    ap2_rtsp_close(s);
    if (s->data_fd >= 0)    { close(s->data_fd);    s->data_fd = -1; }
    if (s->control_fd >= 0) { close(s->control_fd); s->control_fd = -1; }
    if (s->event_fd >= 0)   { close(s->event_fd);   s->event_fd = -1; }

    s->state = AP2_STATE_INIT;
    s->encrypted = 0;
}

static void nativeAp2Destroy(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return;

    __android_log_print(ANDROID_LOG_INFO, TAG, "Destroy session");
    ap2_session_destroy(&s);
}

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

static jboolean nativeAp2PairPinStart(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return JNI_FALSE;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "PairPinStart: %s:%d", s->host, s->rtsp_port);

    return ap2_pair_pin_start(s) == 0 ? JNI_TRUE : JNI_FALSE;
}

static jstring nativeAp2PairSetup(JNIEnv *env, jclass,
                                  jlong handle, jstring jPin) {
    auto *s = toSession(handle);
    if (!s || !jPin) return nullptr;

    const char *pin = env->GetStringUTFChars(jPin, nullptr);
    if (!pin) return nullptr;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "PairSetup: credential_len=%zu one_time_pairing=%d",
        strlen(pin), s->one_time_pairing);

    /* NOTE: pair-pin-start is NOT called here — Kotlin handles it in
     * Phase 1 (nativeAp2PairPinStart) before reaching this function.
     * The credential parameter is either a displayed PIN (PIN mode) or
     * a password (PASSWORD-only mode).  For PIN mode with PASSWORD_REQUIRED,
     * the password for HTTP Digest auth must be set separately via
     * nativeAp2SetPassword before calling this function. */

    char *auth_key = nullptr;
    int ret = ap2_pair_setup(s, pin, &auth_key);
    env->ReleaseStringUTFChars(jPin, pin);

    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "PairSetup failed");
        free(auth_key);
        return nullptr;
    }

    /* Per OwnTone's architecture: pair-setup and streaming are SEPARATE
     * operations.  OwnTone's sequence engine pairs once, stores the auth
     * key, then uses pair-verify on every subsequent connect.  Doing
     * pair-setup + pair-verify + SETUP back-to-back in one call doesn't
     * give the Mac time to commit the pairing.  Instead: close the
     * connection here, return the auth_key, and let Kotlin do a fresh
     * nativeAp2Connect with the stored key — that triggers pair-verify
     * on a clean connection, exactly like OwnTone's device_start flow. */
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "PairSetup succeeded — closing connection, auth_key=%s",
        auth_key ? "present" : "null");
    ap2_rtsp_close(s);
    s->encrypted = 0;
    s->write_nonce = 0;
    s->read_nonce = 0;
    s->cseq = 0;

    jstring result = auth_key ? env->NewStringUTF(auth_key) : nullptr;
    free(auth_key);
    return result;
}

static jint nativeAp2GetAuthMode(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    return s ? static_cast<jint>(s->auth_mode) : 0;
}

static jstring nativeAp2GetDeviceId(JNIEnv *env, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s || s->device_id[0] == '\0') return nullptr;
    return env->NewStringUTF(s->device_id);
}

static void nativeAp2SetPassword(JNIEnv *env, jclass,
                                 jlong handle, jstring jPassword) {
    auto *s = toSession(handle);
    if (!s || !jPassword) return;

    const char *password = env->GetStringUTFChars(jPassword, nullptr);
    if (!password) return;

    ap2_set_password(s, password);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "SetPassword: len=%zu", strlen(password));

    env->ReleaseStringUTFChars(jPassword, password);
}

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------

static jint nativeAp2FeedPcm(JNIEnv *env, jclass,
                             jlong handle, jbyteArray jData,
                             jint offset, jint length) {
    auto *s = toSession(handle);
    if (!s || !jData) return 0;

    jbyte *buf = env->GetByteArrayElements(jData, nullptr);
    if (!buf) return 0;

    int consumed = ap2_audio_write(s, (const uint8_t *)buf + offset, (size_t)length);

    env->ReleaseByteArrayElements(jData, buf, JNI_ABORT);
    return (jint)consumed;
}

static jint nativeAp2RingAvailable(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    return s ? (jint)ap2_audio_ring_available(s) : 0;
}

static jlong nativeAp2GetBytesSent(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    return s ? (jlong)s->bytes_sent : 0;
}

// ---------------------------------------------------------------------------
// Control
// ---------------------------------------------------------------------------

static void nativeAp2SetVolume(JNIEnv *, jclass, jlong handle, jfloat vol) {
    auto *s = toSession(handle);
    if (!s) return;
    s->volume = vol;
    /* Send volume over RTSP if streaming.
     * Convert 0.0-1.0 linear to dB: 0→-144 (mute), 1→0 (full).
     * Per OwnTone airplay.c — SET_PARAMETER text/parameters. */
    if (s->state >= AP2_STATE_RECORD) {
        float vol_db;
        if (vol <= 0.0f) {
            vol_db = -144.0f;
        } else if (vol >= 1.0f) {
            vol_db = 0.0f;
        } else {
            vol_db = -30.0f + (vol * 30.0f);
        }
        ap2_set_volume(s, vol_db);
    }
}

static void nativeAp2SetMetadata(JNIEnv *env, jclass, jlong handle,
                                 jstring jTitle, jstring jArtist,
                                 jstring jAlbum) {
    auto *s = toSession(handle);
    if (!s) return;

    const char *title  = jTitle  ? env->GetStringUTFChars(jTitle, nullptr)  : nullptr;
    const char *artist = jArtist ? env->GetStringUTFChars(jArtist, nullptr) : nullptr;
    const char *album  = jAlbum  ? env->GetStringUTFChars(jAlbum, nullptr)  : nullptr;

    if (s->state >= AP2_STATE_RECORD) {
        ap2_set_metadata(s, title, artist, album);
    }

    if (title)  env->ReleaseStringUTFChars(jTitle, title);
    if (artist) env->ReleaseStringUTFChars(jArtist, artist);
    if (album)  env->ReleaseStringUTFChars(jAlbum, album);
}

static void nativeAp2SetArtwork(JNIEnv *env, jclass, jlong handle,
                                jbyteArray jJpeg, jint length) {
    auto *s = toSession(handle);
    if (!s || !jJpeg || length <= 0) return;

    if (s->state >= AP2_STATE_RECORD) {
        jbyte *buf = env->GetByteArrayElements(jJpeg, nullptr);
        if (buf) {
            ap2_set_artwork(s, (const uint8_t *)buf, (size_t)length, "image/jpeg");
            env->ReleaseByteArrayElements(jJpeg, buf, JNI_ABORT);
        }
    }
}

static void nativeAp2SetProgressMs(JNIEnv *, jclass, jlong handle,
                                   jlong elapsed, jlong duration) {
    auto *s = toSession(handle);
    if (!s) return;

    if (s->state >= AP2_STATE_RECORD) {
        ap2_set_progress(s, (uint32_t)elapsed, (uint32_t)duration);
    }
}

static void nativeAp2Pause(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return;
    ap2_audio_pause(s);
    /* Send FLUSH so receiver knows audio stopped.
     * Per OwnTone airplay.c — FLUSH after stopping audio. */
    if (s->state >= AP2_STATE_RECORD) {
        ap2_flush(s);
    }
}

static void nativeAp2Resume(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return;
    ap2_audio_resume(s);
    /* Drain thread resumes sending — no re-RECORD needed per OwnTone. */
}

// ---------------------------------------------------------------------------
// File logging
// ---------------------------------------------------------------------------

static void nativeAp2InitFileLog(JNIEnv *env, jclass, jstring jPath) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return;
    ap2_filelog_open(path);
    __android_log_print(ANDROID_LOG_INFO, TAG, "File logging opened: %s", path);
    env->ReleaseStringUTFChars(jPath, path);
}

static void nativeAp2CloseFileLog(JNIEnv *, jclass) {
    ap2_filelog_close();
    __android_log_print(ANDROID_LOG_INFO, TAG, "File logging closed");
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

static jint nativeAp2GetState(JNIEnv *, jclass, jlong handle) {
    auto *s = toSession(handle);
    return s ? static_cast<jint>(s->state) : 0;
}

// ---------------------------------------------------------------------------
// RegisterNatives table
// ---------------------------------------------------------------------------
static const JNINativeMethod sMethods[] = {
    /* Session lifecycle */
    {"nativeAp2Open",
        "(Ljava/lang/String;IIIIF)J",
        reinterpret_cast<void *>(nativeAp2Open)},
    {"nativeAp2SetAuthKey",
        "(JLjava/lang/String;)V",
        reinterpret_cast<void *>(nativeAp2SetAuthKey)},
    {"nativeAp2SetDeviceName",
        "(JLjava/lang/String;)V",
        reinterpret_cast<void *>(nativeAp2SetDeviceName)},
    {"nativeAp2Connect",
        "(J)Z",
        reinterpret_cast<void *>(nativeAp2Connect)},
    {"nativeAp2Disconnect",
        "(J)V",
        reinterpret_cast<void *>(nativeAp2Disconnect)},
    {"nativeAp2Destroy",
        "(J)V",
        reinterpret_cast<void *>(nativeAp2Destroy)},
    /* Pairing */
    {"nativeAp2PairPinStart",
        "(J)Z",
        reinterpret_cast<void *>(nativeAp2PairPinStart)},
    {"nativeAp2PairSetup",
        "(JLjava/lang/String;)Ljava/lang/String;",
        reinterpret_cast<void *>(nativeAp2PairSetup)},
    {"nativeAp2GetAuthMode",
        "(J)I",
        reinterpret_cast<void *>(nativeAp2GetAuthMode)},
    {"nativeAp2GetDeviceId",
        "(J)Ljava/lang/String;",
        reinterpret_cast<void *>(nativeAp2GetDeviceId)},
    {"nativeAp2SetPassword",
        "(JLjava/lang/String;)V",
        reinterpret_cast<void *>(nativeAp2SetPassword)},
    /* Audio */
    {"nativeAp2FeedPcm",
        "(J[BII)I",
        reinterpret_cast<void *>(nativeAp2FeedPcm)},
    {"nativeAp2GetBytesSent",
        "(J)J",
        reinterpret_cast<void *>(nativeAp2GetBytesSent)},
    {"nativeAp2RingAvailable",
        "(J)I",
        reinterpret_cast<void *>(nativeAp2RingAvailable)},
    /* Control */
    {"nativeAp2SetVolume",
        "(JF)V",
        reinterpret_cast<void *>(nativeAp2SetVolume)},
    {"nativeAp2SetMetadata",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        reinterpret_cast<void *>(nativeAp2SetMetadata)},
    {"nativeAp2SetArtwork",
        "(J[BI)V",
        reinterpret_cast<void *>(nativeAp2SetArtwork)},
    {"nativeAp2SetProgressMs",
        "(JJJ)V",
        reinterpret_cast<void *>(nativeAp2SetProgressMs)},
    {"nativeAp2Pause",
        "(J)V",
        reinterpret_cast<void *>(nativeAp2Pause)},
    {"nativeAp2Resume",
        "(J)V",
        reinterpret_cast<void *>(nativeAp2Resume)},
    /* Status */
    {"nativeAp2GetState",
        "(J)I",
        reinterpret_cast<void *>(nativeAp2GetState)},
    /* File logging */
    {"nativeAp2InitFileLog",
        "(Ljava/lang/String;)V",
        reinterpret_cast<void *>(nativeAp2InitFileLog)},
    {"nativeAp2CloseFileLog",
        "()V",
        reinterpret_cast<void *>(nativeAp2CloseFileLog)},
};

static const char *sClassName =
    "com/margelo/nitro/queueplayer/cast/airplay/AirPlay2JNI";

/**
 * Register natives for AirPlay2JNI.  Called from JNI_OnLoad in the
 * raop .so (single shared library hosts both sets of natives), OR
 * from the airplay2 .so's own JNI_OnLoad if built separately.
 */
jint ap2_register_natives(JavaVM *vm) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    sJavaVM = vm;

    jclass clazz = env->FindClass(sClassName);
    if (!clazz) {
        /* Leaves a pending NoClassDefFoundError. Clear it and fail the load
         * outright: nothing retries registration, so reporting success here
         * would leave the Kotlin object reporting AirPlay 2 as available with
         * no natives bound, and the first call would throw
         * UnsatisfiedLinkError. Failing makes System.loadLibrary throw, and
         * AirPlay2JNI's initialiser catches that and sets isAvailable false. */
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "AirPlay2JNI class not found — AirPlay 2 unavailable");
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, sMethods,
            sizeof(sMethods) / sizeof(sMethods[0])) < 0) {
        /* An unresolved table entry leaves a pending NoSuchMethodError. */
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "RegisterNatives failed");
        return JNI_ERR;
    }

    /* Cache the session-death upcall target (global ref + static method id). */
    sAp2JniClass = (jclass)env->NewGlobalRef(clazz);
    if (!sAp2JniClass) {
        /* Null only when the global reference table is exhausted, which can
         * leave a pending OutOfMemoryError; clear it so the load reports
         * success with upcalls disabled, matching the missing-method path
         * below. The check has to precede GetStaticMethodID, which aborts
         * under CheckJNI when handed a null class. */
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "could not retain AirPlay2JNI — upcalls disabled");
        return JNI_VERSION_1_6;
    }
    sOnNativeSessionDied = env->GetStaticMethodID(
        sAp2JniClass, "onNativeSessionDied", "(JI)V");
    if (!sOnNativeSessionDied) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "onNativeSessionDied(JI)V not found — upcalls disabled");
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "libairplay2 native registered (%zu methods)",
        sizeof(sMethods) / sizeof(sMethods[0]));
    return JNI_VERSION_1_6;
}

/* Standalone JNI_OnLoad for the airplay2 shared library. */
jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    return ap2_register_natives(vm);
}
