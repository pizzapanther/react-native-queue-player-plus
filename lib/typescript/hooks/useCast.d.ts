import { type CastLocalNetworkPermission, type CastProtocol, type CastReceiver, type CastRoute, type CastSessionDiedEvent } from '../cast';
export interface CastSnapshot {
    /** Protocols compiled into the current build (AirPlay / Chromecast). */
    supportedProtocols: CastProtocol[];
    /** `true` if at least one cast protocol is available on the device. */
    isAvailable: boolean;
    /** Live receiver list, replayed once synchronously on subscribe. */
    receivers: CastReceiver[];
    /** Currently active route — falls back to a synthesised local route. */
    route: CastRoute;
    /** `true` while at least one backend is actively scanning. */
    isDiscovering: boolean;
    /**
     * Last `onSessionDied` event, or `null` until one arrives. Consumers
     * clear by re-reading state after surfacing a banner.
     */
    lastSessionDied: CastSessionDiedEvent | null;
    /**
     * Local Network permission state. Seeded from
     * `getLocalNetworkPermissionState()` on mount and updated by every
     * `onLocalNetworkPermissionChange` delivery; `'granted'` on Android below
     * target SDK 37, which never emits a change.
     */
    permission: CastLocalNetworkPermission;
}
/**
 * Aggregated cast state for picker UIs. Subscribes to all five Cast
 * event streams + reads the initial snapshot synchronously on mount.
 *
 * Returns a single object so consumers can pattern-match without
 * threading five hook calls through their render. Consumers needing
 * just one slice (e.g. only `receivers`) should still prefer this
 * hook over the underlying listeners — the lib already debounces +
 * coalesces updates.
 */
export declare function useCast(): CastSnapshot;
//# sourceMappingURL=useCast.d.ts.map