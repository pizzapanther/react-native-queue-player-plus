"use strict";

import { useCallback, useEffect, useState } from 'react';
import { getTrackPlayer } from "../clients.js";
// Pre-install fallback. `maxSizeMb: 100` matches the lib's documented
// default so consumers reading the value before the first
// `onCacheStatusChange` event don't see a misleading "0 MB budget".
// The first event overrides this with the actual configured value.
const INITIAL = {
  enabled: false,
  currentSizeMb: 0,
  maxSizeMb: 100,
  tracksFullyCached: 0,
  currentlyCaching: []
};

/**
 * Subscribes to lookahead-cache status updates and exposes imperative
 * setters for runtime reconfiguration (e.g. switch from wifi → cellular
 * and drop the lookahead count).
 */
export function useLookaheadCache() {
  // Seeded by the listener's own first fire, which delivers current status on
  // registration — no getter call, so this survives the getter being async.
  const [status, setStatus] = useState(INITIAL);
  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onCacheStatusChange(next => {
      setStatus(next);
    });
    return dispose;
  }, []);
  const setConfig = useCallback(async config => {
    await getTrackPlayer().setLookaheadCache(config);
  }, []);
  const clear = useCallback(async () => {
    await getTrackPlayer().clearLookaheadCache();
  }, []);
  return {
    status,
    setConfig,
    clear
  };
}
//# sourceMappingURL=useLookaheadCache.js.map