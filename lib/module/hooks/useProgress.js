"use strict";

import { useEffect, useState } from 'react';
import { getTrackPlayer } from "../clients.js";
const INITIAL = {
  position: 0,
  duration: 0,
  buffered: 0
};

/**
 * Subscribes to native `onProgress` updates. Progress is driven by a native
 * periodic timer (see NOTES.md §5), so this hook stays accurate even when
 * the app is backgrounded.
 */
export function useProgress() {
  const [progress, setProgress] = useState(INITIAL);
  useEffect(() => {
    const player = getTrackPlayer();
    const dispose = player.onProgress(p => {
      setProgress({
        position: p.position,
        duration: p.duration,
        buffered: p.buffered
      });
    });
    return dispose;
  }, []);
  return progress;
}
//# sourceMappingURL=useProgress.js.map