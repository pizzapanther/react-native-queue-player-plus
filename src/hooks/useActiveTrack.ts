import { useEffect, useState } from 'react';
import { getTrackPlayer } from '../clients';
import type { TrackItem, TrackChangeReason } from '../types';

export interface ActiveTrackSnapshot {
  track: TrackItem | undefined;
  /** -1 when the queue is empty. */
  index: number;
  /** Why the track last changed. `'queue-replaced'` for the initial render. */
  reason: TrackChangeReason;
}

const INITIAL: ActiveTrackSnapshot = {
  track: undefined,
  index: -1,
  reason: 'queue-replaced',
};

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
export function useActiveTrack(): ActiveTrackSnapshot {
  const [snapshot, setSnapshot] = useState<ActiveTrackSnapshot>(INITIAL);

  useEffect(() => {
    const player = getTrackPlayer();
    let seeded = false;
    // A seed still in flight when this effect is cleaned up (a StrictMode
    // remount, an unmount) must not land on a later mount's state.
    let disposed = false;
    void (async () => {
      try {
        const queue = await player.getQueue();
        const index = player.getCurrentTrackIndex();
        if (disposed || seeded) return;
        setSnapshot({
          track: index >= 0 && index < queue.length ? queue[index] : undefined,
          index,
          reason: 'queue-replaced',
        });
      } catch {
        // Player not configured yet; the first event seeds instead.
      }
    })();
    const dispose = player.onTrackChange((track, index, reason) => {
      seeded = true;
      setSnapshot({ track, index, reason });
    });
    const disposeQueue = player.onQueueChange((delta, currentIndex, reason) => {
      if (reason !== 'update-metadata' || delta.insertedAt !== currentIndex) return;
      const track = delta.inserted[0];
      if (!track) return;
      // An edit arriving before getQueue resolves is newer than that seed.
      seeded = true;
      setSnapshot((previous) => ({ ...previous, track, index: currentIndex }));
    });
    return () => {
      disposed = true;
      dispose();
      disposeQueue();
    };
  }, []);

  return snapshot;
}
