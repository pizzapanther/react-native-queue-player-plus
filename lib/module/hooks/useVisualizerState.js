"use strict";

import { useEffect, useRef, useState } from 'react';
import { getVisualizer } from "../clients.js";
const INITIAL = {
  fft: null,
  samples: null,
  sampleRate: 0,
  available: false,
  reason: null
};

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
export function useVisualizerState(config, sampleRateHz = 30) {
  const [state, setState] = useState(INITIAL);
  // Pull primitive config fields into local scope so the effect dep
  // array can list them directly — passing an inline `config` object
  // each render would re-fire the effect on every parent re-render.
  const {
    fftSize,
    updateIntervalMs,
    includeSamples
  } = config;

  // Latest frame held in a ref so the audio-thread bridge callback
  // doesn't trigger a React re-render per frame. The throttle
  // interval below pulls from the ref. `frameEpochRef` increments
  // once per native frame so the throttle can detect "no new frame
  // since last setState" and skip the re-render entirely.
  const latestFrameRef = useRef(null);
  const latestReasonRef = useRef(null);
  const frameEpochRef = useRef(0);
  useEffect(() => {
    const viz = getVisualizer();
    const dispose = viz.subscribe({
      fftSize,
      updateIntervalMs,
      includeSamples
    }, frame => {
      latestFrameRef.current = frame;
      latestReasonRef.current = null;
      frameEpochRef.current += 1;
    }, reason => {
      latestReasonRef.current = reason;
    });
    const intervalMs = Math.max(16, Math.round(1000 / sampleRateHz));
    let lastSeenEpoch = 0;
    let lastSeenReason = null;
    const timer = setInterval(() => {
      const frame = latestFrameRef.current;
      const reason = latestReasonRef.current;
      const epoch = frameEpochRef.current;
      if (frame && epoch !== lastSeenEpoch) {
        lastSeenEpoch = epoch;
        lastSeenReason = reason;
        setState({
          fft: new Float32Array(frame.fft),
          samples: frame.samples ? new Float32Array(frame.samples) : null,
          sampleRate: frame.sampleRate,
          available: reason === null,
          reason
        });
      } else if (reason && reason !== lastSeenReason) {
        lastSeenReason = reason;
        setState(prev => ({
          ...prev,
          reason,
          available: false
        }));
      }
    }, intervalMs);
    return () => {
      clearInterval(timer);
      dispose();
      latestFrameRef.current = null;
      latestReasonRef.current = null;
      frameEpochRef.current = 0;
    };
  }, [fftSize, updateIntervalMs, includeSamples, sampleRateHz]);
  return state;
}
//# sourceMappingURL=useVisualizerState.js.map