import type { PlayerState, StateChangeReason } from '../types';
export interface PlaybackStateSnapshot {
    state: PlayerState;
    /** Why the state last changed. `'system'` for the initial render. */
    reason: StateChangeReason;
}
/** Updates only when the playback state changes; duplicate reasons are ignored. */
export declare function usePlaybackState(): PlaybackStateSnapshot;
//# sourceMappingURL=usePlaybackState.d.ts.map