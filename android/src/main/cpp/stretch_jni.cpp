// JNI bridge for Signalsmith Stretch — the Android "music" pitch-correction
// time-stretch engine. Wraps a per-instance SignalsmithStretch<float>,
// converting between Media3's interleaved float PCM and Signalsmith's planar
// per-channel buffers. One native instance per AudioProcessor (per crossfade
// leg). Pure DSP — no Android framework deps beyond JNI + __android_log.
//
// Stretch ratio is set per process() call by the input/output frame counts
// (Signalsmith has no ratio setter): outputFrames/inputFrames == time-stretch.

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <cstring>
#include "signalsmith-stretch.h"

#define LOG_TAG "RNQP-Stretch"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct StretchHandle {
  signalsmith::stretch::SignalsmithStretch<float> stretch;
  int channels = 2;
  // Planar scratch — inputs[ch] / outputs[ch] pointers handed to process().
  std::vector<std::vector<float>> planarIn;
  std::vector<std::vector<float>> planarOut;
  std::vector<float *> inPtrs;
  std::vector<float *> outPtrs;

  void ensureCapacity(int inFrames, int outFrames) {
    for (int c = 0; c < channels; ++c) {
      if ((int)planarIn[c].size() < inFrames) planarIn[c].resize(inFrames);
      if ((int)planarOut[c].size() < outFrames) planarOut[c].resize(outFrames);
      inPtrs[c] = planarIn[c].data();
      outPtrs[c] = planarOut[c].data();
    }
  }
};

inline StretchHandle *fromHandle(jlong h) {
  return reinterpret_cast<StretchHandle *>(h);
}

}  // namespace

extern "C" {

// Returns an opaque handle, or 0 on failure (invalid channels / allocation
// error). The Kotlin binding turns a 0 handle into a thrown error.
JNIEXPORT jlong JNICALL
Java_com_margelo_nitro_queueplayer_SignalsmithStretchNative_nativeCreate(
    JNIEnv *, jclass, jint channels, jfloat sampleRate, jboolean splitComputation) {
  if (channels <= 0) {
    LOGE("nativeCreate: invalid channels=%d", channels);
    return 0;
  }
  try {
    auto *h = new StretchHandle();
    h->channels = channels;
    h->planarIn.assign(channels, {});
    h->planarOut.assign(channels, {});
    h->inPtrs.assign(channels, nullptr);
    h->outPtrs.assign(channels, nullptr);
    // presetDefault = the music-grade quality preset; splitComputation spreads
    // the FFT work across calls to avoid per-callback CPU spikes.
    h->stretch.presetDefault(channels, sampleRate, splitComputation == JNI_TRUE);
    h->stretch.reset();
    return reinterpret_cast<jlong>(h);
  } catch (const std::exception &e) {
    LOGE("nativeCreate: %s", e.what());
    return 0;
  }
}

// True if `buf`'s direct capacity holds at least `frames * ch` floats from its
// start. GetDirectBufferAddress points at offset 0, so callers must hand
// buffers whose interleaved data begins at position 0.
inline bool hasCapacity(JNIEnv *env, jobject buf, int frames, int ch) {
  const jlong needBytes = static_cast<jlong>(frames) * ch * sizeof(float);
  return env->GetDirectBufferCapacity(buf) >= needBytes;
}

// Process `inFrames` interleaved float frames from `inBuf` and write exactly
// `outFrames` interleaved float frames to `outBuf`. Both are direct
// ByteBuffers (native byte order) whose data starts at offset 0. Returns
// frames written (== outFrames), or 0 on any validation failure.
JNIEXPORT jint JNICALL
Java_com_margelo_nitro_queueplayer_SignalsmithStretchNative_nativeProcess(
    JNIEnv *env, jclass, jlong handle, jobject inBuf, jint inFrames,
    jobject outBuf, jint outFrames) {
  auto *h = fromHandle(handle);
  if (h == nullptr || inFrames < 0 || outFrames <= 0) return 0;
  auto *in = static_cast<float *>(env->GetDirectBufferAddress(inBuf));
  auto *out = static_cast<float *>(env->GetDirectBufferAddress(outBuf));
  if (in == nullptr || out == nullptr) {
    LOGE("nativeProcess: null direct buffer address");
    return 0;
  }
  const int ch = h->channels;
  if (!hasCapacity(env, inBuf, inFrames, ch) ||
      !hasCapacity(env, outBuf, outFrames, ch)) {
    LOGE("nativeProcess: buffer too small for %d ch (in=%d out=%d frames)", ch,
         inFrames, outFrames);
    return 0;
  }
  try {
    h->ensureCapacity(inFrames, outFrames);
    // De-interleave input -> planar.
    for (int i = 0; i < inFrames; ++i) {
      for (int c = 0; c < ch; ++c) h->planarIn[c][i] = in[i * ch + c];
    }
    h->stretch.process(h->inPtrs.data(), inFrames, h->outPtrs.data(), outFrames);
    // Re-interleave planar -> output.
    for (int i = 0; i < outFrames; ++i) {
      for (int c = 0; c < ch; ++c) out[i * ch + c] = h->planarOut[c][i];
    }
    return outFrames;
  } catch (const std::exception &e) {
    LOGE("nativeProcess: %s", e.what());
    return 0;
  }
}

// Drain the stretcher's internal tail (output latency) at end-of-stream into
// `outBuf`; returns frames written. Mirrors process() interleaving.
JNIEXPORT jint JNICALL
Java_com_margelo_nitro_queueplayer_SignalsmithStretchNative_nativeFlush(
    JNIEnv *env, jclass, jlong handle, jobject outBuf, jint outFrames) {
  auto *h = fromHandle(handle);
  if (h == nullptr || outFrames <= 0) return 0;
  auto *out = static_cast<float *>(env->GetDirectBufferAddress(outBuf));
  if (out == nullptr) return 0;
  const int ch = h->channels;
  if (!hasCapacity(env, outBuf, outFrames, ch)) {
    LOGE("nativeFlush: buffer too small for %d ch (out=%d frames)", ch, outFrames);
    return 0;
  }
  try {
    h->ensureCapacity(0, outFrames);
    h->stretch.flush(h->outPtrs.data(), outFrames);
    for (int i = 0; i < outFrames; ++i) {
      for (int c = 0; c < ch; ++c) out[i * ch + c] = h->planarOut[c][i];
    }
    return outFrames;
  } catch (const std::exception &e) {
    LOGE("nativeFlush: %s", e.what());
    return 0;
  }
}

JNIEXPORT jint JNICALL
Java_com_margelo_nitro_queueplayer_SignalsmithStretchNative_nativeOutputLatency(
    JNIEnv *, jclass, jlong handle) {
  auto *h = fromHandle(handle);
  return h == nullptr ? 0 : static_cast<jint>(h->stretch.outputLatency());
}

JNIEXPORT void JNICALL
Java_com_margelo_nitro_queueplayer_SignalsmithStretchNative_nativeReset(
    JNIEnv *, jclass, jlong handle) {
  auto *h = fromHandle(handle);
  if (h != nullptr) h->stretch.reset();
}

JNIEXPORT void JNICALL
Java_com_margelo_nitro_queueplayer_SignalsmithStretchNative_nativeDestroy(
    JNIEnv *, jclass, jlong handle) {
  delete fromHandle(handle);
}

}  // extern "C"
