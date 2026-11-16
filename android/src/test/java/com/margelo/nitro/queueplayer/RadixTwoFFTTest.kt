package com.margelo.nitro.queueplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit coverage for the radix-2 forward FFT — synthesizes inputs with
 * known spectra and asserts the magnitude bins land where they should.
 */
class RadixTwoFFTTest {

  @Test
  fun `DC input puts all energy in bin 0`() {
    val n = 64
    val real = FloatArray(n) { 1.0f }
    val imag = FloatArray(n)
    val mags = FloatArray(n / 2)
    RadixTwoFFT.forward(real, imag)
    RadixTwoFFT.magnitudes(real, imag, mags)
    assertEquals(2.0f, mags[0], 1e-3f) // 2 * (sum / n) for DC=1 input
    for (k in 1 until mags.size) {
      assertTrue("bin $k should be ~0 for DC input, was ${mags[k]}", mags[k] < 1e-3f)
    }
  }

  @Test
  fun `pure sine at bin k peaks magnitude at bin k`() {
    val n = 256
    val k = 8
    val real = FloatArray(n) { i -> cos(2.0 * PI * k * i / n).toFloat() }
    val imag = FloatArray(n)
    val mags = FloatArray(n / 2)
    RadixTwoFFT.forward(real, imag)
    RadixTwoFFT.magnitudes(real, imag, mags)
    val peakBin = (0 until mags.size).maxBy { mags[it] }
    assertEquals("peak should be at bin $k", k, peakBin)
    // Other bins should be far below the peak (numerical leakage allowed).
    for (i in 0 until mags.size) {
      if (i == k) continue
      assertTrue(
        "bin $i (${mags[i]}) should be < bin $k (${mags[k]}) / 100",
        mags[i] < mags[k] / 100f,
      )
    }
  }

  @Test
  fun `power-of-two requirement is enforced`() {
    val real = FloatArray(7)
    val imag = FloatArray(7)
    try {
      RadixTwoFFT.forward(real, imag)
      throw AssertionError("expected IllegalArgumentException for non-power-of-two length")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("power of two") == true)
    }
  }

  @Test
  fun `Hann window has the expected shape`() {
    val w = RadixTwoFFT.hannWindow(8)
    assertEquals(8, w.size)
    assertEquals(0.0f, w[0], 1e-6f) // first sample
    assertEquals(0.0f, w[7], 1e-6f) // last sample
    // Symmetric: w[i] == w[n-1-i]
    for (i in 0 until w.size / 2) {
      assertEquals(w[i], w[w.size - 1 - i], 1e-6f)
    }
    // Peak in the middle (between indices 3 and 4 for n=8).
    assertTrue(w[3] > w[0])
    assertTrue(w[4] > w[7])
  }

  @Test
  fun `mismatched array lengths throw`() {
    try {
      RadixTwoFFT.forward(FloatArray(8), FloatArray(4))
      throw AssertionError("expected IllegalArgumentException for length mismatch")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("equal length") == true)
    }
  }

  @Test
  fun `forward of single-sample is a no-op`() {
    val real = floatArrayOf(7f)
    val imag = floatArrayOf(0f)
    RadixTwoFFT.forward(real, imag)
    assertEquals(7f, real[0], 0f)
    assertEquals(0f, imag[0], 0f)
  }

  @Test
  fun `magnitudes output size must be n div 2`() {
    val real = FloatArray(8) { 1f }
    val imag = FloatArray(8)
    RadixTwoFFT.forward(real, imag)
    try {
      RadixTwoFFT.magnitudes(real, imag, FloatArray(3))
      throw AssertionError("expected IllegalArgumentException for wrong out length")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("n/2") == true)
    }
  }
}
