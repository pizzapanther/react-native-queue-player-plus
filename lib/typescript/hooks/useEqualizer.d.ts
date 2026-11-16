import type { EqualizerBand, EqualizerPreset } from '../types';
export interface UseEqualizerResult {
    enabled: boolean;
    bands: EqualizerBand[];
    presets: EqualizerPreset[];
    setEnabled: (enabled: boolean) => Promise<void>;
    setBandGain: (index: number, gainDb: number) => Promise<void>;
    setAllBandGains: (gains: number[]) => Promise<void>;
    applyPreset: (name: string) => Promise<void>;
    saveCustomPreset: (name: string) => Promise<void>;
    deleteCustomPreset: (name: string) => Promise<void>;
    reset: () => Promise<void>;
}
/**
 * Subscribes to Equalizer band-change and enabled-change events and
 * exposes imperative setters.
 * Initial state is pulled synchronously on mount so consumers don't flash
 * empty bands.
 */
export declare function useEqualizer(): UseEqualizerResult;
//# sourceMappingURL=useEqualizer.d.ts.map