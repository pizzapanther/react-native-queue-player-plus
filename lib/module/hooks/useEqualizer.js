"use strict";

import { useCallback, useEffect, useState } from 'react';
import { getEqualizer } from "../clients.js";
/**
 * Subscribes to Equalizer band-change and enabled-change events and
 * exposes imperative setters.
 * Initial state is pulled synchronously on mount so consumers don't flash
 * empty bands.
 */
export function useEqualizer() {
  const [enabled, setEnabledState] = useState(() => {
    try {
      return getEqualizer().isEnabled();
    } catch {
      return false;
    }
  });
  const [bands, setBands] = useState(() => {
    try {
      return getEqualizer().getBands();
    } catch {
      return [];
    }
  });
  const [presets, setPresets] = useState(() => {
    try {
      return getEqualizer().getPresets();
    } catch {
      return [];
    }
  });
  useEffect(() => {
    const eq = getEqualizer();
    const disposeBands = eq.onBandChange(updated => {
      setBands(updated);
    });
    const disposeEnabled = eq.onEnabledChange(next => {
      setEnabledState(next);
    });
    return () => {
      disposeBands();
      disposeEnabled();
    };
  }, []);
  const setEnabled = useCallback(async next => {
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
  const setBandGain = useCallback(async (index, gainDb) => {
    await getEqualizer().setBandGain(index, gainDb);
  }, []);
  const setAllBandGains = useCallback(async gains => {
    await getEqualizer().setAllBandGains(gains);
  }, []);
  const applyPreset = useCallback(async name => {
    await getEqualizer().applyPreset(name);
  }, []);

  // The spec carries no presets-changed event, so save and delete
  // re-read `getPresets()` themselves to refresh the local list.
  const saveCustomPreset = useCallback(async name => {
    await getEqualizer().saveCustomPreset(name);
    setPresets(getEqualizer().getPresets());
  }, []);
  const deleteCustomPreset = useCallback(async name => {
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
    reset
  };
}
//# sourceMappingURL=useEqualizer.js.map