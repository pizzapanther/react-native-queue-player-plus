@testable import QueuePlayer

extension TrackPlayer {
  /// Runs `body` on `playerQueue` and hands its value back — the read side of
  /// the tests' player-state assertions. The library has no async getter that
  /// needs this; its own hops are `onPlayerQueue` and `onPlayerQueueSync`.
  func onPlayerQueueValue<T>(_ body: @escaping () -> T) async -> T {
    await withCheckedContinuation { (cont: CheckedContinuation<T, Never>) in
      playerQueue.async {
        cont.resume(returning: body())
      }
    }
  }
}
