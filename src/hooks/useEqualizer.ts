import { useCallback, useEffect, useState } from 'react';
import { getEqualizer } from '../clients';
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
export function useEqualizer(): UseEqualizerResult {
  const [enabled, setEnabledState] = useState<boolean>(() => {
    try {
      return getEqualizer().isEnabled();
    } catch {
      return false;
    }
  });
  const [bands, setBands] = useState<EqualizerBand[]>(() => {
    try {
      return getEqualizer().getBands();
    } catch {
      return [];
    }
  });
  const [presets, setPresets] = useState<EqualizerPreset[]>(() => {
    try {
      return getEqualizer().getPresets();
    } catch {
      return [];
    }
  });

  useEffect(() => {
    const eq = getEqualizer();
    const disposeBands = eq.onBandChange((updated) => {
      setBands(updated);
    });
    const disposeEnabled = eq.onEnabledChange((next) => {
      setEnabledState(next);
    });
    return () => {
      disposeBands();
      disposeEnabled();
    };
  }, []);

  const setEnabled = useCallback(async (next: boolean) => {
    const eq = getEqualizer();
    await eq.setEnabled(next);
    // `isEnabled()` is the authority on what the native side committed,
    // so read it back rather than echoing the request. `onEnabledChange`
    // has already delivered the same value — both platforms emit it
    // synchronously inside `setEnabled`'s promise body — which makes this
    // a redundant write on the happy path and the only correction if a
    // future native build stops emitting. Fall back to the requested
    // value when the read-back throws, matching the seed's try/catch.
    try {
      setEnabledState(eq.isEnabled());
    } catch {
      setEnabledState(next);
    }
  }, []);

  const setBandGain = useCallback(async (index: number, gainDb: number) => {
    await getEqualizer().setBandGain(index, gainDb);
  }, []);

  const setAllBandGains = useCallback(async (gains: number[]) => {
    await getEqualizer().setAllBandGains(gains);
  }, []);

  const applyPreset = useCallback(async (name: string) => {
    await getEqualizer().applyPreset(name);
  }, []);

  // The spec carries no presets-changed event, so save and delete
  // re-read `getPresets()` themselves to refresh the local list.
  const saveCustomPreset = useCallback(async (name: string) => {
    await getEqualizer().saveCustomPreset(name);
    setPresets(getEqualizer().getPresets());
  }, []);

  const deleteCustomPreset = useCallback(async (name: string) => {
    await getEqualizer().deleteCustomPreset(name);
    setPresets(getEqualizer().getPresets());
  }, []);

  const reset = useCallback(async () => {
    await getEqualizer().reset();
  }, []);

  return {
    enabled,
    bands,
    presets,
    setEnabled,
    setBandGain,
    setAllBandGains,
    applyPreset,
    saveCustomPreset,
    deleteCustomPreset,
    reset,
  };
}
