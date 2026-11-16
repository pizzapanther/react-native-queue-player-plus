import AVFoundation
import Foundation

// `StateChangeReason` and `RepeatMode` are Nitrogen-generated enums in
// the same module — no extra imports needed.

/// Strategy abstraction over the audio output pipeline.
///
/// Two concrete implementations:
///  - `GaplessEngine`: single `AVQueuePlayer`, native gapless via item chaining.
///  - `CrossfadeEngine`: dual `AVPlayer` with equal-power gain ramping
///    via `AVMutableAudioMix.setVolumeRamp(...)` at track boundaries.
///
/// The engine owns the player instance(s), audio-thread effects, and
/// track-boundary scheduling. The lib's `TrackPlayer` holds the canonical
/// queue model + listener registries + system-side surfaces (NowPlaying,
/// RemoteCommands, AudioSession, ArtworkResolver) and routes transport /
/// queue calls through whichever engine is active. An engine swap (gapless ↔
/// crossfade) is `TrackPlayer.applyEngineSwapForMode`: it builds the new
/// engine, seeds it through `setItems`, then releases the old one.
///
/// Threading: `TrackPlayer` calls every method on its `playerQueue`, a
/// private serial queue, and every delegate fire lands on that queue.
/// The crossfade engine is constructed confined to it and hops each
/// AVFoundation observer, notification and timer callback onto it before
/// firing (a few fires are also synchronous inside a `setItems` / `seek`
/// / `remove` call). The gapless engine does not use the delegate at all:
/// `TrackPlayer` observes its player directly and does its own hop. State
/// queries (positions, indices, isPlaying) read the underlying player from
/// that queue. An engine is responsible for any internal off-queue work
/// (the crossfade engine's standby preroll) and for returning to the queue
/// before touching shared state.
protocol PlaybackEngine: AnyObject {

  /// Release the underlying player(s) and stop all background work.
  /// After `release`, no further methods are valid.
  func release()

  // MARK: - Queue mutation

  /// Drain the queue and install [items], beginning playback at
  /// [startIndex]. Callers pass a run that may start behind the playhead, so
  /// [startIndex] names where in that run to begin rather than where to cut
  /// it: an engine holds every item it is given unless it cannot.
  ///
  /// `GaplessEngine` cannot: `AVQueuePlayer` is forward-only and consumes
  /// finished items, so it drops everything before [startIndex] and
  /// `allMediaItems.count == max(0, items.count - startIndex)` there.
  /// `CrossfadeEngine` holds the whole run, which is what lets it keep a
  /// trail behind the playhead for an immediate backward skip.
  ///
  /// Active index resolves to `items[startIndex]` at
  /// [startPositionSeconds].
  ///
  /// `removeAllItems()` runs unconditionally before the slice
  /// install. Empty [items], [startIndex] out of range, or
  /// [startIndex] >= items.count all leave the queue drained.
  /// Player rate is captured pre-drain and restored post-install
  /// so a `playing → setItems → still-playing` sequence survives
  /// the rebuild.
  ///
  /// `GaplessEngine` implementation: `removeAllItems` + insert loop
  /// + seek. `CrossfadeEngine` implementation: assign queue snapshot
  /// + active-index tracking; leading leg loads items[startIndex],
  /// standby preroll arms for items[startIndex + 1] when applicable.
  ///
  /// Caller (`TrackPlayer`) constructs `[AVPlayerItem]` via
  /// `AVQueueBuilder` so consumer config (HTTP headers, user-agent,
  /// read-through server routing) is applied before items reach
  /// the engine.
  func setItems(
    _ items: [AVPlayerItem],
    startIndex: Int,
    startPositionSeconds: TimeInterval
  )

