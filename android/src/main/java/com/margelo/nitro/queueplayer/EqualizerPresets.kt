package com.margelo.nitro.queueplayer

/**
 * Built-in 10-band equalizer presets keyed by display name.
 *
 * 10 ISO-standard centre frequencies (Hz) span the audible spectrum
 * from sub-bass (31) to high treble (16k). Each preset's gains array
 * is in dB, one entry per band, ordered low-to-high to match
 * [frequencies].
 *
 * Presets are stored in a [LinkedHashMap] so [getNames] returns the
 * canonical chip-display order. The presets are read-only constants —
 * consumer-side custom presets live in [CustomPresetStore].
 */
object EqualizerPresets {

  val frequencies: List<Int> = listOf(
    31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
  )

  private val presets: Map<String, FloatArray> = linkedMapOf(
    "Flat"           to floatArrayOf( 0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f),
    "Reference"      to floatArrayOf( 4.0f,  4.0f,  2.0f,  1.0f,  0.0f,  0.0f,  2.0f,  3.0f,  1.0f, -2.0f),
    "Bass Boost"     to floatArrayOf( 6.0f,  5.0f,  3.0f,  1.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f),
    "Bass Reducer"   to floatArrayOf(-6.0f, -5.0f, -3.0f, -1.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f),
    "Treble Boost"   to floatArrayOf( 0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  2.0f,  4.0f,  5.0f,  6.0f),
    "Treble Reducer" to floatArrayOf( 0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f, -2.0f, -4.0f, -5.0f, -6.0f),
    "V-Shaped"       to floatArrayOf( 5.0f,  4.0f,  2.0f,  0.0f, -2.0f, -3.0f, -1.0f,  2.0f,  4.0f,  5.0f),
    "Vocal Boost"    to floatArrayOf(-3.0f, -2.0f,  0.0f,  1.0f,  2.0f,  3.0f,  4.0f,  3.0f,  1.0f,  0.0f),
    "Spoken Word"    to floatArrayOf(-6.0f, -4.0f, -2.0f,  0.0f,  2.0f,  4.0f,  4.0f,  3.0f, -1.0f, -3.0f),
    "Late Night"     to floatArrayOf( 6.0f,  5.0f,  2.0f,  1.0f,  1.0f,  2.0f,  3.0f,  2.0f,  3.0f,  5.0f),
    "Acoustic"       to floatArrayOf( 3.0f,  2.0f,  1.0f,  0.0f,  0.0f,  1.0f,  2.0f,  3.0f,  2.0f,  1.0f),
    "Rock"           to floatArrayOf( 5.0f,  3.0f, -2.0f, -3.0f, -1.0f,  2.0f,  4.0f,  5.0f,  5.0f,  5.0f),
    "Hip-Hop"        to floatArrayOf( 5.0f,  5.0f,  3.0f,  1.0f, -1.0f,  0.0f,  1.0f,  3.0f,  4.0f,  5.0f),
    "Small Speakers" to floatArrayOf( 5.0f,  6.0f,  3.0f,  0.0f,  0.0f,  1.0f,  2.0f,  4.0f,  5.0f,  6.0f),
    "Jazz"           to floatArrayOf( 3.0f,  2.0f,  0.0f,  1.0f,  2.0f,  1.0f,  0.0f,  1.0f,  2.0f,  3.0f),
    "Electronic"     to floatArrayOf( 5.0f,  4.0f,  2.0f,  0.0f, -2.0f,  1.0f,  2.0f,  3.0f,  4.0f,  6.0f),
  )

  /** Returns the gain array (dB) for [name], or null if unknown. */
  fun getByName(name: String): FloatArray? = presets[name]?.copyOf()

  /** Display names in canonical chip-display order. */
  fun getNames(): List<String> = presets.keys.toList()
}
