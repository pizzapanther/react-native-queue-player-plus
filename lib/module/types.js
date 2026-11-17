"use strict";

/**
 * Public value types for react-native-queue-player.
 *
 * All Nitro HybridObject interfaces live in their own `.nitro.ts` files
 * (TrackPlayer, Equalizer, Visualizer, CastManager). The **pure value
 * types** those objects exchange — every `interface` / `type` alias that
 * is *not* a HybridObject — live here, so there is one canonical home for
 * anything a consumer might want to `import` by name.
 *
 * Naming rules:
 *   - Interfaces are structural (`TrackItem`, `CastDevice`, …).
 *   - String-literal unions are top-level named aliases (`PlayerState`,
 *     `CastProtocol`, …), never inline in a field type — Nitrogen's C++
 *     codegen requires named enums.
 *   - Enum-like types with one real value include a reserved placeholder
 *     (e.g. `EvictionPolicy = 'lru' | 'fifo'`) to disambiguate from
 *     plain `string` during Nitrogen codegen. See NOTES.md §1.4.
 */

// ============================================================
// Player — shared playback value types
// ============================================================

/** A playback-timed metadata sample. Missing fields were not supplied by the stream. */

/** Patch for a queue entry. Omitted fields keep their existing values. */

/**
 * Result returned by `shuffleQueue()`. The consumer app uses this
 * snapshot to immediately reflect the new ordering in its UI.
 *
 * - `tracks`: the post-shuffle queue (Fisher-Yates permutation of the
 *   pre-shuffle queue). The same `TrackItem` references the consumer
 *   passed in, just reordered.
 * - `currentIndex`: position of the now-current track. Always `0` for
 *   non-empty queues; `-1` for an empty queue.
 * - `currentTrack`: convenience accessor for `tracks[currentIndex]`.
 *   `undefined` for empty queues.
 */

/**
 * High-level content classification used by the lib to drive
 * platform audio-session shape (iOS mode + Android
 * `AudioAttributes.contentType`).
 */

/**
 * Optional AVAudioSession `CategoryOptions` flags. iOS-effective
 * (Android uses auto-ducking via audio focus and ignores these).
 */

/**
 * Discrete buffer state for the current track, derived from the platform's
 * native buffer signals (independent of the 2 Hz progress tick, so a spinner /
 * stall indicator reacts immediately):
 *
 * - `'full'` — enough is buffered to play smoothly from the current position
 *   (iOS `playbackLikelyToKeepUp` / `playbackBufferFull`; Android
 *   `STATE_READY`). Hide the loading spinner.
 * - `'buffering'` — loading, and playback has **not started yet** for this
 *   track (the initial buffer, even when play was requested), or loading while
 *   not actively trying to play. This is the expected, non-urgent loading case.
 * - `'stalled'` — playback **had started** and is now rebuffering mid-play
 *   (it wants to continue but ran out of data). This is the case where a
 *   spinner interrupts audio the user was already hearing.
 * - `'empty'` — nothing is loaded to play (iOS: no current item; Android
 *   `STATE_IDLE`).
 *
 * `'full'` describes playback health, not that the whole track is downloaded —
 * for buffered-ahead length in seconds use `PlayerProgress.buffered`.
 */

/**
 * Playback mode. §4.2 specifies `'gapless' | { crossfade: number }`; Nitrogen
 * can't represent a discriminated union with a string-literal tag (see
 * NOTES.md §1.2), so the API uses a single interface with a `kind` enum
 * field plus optional per-variant payload.
 */

/**
 * Canonical bounds for `PlaybackMode.crossfadeDurationMs`. Mirrors
 * the native validation constants — consumers building stepper /
 * slider UIs can read from here instead of hardcoding magic numbers.
 */
export const crossfadeDurationBounds = {
  /** Minimum accepted crossfade duration in ms. */
  minMs: 1_000,
  /** Maximum accepted crossfade duration in ms. */
  maxMs: 12_000,
  /** Quantisation step in ms — values must be multiples of this. */
  stepMs: 500
};

