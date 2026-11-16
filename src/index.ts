// HybridObject type exports
export type { TrackPlayer } from './TrackPlayer.nitro';
export type { Equalizer } from './Equalizer.nitro';
export type { Visualizer } from './Visualizer.nitro';
export type { CastManager } from './CastManager.nitro';

// All value types live in src/types.ts — see there for the canonical home.
export type {
  // Player
  TrackItem,
  PlayerConfig,
  AudioContentType,
  AudioCategoryOptions,
  VoiceVocabulary,
  PlayerState,
  BufferState,
  PlaybackMode,
  PlaybackModeKind,
  RepeatMode,
  SkipToPreviousBehavior,
  RemoteSkipMode,
  RemoteControlOptions,
  StateChangeReason,
  TrackChangeReason,
  QueueChangeReason,
  QueueChangeDelta,
  TrackSource,
  PlayerProgress,
  PlaybackError,
  PlaybackErrorCode,
  ShuffleResult,
  SkipCapability,
  SleepTimerState,
  NowPlayingFormat,
  NowPlayingFormatIosExtras,
  NowPlayingFormatAndroidExtras,
  AudioCodec,
  AudioContainer,
  // Lookahead cache
  LookaheadCacheConfig,
  EvictionPolicy,
  CacheStatus,
  // Automotive / voice
  MediaSearchRequest,
  MediaSearchType,
  MediaSearchOrigin,
  MediaSearchReference,
  MediaSearchRepeatMode,
  MediaSearchReleaseDateRange,
  MediaSearchQueueLocation,
  BrowseItem,
  BrowseSection,
  BrowseSnapshot,
  ServiceReadyReason,
  // Equalizer
  EqualizerBand,
  EqualizerPreset,
  // ReplayGain
  ReplayGainMode,
  // Pitch correction
  PitchCorrectionMode,
  // Visualizer
  VisualizerConfig,
  VisualizerFftSize,
  VisualizerFrame,
  VisualizerErrorReason,
  // Cast
  CastProtocol,
  CastStreamingModel,
  CastDeviceKind,
  CastSessionEndReason,
  CastLocalNetworkPermission,
  CastReceiverCapabilities,
  CastReceiver,
  CastRoute,
  CastDiscoveryState,
  CastSessionDiedEvent,
  CastLocalNetworkPermissionEvent,
  CastConnectOptions,
  CastConfigureOptions,
  // Audio route (cast + on-device)
  AudioRouteKind,
  AudioRoute,
} from './types';

// HybridObject accessors — defined in `clients.ts` to break the
// require cycle between `index.ts` and `hooks/*` (each hook needs
// `getTrackPlayer` etc, but `index.ts` re-exports the hooks).
export { getCastManager, getEqualizer, getTrackPlayer, getVisualizer } from './clients';

// Automotive / voice — playback service registration. Consumer registers
// a handler factory once at app boot to service browse / search / play
// requests routed in from CarPlay, Android Auto, and voice assistants.
export {
  registerPlaybackService,
  unregisterPlaybackService,
} from './playbackService';
export type {
  PlaybackServiceHandler,
  PlaybackServiceFactory,
} from './playbackService';

// Canonical bounds for `crossfadeDurationMs`; consumer apps building
// stepper / slider UIs read from here.
export { crossfadeDurationBounds } from './types';

// Automotive — closed enum of tab-bar icons the lib knows how to
// render. Consumers reference `SectionIcon.Albums` etc; lib maps to
// SF Symbol on iOS. Android Auto has no tab-icon API today.
export { SectionIcon } from './types';

// Pure-function equalizer helpers — range constants, band frequency
// table, clamp + lookup utilities. Live alongside the imperative
// `getEqualizer()` API for consumers building EQ UIs.
export {
  EQ_BAND_COUNT,
  EQ_BAND_FREQUENCIES_HZ,
  EQ_FLAT_PRESET_NAME,
  EQ_GAIN_MAX_DB,
  EQ_GAIN_MIN_DB,
  clampGain,
  gainsFromBands,
  gainsFromMap,
  isBuiltinPreset,
  nearestBandIndex,
} from './equalizer';

// Pure helper for applying an onQueueChange delta to a queue copy.
export { applyQueueDelta } from './queueDelta';

// React hooks — thin observers over the HybridObject event surface.
export * from './hooks';

// Cast — `Cast.*`: thin functions over the `CastManager` HybridObject
// (optional-argument shapes, `CastSubscription` handles, `CastErrorCodes`,
// `localRoute()`); `getCastManager()` returns the HybridObject itself.
export * as Cast from './cast';
