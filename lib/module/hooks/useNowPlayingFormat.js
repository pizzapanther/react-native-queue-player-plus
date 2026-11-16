"use strict";

import { useEffect, useState } from 'react';
import { getTrackPlayer } from "../clients.js";
/**
 * Subscribes to native `onNowPlayingFormatChange` updates.
 *
 * Returns `null` until the native side has resolved the active
 * track's audio format. iOS requires `await asset.load(.tracks,
 * .formatDescriptions, .estimatedDataRate)` per item; Android lands
 * the data via `Player.Listener.onTracksChanged(Tracks)` before
 * STATE_READY. Either way the format arrives moments after the
 * `onTrackChange` event for that item — render against `null`
 * during the gap.
 *
 * The first-render seed reads `getNowPlayingFormat()` synchronously
 * so a tab re-mount mid-playback doesn't transiently flash `null`
 * if the format is already known.
 */
export function useNowPlayingFormat() {
  const [format, setFormat] = useState(() => {
    try {
      return getTrackPlayer().getNowPlayingFormat();
    } catch {
      return null;
    }
  });
  useEffect(() => {
    const player = getTrackPlayer();
    // Nitrogen types this callback parameter as `NowPlayingFormat | null
    // | undefined` (the variant is generated as nullable-optional). Both
    // native implementations always emit the explicit-null variant, but
    // normalising `undefined → null` keeps the hook's React state strict
    // against the documented `NowPlayingFormat | null` contract.
    const dispose = player.onNowPlayingFormatChange(next => {
      setFormat(next ?? null);
    });
    return dispose;
  }, []);
  return format;
}
//# sourceMappingURL=useNowPlayingFormat.js.map