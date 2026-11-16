import type { HybridObject } from 'react-native-nitro-modules';
import type { AudioRoute, CastConfigureOptions, CastConnectOptions, CastDiscoveryState, CastLocalNetworkPermission, CastLocalNetworkPermissionEvent, CastProtocol, CastReceiver, CastRoute, CastSessionDiedEvent } from './types';
/**
 * Unified cast surface — AirPlay 1, AirPlay 2, Chromecast (and future
 * DLNA / Sonos behind the same interface). The JS contract is identical
 * regardless of which protocol is active; per-protocol UX the platform
 * mandates (e.g. iOS `AVRoutePickerView` system AirPlay picker) is
 * surfaced via separate helpers (`showSystemPicker`), not branched here.
 *
 * Lifecycle of a typical cast session:
 *   1. `startDiscovery()` — backends begin scanning. `onReceiversChange`
 *      fires as devices appear.
 *   2. `connect(receiverId, {})` — backend establishes the session. On
 *      AirPlay 2 + pairing-required receivers, rejects with `authFailed`
 *      + `requiresPairing` capability set; consumer re-calls with
 *      `{ password: pin }`.
 *   3. `onRouteChange` fires with the now-active route. Playback engine
 *      (in `TrackPlayer`) hands off to the remote engine (Chromecast,
 *      DLNA, Sonos) or hot-swaps the local audio sink to the AirPlay
 *      sink. Caller continues to drive transport against `TrackPlayer`
 *      as normal.
 *   4. `disconnect()` (or unexpected session death → `onSessionDied`)
 *      — local engine resumes at the receiver's last-known position +
 *      play-state.
 *
 * Cast events are mirrored to JS via the listener methods below. Each
 * `on*` method returns a `() => void` disposer; call it to unsubscribe.
 */
export interface CastManager extends HybridObject<{
    ios: 'swift';
    android: 'kotlin';
}> {
    /**
     * Protocols this native build actually handles. Empty array means the
     * build was compiled without cast support; the JS wrapper falls back
     * to a local-only synthetic route.
     */
    getSupportedProtocols(): CastProtocol[];
    /**
     * Apply cast configuration: the AirPlay handshake device name and
     * discovery-filter options. See {@link CastConfigureOptions}. Fields
     * beyond `deviceName` are Android-only (iOS AirPlay is OS-managed).
     */
    configure(config: CastConfigureOptions): Promise<void>;
    /**
     * Begin scanning across all registered backends. Auto-pauses on app
     * background; auto-resumes on foreground. Idempotent.
     */
    startDiscovery(): Promise<void>;
    stopDiscovery(): Promise<void>;
    isDiscovering(): boolean;
    /** Synchronous snapshot of currently-discovered receivers. */
    getReceivers(): CastReceiver[];
    /**
     * The route currently delivering audio. When no remote session is
     * active, returns the OS-managed local route (speaker / headphones /
     * BT / system-AirPlay on iOS).
     */
    getCurrentRoute(): CastRoute;
    /**
     * Establish a session with the receiver identified by `receiverId`.
     * Rejects with code `authFailed` when AirPlay 2 pair-setup is required
     * — consumer prompts the user for a PIN/password and re-calls with
     * `{ password }`. Chromecast ignores `options.password`.
     */
    connect(receiverId: string, options: CastConnectOptions): Promise<void>;
    /**
     * End the active session. No-op when already local. Local engine
     * resumes at the receiver's last-reported position with the prior
     * play-state (was-playing → continue; was-paused → stay paused).
     */
    disconnect(): Promise<void>;
    /**
     * iOS: presents the system `AVRoutePickerView` sheet (AirPlay +
     * Bluetooth + native receivers). Android: no-op (Android has no
     * equivalent system sheet — the lib's custom picker covers it).
     */
    showSystemPicker(): Promise<void>;
    /**
     * Volume of the active receiver, 0.0..1.0. Returns NaN when no remote
     * session is active or the receiver does not support remote volume.
     */
    getRouteVolume(): number;
    /**
     * Sets the active receiver's volume, 0.0..1.0. No-op when no remote
     * session is active or the receiver does not support remote volume.
     */
    setRouteVolume(value: number): Promise<void>;
    /**
     * Submit a PIN/password for an in-flight AirPlay 2 pair-setup. Most
     * consumers call `connect(id, { password })` instead — this is the
     * separate-step variant for UI flows where the pair-setup is decoupled
     * from the connect itself. No-op on iOS (the system handles AirPlay
     * pairing UI itself).
     */
    submitPairingPin(pin: string): Promise<void>;
    /**
     * Local Network access gate. iOS 14+ reflects the Bonjour
     * authorization state. Android returns `granted` while the host app
     * targets SDK < 37 (Local Network Protections only gate apps targeting
     * SDK 37+); at target 37+ it reflects the live `ACCESS_LOCAL_NETWORK`
     * grant.
     */
    getLocalNetworkPermissionState(): CastLocalNetworkPermission;
    /**
     * iOS: triggers the Local Network permission probe (NWBrowser +
     * NWListener Bonjour self-probe). The system dialog is shown by iOS on
     * the FIRST mDNS attempt, not by this call; this method makes that
     * attempt happen at a controlled moment so the user sees the prompt in
     * context.
     *
     * Android: the library holds no Activity, so it cannot raise the system
     * dialog — this resolves with the current state. A host app targeting
     * SDK 37+ must request `ACCESS_LOCAL_NETWORK` from its own Activity (as
     * it does for POST_NOTIFICATIONS); below target 37 it always resolves
     * `granted`.
     */
    requestLocalNetworkPermission(): Promise<CastLocalNetworkPermission>;
    /**
     * Fires on every active-route change (local → remote, remote → remote,
     * remote → local, on-device route swap inside `local`). Delivers the
     * current route synchronously on subscribe for state-replay.
     */
    onRouteChange(callback: (route: CastRoute) => void): () => void;
    /**
     * Fires when the discovered-receiver list changes. Delivers the
     * current snapshot synchronously on subscribe.
     */
    onReceiversChange(callback: (receivers: CastReceiver[]) => void): () => void;
    /** Fires on `startDiscovery` / `stopDiscovery` + auto-pause/resume. */
    onDiscoveryStateChange(callback: (state: CastDiscoveryState) => void): () => void;
    /**
     * Fires when the active session dies unexpectedly (receiver power-off,
     * network loss, AirPlay 2 feedback timeout, receiver stolen by another
     * sender). The native side has already reverted the active route to
     * `local` by the time this fires; the event is advisory.
     */
    onSessionDied(callback: (event: CastSessionDiedEvent) => void): () => void;
    /** iOS LNA state transitions; Android fires once with `granted` on subscribe. */
    onLocalNetworkPermissionChange(callback: (event: CastLocalNetworkPermissionEvent) => void): () => void;
    /**
     * Snapshot of the active audio output (phone speaker, BT, wired, USB,
     * HDMI, AirPlay, Chromecast). Independent of cast discovery — reading
     * does not start a scan.
     */
    getCurrentAudioRoute(): AudioRoute;
    /**
     * Fires on every system audio-route change (BT pair/unpair, wired
     * plug/unplug, AirPlay route swap, etc.). Delivers the current route
     * synchronously on subscribe.
     */
    onAudioRouteChange(callback: (route: AudioRoute) => void): () => void;
}
//# sourceMappingURL=CastManager.nitro.d.ts.map