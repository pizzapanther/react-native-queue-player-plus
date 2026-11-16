import { useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';
import type { TrackItem } from '../types';
import { applyQueueDelta } from '../queueDelta';

export interface QueueSnapshot {
  /** The current queue, in play order. */
  queue: TrackItem[];
  /** Index of the active track. `-1` when the queue is empty. */
  currentIndex: number;
}

const INITIAL: QueueSnapshot = { queue: [], currentIndex: -1 };

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
export function useQueue(): QueueSnapshot {
  const [snapshot, setSnapshot] = useState<QueueSnapshot>(INITIAL);

  useEffect(() => {
    const player = getTrackPlayer();
    let disposed = false;
    // `haveBase` is what makes a delta applicable: without a queue to apply it
    // to, applying one silently produces a wrong mirror — and for `shuffle`, an
    // array of holes. `seenEvent` separately stops a slow seed overwriting
    // newer state.
    let haveBase = false;
    let seenEvent = false;
    let revision = 0;
    // Guards against two overlapping re-reads resolving out of order.
    let readTicket = 0;

    const reread = () => {
      const ticket = ++readTicket;
      // Until this read lands there is no base a delta can be applied to:
      // an event arriving meanwhile re-reads too, and the ticket keeps the
      // last read's answer.
      haveBase = false;
      void player
        .getQueue()
        .then((queue) => {
          if (disposed || ticket !== readTicket) return;
          haveBase = true;
          // Re-read the index too: the queue just came from a fresh read, and
          // the index captured when the event fired may already be stale.
          setSnapshot({ queue, currentIndex: player.getCurrentTrackIndex() });
        })
        .catch(() => {
          // Player not configured yet; a later event re-reads.
        });
    };

    void (async () => {
      try {
        const ticket = ++readTicket;
        const queue = await player.getQueue();
        const currentIndex = player.getCurrentTrackIndex();
        if (disposed || seenEvent || ticket !== readTicket) return;
        haveBase = true;
        setSnapshot({ queue, currentIndex });
      } catch {
        // Player not configured yet; the first event re-reads instead.
      }
    })();

    // A delta only makes sense against the copy it was computed from, so a
    // missed event — or one that beats the seed — is recovered by re-reading
    // rather than patched over.
    const offQueue = player.onQueueChange((delta, currentIndex, reason) => {
      seenEvent = true;
      const missed = revision !== 0 && delta.revision !== revision + 1;
      revision = delta.revision;
      if (!haveBase || missed) {
        reread();
        return;
      }
      setSnapshot((prev) => ({
        queue: applyQueueDelta(prev.queue, delta, reason),
        currentIndex,
      }));
    });
    // Keep `currentIndex` live when the active track changes without a
    // queue-content change (auto-advance, skip). Preserves the queue from
    // the last `onQueueChange`.
    const offTrack = player.onTrackChange((_track, index) => {
      setSnapshot((prev) => ({ queue: prev.queue, currentIndex: index }));
    });
    return () => {
      disposed = true;
      offQueue();
      offTrack();
    };
  }, []);

  return snapshot;
}
