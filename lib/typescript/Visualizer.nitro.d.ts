import type { HybridObject } from 'react-native-nitro-modules';
import type { VisualizerConfig, VisualizerErrorReason, VisualizerFrame } from './types';
export interface Visualizer extends HybridObject<{
    ios: 'swift';
    android: 'kotlin';
}> {
    /**
     * Subscribe to FFT (and optionally raw PCM) frames.
     *
     * `onFrame` fires every `config.updateIntervalMs` while the engine is
     * actively producing audio:
     *   - `frame.fft`: Float32 magnitudes, length = `fftSize / 2`.
     *     Underlying storage is shared zero-copy with the native buffer
     *     for the lifetime of the call.
     *   - `frame.samples`: Float32 raw pre-window time-domain PCM,
     *     length = `fftSize`. The Hann window applied for the FFT
     *     does NOT touch this payload — consumers receive the same
     *     mono-mixed samples that fed the FFT, suitable for
     *     oscilloscope / Lissajous / waveform displays. Present iff
     *     `config.includeSamples === true`.
     *   - `frame.sampleRate`: stream sample rate in Hz.
     *
     * `onError` fires when a subscription cannot deliver frames — see
     * VisualizerErrorReason for the cases. The lib does not request the
     * Android `RECORD_AUDIO` permission: the FFT runs against the app's
     * own decoded PCM via Media3's TeeAudioProcessor (Android) and an
     * MTAudioProcessingTap on the AVAudioMix (iOS).
     *
     * Call the returned disposer to unsubscribe; when the last subscriber
     * disposes, native FFT processing stops.
     */
    subscribe(config: VisualizerConfig, onFrame: (frame: VisualizerFrame) => void, onError: (reason: VisualizerErrorReason) => void): () => void;
    /** How many active subscriptions exist right now. Useful for diagnostics. */
    getSubscriberCount(): number;
}
//# sourceMappingURL=Visualizer.nitro.d.ts.map