/**
 * Queue repeat behaviour:
 * - `'off'` — play through the queue once and stop at the end.
 * - `'queue'` — loop the whole queue; skip wraps at both ends.
 * - `'track'` — repeat-one: the current track replays on natural end.
 *   A user skip still navigates the queue with no wrap (exactly like
 *   `'off'`), and repeat-one persists so the newly-current track is the
 *   one that loops.
 */

/**
 * How `skipToPrevious` (and the OS Previous control) behaves.
 * - `'previous'` — always go to the previous track (the default; current
 *   behavior).
 * - `'restart-or-previous'` — restart the current track (seek to 0) when it
 *   is past ~3 s, otherwise go to the previous track. The standard
 *   music-player model. See `PlayerConfig.skipToPreviousBehavior`.
 */

/**
 * How the lock-screen / notification skip controls behave: skip between queue
 * tracks (`'track'`) or jump forward/back within the current track by a fixed
 * interval (`'interval'`).
 */

/**
 * Lock-screen / notification remote-control configuration. Wrapped in a struct
 * so fields can be added without a breaking change.
 */

/**
 * ReplayGain loudness-normalisation mode.
 *
 * - `'off'`: no gain applied; playback is sample-perfect relative
 *   to the source.
 * - `'track'`: read `REPLAYGAIN_TRACK_GAIN` per track. Best for
 *   shuffled / mixed playlists — every track plays at roughly the
 *   same perceived loudness.
 * - `'album'`: read `REPLAYGAIN_ALBUM_GAIN` per track (preserves
 *   the album's intentional cross-track dynamics — e.g. a quiet
 *   intro vs a loud climax). Strict — does NOT fall back to track
 *   gain when a track has no album tag (RG is simply inactive for
 *   that track; `replayGainActive` reports `false`).
 *
 * Auto-mode (lib heuristically picks track-vs-album from queue
 * context) is intentionally NOT supported — the consuming app
 * knows whether the user is playing an album front-to-back vs
 * shuffling a mixed playlist, and should call this setter
 * accordingly.
 */

/**
 * How pitch behaves when `playbackSpeed != 1.0`:
 * - `none` — no pitch correction; pitch follows rate (the
 *   "chipmunk" effect on speed-up, deepening on slow-down).
 * - `voice` — speech-tuned time-stretch; pitch is preserved.
 *   Cheapest correct option and the default.
 * - `music` — music-tuned time-stretch that preserves transients
 *   (percussion, attacks) on speed change.
 *
 * Auto-bypassed at `playbackSpeed == 1.0` (nothing to stretch).
 */

/**
 * Progress snapshot returned by `getProgress` and emitted by `onProgress`. Positions in seconds,
 * driven by the native periodic time observer at ~2 Hz. Wrapped in a
 * struct (rather than three positional `number` callback args) so
 * future progress fields can be added without a breaking spec change.
 */

/**
 * Why the queue contents or order changed, reported by `onQueueChange`.
 * Each value maps 1:1 to the public mutation that produced it.
 */

// updateMetadata edited a queue entry in place

/**
 * What a queue mutation did, delivered by `onQueueChange` alongside the reason.
 *
 * Describes the change rather than re-sending the queue, so a reorder costs two
 * numbers instead of one `TrackItem` per entry. Which fields carry meaning
 * depends on the reason; the rest are empty or `-1`.
 *
 * Apply it to your own copy of the queue, or call `getQueue()` if you would
 * rather re-read than track it. `useQueue()` does the former for you.
 *
 * **Not every emptying of the queue is reported.** `destroy()` and `configure()`
 * clear it without an event, and `configure()` runs that teardown on every call,
 * so a mirror held across one is stale with nothing to signal it.
 */

