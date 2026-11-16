import { useCallback, useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';
import type { CacheStatus, LookaheadCacheConfig } from '../types';

export interface UseLookaheadCacheResult {
  status: CacheStatus;
  setConfig: (config: LookaheadCacheConfig) => Promise<void>;
  clear: () => Promise<void>;
}

// Pre-install fallback. `maxSizeMb: 100` matches the lib's documented
// default so consumers reading the value before the first
// `onCacheStatusChange` event don't see a misleading "0 MB budget".
// The first event overrides this with the actual configured value.
const INITIAL: CacheStatus = {
  enabled: false,
  currentSizeMb: 0,
  maxSizeMb: 100,
  tracksFullyCached: 0,
  currentlyCaching: [],
};

/**
 * Subscribes to lookahead-cache status updates and exposes imperative
 * setters for runtime reconfiguration (e.g. switch from wifi → cellular
 * and drop the lookahead count).
 */
export function useLookaheadCache(): UseLookaheadCacheResult {
  // Seeded by the listener's own first fire, which delivers current status on
  // registration — no getter call, so this survives the getter being async.
  const [status, setStatus] = useState<CacheStatus>(INITIAL);

  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onCacheStatusChange((next) => {
      setStatus(next);
    });
    return dispose;
  }, []);

  const setConfig = useCallback(async (config: LookaheadCacheConfig) => {
    await getTrackPlayer().setLookaheadCache(config);
  }, []);

  const clear = useCallback(async () => {
    await getTrackPlayer().clearLookaheadCache();
  }, []);

  return { status, setConfig, clear };
}
