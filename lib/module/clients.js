"use strict";

import { NitroModules } from 'react-native-nitro-modules';
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

let _player = null;
let _equalizer = null;
let _visualizer = null;
let _castManager = null;
export function getTrackPlayer() {
  if (_player == null) {
    _player = NitroModules.createHybridObject('TrackPlayer');
  }
  return _player;
}
export function getEqualizer() {
  if (_equalizer == null) {
    _equalizer = NitroModules.createHybridObject('Equalizer');
  }
  return _equalizer;
}
export function getVisualizer() {
  if (_visualizer == null) {
    _visualizer = NitroModules.createHybridObject('Visualizer');
  }
  return _visualizer;
}
export function getCastManager() {
  if (_castManager == null) {
    _castManager = NitroModules.createHybridObject('CastManager');
  }
  return _castManager;
}
//# sourceMappingURL=clients.js.map