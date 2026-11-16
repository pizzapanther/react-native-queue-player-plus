import { useEffect, useRef, useState } from 'react';
import { getCastManager } from '../clients';
import type { AudioRoute } from '../types';

const LOCAL_FALLBACK: AudioRoute = {
  kind: 'speaker',
  name: 'Phone speaker',
  identifier: '',
  isExternal: false,
  isSystemManaged: true,
};

/**
 * Subscribe to the active audio output. Returns the current `AudioRoute`
 * (kind + name + identifier + isExternal + isSystemManaged) and updates
 * on every system route change — BT pair/unpair, wired plug/unplug,
 * AirPlay route swap, Chromecast session activate/deactivate.
 *
 * Independent of cast discovery: reading does not trigger a scan. The
 * picker can show "currently playing on AirPods (Bluetooth)" while
 * cast discovery is idle.
 */
export function useAudioRoute(): AudioRoute {
  const initialRef = useRef<AudioRoute | null>(null);
  const [route, setRoute] = useState<AudioRoute>(() => {
    const initial = readInitialRoute();
    initialRef.current = initial;
    return initial;
  });

  useEffect(() => {
    const dispose = getCastManager().onAudioRouteChange((next) => {
      // The native side replays the current route synchronously on
      // subscribe. The `useState` initializer already captured an
      // initial value; skip the replay only when it matches that
      // value. If the route changed in the gap between mount and
      // subscribe (e.g. BT pair raced the render), the replay
      // delivers the new state instead of being silently dropped.
      const initial = initialRef.current;
      if (initial != null && audioRouteEquals(initial, next)) {
        initialRef.current = null;
        return;
      }
      initialRef.current = null;
      setRoute(next);
    });
    return dispose;
  }, []);

  return route;
}

function readInitialRoute(): AudioRoute {
  try {
    return getCastManager().getCurrentAudioRoute();
  } catch {
    return LOCAL_FALLBACK;
  }
}

function audioRouteEquals(a: AudioRoute, b: AudioRoute): boolean {
  return (
    a.kind === b.kind &&
    a.name === b.name &&
    a.identifier === b.identifier &&
    a.isExternal === b.isExternal &&
    a.isSystemManaged === b.isSystemManaged
  );
}
