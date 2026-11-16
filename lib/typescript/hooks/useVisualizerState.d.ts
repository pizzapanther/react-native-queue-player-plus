import type { VisualizerConfig, VisualizerErrorReason } from '../types';
export interface VisualizerState {
    /**
     * Float32Array view over the FFT magnitudes. Length = `fftSize / 2`.
     * `null` until the first frame lands.
     */
    fft: Float32Array | null;
    /**
     * Float32Array view over the raw PCM samples. Length = `fftSize`.
     * `null` unless the subscription was opened with
     * `config.includeSamples === true` AND a frame has landed.
     */
    samples: Float32Array | null;
    /** Stream sample rate in Hz, or 0 before any frame lands. */
    sampleRate: number;
    /** True once any frame has landed and no error reason is active. */
    available: boolean;
    /** Last-reported error reason, or `null` if frames are flowing. */
    reason: VisualizerErrorReason | null;
}
/**
 * Subscribe to the Visualizer for the lifetime of the hook + surface
 * the latest frame as plain React state at `sampleRateHz` cadence
 * (default 30 Hz).
 *
 * The native bridge fires frames at `config.updateIntervalMs` (the
 * audio render thread cadence — typically 60+ Hz). Throttling to
 * 30 Hz on the React side keeps re-render pressure manageable; ~25
 * FPS is enough for the visualizer aesthetic and renders smoothly
 * on 60 Hz screens.
 *
 * The subscription re-creates only on primitive `config` field
 * changes — passing an inline literal each render is safe.
 *
 * Effects cleanly: when the hook unmounts, the disposer runs; when
 * no other subscribers are live, the native layer pauses FFT
 * processing.
 */
export declare function useVisualizerState(config: VisualizerConfig, sampleRateHz?: number): VisualizerState;
//# sourceMappingURL=useVisualizerState.d.ts.map