  /// Surgically insert [items] after [after]. `nil` [after]
  /// appends to the END of the queue (matches
  /// `AVQueuePlayer.insert(_:after:)`'s contract: `nil` afterItem
  /// → end of queue).
  ///
  /// Returns `true` when the engine accepted every item without
  /// falling back to a full rebuild. Returns `false` when the
  /// engine could not honour the surgical insert (`AVQueuePlayer.canInsert`
  /// returned false on at least one item, or the standby leg
  /// could not absorb the new item) — the caller treats `false`
  /// as a signal to invoke `setItems` with the authoritative
  /// queue snapshot.
  @discardableResult
  func insertItems(
    _ items: [AVPlayerItem],
    after: AVPlayerItem?
  ) -> Bool

  /// Remove [item] from the engine's queue. Removing the current
  /// item on `GaplessEngine` triggers `AVQueuePlayer` auto-advance
  /// to the next queued item; on `CrossfadeEngine` removing the
  /// current item cancels any pending fade and replaces the leading
  /// leg's item with the one now at that index (the previous item when
  /// the removed one was last), or drains when the queue is empty.
  /// No-op when [item] is not present.
  func remove(_ item: AVPlayerItem)

  /// Remove every item from the queue. After this call,
  /// `currentMediaItem == nil` and `allMediaItems` is empty. Used
  /// by `TrackPlayer.fullRebuildPlayerQueue` when it installs a whole slice,
  /// before re-inserting
  /// the post-mutation queue snapshot.
  func removeAllItems()

  /// Whether [item] can be inserted after [after] without
  /// disrupting playback. Mirrors `AVQueuePlayer.canInsert`:
  /// `nil` [after] checks insert-at-end of the queue.
  /// `CrossfadeEngine` returns `true` when the standby leg can
  /// accept the item.
  func canInsert(_ item: AVPlayerItem, after: AVPlayerItem?) -> Bool

  // MARK: - Transport

  func play()
  func pause()
  func stop()
  /// Seek to [index] within the engine's own queue, at [position].
  ///
  /// [index] is an index into the slice this engine was handed, **not** a
  /// queue position — the two differ whenever the caller installed anything
  /// other than the whole track list. Callers translate by `queueItemId`
  /// identity before calling.
  func seek(toIndex index: Int, position: TimeInterval)
  func skipToNext()
  func skipToPrevious()

  // MARK: - Config

  func setVolume(_ volume: Float)
  func setPlaybackSpeed(_ speed: Float)
  /// Set the persisted playback speed WITHOUT touching the live rate
  /// (so it won't start a paused player — `setPlaybackSpeed` sets
  /// `AVPlayer.rate`, which would). `play()` applies it on the next
  /// resume. Used to carry the user's speed into a freshly-built engine
  /// at configure / engine-swap-while-paused without auto-starting.
  func seedPlaybackSpeed(_ speed: Float)
  func setRepeatMode(_ mode: RepeatMode)
  /// Set how pitch behaves when speed != 1.0. The engine stamps the
  /// resolved `AVAudioTimePitchAlgorithm` on its live item(s) and on
  /// every item it later attaches. Non-local engines (cast / AirPlay)
  /// no-op — the receiver owns its own rendering.
  func setPitchCorrectionMode(_ mode: PitchCorrectionMode)

  // MARK: - State

  /// Current item index within the engine's own queue. Returns `-1`
  /// when the queue is empty (matches the canonical sentinel in
  /// `TrackPlayer`).
  ///
  /// This is **not** a queue position — it counts within the slice the
  /// engine was handed. Resolve the active track by item identity
  /// (`currentMediaItem.queueItemId`) rather than reading this as an index
  /// into the consumer's track list.
  var currentMediaItemIndex: Int { get }
  var currentPositionSeconds: TimeInterval { get }
  var currentDurationSeconds: TimeInterval { get }
  var bufferedPositionSeconds: TimeInterval { get }
  var isPlaying: Bool { get }
  /// The underlying player's time-control status. Drives the unified
  /// `PlayerState` derivation (`PlayerStateDerivation`), so
  /// `.waitingToPlayAtSpecifiedRate` (initial load / rebuffer) surfaces as
  /// `.buffering`. Crossfade reports the active leg's status.
  var timeControlStatus: AVPlayer.TimeControlStatus { get }
  var currentMediaItem: AVPlayerItem? { get }

