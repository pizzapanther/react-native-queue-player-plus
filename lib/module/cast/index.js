"use strict";

import { getCastManager } from "../clients.js";

/**
 * Subscription handle returned by every `add*Listener` function. Call
 * [remove] to unsubscribe — idempotent + safe inside the listener body.
 */

/**
 * Native error codes surfaced on rejected `connect()` promises. Use these
 * to detect specific failure modes — e.g. `AUTH_REQUIRED` indicates the
 * receiver wants a password and the JS layer should prompt the user then
 * retry with `connect(id, { password })`.
 */
export const CastErrorCodes = {
  CONNECT_FAILED: 'cast_connect_failed',
  AUTH_REQUIRED: 'cast_auth_required'
};
/** Synthesised local route used when no remote session is active. */
const LOCAL_ROUTE = {
  castProtocol: 'local',
  receiverId: '',
  name: 'Phone speaker',
  iconHint: 'phone',
  capabilities: {
    supportsRemoteVolume: false,
    streamingModel: 'pushPcm',
    requiresPairing: false
  },
  protocolVersion: ''
};

// === Capability

/** Protocols the current build actually supports (AirPlay, Chromecast, both). */
export function getSupportedProtocols() {
  return getCastManager().getSupportedProtocols();
}

/** Convenience: true when at least one cast protocol is available. */
export function isCastAvailable() {
  return getSupportedProtocols().length > 0;
}

// === Configuration

export function configure(config) {
  return getCastManager().configure({
    deviceName: config.deviceName,
    hideAirPlayComputers: config.hideAirPlayComputers
  });
}

// === Discovery

export function startDiscovery() {
  return getCastManager().startDiscovery();
}
export function stopDiscovery() {
  return getCastManager().stopDiscovery();
}
export function isDiscovering() {
  return getCastManager().isDiscovering();
}

// === Receivers + Route

export function getReceivers() {
  return getCastManager().getReceivers();
}
export function getCurrentRoute() {
  return getCastManager().getCurrentRoute();
}

// === Session

export function connect(receiverId, options) {
  const payload = {
    password: options?.password ?? ''
  };
  return getCastManager().connect(receiverId, payload);
}
export function disconnect() {
  return getCastManager().disconnect();
}

/**
 * Show the system route picker (iOS AVRoutePickerView). On Android there
 * is no native system Cast picker — render the bundled `RoutePicker`
 * component instead.
 */
export function showSystemPicker() {
  return getCastManager().showSystemPicker();
}

// === Volume

/** Read the active receiver's volume (`0.0..1.0`), or `NaN` when unknown. */
export function getRouteVolume() {
  return getCastManager().getRouteVolume();
}
export function setRouteVolume(value) {
  return getCastManager().setRouteVolume(value);
}

// === AirPlay pairing

export function submitPairingPin(pin) {
  return getCastManager().submitPairingPin(pin);
}

// === Local Network permission

export function getLocalNetworkPermissionState() {
  return getCastManager().getLocalNetworkPermissionState();
}
export function requestLocalNetworkPermission() {
  return getCastManager().requestLocalNetworkPermission();
}

// === Listeners

function toSubscription(dispose) {
  let removed = false;
  return {
    remove() {
      if (removed) return;
      removed = true;
      dispose();
    }
  };
}
export function addRouteListener(listener) {
  return toSubscription(getCastManager().onRouteChange(listener));
}
export function addReceiversListener(listener) {
  return toSubscription(getCastManager().onReceiversChange(listener));
}
export function addDiscoveryStateListener(listener) {
  return toSubscription(getCastManager().onDiscoveryStateChange(listener));
}
export function addSessionDiedListener(listener) {
  return toSubscription(getCastManager().onSessionDied(listener));
}
export function addLocalNetworkPermissionListener(listener) {
  return toSubscription(getCastManager().onLocalNetworkPermissionChange(listener));
}

// === Helpers

/** Local fallback route. Useful for picker UI before discovery starts. */
export function localRoute() {
  return LOCAL_ROUTE;
}
//# sourceMappingURL=index.js.map