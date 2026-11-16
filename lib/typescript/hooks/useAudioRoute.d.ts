import type { AudioRoute } from '../types';
/**
 * Subscribe to the active audio output. Returns the current `AudioRoute`
 * (kind + name + identifier + isExternal + isSystemManaged) and updates
 * on every system route change — BT pair/unpair, wired plug/unplug,
 * AirPlay route swap, Chromecast session activate/deactivate.
 *
 * Independent of cast discovery: reading does not trigger a scan. The
 * picker can show "currently playing on AirPods (Bluetooth)" while
 * cast discovery is idle.
 */
export declare function useAudioRoute(): AudioRoute;
//# sourceMappingURL=useAudioRoute.d.ts.map