  /// Snapshot of every queued `AVPlayerItem`. Called rarely
  /// (engine swap, diagnostic dumps); impl may allocate. The
  /// canonical queue model lives on `TrackPlayer`; engines do not
  /// duplicate it for hot-path reads.
  var allMediaItems: [AVPlayerItem] { get }

  /// Identity of the item actually being heard.
  ///
  /// `currentMediaItem` reports the incoming leg the moment a crossfade is in
  /// flight, which is right for "what is the queue moving to" and wrong for
  /// "where is the run anchored". Callers that reason about the installed run
  /// — where it starts, what may be trimmed behind it — read this instead, and
  /// it stays on the outgoing leg until the fade completes.
  var leadingQueueItemId: String? { get }

  /// Identities of the items attached to the engine's players right now.
  ///
  /// Not the whole installed run — only what is loaded and about to be, or
  /// already, heard. A queue mutation that touches one of these has to be
  /// applied to the engine at once; one that does not can wait for a fade to
  /// finish rather than cancelling it.
  ///
  /// Mid-fade a crossfading engine reports both legs, which is the only
  /// truthful answer while two items are audible.
  ///
  /// Required rather than defaulted, for the same reason as
  /// `isFadePendingOrActive` and more so: an engine that under-reports has a
  /// mutation to an audible item deferred, and the fade then runs into a track
  /// the queue no longer has.
  var attachedQueueItemIds: [String] { get }

  /// Whether a crossfade is scheduled or running.
  ///
  /// Every branch that changes the installed items cancels an in-flight fade,
  /// so work that can wait — extending the run, trimming behind it — asks
  /// first rather than cutting the fade short. Required rather than defaulted:
  /// an engine that answers wrongly has its fades cut short silently, so each
  /// one states its own answer.
  var isFadePendingOrActive: Bool { get }

  // MARK: - Effect chain

  /// Set the single active `AudioMixProvider`. The engine consults
  /// it when building or refreshing each `AVPlayerItem`'s
  /// `audioMix`. Setting `nil` detaches; calling with a fresh
  /// provider replaces the previous one. The engine holds the
  /// provider strongly until it is replaced or set to `nil`.
  ///
  /// Single-slot by design — equalizer + visualizer share a single
  /// `MTAudioProcessingTap` chain inside one provider rather than
  /// composing multiple providers.
  func setAudioMixProvider(_ provider: AudioMixProvider?)

  /// Re-evaluate every existing item's `audioMix` by re-asking the
  /// active provider for each. Called by the provider when its
  /// internal state changes (e.g. EQ enable / disable toggle).
  /// No-op when no provider is set.
  func refreshAllItemMixes()

  // MARK: - Delegate

  /// Single delegate receiving state / track / error /
  /// queue-end events. `TrackPlayer` is the canonical delegate; the
  /// crossfade engine fires it on `playerQueue`, the gapless engine
  /// never fires it (`TrackPlayer` leaves its delegate nil).
  ///
  /// Threading: set on `playerQueue`, the queue the fires run on, so a
  /// write cannot race a fire during an engine swap.
  var delegate: PlaybackEngineDelegate? { get set }
}

/// Engine → TrackPlayer event channel.
///
/// Delivers raw AVFoundation-flavored signals; the delegate
/// (typically `TrackPlayer`) consults the canonical queue model +
/// lib-internal pending-reason flags to compute the JS-facing event
/// shape. The Android counterpart fires structurally similar
/// callbacks with Media3-flavored parameters in `PlaybackEngine.kt`.
protocol PlaybackEngineDelegate: AnyObject {

