"use strict";

import { useEffect, useState } from 'react';
import { getTrackPlayer } from "../clients.js";
const INACTIVE = {
  active: false,
  endOfTrack: false
};

/** Whole seconds until the wall-clock deadline, or undefined when there isn't one yet. */
function remainingFrom(state) {
  if (state.endsAtEpochMs === undefined) return undefined;
  return Math.max(0, Math.round((state.endsAtEpochMs - Date.now()) / 1000));
}

/**
 * Subscribes to native `onSleepTimerChange` and exposes the current sleep-timer
 * state. `remainingSeconds` is recomputed locally once a second from the
 * wall-clock `endsAtEpochMs`, so a countdown label stays live — the native side
 * emits only on arm / clear / end-of-track conversion / fire, not on every tick.
 *
 * ```tsx
 * const timer = useSleepTimer();
 * // arm: player.setSleepTimer(15 * 60) or player.setSleepTimerToTrackEnd()
 * {timer.active && (
 *   <Text>
 *     {timer.remainingSeconds === undefined
 *       ? 'Stopping at end of track'
 *       : `Sleeping in ${timer.remainingSeconds}s`}
 *   </Text>
 * )}
 * ```
 *
 * When end-of-track is armed, `endsAtEpochMs` / `remainingSeconds` are absent
 * while `endOfTrack` is true. Once the current track enters the tail window the
 * timer converts to a concrete countdown: `endOfTrack` flips to false and
 * `remainingSeconds` starts counting down.
 */
export function useSleepTimer() {
  const [state, setState] = useState(() => {
    try {
      return getTrackPlayer().getSleepTimer();
    } catch {
      return INACTIVE;
    }
  });
  useEffect(() => {
    const player = getTrackPlayer();
    return player.onSleepTimerChange(setState);
  }, []);
  const [remainingSeconds, setRemainingSeconds] = useState(() => remainingFrom(state));
  useEffect(() => {
    setRemainingSeconds(remainingFrom(state));
    // Only a concrete duration has a deadline to count down; inactive and
    // awaiting-end-of-track states carry no `endsAtEpochMs`, so no interval runs.
    if (state.endsAtEpochMs === undefined) return;
    const id = setInterval(() => setRemainingSeconds(remainingFrom(state)), 1000);
    return () => clearInterval(id);
  }, [state]);
  return {
    ...state,
    remainingSeconds
  };
}
//# sourceMappingURL=useSleepTimer.js.map