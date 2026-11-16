import { useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';
import type { BufferState } from '../types';

/**
 * Subscribes to native `onBufferStateChange` and exposes the current discrete
 * buffer state for the active track (`'empty' | 'buffering' | 'stalled' | 'full'`). Driven by
 * the platform's native buffer signals rather than the progress tick, so a
 * loading / stall indicator reacts immediately.
 *
 * ```tsx
 * const buffer = useBufferState();
 * {buffer !== 'full' && <Spinner />}
 * ```
 *
 * `'full'` means playback can proceed smoothly from the current position, not
 * that the whole track is downloaded — for buffered-ahead seconds read
 * `useProgress().buffered`.
 */
export function useBufferState(): BufferState {
  const [state, setState] = useState<BufferState>(() => {
    try {
      return getTrackPlayer().getBufferState();
    } catch {
      return 'empty';
    }
  });

  useEffect(() => {
    const player = getTrackPlayer();
    return player.onBufferStateChange(setState);
  }, []);

  return state;
}