/**
 * How the active track is being played:
 * - `'local'` — `track.url` is a `file://` URL.
 * - `'cached'` — `track.url` is `http(s)://` and the lookahead cache holds
 *   a complete copy; native playback reads from the local cache file.
 * - `'streaming'` — `track.url` is `http(s)://` and the lookahead cache
 *   does NOT have a complete copy; native playback reads from the network
 *   (a partial cache may exist, but bytes are still arriving).
 *
 * Classified at currentItem-change time (the moment the new track becomes
 * current). A track that's mid-download surfaces as `'streaming'`; the next
 * track-change re-classifies once the cache has more.
 *
 * `undefined` when there is no active track (empty queue, post-clear, or
 * post-destroy) OR when `track.url` uses a scheme other than `file://` /
 * `http://` / `https://` (e.g. `data:`, `blob:`, custom schemes — the
 * classifier returns `undefined` rather than a misleading default).
 */

/**
 * Canonical audio codec identifier surfaced by `getNowPlayingFormat()`.
 * Both natives can detect the AAC family (LC / HE / HE-v2 / xHE) but
 * surfacing the profile requires extra parsing — v1 collapses to
 * `'aac'`. Consumers needing the AAC profile read it from the
 * platform-specific extras object's `codecs` field (RFC 6381 string
 * on Android) or off the iOS `formatDescriptions` directly.
 */

/**
 * Canonical container identifier. `'mp4'` covers `.m4a`, `.m4b`, `.m4r`,
 * and audio-only `.mp4`. `'hls'` is the playlist (.m3u8) container,
 * not a file format.
 */

/**
 * Snapshot of the currently-playing track's audio format. Surfaced
 * via `getNowPlayingFormat()` (sync getter — null until populated)
 * and `onNowPlayingFormatChange` (event-driven). Refresh on every
 * `onTrackChange` — the snapshot is keyed on whatever AVPlayerItem
 * (iOS) / MediaItem (Android) is currently active.
 *
 * Six fields are reliable on BOTH platforms for both `file://` and
 * `http(s)://` sources. Platform-specific extras live under the
 * optional `ios` / `android` objects so consumers can access richer
 * data when available without forcing cross-platform branching.
 */

/** iOS-specific NowPlayingFormat fields. */

/** Android-specific NowPlayingFormat fields. */

/**
 * Lib-standardized cross-platform error code vocabulary. Drives consumer
 * branching logic (`if (err.code === 'TIMEOUT') ...`) without per-platform
 * forking. Both iOS NSError + AVError codes and Android Media3
 * `PlaybackException.errorCode` values map onto this enum via the
 * `PlaybackErrorMapping` helper on each platform.
 *
 * **Transient (auto-retry eligible):** TIMEOUT, CONNECTION_LOST,
 * NETWORK_UNREACHABLE, SOURCE_UNREACHABLE, CANNOT_OPEN (when
 * iOS-NSURLError-wrapped — see iOS PlaybackErrorMapping for the
 * two-tier classifier), UNKNOWN.
 *
 * **Fatal (never retried):** HTTP_ERROR, AUTH_ERROR,
 * CLEARTEXT_NOT_PERMITTED, FILE_FORMAT_NOT_RECOGNIZED, DECODER_NOT_FOUND,
 * DECODING_FAILED.
 */

/**
 * Skip-capability snapshot. Emitted by `onSkipCapabilityChange` and
 * mirrored by the `useCanSkip()` hook. Reflects whether there's a
 * track to skip to under the current repeat mode. Under
 * `RepeatMode.track` (repeat-one) skip navigates with no wrap exactly
 * like `RepeatMode.off`, so a flag is `false` at the queue boundary
 * (no skip-next on the last track, no skip-previous on the first).
 *
 * Wrapped in a struct (rather than two separate boolean callback
 * args) so further capability flags can be added without a breaking
 * API change.
 */

