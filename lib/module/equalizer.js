"use strict";

/**
 * Pure-function helper utilities for the equalizer surface. The
 * imperative API lives on `getEqualizer()` and the React hook
 * lives in `hooks/useEqualizer`; this module gives consumers
 * format / range / lookup helpers that don't require a Hybrid call.
 */

/** Project-wide gain range. Matches the native clamp on both platforms. */
export const EQ_GAIN_MIN_DB = -12;
export const EQ_GAIN_MAX_DB = 12;

/** ISO-standard centre frequencies (Hz) the cascade processes. */
export const EQ_BAND_FREQUENCIES_HZ = [31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

/** Number of bands in the cascade (pinned to 10). */
export const EQ_BAND_COUNT = 10;

/**
 * Clamp a gain (dB) to the project-wide spec range. Values
 * outside the range round to the nearest endpoint rather than
 * throwing — JS callers can pass slider values without explicit
 * pre-validation.
 */
export function clampGain(dB) {
  if (Number.isNaN(dB)) return 0;
  if (dB < EQ_GAIN_MIN_DB) return EQ_GAIN_MIN_DB;
  if (dB > EQ_GAIN_MAX_DB) return EQ_GAIN_MAX_DB;
  return dB;
}

/** Sentinel name used by both platforms for the no-EQ identity preset. */
export const EQ_FLAT_PRESET_NAME = 'Flat';

/** Returns true when [name] is one of the 16 built-in presets. */
export function isBuiltinPreset(presets, name) {
  return presets.some(p => p.name === name && p.builtin);
}

/**
 * Build a 10-element gain array from a partial map keyed by
 * band index. Missing entries default to 0; out-of-range indices
 * are silently dropped. Useful when the consumer's UI tracks
 * gains as a sparse `{ [index]: gainDb }` shape.
 */
export function gainsFromMap(map) {
  const out = new Array(EQ_BAND_COUNT).fill(0);
  for (const [k, v] of Object.entries(map)) {
    const idx = Number(k);
    if (Number.isInteger(idx) && idx >= 0 && idx < EQ_BAND_COUNT) {
      out[idx] = clampGain(v);
    }
  }
  return out;
}

/**
 * Find the band whose centre frequency is closest to [hz]. Used
 * by visualisations that need to map an arbitrary frequency
 * (e.g. an FFT bin) to the nearest cascade band.
 */
export function nearestBandIndex(hz) {
  let bestIdx = 0;
  let bestDist = Infinity;
  for (let i = 0; i < EQ_BAND_FREQUENCIES_HZ.length; i++) {
    const freq = EQ_BAND_FREQUENCIES_HZ[i] ?? 0;
    const d = Math.abs(freq - hz);
    if (d < bestDist) {
      bestDist = d;
      bestIdx = i;
    }
  }
  return bestIdx;
}

/**
 * Snapshot a band array down to a plain `number[]` of gains in
 * cascade order. Useful when feeding [setAllBandGains] from a
 * UI that tracks `EqualizerBand[]` shape internally.
 */
export function gainsFromBands(bands) {
  return bands.map(b => clampGain(b.gainDb));
}
//# sourceMappingURL=equalizer.js.map