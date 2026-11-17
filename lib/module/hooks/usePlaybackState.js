"use strict";

import { useEffect, useState } from 'react';
import { getTrackPlayer } from "../clients.js";
const INITIAL = {
  state: 'none',
  reason: 'system'
};

/** Updates only when the playback state changes; duplicate reasons are ignored. */
export function usePlaybackState() {
  const [snapshot, setSnapshot] = useState(() => {
    // Pull the current state synchronously on first render so consumers
    // don't flash 'none' on mount.
    try {
      return {
        state: getTrackPlayer().getState(),
        reason: 'system'
      };
    } catch {
      return INITIAL;
    }
  });
  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onStateChange((state, reason) => {
      setSnapshot(previous => previous.state === state ? previous : {
        state,
        reason
      });
    });
    return dispose;
  }, []);
  return snapshot;
}
//# sourceMappingURL=usePlaybackState.js.map