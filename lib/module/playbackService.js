"use strict";

import { AppRegistry, Platform } from 'react-native';
import { getTrackPlayer } from "./clients.js";
/**
 * Headless task name shared with the Android `PlaybackService`. Must
 * match `PlaybackService.HEADLESS_TASK_NAME` exactly — Media3's
 * `MediaLibraryService` cold-wakes the JS bridge by spawning a
 * `HeadlessJsTaskService` task with this name when an automotive /
 * voice controller (Android Auto, Google Assistant) connects before
 * any Activity has booted React.
 */
const HEADLESS_TASK_NAME = 'RNQueuePlayerHeadlessTask';
let headlessTaskRegistered = false;

/**
 * Register a no-op headless task body so the React Native bridge
 * stays alive long enough for the consumer's `registerPlaybackService`
 * callbacks to wire themselves into the native callback registry.
 *
 * `PlaybackService.onHeadlessJsTaskFinish` overrides the base-class
 * `stopSelf` so the foreground service keeps running once the JS
 * task body resolves — the callbacks remain dispatchable for the
 * lifetime of the service. iOS has no equivalent path; this entire
 * function is a no-op there.
 */
function ensureHeadlessTaskRegistered() {
  if (headlessTaskRegistered) return;
  if (Platform.OS !== 'android') {
    headlessTaskRegistered = true;
    return;
  }
  AppRegistry.registerHeadlessTask(HEADLESS_TASK_NAME, () => async () => {
    // Body intentionally resolves immediately — the bridge is
    // already alive (the task spawn boots it), and the service-
    // side callback registry uses it directly. The foreground
    // service stays up via `onHeadlessJsTaskFinish` keeping
    // `stopSelf` from running.
  });
  headlessTaskRegistered = true;
}

/**
 * Handler the consumer registers to service automotive (CarPlay /
 * Android Auto) and voice-assistant requests.
 *
 * Each field is optional — the consumer wires only the cases it cares
 * about. When a callback is omitted the lib does not subscribe; the
 * native side's behaviour for the unhandled request is platform-
 * specific (see CarPlay / Android Auto integration docs).
 *
 * The contract for each callback matches the corresponding
 * `TrackPlayer.onXxx` Hybrid callback.
 */

/**
 * Factory that returns the handler. Invoked synchronously the first
 * time `registerPlaybackService` runs and again on every subsequent
 * registration. The factory indirection lets the consumer defer the
 * service module's imports via `require()`, keeping the cold-start
 * bundle small:
 *
 *     registerPlaybackService(() => require('./carPlaybackHandler').default);
 */

let registeredDisposers = [];
function disposeAll(disposers) {
  for (const dispose of disposers) {
    try {
      dispose();
    } catch (e) {
      // A disposer throwing usually signals a leaked native subscription —
      // surface it so it shows up in production logs rather than vanish.
      console.warn('[react-native-queue-player] playback-service disposer threw:', e);
    }
  }
}

/**
 * Per-node browse item cap used as the JS-console warning threshold.
 * Android's native cap (`PlaybackServiceCallback.MAX_ITEMS_PER_NODE`) is
 * exactly this; iOS truncates against `CPListTemplate.maximumItemCount`,
 * read at runtime (currently 500), so this is the shared warning
 * threshold, not an exact iOS guarantee.
 */
const MAX_ITEMS_PER_NODE = 500;

/**
 * parentIds already warned about, so the over-cap log fires once each.
 * Reset on (re)register / unregister so its lifetime tracks the active
 * registration — a re-registered, still-over-cap node warns again.
 */
const overCapNodesWarned = new Set();

/**
 * Wrap a consumer `onBrowseRequest` so an over-cap result surfaces a
 * one-time warning in the JS console (Metro), where a React Native dev
 * looks — the native side already logs it and does the actual
 * truncation. Returns the consumer's items unchanged, and stays
 * transparent on the error path (a rejection propagates untouched).
 */
function withBrowseCapWarning(onBrowseRequest) {
  return async parentId => {
    const items = await onBrowseRequest(parentId);
    if (Array.isArray(items) && items.length > MAX_ITEMS_PER_NODE && !overCapNodesWarned.has(parentId)) {
      overCapNodesWarned.add(parentId);
      console.warn(`[react-native-queue-player] Browse node "${parentId}" returned ` + `${items.length} items, over the ~${MAX_ITEMS_PER_NODE}-item per-node ` + `cap (Android Auto requests whole nodes; CarPlay caps a CPListTemplate ` + `at its runtime maximumItemCount). Items past the cap are dropped on ` + `the car screen — use a hierarchical browse (A–Z / by-artist) for ` + `larger catalogs.`);
    }
    return items;
  };
}

/**
 * Register the playback-service handler. Idempotent — calling again
 * disposes the prior subscriptions and re-subscribes from the new
 * factory. Single-threaded JS guarantees serialised execution; do not
 * call from multiple JS workers.
 *
 * Consumers typically call this once at app boot from `index.js`:
 *
 *     import { registerPlaybackService } from 'react-native-queue-player';
 *     registerPlaybackService(() => require('./carPlaybackHandler').default);
 *
 * If the factory throws, the previously-registered handler stays
 * active (no swap is performed). This keeps a misbehaving consumer
 * service module from leaving the lib with no automotive surface.
 */
export function registerPlaybackService(factory) {
  ensureHeadlessTaskRegistered();
  // Resolve the handler FIRST. If the factory throws, the prior
  // subscriptions stay intact rather than getting torn down with no
  // replacement.
  const handler = factory();
  const player = getTrackPlayer();

  // Build the new disposer list locally and atomically swap into the
  // module-scope reference at the end. This avoids a partially-installed
  // state if a subscription call throws midway through.
  const next = [];
  if (handler.onBrowseRequest) {
    next.push(player.onBrowseRequest(withBrowseCapWarning(handler.onBrowseRequest)));
  }
  if (handler.onSearchRequest) {
    next.push(player.onSearchRequest(handler.onSearchRequest));
  }
  if (handler.onPlayFromSearchRequest) {
    next.push(player.onPlayFromSearchRequest(handler.onPlayFromSearchRequest));
  }
  if (handler.onPlayFromIdRequest) {
    next.push(player.onPlayFromIdRequest(handler.onPlayFromIdRequest));
  }
  if (handler.onCarConnect) {
    next.push(player.onCarConnect(handler.onCarConnect));
  }
  if (handler.onCarDisconnect) {
    next.push(player.onCarDisconnect(handler.onCarDisconnect));
  }
  if (handler.onServiceReady) {
    next.push(player.onServiceReady(handler.onServiceReady));
  }
  const prior = registeredDisposers;
  registeredDisposers = next;
  overCapNodesWarned.clear();
  disposeAll(prior);
}

/** Tear down the registered handler. Mainly for tests + clean shutdown. */
export function unregisterPlaybackService() {
  const prior = registeredDisposers;
  registeredDisposers = [];
  overCapNodesWarned.clear();
  disposeAll(prior);
}
//# sourceMappingURL=playbackService.js.map