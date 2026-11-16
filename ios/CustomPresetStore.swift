import Foundation

/// UserDefaults-backed persistence for user-defined equalizer
/// presets. Keys live under [STORE_KEY] as a single JSON object —
/// `{ "MyPreset": [0.0, 1.5, ...], "Studio": [...] }` — so a
/// snapshot is one small read.
///
/// Malformed JSON (manual edit, partial write across versions)
/// resolves to an empty preset list rather than throwing — the
/// recovery path is "user re-saves their preset" rather than a
/// crash.
///
/// Threading: `UserDefaults` is documented thread-safe for
/// individual edits, but the load-modify-write sequence in `save`
/// and `delete` is not atomic across threads on its own. Both
/// methods take an internal lock so concurrent writers don't drop
/// each other's edits. Mirrors the Android `CustomPresetStore.kt`
/// shape.
final class CustomPresetStore {

  private static let storeKey = "com.queueplayer.equalizer.customPresets"

  private let defaults: UserDefaults
  private let writeQueue = DispatchQueue(label: "com.margelo.nitro.queueplayer.customPresetStore")

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
  }

  /// Persist `name` → `gains`. Overwrites a same-name entry.
  func save(name: String, gains: [Double]) {
    writeQueue.sync {
      var current = loadRaw()
      current[name] = gains
      defaults.set(jsonString(from: current), forKey: Self.storeKey)
    }
  }

  /// Remove the named preset; no-op if absent.
  func delete(name: String) {
    writeQueue.sync {
      var current = loadRaw()
      if current.removeValue(forKey: name) != nil {
        defaults.set(jsonString(from: current), forKey: Self.storeKey)
      }
    }
  }

  /// Snapshot every persisted preset as `EqualizerPreset` (with
  /// `builtin = false`). Order is unspecified — consumers
  /// typically merge with the built-ins and sort at the JS layer.
  func loadAll() -> [EqualizerPreset] {
    let raw = loadRaw()
    return raw.map { name, gains in
      EqualizerPreset(name: name, gains: gains, builtin: false)
    }
  }

  // MARK: - JSON I/O

  private func loadRaw() -> [String: [Double]] {
    guard let stored = defaults.string(forKey: Self.storeKey),
          let data = stored.data(using: .utf8),
          let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: [Double]]
    else {
      return [:]
    }
    return parsed
  }

  private func jsonString(from dict: [String: [Double]]) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: dict),
          let str = String(data: data, encoding: .utf8) else {
      return "{}"
    }
    return str
  }
}
