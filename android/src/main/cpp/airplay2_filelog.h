/**
 * Persistent file logging for AirPlay 2 native layer.
 *
 * Writes to a file on device storage so logs survive USB disconnection.
 * Thread-safe via a simple mutex. Include this header and use the
 * AP2_LOG* macros instead of raw __android_log_print.
 */
#ifndef AIRPLAY2_FILELOG_H
#define AIRPLAY2_FILELOG_H

#include <stdio.h>
#include <stdarg.h>
#include <time.h>
#include <pthread.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

extern FILE *g_ap2_logfile;
extern pthread_mutex_t g_ap2_logfile_mutex;

/** Open the persistent log file. Call once from JNI. */
static inline void ap2_filelog_open(const char *path) {
    pthread_mutex_lock(&g_ap2_logfile_mutex);
    if (g_ap2_logfile) fclose(g_ap2_logfile);
    g_ap2_logfile = fopen(path, "a");
    if (g_ap2_logfile) {
        fprintf(g_ap2_logfile, "\n=== AirPlay2 log opened ===\n");
        fflush(g_ap2_logfile);
    }
    pthread_mutex_unlock(&g_ap2_logfile_mutex);
}

/** Close the persistent log file. */
static inline void ap2_filelog_close(void) {
    pthread_mutex_lock(&g_ap2_logfile_mutex);
    if (g_ap2_logfile) {
        fprintf(g_ap2_logfile, "=== AirPlay2 log closed ===\n");
        fclose(g_ap2_logfile);
        g_ap2_logfile = NULL;
    }
    pthread_mutex_unlock(&g_ap2_logfile_mutex);
}

/** Write a line to the file log. */
static inline void ap2_flog(const char *tag, char level, const char *fmt, ...) {
    FILE *f;
    pthread_mutex_lock(&g_ap2_logfile_mutex);
    f = g_ap2_logfile;
    if (f) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        fprintf(f, "%02d:%02d:%02d.%03ld %c/%-10s ",
                tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000,
                level, tag);
        va_list ap;
        va_start(ap, fmt);
        vfprintf(f, fmt, ap);
        va_end(ap);
        fputc('\n', f);
        fflush(f);
    }
    pthread_mutex_unlock(&g_ap2_logfile_mutex);
}

#ifdef __cplusplus
}
#endif

/*
 * Convenience macros — each .c file defines TAG before including this header,
 * then uses LOGI/LOGE/LOGD which write to both logcat AND the file.
 */
#ifdef __ANDROID__
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__); \
    ap2_flog(TAG, 'I', __VA_ARGS__); \
} while(0)
#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); \
    ap2_flog(TAG, 'E', __VA_ARGS__); \
} while(0)
#define LOGD(...) do { \
    __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__); \
    ap2_flog(TAG, 'D', __VA_ARGS__); \
} while(0)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); ap2_flog(TAG, 'I', __VA_ARGS__); } while(0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); ap2_flog(TAG, 'E', __VA_ARGS__); } while(0)
#define LOGD(...) ((void)0)
#endif

#endif /* AIRPLAY2_FILELOG_H */
