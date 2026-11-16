import type { HybridObject } from 'react-native-nitro-modules';
import type { EqualizerBand, EqualizerPreset } from './types';
export interface Equalizer extends HybridObject<{
    ios: 'swift';
    android: 'kotlin';
}> {
    setEnabled(enabled: boolean): Promise<void>;
    isEnabled(): boolean;
    getBands(): EqualizerBand[];
    setBandGain(index: number, gainDb: number): Promise<void>;
    setAllBandGains(gains: number[]): Promise<void>;
    applyPreset(name: string): Promise<void>;
    getPresets(): EqualizerPreset[];
    /** Saves the current band gains under `name`. Overwrites if already exists (custom only). */
    saveCustomPreset(name: string): Promise<void>;
    /** No-op on built-in presets. */
    deleteCustomPreset(name: string): Promise<void>;
    /** Sets all bands back to 0 dB gain. EQ stays enabled. */
    reset(): Promise<void>;
    onBandChange(callback: (bands: EqualizerBand[]) => void): () => void;
    /**
     * Fires after every successful `setEnabled` once the underlying
     * audio-unit / `AudioEffect` state is committed. Auto-fires once on
     * registration with the current state so consumers don't have to
     * seed via `isEnabled()` first (mirrors `onSkipCapabilityChange`
     * pattern). Returns a disposer.
     */
    onEnabledChange(callback: (enabled: boolean) => void): () => void;
}
//# sourceMappingURL=Equalizer.nitro.d.ts.map