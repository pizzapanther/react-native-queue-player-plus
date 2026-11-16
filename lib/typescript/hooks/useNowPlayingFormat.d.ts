import type { NowPlayingFormat } from '../types';
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
export declare function useNowPlayingFormat(): NowPlayingFormat | null;
//# sourceMappingURL=useNowPlayingFormat.d.ts.map