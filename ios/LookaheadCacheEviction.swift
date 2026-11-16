import Foundation

/// Pure-logic LRU eviction policy for the iOS lookahead cache.
///
/// Tracks in the active lookahead window have recent
/// `lastAccessTick` values (the prefetcher just downloaded them or
/// the player just hit them), so they sort to the *end* of the LRU
/// order naturally. Pure-tick ordering is the proxy for "in window"
/// — no exclusion list needed.
///
/// Eviction by *budget* enforcement: called after each successful
/// download in [LookaheadCache.download], plus on a
/// `LookaheadCache.setMaxSizeBytes(_:)` shrink and
/// `clearLookaheadCache()`.
internal enum LookaheadCacheEviction {

  /// Compute which entries to evict so total cached bytes drop to
  /// `maxBytes` or below. Sorted ascending by `lastAccessTick`
  /// (oldest first); evicts in order until the cumulative size of
  /// remaining entries is within budget.
  ///
  /// `protectedUrls` skips matching entries from the eviction
  /// candidate set entirely — even if they're the LRU-oldest. The
  /// download body uses this to protect the just-appended entry:
  /// without it, a single download larger than the entire budget
  /// would silently evict itself, leaving the consumer with a
  /// successful-but-immediately-discarded prefetch. With it, the
  /// new entry stays + only previously-cached entries are eviction
  /// candidates. Compared against `Entry.trackId` (which holds the
  /// source URL — see `LookaheadCache.Entry`).
  ///
  /// Returns an empty array when the cache is already under budget
  /// or when no non-protected entries are eviction candidates.
  /// Returns evicted entries in eviction order — caller can use the
  /// list to delete files + remove from the index in one pass.
  ///
  /// Pure: no FS access, no index mutation. The caller orchestrates
  /// the actual file removal + plist persist.
  /// `useFifoOrder` picks the sort key: `false` = LRU (`lastAccessTick`),
  /// `true` = FIFO (`insertionOrderTick`). A plain `Bool` — not the generated
  /// `EvictionPolicy` enum — keeps this type off cxx-interop's bridge, which
  /// otherwise drops symbols referencing the generated enum from the emitted
  /// module interface. The caller maps the configured `EvictionPolicy` to it.
  static func computeEvictions(
    entries: [LookaheadCache.Entry],
    maxBytes: UInt64,
    useFifoOrder: Bool = false,
    protectedUrls: Set<String> = []
  ) -> [LookaheadCache.Entry] {
    let totalBytes = entries.reduce(UInt64(0)) { $0 + $1.sizeBytes }
    if totalBytes <= maxBytes { return [] }

    // Oldest-first. LRU orders by `lastAccessTick` (bumped on every playback
    // touch); FIFO by `insertionOrderTick` (stamped once at download, never
    // bumped). Stable tie-break on trackId for determinism when ticks collide.
    let candidates = entries.filter { !protectedUrls.contains($0.trackId) }
    let oldestFirst = candidates.sorted {
      let lhs = useFifoOrder ? $0.insertionOrderTick : $0.lastAccessTick
      let rhs = useFifoOrder ? $1.insertionOrderTick : $1.lastAccessTick
      if lhs != rhs { return lhs < rhs }
      return $0.trackId < $1.trackId
    }

    var remainingBytes = totalBytes
    var evicted: [LookaheadCache.Entry] = []
    for entry in oldestFirst {
      if remainingBytes <= maxBytes { break }
      evicted.append(entry)
      // Defensive saturating subtraction — `&-=` underflows silently
      // on a corrupt entry whose recorded sizeBytes exceeds the
      // running total (shouldn't happen with the in-process index
      // as the source of truth, but the plist could conceivably
      // round-trip through a corrupt-on-disk state). Underflow
      // would wrap to UInt64.max + evict everything.
      if entry.sizeBytes >= remainingBytes {
        remainingBytes = 0
      } else {
        remainingBytes -= entry.sizeBytes
      }
    }
    return evicted
  }
}