  /// Fires when the player's effective state may have changed
  /// (rate / status / timeControlStatus transitioned). The delegate
  /// consults [engine] state, the canonical queue model, and
  /// lib-internal pending-reason flags to compute the emitted
  /// `PlayerState`.
  ///
  /// [reason] carries the lib's typed `StateChangeReason` when the
  /// underlying signal was an interruption / route-change / focus
  /// event the engine has already classified. The delegate stamps
  /// it onto its own pending-reason channel before `PlayerState`
  /// dedup runs. `nil` when the signal came from a generic
  /// `rate` / `status` / `timeControlStatus` change with no
  /// classification — the delegate preserves any previously-stamped
  /// pending reason in that case.
  func engineStateMaybeChanged(
    _ engine: PlaybackEngine,
    reason: StateChangeReason?
  )

  /// Fires on `AVPlayer.currentItem` KVO when the active item
  /// changes (auto-advance, user skip, queue replace). The
  /// delegate translates against its own state machine (e.g.
  /// consuming a pending user-skip reason).
  func engineDidTransitionTrack(_ engine: PlaybackEngine)

  /// Fires when the active item's `tracks` populate with format
  /// details (codec / sample rate / channel count / bitrate). Drives
  /// `TrackPlayer.refreshNowPlayingFormatForActiveItem`. Android
  /// counterpart fires on `Player.Listener.onTracksChanged`.
  func engineActiveItemFormatChanged(_ engine: PlaybackEngine)

  /// A playback failure, with the item it happened on when the engine can name
  /// it.
  ///
  /// The item is what makes the automatic retry reachable: the retry is keyed
  /// by `queueItemId`, so a failure reported without one is surfaced to the
  /// consumer and never retried. Engines that know which item failed must say
  /// so — both of `CrossfadeEngine`'s failure paths do, since each is already
  /// filtered to the leading leg's current item.
  func engineDidFailWithError(
    _ engine: PlaybackEngine, error: Error, item: AVPlayerItem?)

  /// Fires on every `AVPlayerItemDidPlayToEndTime` — i.e. each
  /// item's natural end, including non-terminal items in a queue.
  /// Drives the gapless-gap timing log (DEBUG-only diagnostic) and
  /// is the iOS counterpart of Android's
  /// `onAutoTransitionDiscontinuity`.
  func engineDidPlayItemToEnd(_ engine: PlaybackEngine)

  /// Fires when the queue ends naturally — the LAST item's
  /// `didPlayToEndTime` with no further items + repeat mode allows
  /// completion. The delegate consults the canonical queue model
  /// + repeat mode to decide whether this is queue-end or a
  /// per-track end that should be ignored. Note that
  /// [engineDidPlayItemToEnd] fires for the same item; this
  /// callback fires AFTER it for the queue-end case only.
  func enginePlaybackEnded(_ engine: PlaybackEngine)

  /// Fires when a crossfade begins ramping — i.e. the standby
  /// player has started playback at volume 0 and the leading leg's
  /// fade-out has begun. The delegate treats this as the canonical
  /// "active track changed" trigger and emits `onTrackChange` +
  /// repoints `MPNowPlayingInfoCenter` to the incoming track. The
  /// subsequent `engineDidTransitionTrack` for the same incoming
  /// index is a structural KVO echo and must be deduplicated.
  ///
  /// Crossfade-only — `GaplessEngine` never invokes this. The
  /// incoming track is read from `engine.currentMediaItem`, which
  /// already resolves to the standby leg once the fade is in flight;
  /// the delegate maps that item to a queue position by identity
  /// rather than trusting an engine-relative index.
  func engineCrossfadeDidBegin(_ engine: PlaybackEngine)

