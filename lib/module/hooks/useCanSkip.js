"use strict";

import { useEffect, useRef, useState } from 'react';
import { getTrackPlayer } from "../clients.js";
const INITIAL = {
  canSkipNext: false,
  canSkipPrevious: false
};

/**
 * Subscribes to native `onSkipCapabilityChange` updates. Bind the
 * returned booleans to the `disabled` prop of skip-next / skip-prev
 * buttons:
 *
 * ```tsx
 * const { canSkipNext, canSkipPrevious } = useCanSkip();
 * <Button disabled={!canSkipPrevious} onPress={() => player.skipToPrevious()} />
 * <Button disabled={!canSkipNext} onPress={() => player.skipToNext()} />
 * ```
 *
 * The native side recomputes whenever queue, current index, repeat
 * mode, or shuffle changes, with dedup so the listener fires only on
 * actual flips. The first render seeds synchronously via
 * `getCanSkipNext` / `getCanSkipPrevious` to avoid the "buttons flash
 * disabled then enabled" UX on mount; the listener also auto-fires
 * on subscribe (Media3 `onAvailableCommandsChanged` semantic). When
 * the auto-fire payload matches the seed, the dedup ref below
 * suppresses the redundant re-render.
 */
export function useCanSkip() {
  const [snapshot, setSnapshot] = useState(() => {
    try {
      const player = getTrackPlayer();
      return {
        canSkipNext: player.getCanSkipNext(),
        canSkipPrevious: player.getCanSkipPrevious()
      };
    } catch {
      return INITIAL;
    }
  });

  // Mirror state in a ref so the listener can dedup against the
  // current value without taking a dep on `snapshot` (which would
  // re-subscribe the listener on every change). Updated alongside
  // `setSnapshot` inside the listener so the ref + state stay in
  // lockstep across back-to-back fires within a single render.
  const snapshotRef = useRef(snapshot);
  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onSkipCapabilityChange(capability => {
      const curr = snapshotRef.current;
      if (curr.canSkipNext === capability.canSkipNext && curr.canSkipPrevious === capability.canSkipPrevious) {
        return;
      }
      const next = {
        canSkipPrevious: capability.canSkipPrevious,
        canSkipNext: capability.canSkipNext
      };
      snapshotRef.current = next;
      setSnapshot(next);
    });
    return dispose;
  }, []);
  return snapshot;
}
//# sourceMappingURL=useCanSkip.js.map