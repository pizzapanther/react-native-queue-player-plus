import { useEffect, useRef, useState } from 'react';
import { getTrackPlayer } from '../clients';

export interface CanSkipSnapshot {
  /**
   * Whether the user-facing Next button should be enabled. True when
   * there's a next track to skip to under the current repeat mode:
   * false at the last track in `'off'` and `'track'` (no wrap), always
   * true under `'queue'` (wraps). `'track'` (repeat-one) navigates
   * exactly like `'off'`; only natural auto-advance replays the
   * current track.
   */
  canSkipNext: boolean;
  /**
   * Whether the user-facing Previous button should be enabled.
   * Symmetric with `canSkipNext` — true when there's a previous track
   * to skip to under the current repeat mode (false at index 0 in
   * `'off'` and `'track'` — no wrap; true under `'queue'` since it
   * wraps). Apps that want the Apple-Music-style "tap Previous
   * restarts current when position > 3s" UX implement that themselves
   * in their button handler.
   */
  canSkipPrevious: boolean;
}

const INITIAL: CanSkipSnapshot = {
  canSkipNext: false,
  canSkipPrevious: false,
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
export function useCanSkip(): CanSkipSnapshot {
  const [snapshot, setSnapshot] = useState<CanSkipSnapshot>(() => {
    try {
      const player = getTrackPlayer();
      return {
        canSkipNext: player.getCanSkipNext(),
        canSkipPrevious: player.getCanSkipPrevious(),
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
    const dispose = player.onSkipCapabilityChange((capability) => {
      const curr = snapshotRef.current;
      if (
        curr.canSkipNext === capability.canSkipNext &&
        curr.canSkipPrevious === capability.canSkipPrevious
      ) {
        return;
      }
      const next = {
        canSkipPrevious: capability.canSkipPrevious,
        canSkipNext: capability.canSkipNext,
      };
      snapshotRef.current = next;
      setSnapshot(next);
    });
    return dispose;
  }, []);

  return snapshot;
}
