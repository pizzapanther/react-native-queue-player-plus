import type { CastConnectOptions, CastDeviceKind, CastDiscoveryState, CastLocalNetworkPermission, CastLocalNetworkPermissionEvent, CastProtocol, CastReceiver, CastReceiverCapabilities, CastRoute, CastSessionDiedEvent, CastSessionEndReason, CastStreamingModel } from '../types';
export type { CastConnectOptions, CastDeviceKind, CastDiscoveryState, CastLocalNetworkPermission, CastLocalNetworkPermissionEvent, CastProtocol, CastReceiver, CastReceiverCapabilities, CastRoute, CastSessionDiedEvent, CastSessionEndReason, CastStreamingModel, };
/**
 * Subscription handle returned by every `add*Listener` function. Call
 * [remove] to unsubscribe — idempotent + safe inside the listener body.
 */
export interface CastSubscription {
    remove(): void;
}
/**
 * Native error codes surfaced on rejected `connect()` promises. Use these
 * to detect specific failure modes — e.g. `AUTH_REQUIRED` indicates the
 * receiver wants a password and the JS layer should prompt the user then
 * retry with `connect(id, { password })`.
 */
export declare const CastErrorCodes: {
    readonly CONNECT_FAILED: "cast_connect_failed";
    readonly AUTH_REQUIRED: "cast_auth_required";
};
export type CastErrorCode = (typeof CastErrorCodes)[keyof typeof CastErrorCodes];
/** Protocols the current build actually supports (AirPlay, Chromecast, both). */
export declare function getSupportedProtocols(): CastProtocol[];
/** Convenience: true when at least one cast protocol is available. */
export declare function isCastAvailable(): boolean;
export interface CastConfig {
    /** Name advertised to receivers — shown on approval dialogs / now-playing. */
    deviceName: string;
    /**
     * Hide AirPlay receivers running on Mac computers from the device list.
     * macOS AirPlay Receiver only accepts Apple senders on the same Apple ID,
     * so it can't be cast to from here — hidden by default. Set `false` to
     * surface them anyway. Android-only (iOS AirPlay is OS-managed).
     */
    hideAirPlayComputers?: boolean;
}
export declare function configure(config: CastConfig): Promise<void>;
export declare function startDiscovery(): Promise<void>;
export declare function stopDiscovery(): Promise<void>;
export declare function isDiscovering(): boolean;
export declare function getReceivers(): CastReceiver[];
export declare function getCurrentRoute(): CastRoute;
export declare function connect(receiverId: string, options?: {
    password?: string;
}): Promise<void>;
export declare function disconnect(): Promise<void>;
/**
 * Show the system route picker (iOS AVRoutePickerView). On Android there
 * is no native system Cast picker — render the bundled `RoutePicker`
 * component instead.
 */
export declare function showSystemPicker(): Promise<void>;
/** Read the active receiver's volume (`0.0..1.0`), or `NaN` when unknown. */
export declare function getRouteVolume(): number;
export declare function setRouteVolume(value: number): Promise<void>;
export declare function submitPairingPin(pin: string): Promise<void>;
export declare function getLocalNetworkPermissionState(): CastLocalNetworkPermission;
export declare function requestLocalNetworkPermission(): Promise<CastLocalNetworkPermission>;
export declare function addRouteListener(listener: (route: CastRoute) => void): CastSubscription;
export declare function addReceiversListener(listener: (receivers: CastReceiver[]) => void): CastSubscription;
export declare function addDiscoveryStateListener(listener: (state: CastDiscoveryState) => void): CastSubscription;
export declare function addSessionDiedListener(listener: (event: CastSessionDiedEvent) => void): CastSubscription;
export declare function addLocalNetworkPermissionListener(listener: (event: CastLocalNetworkPermissionEvent) => void): CastSubscription;
/** Local fallback route. Useful for picker UI before discovery starts. */
export declare function localRoute(): CastRoute;
//# sourceMappingURL=index.d.ts.map