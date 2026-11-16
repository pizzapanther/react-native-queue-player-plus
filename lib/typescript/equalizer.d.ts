import type { EqualizerBand, EqualizerPreset } from './types';
/**
 * Pure-function helper utilities for the equalizer surface. The
 * imperative API lives on `getEqualizer()` and the React hook
 * lives in `hooks/useEqualizer`; this module gives consumers
 * format / range / lookup helpers that don't require a Hybrid call.
 */
/** Project-wide gain range. Matches the native clamp on both platforms. */
export declare const EQ_GAIN_MIN_DB = -12;
export declare const EQ_GAIN_MAX_DB = 12;
/** ISO-standard centre frequencies (Hz) the cascade processes. */
export declare const EQ_BAND_FREQUENCIES_HZ: readonly number[];
/** Number of bands in the cascade (pinned to 10). */
export declare const EQ_BAND_COUNT = 10;
/**
 * Clamp a gain (dB) to the project-wide spec range. Values
 * outside the range round to the nearest endpoint rather than
 * throwing — JS callers can pass slider values without explicit
 * pre-validation.
 */
export declare function clampGain(dB: number): number;
/** Sentinel name used by both platforms for the no-EQ identity preset. */
export declare const EQ_FLAT_PRESET_NAME = "Flat";
/** Returns true when [name] is one of the 16 built-in presets. */
export declare function isBuiltinPreset(presets: readonly EqualizerPreset[], name: string): boolean;
/**
 * Build a 10-element gain array from a partial map keyed by
 * band index. Missing entries default to 0; out-of-range indices
 * are silently dropped. Useful when the consumer's UI tracks
 * gains as a sparse `{ [index]: gainDb }` shape.
 */
export declare function gainsFromMap(map: Readonly<Record<number, number>>): number[];
/**
 * Find the band whose centre frequency is closest to [hz]. Used
 * by visualisations that need to map an arbitrary frequency
 * (e.g. an FFT bin) to the nearest cascade band.
 */
export declare function nearestBandIndex(hz: number): number;
/**
 * Snapshot a band array down to a plain `number[]` of gains in
 * cascade order. Useful when feeding [setAllBandGains] from a
 * UI that tracks `EqualizerBand[]` shape internally.
 */
export declare function gainsFromBands(bands: readonly EqualizerBand[]): number[];
//# sourceMappingURL=equalizer.d.ts.map