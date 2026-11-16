import { useEffect, useState } from 'react';
import {
  addDiscoveryStateListener,
  addLocalNetworkPermissionListener,
  addReceiversListener,
  addRouteListener,
  addSessionDiedListener,
  getCurrentRoute,
  getLocalNetworkPermissionState,
  getReceivers,
  getSupportedProtocols,
  isCastAvailable,
  isDiscovering,
  localRoute,
  type CastLocalNetworkPermission,
  type CastProtocol,
  type CastReceiver,
  type CastRoute,
  type CastSessionDiedEvent,
} from '../cast';

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

const INITIAL: CastSnapshot = {
  supportedProtocols: [],
  isAvailable: false,
  receivers: [],
  route: localRoute(),
  isDiscovering: false,
  lastSessionDied: null,
  permission: 'undetermined',
};

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
export function useCast(): CastSnapshot {
  const [snapshot, setSnapshot] = useState<CastSnapshot>(() => {
    try {
      const protocols = getSupportedProtocols();
      return {
        supportedProtocols: protocols,
        isAvailable: protocols.length > 0,
        receivers: getReceivers(),
        route: getCurrentRoute(),
        isDiscovering: isDiscovering(),
        lastSessionDied: null,
        permission: getLocalNetworkPermissionState(),
      };
    } catch {
      return INITIAL;
    }
  });

  useEffect(() => {
    if (!isCastAvailable()) return;

    // Each `addXxxListener` replays the current value synchronously on
    // subscribe, and every delivery is applied: a replay that equals the
    // `useState` seed costs one batched render, while a replay that differs
    // is a change that landed between the render and this effect — dropping
    // it would leave the snapshot on the stale seed (on Android the
    // permission replay is the only delivery there ever is).
    const routeSub = addRouteListener((route) => {
      setSnapshot((prev) => ({ ...prev, route }));
    });
    const receiversSub = addReceiversListener((receivers) => {
      setSnapshot((prev) => ({ ...prev, receivers }));
    });
    const discoverySub = addDiscoveryStateListener((state) => {
      setSnapshot((prev) => ({ ...prev, isDiscovering: state.isDiscovering }));
    });
    const deathSub = addSessionDiedListener((event) =>
      setSnapshot((prev) => ({ ...prev, lastSessionDied: event }))
    );
    const permissionSub = addLocalNetworkPermissionListener((event) => {
      setSnapshot((prev) => ({ ...prev, permission: event.state }));
    });

    return () => {
      routeSub.remove();
      receiversSub.remove();
      discoverySub.remove();
      deathSub.remove();
      permissionSub.remove();
    };
  }, []);

  return snapshot;
}
