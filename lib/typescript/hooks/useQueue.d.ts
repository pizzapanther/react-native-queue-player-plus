import type { TrackItem } from '../types';
export interface QueueSnapshot {
    /** The current queue, in play order. */
    queue: TrackItem[];
    /** Index of the active track. `-1` when the queue is empty. */
    currentIndex: number;
}
/**
 * Subscribes to native `onQueueChange` for the live queue + current
 * index, delivered as one atomic snapshot. Also subscribes to
 * `onTrackChange` so `currentIndex` stays live on auto-advance / skip,
 * which move the active position without changing the queue contents.
 *
 * Renders empty for the first frame, then fills in: the seed reads
 * `getQueue()`, which resolves asynchronously. A mutation landing in that gap
 * is not lost: a delta that arrives before the seed lands cannot be applied —
 * there is nothing correct to apply it to — so the queue is re-read instead.
 */
export declare function useQueue(): QueueSnapshot;
//# sourceMappingURL=useQueue.d.ts.map