/**
 * Sleep-timer snapshot — the current state of the native sleep timer, as
 * returned by `getSleepTimer()` and delivered to `onSleepTimerChange`.
 *
 * The timer is wall-clock: `endsAtEpochMs` is a fixed deadline that counts down
 * whether playback is playing or paused. In end-of-track mode the timer instead
 * waits for the current track to finish, so `endsAtEpochMs` is `null` until it
 * converts to a concrete deadline near the track's end.
 *
 * Wrapped in a struct (rather than positional callback args) so fields can be
 * added without a breaking change.
 */

// ============================================================
// Lookahead cache
// ============================================================

/**
 * Eviction policy for the lookahead cache, chosen at `configure()` via
 * {@link PlayerConfig.lookaheadCacheEvictionPolicy}.
 *
 * - `'lru'` (default): evict the least-recently-used track first — recency is
 *   bumped when a track is loaded for playback, so replayed tracks are kept.
 * - `'fifo'`: evict the oldest-inserted track first, regardless of replays
 *   ("keep the newest-fetched") — suits a forward-only prefetch queue.
 *
 * Construction-fixed like the cache size: changing it at `configure()` clears
 * the cache and rebuilds with the new policy.
 *
 * **Protecting the playing track differs by platform.** Eviction runs on every
 * download commit, so without protection the entry backing the track being
 * played can be deleted while it plays.
 *
 * - **iOS** protects under both policies.
 * - **Android** protects under `'fifo'` only. Media3's LRU evictor has no
 *   protected-key concept, and its recency bump is per byte-range span rather
 *   than per track, so a long track's already-played spans stay evictable while
 *   it is still playing. Choose `'fifo'` if that matters to you.
 */

// ============================================================
// Automotive / voice — handler request types
// ============================================================

/**
 * Which voice surface the request came from. Drives consumer's
 * resolver: iOS-only fields (`reference`, `playShuffled`,
 * `playbackRepeatMode`, `resumePlayback`, `playbackSpeed`,
 * `releaseDate`) are only populated when `origin === 'ios-siri'`.
 * The lib never invents an origin; if the request arrived from a
 * source the lib couldn't classify, `origin` is undefined.
 */

/**
 * Apple's `INMediaReference` mapped to a JS enum. Apple's `.unknown`
 * case is mapped to `undefined` (the field is absent rather than
 * carrying a sentinel string).
 *
 * - `'currentlyPlaying'` — request refers to whatever is currently
 *   playing (e.g. resume / "more like this" patterns).
 * - `'my'` — request is scoped to the user's library / owned content
 *   (any utterance that signals "from my collection", not just the
 *   literal word "my").
 */

/**
 * Apple's `INPlaybackRepeatMode` mapped to a JS enum. Apple's
 * `.unknown` case is mapped to `undefined`.
 */

/**
 * Apple's `INPlaybackQueueLocation` mapped to a JS enum. Drives
 * "play X next" / "add X to the queue" voice utterances — Siri's
 * NLU parses the queue-position hint and surfaces it here so the
 * consumer's resolver can choose between replacing the queue
 * (`'now'`, the default), inserting after the current track
 * (`'next'`), or appending to the tail (`'later'`). Apple's
 * `.unknown` case is mapped to `undefined`.
 *
 * iOS only — Google Assistant's App Actions schema has no
 * structurally-equivalent slot, so the field is always undefined
 * for `origin === 'android-assistant'` requests.
 */

/**
 * Inclusive year range derived from `INMediaSearch.releaseDate`. Both
 * bounds are optional — Siri may surface only one end of the range
 * for queries like "play music from 1997". When both are populated
 * they describe a closed interval (e.g. `{ startYear: 1990, endYear:
 * 1999 }` for "play 90s music").
 */

