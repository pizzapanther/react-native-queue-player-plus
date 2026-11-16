package com.margelo.nitro.queueplayer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * SharedPreferences-backed persistence for user-defined equalizer
 * presets. Keys live under [PREFS_NAME] / [STORE_KEY] as a single
 * JSON object — `{ "MyPreset": [0.0, 1.5, ...], "Studio": [...] }`
 * — so a snapshot is one small read on the playback service main
 * looper.
 *
 * Malformed JSON (manual edit, partial write across versions)
 * resolves to an empty preset list rather than throwing — the
 * recovery path is "user re-saves their preset" rather than a crash.
 *
 * Threading: SharedPreferences is documented thread-safe for
 * individual edits, but the load-modify-write sequence in [save]
 * and [delete] is not atomic across threads on its own. Both
 * methods take an internal lock so concurrent writers don't drop
 * each other's edits. Reads ([loadAll]) don't take the lock —
 * they snapshot whatever is most recently committed.
 *
 * Durability: [save] uses `commit()` (synchronous fsync) so a
 * caller that returns successfully has guaranteed on-disk state;
 * [delete] uses `apply()` (queued write) — a lost-delete on
 * process kill is recoverable by re-deleting.
 */
internal class CustomPresetStore(context: Context) {

  companion object {
    private const val PREFS_NAME = "com.queueplayer.equalizer"
    private const val STORE_KEY = "customPresets"
    private const val LOG_TAG = "CustomPresetStore"
  }

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val writeLock = Any()

  /**
   * Persist [name] → [gains]. Overwrites a same-name entry.
   * Caller is responsible for length validation; the store does
   * not constrain band count.
   */
  fun save(name: String, gains: FloatArray) {
    synchronized(writeLock) {
      val current = loadRaw()
      current.put(name, gainsToJsonArray(gains))
      prefs.edit().putString(STORE_KEY, current.toString()).commit()
    }
  }

  /** Remove the named preset; no-op if absent. */
  fun delete(name: String) {
    synchronized(writeLock) {
      val current = loadRaw()
      if (!current.has(name)) return
      current.remove(name)
      prefs.edit().putString(STORE_KEY, current.toString()).apply()
    }
  }

  /**
   * Snapshot every persisted preset as [EqualizerPreset] (with
   * `builtin = false`). Order is unspecified — the consumer
   * typically merges these with the built-in list and sorts at
   * the JS layer.
   */
  fun loadAll(): List<EqualizerPreset> {
    val raw = loadRaw()
    val result = mutableListOf<EqualizerPreset>()
    val keys = raw.keys()
    while (keys.hasNext()) {
      val name = keys.next()
      val arr = raw.optJSONArray(name) ?: continue
      val gains = DoubleArray(arr.length()) { i -> arr.optDouble(i, 0.0) }
      result.add(EqualizerPreset(name = name, gains = gains, builtin = false))
    }
    return result
  }

  private fun loadRaw(): JSONObject {
    val stored = prefs.getString(STORE_KEY, null) ?: return JSONObject()
    return try {
      JSONObject(stored)
    } catch (e: JSONException) {
      Log.w(LOG_TAG, "Custom preset JSON failed to parse; falling back to empty store", e)
      JSONObject()
    }
  }

  private fun gainsToJsonArray(gains: FloatArray): JSONArray {
    val arr = JSONArray()
    for (g in gains) arr.put(g.toDouble())
    return arr
  }
}
