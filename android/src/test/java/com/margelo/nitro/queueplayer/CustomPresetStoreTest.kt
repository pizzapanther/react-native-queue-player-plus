package com.margelo.nitro.queueplayer

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SharedPreferences-backed persistence coverage for
 * [CustomPresetStore]. Robolectric provides an in-memory
 * SharedPreferences shadow so the round-trip is exercised without
 * touching real disk state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomPresetStoreTest {

  private fun makeStore() = CustomPresetStore(ApplicationProvider.getApplicationContext())

  @After
  fun tearDown() {
    val context: android.content.Context = ApplicationProvider.getApplicationContext()
    context
      .getSharedPreferences("com.queueplayer.equalizer", android.content.Context.MODE_PRIVATE)
      .edit()
      .clear()
      .apply()
  }

  @Test
  fun `save then loadAll round-trips one preset`() {
    val store = makeStore()
    store.save("MyPreset", floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f))
    val loaded = store.loadAll()
    assertEquals(1, loaded.size)
    val preset = loaded.single()
    assertEquals("MyPreset", preset.name)
    assertEquals(false, preset.builtin)
    assertEquals(10, preset.gains.size)
    assertEquals(0.0, preset.gains[0], 0.0001)
    assertEquals(9.0, preset.gains[9], 0.0001)
  }

  @Test
  fun `save with a duplicate name overwrites the prior entry`() {
    val store = makeStore()
    store.save("Studio", floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    store.save("Studio", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 5f))
    val loaded = store.loadAll().single()
    // Second save's gains take effect; first save is gone.
    assertEquals(0.0, loaded.gains[0], 0.0001)
    assertEquals(5.0, loaded.gains[9], 0.0001)
  }

  @Test
  fun `delete removes the named preset`() {
    val store = makeStore()
    store.save("A", FloatArray(10))
    store.save("B", FloatArray(10))
    store.delete("A")
    val names = store.loadAll().map { it.name }
    assertEquals(listOf("B"), names)
  }

  @Test
  fun `delete is a no-op for unknown names`() {
    val store = makeStore()
    store.save("Keep", FloatArray(10))
    store.delete("DoesNotExist")
    assertEquals(1, store.loadAll().size)
  }

  @Test
  fun `loadAll returns empty list on a fresh store`() {
    val store = makeStore()
    assertTrue(store.loadAll().isEmpty())
  }

  @Test
  fun `malformed JSON resolves to empty list rather than throwing`() {
    val context: android.content.Context = ApplicationProvider.getApplicationContext()
    context
      .getSharedPreferences("com.queueplayer.equalizer", android.content.Context.MODE_PRIVATE)
      .edit()
      .putString("customPresets", "{ this is not valid json }")
      .apply()
    val store = makeStore()
    assertTrue(store.loadAll().isEmpty())
  }

  @Test
  fun `save round-trips names with quotes backslashes and unicode glyphs`() {
    val store = makeStore()
    val tricky = "He said \"hi\"\\back\nnewline 🎛 café"
    store.save(tricky, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f))
    val loaded = store.loadAll().single()
    assertEquals(tricky, loaded.name)
    assertEquals(10.0, loaded.gains[9], 0.0001)
  }

  @Test
  fun `save with an empty gains array round-trips as an empty array`() {
    val store = makeStore()
    store.save("Empty", floatArrayOf())
    val loaded = store.loadAll().single()
    assertEquals("Empty", loaded.name)
    assertEquals(0, loaded.gains.size)
  }

  @Test
  fun `loadAll preserves all distinct presets across multiple saves`() {
    val store = makeStore()
    store.save("A", floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    store.save("B", floatArrayOf(0f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    store.save("C", floatArrayOf(0f, 0f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val byName = store.loadAll().associateBy { it.name }
    assertEquals(3, byName.size)
    assertEquals(1.0, byName["A"]!!.gains[0], 0.0001)
    assertEquals(2.0, byName["B"]!!.gains[1], 0.0001)
    assertEquals(3.0, byName["C"]!!.gains[2], 0.0001)
    assertNull(byName["D"])
  }
}
