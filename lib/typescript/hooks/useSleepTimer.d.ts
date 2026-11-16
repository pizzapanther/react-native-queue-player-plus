import type { SleepTimerState } from '../types';
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
export declare function useSleepTimer(): SleepTimerState;
//# sourceMappingURL=useSleepTimer.d.ts.map