package com.margelo.nitro.queueplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerPresetsTest {

  private val expectedOrderedNames: List<String> = listOf(
    "Flat",
    "Reference",
    "Bass Boost",
    "Bass Reducer",
    "Treble Boost",
    "Treble Reducer",
    "V-Shaped",
    "Vocal Boost",
    "Spoken Word",
    "Late Night",
    "Acoustic",
    "Rock",
    "Hip-Hop",
    "Small Speakers",
    "Jazz",
    "Electronic",
  )

  @Test
  fun `frequencies span 10 ISO bands from 31 Hz to 16 kHz`() {
    assertEquals(
      listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000),
      EqualizerPresets.frequencies,
    )
  }

  @Test
  fun `Rock preset gains match the RBJ table`() {
    assertArrayEquals(
      floatArrayOf(5.0f, 3.0f, -2.0f, -3.0f, -1.0f, 2.0f, 4.0f, 5.0f, 5.0f, 5.0f),
      EqualizerPresets.getByName("Rock"),
      0f,
    )
  }

  @Test
  fun `Treble Boost preset gains match the RBJ table`() {
    assertArrayEquals(
      floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.0f, 4.0f, 5.0f, 6.0f),
      EqualizerPresets.getByName("Treble Boost"),
      0f,
    )
  }

  @Test
  fun `getNames returns 16 entries in canonical display order`() {
    val names = EqualizerPresets.getNames()
    assertEquals(16, names.size)
    assertEquals(expectedOrderedNames, names)
  }

  @Test
  fun `every preset has 10 bands`() {
    for (name in EqualizerPresets.getNames()) {
      val gains = EqualizerPresets.getByName(name)
      assertTrue("Preset '$name' must have 10 bands", gains?.size == 10)
    }
  }

  @Test
  fun `Flat preset is all zero gains`() {
    val flat = EqualizerPresets.getByName("Flat")
    assertArrayEquals(FloatArray(10) { 0f }, flat, 0f)
  }

  @Test
  fun `getByName returns null for unknown preset`() {
    assertNull(EqualizerPresets.getByName("Nonexistent"))
  }

  @Test
  fun `getByName returns a defensive copy so callers cannot mutate the table`() {
    val first = EqualizerPresets.getByName("Rock")
    first?.set(0, 99f)
    val second = EqualizerPresets.getByName("Rock")
    assertEquals(5.0f, second?.get(0))
  }
}
