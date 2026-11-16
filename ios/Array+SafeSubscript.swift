extension Array {
  /// Bounds-checked subscript that returns nil for out-of-range indices.
  subscript(safe index: Int) -> Element? {
    indices.contains(index) ? self[index] : nil
  }
}
