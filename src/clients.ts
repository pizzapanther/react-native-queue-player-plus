import { NitroModules } from 'react-native-nitro-modules';

import type { CastManager } from './CastManager.nitro';
import type { Equalizer } from './Equalizer.nitro';
import type { TrackPlayer } from './TrackPlayer.nitro';
import type { Visualizer } from './Visualizer.nitro';

/**
 * HybridObject accessors. Lives in its own module so the public
 * `index.ts` barrel and the React hooks under `hooks/` can both
 * import from here without forming a require cycle through the
 * barrel.
 *
 * Each accessor is a memoised singleton — Nitro is happy to vend
 * the same hybrid instance on repeated `createHybridObject` calls,
 * but caching avoids the round-trip through the JSI bridge.
 */

let _player: TrackPlayer | null = null;
let _equalizer: Equalizer | null = null;
let _visualizer: Visualizer | null = null;
let _castManager: CastManager | null = null;

export function getTrackPlayer(): TrackPlayer {
  if (_player == null) {
    _player = NitroModules.createHybridObject<TrackPlayer>('TrackPlayer');
  }
  return _player;
}

export function getEqualizer(): Equalizer {
  if (_equalizer == null) {
    _equalizer = NitroModules.createHybridObject<Equalizer>('Equalizer');
  }
  return _equalizer;
}

export function getVisualizer(): Visualizer {
  if (_visualizer == null) {
    _visualizer = NitroModules.createHybridObject<Visualizer>('Visualizer');
  }
  return _visualizer;
}

export function getCastManager(): CastManager {
  if (_castManager == null) {
    _castManager = NitroModules.createHybridObject<CastManager>('CastManager');
  }
  return _castManager;
}
