"use strict";

/**
 * Rebuild the queue from the previous copy plus one change.
 *
 * Exported from the package root as the reference for what a consumer must do
 * with a delta; `useQueue` applies it too. The shapes it reads are documented
 * on [QueueChangeDelta].
 */
export function applyQueueDelta(previous, delta, reason) {
  switch (reason) {
    case 'set-queue':
      return delta.inserted.slice();
    case 'clear':
      return [];
    case 'add':
      {
        const next = previous.slice();
        next.splice(delta.insertedAt, 0, ...delta.inserted);
        return next;
      }
    case 'remove':
      {
        const drop = new Set(delta.removed);
        return previous.filter((_, i) => !drop.has(i));
      }
    case 'move':
      {
        const next = previous.slice();
        const [moved] = next.splice(delta.movedFrom, 1);
        if (moved) next.splice(delta.movedTo, 0, moved);
        return next;
      }
    case 'shuffle':
      // `order[i]` is where the track now at `i` used to be.
      return delta.order.map(from => previous[from]);
    default:
      return previous;
  }
}
//# sourceMappingURL=queueDelta.js.map