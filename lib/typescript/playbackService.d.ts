import type { BrowseItem, MediaSearchRequest, ServiceReadyReason, TrackItem } from './types';
/**
 * Handler the consumer registers to service automotive (CarPlay /
 * Android Auto) and voice-assistant requests.
 *
 * Each field is optional — the consumer wires only the cases it cares
 * about. When a callback is omitted the lib does not subscribe; the
 * native side's behaviour for the unhandled request is platform-
 * specific (see CarPlay / Android Auto integration docs).
 *
 * The contract for each callback matches the corresponding
 * `TrackPlayer.onXxx` Hybrid callback.
 */
export interface PlaybackServiceHandler {
    /**
     * Drilldown lookup. Native calls this when an automotive controller
     * asks for the children of `parentId` and the answer is not in the
     * snapshot already pushed via `setBrowseSnapshot`. Return the items
     * to display; an empty array means "no children" (treated as a leaf).
     */
    onBrowseRequest?: (parentId: string) => Promise<BrowseItem[]>;
    /**
     * **Android-only.** Live search results for the Android Auto search
     * surface. Drives Media3's `MediaLibrarySession.Callback.onSearch`.
     * Return the items to display; tapping one fires
     * `onPlayFromIdRequest`. Gearhead surfaces the search affordance
     * once `EXTRAS_KEY_MEDIA_SEARCH_SUPPORTED` is set on the root
     * `LibraryParams` extras — the lib sets it for you.
     *
     * Not invoked on iOS. CarPlay's audio entitlement does not permit
     * `CPSearchTemplate` (per the CarPlay App Programming Guide
     * template-by-category matrix); pushing or presenting it raises
     * `NSInvalidArgumentException`. The iOS-canonical search affordance
     * is Siri via the App Intents framework — see `onPlayFromSearchRequest`
     * below. The lib accepts the `onSearchRequest` registration on iOS
     * to keep the cross-platform API shape consistent but never invokes
     * it.
     */
    onSearchRequest?: (query: string) => Promise<BrowseItem[]>;
    /**
     * Voice / structured search resolution. Fires when a voice
     * assistant delivers a structured request:
     *
     * - **Android** — Google Assistant via
     *   `MediaSession.Callback.onPlayFromSearch` (e.g. "Hey Google,
     *   play Pearl Jam on YourApp"). Request carries `origin:
     *   'android-assistant'`. Genre may be populated via the search
     *   extras; iOS-only fields are always undefined.
     * - **iOS** — SiriKit `INPlayMediaIntent` (e.g. "Hey Siri, play
     *   Pearl Jam on YourApp"). Siri parses the free-text utterance
     *   into a structured `INMediaSearch`, which the lib's
     *   `PlayMediaIntentHandler` maps into the populated fields
     *   (`artist`, `album`, `song`, `genre`, `type`, `reference`,
     *   `playShuffled`, `playbackRepeatMode`, etc.). Request carries
     *   `origin: 'ios-siri'`.
     *
     * Platform-split on auto-play:
     * - **Android** — Media3's `MediaSession.Callback.onAddMediaItems`
     *   applies the resolved track list to the player + starts
     *   playback automatically; the consumer just returns tracks.
     * - **iOS** — the consumer handles queue setup; the lib hands the
     *   resolved tracks back, the consumer's handler calls
     *   `player.setQueue` + `player.play()` itself before returning.
     *   The demo's `playResolvedTracks` in `carService.ts` is the
     *   reference. The handler treats an empty return as `.failure`
     *   so Siri renders its generic "Sorry, I couldn't…" message.
     *
     * For resume-style intents (`resumePlayback === true` or
     * `reference === 'currentlyPlaying'`), return `[]` and call
     * `player.play()` directly from the consumer.
     *
     * Consumers branch on `request.origin` before reading iOS-only
     * fields. The lib never invents an origin; if the request arrived
     * through a path the lib couldn't classify, `origin` is undefined.
     */
    onPlayFromSearchRequest?: (request: MediaSearchRequest) => Promise<TrackItem[]>;
    /**
     * Resolve a specific browse-tree item (or search result) to playable
     * tracks. `mediaId` matches a `BrowseItem.id` the consumer chose.
     */
    onPlayFromIdRequest?: (mediaId: string) => Promise<TrackItem[]>;
    /**
     * Fired when the FIRST automotive controller connects this session
     * (CarPlay scene attach, Android Auto MediaController bind, etc).
     * Subsequent connections during the same session do not re-fire.
     */
    onCarConnect?: () => void;
    /** Fired when the LAST automotive controller drops. */
    onCarDisconnect?: () => void;
    /**
     * Fires when the platform media service is freshly bound to the JS
     * side. `reason = 'first-bind'` on the initial bind of a JS-process
     * lifetime; `reason = 'service-reborn'` when the lib's auto-rebind
     * watchdog reconnects after an OS-driven service kill (Android
     * memory reclaim, doze, idle-bucket reaper).
     *
     * **Typical usage**: re-push the browse snapshot. The reborn
     * service instance starts with a fresh `BrowseDataProvider` —
     * the consumer's previously-pushed snapshot is gone, and any
     * automotive controller binding into the new instance sees an
     * empty library until the snapshot is re-pushed.
     *
     * **Android-only** signal. iOS apps die fully under memory pressure
     * (no separate service lifecycle to rebirth), so this callback is
     * never invoked on iOS — the registration is accepted for API
     * parity and the returned disposer is a no-op. Consumers writing
     * for both platforms can wire one handler and rely on `onCarConnect`
     * for the iOS-side equivalent attach signal.
     */
    onServiceReady?: (reason: ServiceReadyReason) => void;
}
/**
 * Factory that returns the handler. Invoked synchronously the first
 * time `registerPlaybackService` runs and again on every subsequent
 * registration. The factory indirection lets the consumer defer the
 * service module's imports via `require()`, keeping the cold-start
 * bundle small:
 *
 *     registerPlaybackService(() => require('./carPlaybackHandler').default);
 */
export type PlaybackServiceFactory = () => PlaybackServiceHandler;
/**
 * Register the playback-service handler. Idempotent — calling again
 * disposes the prior subscriptions and re-subscribes from the new
 * factory. Single-threaded JS guarantees serialised execution; do not
 * call from multiple JS workers.
 *
 * Consumers typically call this once at app boot from `index.js`:
 *
 *     import { registerPlaybackService } from 'react-native-queue-player';
 *     registerPlaybackService(() => require('./carPlaybackHandler').default);
 *
 * If the factory throws, the previously-registered handler stays
 * active (no swap is performed). This keeps a misbehaving consumer
 * service module from leaving the lib with no automotive surface.
 */
export declare function registerPlaybackService(factory: PlaybackServiceFactory): void;
/** Tear down the registered handler. Mainly for tests + clean shutdown. */
export declare function unregisterPlaybackService(): void;
//# sourceMappingURL=playbackService.d.ts.map