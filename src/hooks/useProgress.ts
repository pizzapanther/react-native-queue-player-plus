import { useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';

export interface ProgressState {
  /** Seconds from start of the current track. 0 when no track active. */
  position: number;
  /** Seconds. 0 when duration is unknown or no track active. */
  duration: number;
  /** Seconds buffered ahead of `position`. 0 when unknown. */
  buffered: number;
}

const INITIAL: ProgressState = { position: 0, duration: 0, buffered: 0 };

/**
 * Subscribes to native `onProgress` updates. Progress is driven by a native
 * periodic timer (see NOTES.md §5), so this hook stays accurate even when
 * the app is backgrounded.
 */
export function useProgress(): ProgressState {
  const [progress, setProgress] = useState<ProgressState>(INITIAL);

  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onProgress((p) => {
      setProgress({
        position: p.position,
        duration: p.duration,
        buffered: p.buffered,
      });
    });
    return dispose;
  }, []);

  return progress;
}
