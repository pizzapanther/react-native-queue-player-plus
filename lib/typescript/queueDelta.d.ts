import type { QueueChangeDelta, QueueChangeReason, TrackItem } from './types';
/**
 * Rebuild the queue from the previous copy plus one change.
 *
 * Exported from the package root as the reference for what a consumer must do
 * with a delta; `useQueue` applies it too. The shapes it reads are documented
 * on [QueueChangeDelta].
 */
export declare function applyQueueDelta(previous: TrackItem[], delta: QueueChangeDelta, reason: QueueChangeReason): TrackItem[];
//# sourceMappingURL=queueDelta.d.ts.map