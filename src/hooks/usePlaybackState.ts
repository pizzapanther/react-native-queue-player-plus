import { useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';
import type { PlayerState, StateChangeReason } from '../types';

export interface PlaybackStateSnapshot {
  state: PlayerState;
  /** Why the state last changed. `'system'` for the initial render. */
  reason: StateChangeReason;
}

const INITIAL: PlaybackStateSnapshot = { state: 'none', reason: 'system' };

/** Updates only when the playback state changes; duplicate reasons are ignored. */
export function usePlaybackState(): PlaybackStateSnapshot {
  const [snapshot, setSnapshot] = useState<PlaybackStateSnapshot>(() => {
    // Pull the current state synchronously on first render so consumers
    // don't flash 'none' on mount.
    try {
      return { state: getTrackPlayer().getState(), reason: 'system' };
    } catch {
      return INITIAL;
    }
  });

  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onStateChange((state, reason) => {
      setSnapshot((previous) =>
        previous.state === state ? previous : { state, reason }
      );
    });
    return dispose;
  }, []);

  return snapshot;
}
