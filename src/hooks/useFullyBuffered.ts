import { useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';

/**
 * Subscribes to native `onFullyBufferedChange` and returns whether the active
 * track has fully downloaded (`true` once the whole media is buffered). Distinct
 * from `useBufferState() === 'full'` (which is "enough buffered to keep up") —
 * a track can keep up while still downloading. Use it to push a buffered bar to
 * 100% or enable a full-length scrubber once nothing is left to transfer.
 *
 * ```tsx
 * const fullyBuffered = useFullyBuffered();
 * ```
 *
 * Always `false` for a live / unknown-length stream that never completes.
 */
export function useFullyBuffered(): boolean {
  const [fullyBuffered, setFullyBuffered] = useState<boolean>(() => {
    try {
      return getTrackPlayer().isFullyBuffered();
    } catch {
      return false;
    }
  });

  useEffect(() => {
    const player = getTrackPlayer();
    return player.onFullyBufferedChange(setFullyBuffered);
  }, []);

  return fullyBuffered;
}