  /// Fires when an in-flight crossfade is cancelled before
  /// `swapRoles` runs (pause / stop / audio-focus loss / queue
  /// mutation that invalidates the standby leg). The delegate
  /// reverts JS-facing state to the leading leg — emit
  /// `onTrackChange(reason: .crossfadeCancelled)` for the leading
  /// track + repoint `MPNowPlayingInfoCenter` back. Pairs with
  /// `engineCrossfadeDidBegin`: only fires when a `DidBegin` was
  /// fired earlier and the fade did not complete.
  ///
  /// Crossfade-only. The reverted track is read from
  /// `engine.currentMediaItem`, which resolves back to the leading leg
  /// once `fadeInFlight` clears.
  func engineCrossfadeDidCancel(_ engine: PlaybackEngine)

  /// A fade is no longer scheduled or running, however it ended.
  ///
  /// Distinct from `engineCrossfadeDidCancel`, which reports that an
  /// *in-flight* fade was reverted and carries a track change with it. This
  /// fires for every end of every fade — completed, cancelled, abandoned
  /// during the arm window, timed out — because work that waits on a fade has
  /// to be released by all of them, not only the ones a listener cares about.
  func engineFadeDidSettle(_ engine: PlaybackEngine)

  /// The active leg's buffer flags changed (`isPlaybackBufferEmpty` /
  /// `isPlaybackLikelyToKeepUp` / `isPlaybackBufferFull`). Prompts a
  /// buffer-state recompute so the discrete `onBufferStateChange` surface
  /// tracks the crossfade engine's leg the same way it tracks the gapless
  /// `AVQueuePlayer`'s item KVO. Buffer state only — no direct player-state
  /// emission.
  func engineBufferStateMaybeChanged(_ engine: PlaybackEngine)
}

extension PlaybackEngineDelegate {
  /// Nothing to release by default.
  func engineFadeDidSettle(_ engine: PlaybackEngine) {}
  /// The gapless engine drives buffer recompute through its own item KVO, not
  /// this delegate; conformers that do not surface buffer state ignore it.
  func engineBufferStateMaybeChanged(_ engine: PlaybackEngine) {}
}

extension PlaybackEngine {
  /// An engine with one leg is always leading with the item it reports as
  /// current; only a crossfading engine has two ends that can disagree.
  var leadingQueueItemId: String? { currentMediaItem?.queueItemId }

}

/// Single-slot audio-mix provider. The equalizer + visualizer share
/// a single provider that builds an `MTAudioProcessingTap`-backed
/// `AVAudioMix` per `AVPlayerItem` the engine constructs.
///
/// `audioMix(for:)` is called on the mix dispatch target — `TrackPlayer`
/// points it at `playerQueue`; implementations may allocate freshly or
/// cache + reuse. Returning `nil` skips
/// attachment for that item — used to bypass mix on items that
/// don't support a tap (HLS sources).
protocol AudioMixProvider: AnyObject {
  /// Build the audio mix for [item], or return `nil` to skip.
  func audioMix(for item: AVPlayerItem) -> AVAudioMix?

  /// Declare which asset is active, without building a mix. `audioMix(for:)`
  /// announces whatever item it is handed, so a caller that refreshes several
  /// items uses this to name the one actually playing.
  func noteActiveAsset(_ asset: AVAsset?)

  /// Whether any registered consumer wants the tap installed right now (the
  /// equaliser is enabled, or a visualizer has subscribers). The crossfade
  /// engine holds a freshly-seated leading leg at rate 0 until its
  /// `.readyToPlay` mix install lands only when this is true — a mix set once
  /// the leg is already rendering is dropped, silencing the tap — so the
  /// common no-tap path is left to start immediately.
  func anyConsumerWantsTap() -> Bool
}

extension AudioMixProvider {
  /// A provider with no consumer model (a test double) never holds a leg for a
  /// tap; only `AudioTapProvider` overrides this.
  func anyConsumerWantsTap() -> Bool { false }
}

extension AudioMixProvider {
  /// Providers that track no active asset need no behaviour here.
  func noteActiveAsset(_ asset: AVAsset?) {}
}
