import type { TrackItem, TrackChangeReason } from '../types';
export interface ActiveTrackSnapshot {
    track: TrackItem | undefined;
    /** -1 when the queue is empty. */
    index: number;
    /** Why the track last changed. `'queue-replaced'` for the initial render. */
    reason: TrackChangeReason;
}
/**
 * Subscribes to native `onTrackChange` updates and active-entry metadata edits
 * from `onQueueChange`.
 *
 * Renders empty for the first frame, then fills in: the seed reads
 * `getQueue()`, which resolves asynchronously, and pairs it with a separately
 * read index — so it can briefly observe a new list against an old index. Any
 * `onTrackChange` that arrives first wins and the seed is dropped, since the
 * event delivers a consistent `(track, index, reason)` triple.
 */
export declare function useActiveTrack(): ActiveTrackSnapshot;
//# sourceMappingURL=useActiveTrack.d.ts.map