/**
 * iOS donation payload — the catalog items the consumer wants Siri's
 * NLU to recognise in voice queries. Maps to
 * `INVocabulary.setVocabularyStrings(_:of:)` with
 * `.mediaMusicArtistName` for `artists` and `.mediaPlaylistTitle`
 * for `playlists`. Apple's `INVocabularyStringType` has no case for
 * music album names or song titles; album matching happens at
 * intent-resolution time via the populated `MediaSearchRequest.album`
 * field, so a separate `albums` donation list has no destination in
 * Apple's API and is intentionally omitted.
 *
 * Both lists are required (empty array means "nothing to donate for
 * this taxonomy") — required `string[]` instead of `string[]?` keeps
 * the Nitrogen-generated C++ bridge cleanly typed; `std::optional<
 * std::vector<std::string>>` triggered a Swift C++ interop regression
 * on pre-existing files during a 5.1-time bisect.
 *
 * No-op on Android — Google Assistant has no in-app vocabulary
 * donation API; vocabulary discovery on Android is driven by App
 * Actions (`shortcuts.xml`) + the in-app browse tree. JSDoc in the
 * Hybrid spec documents the asymmetry to consumers.
 */

/**
 * Voice / automotive search request. The wire shape is a single
 * interface with an enum-like `type` field plus per-type optional
 * refinements — Nitrogen disallows discriminated unions with
 * string-literal tags (NOTES.md §1.2), so the variants are
 * encoded by which optional field is populated rather than by
 * separate type members.
 *
 * **Field semantics by `type`** (consumer should populate only
 * the matching field; others should be `undefined`):
 *
 * - `'artist'` — `artist` carries the searched artist name; `query`
 *   is the original spoken/typed string.
 * - `'album'` — `album` and (optionally) `artist` qualify the
 *   album lookup.
 * - `'song'` — `song` and (optionally) `artist` / `album` qualify
 *   the song lookup.
 * - `'playlist'` — `playlist` carries the playlist name.
 * - `'genre'` — `genre` carries the genre name.
 * - `undefined` — generic search; consumer falls back to a
 *   best-effort lookup across all media kinds using `query`.
 *
 * Combinations not described above (e.g. `type: 'song'` with a
 * populated `playlist` field) are ambiguous; consumers should
 * ignore mismatched fields rather than reject the request.
 *
 * **Voice-only fields** (`origin`, `reference`, `playShuffled`,
 * `playbackRepeatMode`, `resumePlayback`, `playbackSpeed`,
 * `releaseDate`) are populated when the request arrived through a
 * voice assistant. `origin` is the discriminator. On Android
 * (`origin === 'android-assistant'`) only `genre` is structurally
 * populated by Google's NLU beyond the base fields; the iOS-only
 * fields are always undefined. Consumer's resolver should branch
 * on `origin` before reading them.
 */

/**
 * Reason code for `onServiceReady` — distinguishes the very first
 * service-bind in a JS-process lifetime (`first-bind`) from a later
 * rebind that the lib detected by comparing the platform service
 * instance's identity against the previously-seen one
 * (`service-reborn`).
 *
 * **Android**: the platform foreground service can be killed by the
 * OS (memory pressure, doze, idle-bucket reaper) and re-created on
 * the next bind. The lib's auto-rebind watchdog reconnects and fires
 * `service-reborn`. Consumers typically respond by re-pushing the
 * browse snapshot (the new service instance starts with a fresh
 * `BrowseDataProvider`, so the cached snapshot is gone).
 *
 * **iOS**: no equivalent service-rebirth scenario — apps die fully
 * under memory pressure. The lib accepts the `onServiceReady`
 * registration for API parity but never invokes the callback on
 * iOS; consumers rely on `onCarConnect` for the iOS-side attach
 * signal.
 */

/**
 * Closed set of section-tab icons the lib knows how to render in a
 * platform-native way. Consumers pick from this enum rather than
 * passing platform-specific symbol names so the icon set stays
 * guard-railed and the lib can swap implementations (SF Symbol on
 * iOS today, Material icon on Android once Android Auto exposes a
 * tab-icon API — currently `androidx/media#646`).
 *
 * String values are stable so consumer apps can persist them.
 */
