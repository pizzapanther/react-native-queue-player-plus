"use strict";

import { useEffect, useState } from 'react';
import { addDiscoveryStateListener, addLocalNetworkPermissionListener, addReceiversListener, addRouteListener, addSessionDiedListener, getCurrentRoute, getLocalNetworkPermissionState, getReceivers, getSupportedProtocols, isCastAvailable, isDiscovering, localRoute } from "../cast/index.js";
const INITIAL = {
  supportedProtocols: [],
  isAvailable: false,
  receivers: [],
  route: localRoute(),
  isDiscovering: false,
  lastSessionDied: null,
  permission: 'undetermined'
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
export function useCast() {
  const [snapshot, setSnapshot] = useState(() => {
    try {
      const protocols = getSupportedProtocols();
      return {
        supportedProtocols: protocols,
        isAvailable: protocols.length > 0,
        receivers: getReceivers(),
        route: getCurrentRoute(),
        isDiscovering: isDiscovering(),
        lastSessionDied: null,
        permission: getLocalNetworkPermissionState()
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
    const routeSub = addRouteListener(route => {
      setSnapshot(prev => ({
        ...prev,
        route
      }));
    });
    const receiversSub = addReceiversListener(receivers => {
      setSnapshot(prev => ({
        ...prev,
        receivers
      }));
    });
    const discoverySub = addDiscoveryStateListener(state => {
      setSnapshot(prev => ({
        ...prev,
        isDiscovering: state.isDiscovering
      }));
    });
    const deathSub = addSessionDiedListener(event => setSnapshot(prev => ({
      ...prev,
      lastSessionDied: event
    })));
    const permissionSub = addLocalNetworkPermissionListener(event => {
      setSnapshot(prev => ({
        ...prev,
        permission: event.state
      }));
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
//# sourceMappingURL=useCast.js.map