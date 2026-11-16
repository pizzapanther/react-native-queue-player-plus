import { getCastManager } from '../clients';
import type {
  CastConnectOptions,
  CastDeviceKind,
  CastDiscoveryState,
  CastLocalNetworkPermission,
  CastLocalNetworkPermissionEvent,
  CastProtocol,
  CastReceiver,
  CastReceiverCapabilities,
  CastRoute,
  CastSessionDiedEvent,
  CastSessionEndReason,
  CastStreamingModel,
} from '../types';

export type {
  CastConnectOptions,
  CastDeviceKind,
  CastDiscoveryState,
  CastLocalNetworkPermission,
  CastLocalNetworkPermissionEvent,
  CastProtocol,
  CastReceiver,
  CastReceiverCapabilities,
  CastRoute,
  CastSessionDiedEvent,
  CastSessionEndReason,
  CastStreamingModel,
};

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
export const CastErrorCodes = {
  CONNECT_FAILED: 'cast_connect_failed',
  AUTH_REQUIRED: 'cast_auth_required',
} as const;

export type CastErrorCode = (typeof CastErrorCodes)[keyof typeof CastErrorCodes];

/** Synthesised local route used when no remote session is active. */
const LOCAL_ROUTE: CastRoute = {
  castProtocol: 'local',
  receiverId: '',
  name: 'Phone speaker',
  iconHint: 'phone',
  capabilities: {
    supportsRemoteVolume: false,
    streamingModel: 'pushPcm',
    requiresPairing: false,
  },
  protocolVersion: '',
};

// === Capability

/** Protocols the current build actually supports (AirPlay, Chromecast, both). */
export function getSupportedProtocols(): CastProtocol[] {
  return getCastManager().getSupportedProtocols();
}

/** Convenience: true when at least one cast protocol is available. */
export function isCastAvailable(): boolean {
  return getSupportedProtocols().length > 0;
}

// === Configuration

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

export function configure(config: CastConfig): Promise<void> {
  return getCastManager().configure({
    deviceName: config.deviceName,
    hideAirPlayComputers: config.hideAirPlayComputers,
  });
}

// === Discovery

export function startDiscovery(): Promise<void> {
  return getCastManager().startDiscovery();
}

export function stopDiscovery(): Promise<void> {
  return getCastManager().stopDiscovery();
}

export function isDiscovering(): boolean {
  return getCastManager().isDiscovering();
}

// === Receivers + Route

export function getReceivers(): CastReceiver[] {
  return getCastManager().getReceivers();
}

export function getCurrentRoute(): CastRoute {
  return getCastManager().getCurrentRoute();
}

// === Session

export function connect(
  receiverId: string,
  options?: { password?: string }
): Promise<void> {
  const payload: CastConnectOptions = { password: options?.password ?? '' };
  return getCastManager().connect(receiverId, payload);
}

export function disconnect(): Promise<void> {
  return getCastManager().disconnect();
}

/**
 * Show the system route picker (iOS AVRoutePickerView). On Android there
 * is no native system Cast picker — render the bundled `RoutePicker`
 * component instead.
 */
export function showSystemPicker(): Promise<void> {
  return getCastManager().showSystemPicker();
}

// === Volume

/** Read the active receiver's volume (`0.0..1.0`), or `NaN` when unknown. */
export function getRouteVolume(): number {
  return getCastManager().getRouteVolume();
}

export function setRouteVolume(value: number): Promise<void> {
  return getCastManager().setRouteVolume(value);
}

// === AirPlay pairing

export function submitPairingPin(pin: string): Promise<void> {
  return getCastManager().submitPairingPin(pin);
}

// === Local Network permission

export function getLocalNetworkPermissionState(): CastLocalNetworkPermission {
  return getCastManager().getLocalNetworkPermissionState();
}

export function requestLocalNetworkPermission(): Promise<CastLocalNetworkPermission> {
  return getCastManager().requestLocalNetworkPermission();
}

// === Listeners

function toSubscription(dispose: () => void): CastSubscription {
  let removed = false;
  return {
    remove() {
      if (removed) return;
      removed = true;
      dispose();
    },
  };
}

export function addRouteListener(
  listener: (route: CastRoute) => void
): CastSubscription {
  return toSubscription(getCastManager().onRouteChange(listener));
}

export function addReceiversListener(
  listener: (receivers: CastReceiver[]) => void
): CastSubscription {
  return toSubscription(getCastManager().onReceiversChange(listener));
}

export function addDiscoveryStateListener(
  listener: (state: CastDiscoveryState) => void
): CastSubscription {
  return toSubscription(getCastManager().onDiscoveryStateChange(listener));
}

export function addSessionDiedListener(
  listener: (event: CastSessionDiedEvent) => void
): CastSubscription {
  return toSubscription(getCastManager().onSessionDied(listener));
}

export function addLocalNetworkPermissionListener(
  listener: (event: CastLocalNetworkPermissionEvent) => void
): CastSubscription {
  return toSubscription(
    getCastManager().onLocalNetworkPermissionChange(listener)
  );
}

// === Helpers

/** Local fallback route. Useful for picker UI before discovery starts. */
export function localRoute(): CastRoute {
  return LOCAL_ROUTE;
}