export let SectionIcon = /*#__PURE__*/function (SectionIcon) {
  SectionIcon["Home"] = "home";
  SectionIcon["Library"] = "library";
  SectionIcon["Playlists"] = "playlists";
  SectionIcon["Favourites"] = "favourites";
  SectionIcon["Albums"] = "albums";
  SectionIcon["Artists"] = "artists";
  SectionIcon["Songs"] = "songs";
  SectionIcon["Genres"] = "genres";
  SectionIcon["RecentlyPlayed"] = "recently-played";
  SectionIcon["Radio"] = "radio";
  SectionIcon["Search"] = "search";
  SectionIcon["Downloaded"] = "downloaded";
  SectionIcon["Podcasts"] = "podcasts";
  return SectionIcon;
}({});

/**
 * One section in a browse snapshot — a labelled group of items the
 * consumer pre-builds for fast cold-start presentation in CarPlay /
 * Android Auto. Sections become root-level tabs at the top of the
 * automotive UI (Apple Music + Spotify shape — up to 4 tabs on both
 * platforms; over-cap sections are truncated with a one-time warning).
 */

/**
 * Full pre-computed browse tree pushed by the consumer via
 * `setBrowseSnapshot`. The native automotive surface caches this so
 * cold-start car connections (CarPlay scene attach, Android Auto bind,
 * Google Assistant wake) can render content immediately without a JS
 * bridge round-trip. When the consumer's underlying data changes, push
 * a fresh snapshot; the native side replaces its cache atomically and
 * notifies connected controllers (push semantics — the most-recent
 * `setBrowseSnapshot` call wins).
 *
 * Consumer-shape-agnostic: the lib never reads the contents of a
 * snapshot beyond what's needed to render the platform UI.
 */

// ============================================================
// Equalizer
// ============================================================

// ============================================================
// Visualizer
// ============================================================

/**
 * Allowed FFT bin counts. AAC / AVAudioEngine + Android Visualizer both
 * accept any power of 2 from 256 to 2048 in this family; the lib narrows
 * to that set so consumers can't pass an arbitrary number that the
 * native side then has to round or reject. Android Path A may serve a
 * smaller bin count when the device caps below the request — check the
 * frame's actual fft.byteLength at runtime.
 */

/**
 * Why a Visualizer subscription cannot deliver frames. The library
 * does not request `RECORD_AUDIO` on Android — the FFT reads the
 * app's own decoded PCM via Media3's TeeAudioProcessor, so denial of
 * any OS audio-capture permission is irrelevant.
 *
 * Currently emitted:
 * - `hls-source`: iOS only — `MTAudioProcessingTap` does not fire for
 *   `.m3u8` HLS sources, so the FFT tap can't see the audio.
 *
 * Reserved (declared but not currently emitted by either platform —
 * kept in the union so the codegen continues to emit a non-trivial
 * enum and consumer apps can prepare exhaustive switches that future-
 * proof against the implementation surfacing them):
 * - `no-active-track`: would fire on subscribe-before-setQueue.
 * - `engine-released`: would fire on engine teardown mid-subscription.
 */

// ============================================================
// Cast
// ============================================================

/**
 * Cast protocol family. `local` is the on-device output route (speaker,
 * headphones, BT — the OS handles routing within this bucket). The
 * remote protocols (`airplay`, `chromecast`, `dlna`, `sonos`) carry the
 * lib's cast surface. `dlna` + `sonos` are reserved for future backends;
 * native implementations advertise actual support via
 * `CastManager.getSupportedProtocols()`.
 */

/**
 * How the sender delivers audio to the receiver.
 *
 * - `pushPcm`: sender encodes + transmits PCM frames (AirPlay 1/2). Local
 *   playback engine continues to schedule and decode; only the audio sink
 *   swaps. Crossfade engine MUST degrade to gapless under push-PCM (the
 *   wire protocol has no sender-side cross-stream mixing).
 * - `pullUrl`: receiver fetches a URL the sender supplies (Chromecast,
 *   DLNA, Sonos). Local engine pauses; remote engine takes over scheduling
 *   + transport.
 */

