import Foundation

/// Whether the network returning should trigger a retry.
///
/// Pure and primitive-typed so it can be asserted directly: the player state
/// it derives from is a Nitrogen-backed type, whose members are not reachable
/// from the test module under cxx-interop.
enum NetworkRecoveryPolicy {

  /// - Parameters:
  ///   - wantsToPlay: the user's transport intent.
  ///   - isInterrupted: whether an audio-session interruption is in force.
  ///   - isInError: whether the current track has a failure that has not
  ///     recovered. Read from the error key rather than the reported state:
  ///     the state has moved on by the time a network returns.
  ///   - isTransient: whether that failure is one a network can cure
  ///     (`PlaybackErrorMapping.isTransient`). A codec, format or auth
  ///     failure is not, and rebuilding it on every network edge would
  ///     re-surface the same error each time.
  ///
  /// All four must hold. Intent alone would start audio that was never
  /// playing when the network happens to change; ignoring the interruption
  /// would start it mid-call; without the error check every interface
  /// handoff would rebuild a perfectly healthy item; and without the
  /// transient check a fatal failure would be retried without end.
  @_spi(QueuePlayerTests)
  public static func shouldRetry(
    wantsToPlay: Bool, isInterrupted: Bool, isInError: Bool, isTransient: Bool
  ) -> Bool {
    wantsToPlay && !isInterrupted && isInError && isTransient
  }
}
