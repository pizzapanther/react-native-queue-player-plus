"use strict";

// HybridObject type exports

// All value types live in src/types.ts — see there for the canonical home.

// HybridObject accessors — defined in `clients.ts` to break the
// require cycle between `index.ts` and `hooks/*` (each hook needs
// `getTrackPlayer` etc, but `index.ts` re-exports the hooks).
export { getCastManager, getEqualizer, getTrackPlayer, getVisualizer } from "./clients.js";

// Automotive / voice — playback service registration. Consumer registers
// a handler factory once at app boot to service browse / search / play
// requests routed in from CarPlay, Android Auto, and voice assistants.
export { registerPlaybackService, unregisterPlaybackService } from "./playbackService.js";
// Canonical bounds for `crossfadeDurationMs`; consumer apps building
// stepper / slider UIs read from here.
export { crossfadeDurationBounds } from "./types.js";

// Automotive — closed enum of tab-bar icons the lib knows how to
// render. Consumers reference `SectionIcon.Albums` etc; lib maps to
// SF Symbol on iOS. Android Auto has no tab-icon API today.
export { SectionIcon } from "./types.js";

// Pure-function equalizer helpers — range constants, band frequency
// table, clamp + lookup utilities. Live alongside the imperative
// `getEqualizer()` API for consumers building EQ UIs.
export { EQ_BAND_COUNT, EQ_BAND_FREQUENCIES_HZ, EQ_FLAT_PRESET_NAME, EQ_GAIN_MAX_DB, EQ_GAIN_MIN_DB, clampGain, gainsFromBands, gainsFromMap, isBuiltinPreset, nearestBandIndex } from "./equalizer.js";

// Pure helper for applying an onQueueChange delta to a queue copy.
export { applyQueueDelta } from "./queueDelta.js";

// React hooks — thin observers over the HybridObject event surface.
export * from "./hooks/index.js";

// Cast — `Cast.*`: thin functions over the `CastManager` HybridObject
// (optional-argument shapes, `CastSubscription` handles, `CastErrorCodes`,
// `localRoute()`); `getCastManager()` returns the HybridObject itself.
export * as Cast from "./cast/index.js";
//# sourceMappingURL=index.js.map