/** Hint to the picker UI for which icon to show. Strings are stable. */

/**
 * Reason an active cast session ended without an explicit `disconnect()`.
 * Advisory — the app resumes local playback regardless.
 */

/**
 * iOS Local Network permission state. Android always returns `granted`.
 */

/**
 * Receiver capability bag. Drives picker UI decisions without protocol
 * branching — e.g. show a volume slider iff `supportsRemoteVolume`.
 */

/**
 * A discovered receiver in the local-network device list. Stable id
 * namespaces by protocol (`"airplay:<mac>"`, `"chromecast:<deviceId>"`)
 * so the JS layer can de-dupe across discovery refreshes.
 *
 * `castProtocol` is named so (not `protocol`) because `protocol` is a
 * Swift-reserved keyword and Nitrogen's codegen doesn't escape it.
 * See NOTES.md §1.8.
 */

/**
 * The currently active audio route. `castProtocol === 'local'` means the
 * OS is routing to the on-device output stack (speaker, headphones, BT,
 * or system-managed AirPlay on iOS); `receiverId` is empty in that case.
 */

/**
 * Options passed to `CastManager.connect`. AirPlay 1/2 pairing supplies
 * `password` on the second-phase connect (after the initial `authFailed`
 * rejection). Chromecast ignores the field.
 */

/**
 * Configuration for {@link CastManager.configure}.
 *
 * @property deviceName Name advertised to receivers in the AirPlay RTSP
 *   handshake (`X-Apple-Client-Name`) and shown on approval dialogs /
 *   now-playing. Falls back to the OS device name when empty. No effect on
 *   Chromecast.
 * @property hideAirPlayComputers Hide AirPlay receivers running on Mac
 *   computers (model `Mac*` / `iMac*`) from discovery. macOS AirPlay
 *   Receiver only accepts Apple senders on the same Apple ID, so it is
 *   un-connectable from this library — surfacing it is a dead picker entry.
 *   Defaults to `true`. Android-only: iOS AirPlay routing is OS-managed, so
 *   the flag is ignored there.
 */

/**
 * Active audio output classification. Cross-platform; covers on-device
 * outputs (phone speaker, BT, wired, USB, HDMI) AND cast targets. Picker
 * UIs read this to show "currently playing on AirPods" regardless of
 * whether cast discovery is running.
 *
 * - `speaker`: phone built-in loudspeaker.
 * - `receiver`: phone earpiece (voice-call speaker; rare for music).
 * - `wired`: 3.5mm / lightning / USB-C analog headphones, dock line-out.
 * - `usb`: USB DAC / USB-C digital headphones.
 * - `hdmi`: HDMI / HDMI-ARC / HDMI-eARC.
 * - `bluetoothA2DP`: classic Bluetooth audio profile.
 * - `bluetoothLE`: LE Audio / hearing aids / BLE speaker/broadcast.
 * - `bluetoothHFP`: Bluetooth hands-free profile (SCO).
 * - `carPlay`: iOS-only; phone is projecting audio via CarPlay.
 * - `androidAuto`: Android-only; audio routed to an automotive bus
 *   (AOSP `TYPE_BUS`). Phone-projected Android Auto over BT is
 *   indistinguishable from regular A2DP at the OS layer + reports as
 *   `bluetoothA2DP`.
 * - `airplay`: active AirPlay route (iOS OS-managed; Android lib-managed).
 * - `chromecast`: active Chromecast session.
 * - `unknown`: unrecognised output type.
 */

/**
 * Snapshot of the currently active audio output. Read via
 * `CastManager.getCurrentAudioRoute()`; observed via
 * `CastManager.onAudioRouteChange()` or the `useAudioRoute()` hook.
 *
 * The route is independent of cast discovery state — knowing what BT
 * headphones the user has connected does not require a cast scan.
 */
//# sourceMappingURL=types.js.map