package com.margelo.nitro.queueplayer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Iterative Cooley–Tukey radix-2 forward FFT. In-place over (real,
 * imag) pairs of length n where n is a power of 2. No external deps.
 *
 * Produces the standard frequency-domain output: bin k of [0, n) gives
 * the complex coefficient of frequency `k * sampleRate / n`. Bin 0 is
 * DC; bin n/2 is Nyquist; the upper half is the conjugate-symmetric
 * mirror for real-valued inputs.
 *
 * The single output buffer length we expose to consumers is
 * `n / 2` magnitudes — bin n/2 (Nyquist) is dropped to keep the lib's
 * `frame.fft.byteLength === fftSize / 2 * 4` contract tight across
 * platforms.
 */
internal object RadixTwoFFT {

  /**
   * Maximum FFT length supported by the pre-computed twiddle tables.
   * Must stay in lockstep with the public `VisualizerFftSize` union
   * in `react-native-queue-player/src/types.ts` — the spec's upper
   * bound is the source of truth; this constant is the iOS / Android
   * implementation cap that backs it.
   */
  private const val MAX_FFT_SIZE = 2048

  /**
   * Pre-computed twiddle factors keyed by stage index, where
   * stage index `s` corresponds to butterfly size `1 shl (s + 1)`
   * (i.e. s=0 → size 2, s=1 → size 4, … s=10 → size 2048).
   *
   * `twiddleReal[s][j]` = cos(-2π · j / size), with j in [0, size/2).
   * `twiddleImag[s][j]` = sin(-2π · j / size).
   *
   * Memory footprint: sum over s of (size/2) floats per table ×
   * 2 tables × 4 bytes ≈ 16 KB total for MAX_FFT_SIZE = 2048.
   */
  private val twiddleReal: Array<FloatArray>
  private val twiddleImag: Array<FloatArray>

  init {
    val stageCount = Integer.numberOfTrailingZeros(MAX_FFT_SIZE)
    twiddleReal = Array(stageCount) { FloatArray(0) }
    twiddleImag = Array(stageCount) { FloatArray(0) }
    var size = 2
    var stageIdx = 0
    while (size <= MAX_FFT_SIZE) {
      val half = size / 2
      val angleStep = -2.0 * PI / size
      val cosTable = FloatArray(half)
      val sinTable = FloatArray(half)
      var j = 0
      while (j < half) {
        val angle = angleStep * j
        cosTable[j] = cos(angle).toFloat()
        sinTable[j] = sin(angle).toFloat()
        j++
      }
      twiddleReal[stageIdx] = cosTable
      twiddleImag[stageIdx] = sinTable
      size = size shl 1
      stageIdx++
    }
  }

  /**
   * In-place forward FFT over a power-of-two-length pair of real +
   * imaginary float arrays. Throws if `real.size != imag.size`,
   * if size is not a power of two, or if size exceeds [MAX_FFT_SIZE].
   */
  fun forward(real: FloatArray, imag: FloatArray) {
    val n = real.size
    require(n == imag.size) { "real and imag arrays must have equal length, got ${real.size} vs ${imag.size}" }
    require(n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of two, got $n" }
    require(n <= MAX_FFT_SIZE) { "FFT length must be <= $MAX_FFT_SIZE, got $n" }
    if (n == 1) return

    bitReversePermute(real, imag)

    var size = 2
    var stageIdx = 0
    while (size <= n) {
      val half = size / 2
      val cosTable = twiddleReal[stageIdx]
      val sinTable = twiddleImag[stageIdx]
      var k = 0
      while (k < n) {
        var j = 0
        while (j < half) {
          val wr = cosTable[j]
          val wi = sinTable[j]
          val a = k + j
          val b = a + half
          val tr = wr * real[b] - wi * imag[b]
          val ti = wr * imag[b] + wi * real[b]
          real[b] = real[a] - tr
          imag[b] = imag[a] - ti
          real[a] = real[a] + tr
          imag[a] = imag[a] + ti
          j++
        }
        k += size
      }
      size = size shl 1
      stageIdx++
    }
  }

  /**
   * Compute the magnitude of bins [0, n/2). Caller must have already
   * called [forward] on (real, imag); this just walks the lower half
   * and writes `sqrt(real² + imag²) * 2 / n` to [out].
   *
   * The `2/n` scaling is the standard one-sided amplitude scaling
   * for non-DC bins. DC (bin 0) is intentionally double-scaled by
   * the same factor — pragmatic so callers can treat every bin's
   * magnitude with the same units; trade-off is a 2× DC overshoot
   * vs the strict spectral interpretation. The iOS `FFTProcessor`
   * mirrors this DC double-scaling so cross-platform magnitudes
   * coincide.
   */
  fun magnitudes(real: FloatArray, imag: FloatArray, out: FloatArray) {
    val n = real.size
    require(out.size == n / 2) { "out array must be n/2 floats, got ${out.size} for n=$n" }
    val scale = 2f / n
    var k = 0
    while (k < out.size) {
      val r = real[k]
      val i = imag[k]
      out[k] = sqrt(r * r + i * i) * scale
      k++
    }
  }

  /**
   * Hann window of length n. Coefficient i is
   * `0.5 * (1 - cos(2π · i / (n - 1)))`. Pre-multiply input samples by
   * this before [forward] to reduce spectral leakage.
   */
  fun hannWindow(n: Int): FloatArray {
    require(n > 1) { "Hann window requires n >= 2, got $n" }
    val w = FloatArray(n)
    val denom = (n - 1).toDouble()
    var i = 0
    while (i < n) {
      w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / denom))).toFloat()
      i++
    }
    return w
  }

  private fun bitReversePermute(real: FloatArray, imag: FloatArray) {
    val n = real.size
    var j = 0
    var i = 1
    while (i < n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j or bit
      if (i < j) {
        val tr = real[i]; real[i] = real[j]; real[j] = tr
        val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
      }
      i++
    }
  }
}
