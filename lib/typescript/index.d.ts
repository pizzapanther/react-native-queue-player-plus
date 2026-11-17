export type { TrackPlayer } from './TrackPlayer.nitro';
export type { Equalizer } from './Equalizer.nitro';
export type { Visualizer } from './Visualizer.nitro';
export type { CastManager } from './CastManager.nitro';
export type { TrackItem, TimedMetadata, TrackMetadataUpdate, PlayerConfig, AudioContentType, AudioCategoryOptions, VoiceVocabulary, PlayerState, BufferState, PlaybackMode, PlaybackModeKind, RepeatMode, SkipToPreviousBehavior, RemoteSkipMode, RemoteControlOptions, StateChangeReason, TrackChangeReason, QueueChangeReason, QueueChangeDelta, TrackSource, PlayerProgress, PlaybackError, PlaybackErrorCode, ShuffleResult, SkipCapability, SleepTimerState, NowPlayingFormat, NowPlayingFormatIosExtras, NowPlayingFormatAndroidExtras, AudioCodec, AudioContainer, LookaheadCacheConfig, EvictionPolicy, CacheStatus, MediaSearchRequest, MediaSearchType, MediaSearchOrigin, MediaSearchReference, MediaSearchRepeatMode, MediaSearchReleaseDateRange, MediaSearchQueueLocation, BrowseItem, BrowseSection, BrowseSnapshot, ServiceReadyReason, EqualizerBand, EqualizerPreset, ReplayGainMode, PitchCorrectionMode, VisualizerConfig, VisualizerFftSize, VisualizerFrame, VisualizerErrorReason, CastProtocol, CastStreamingModel, CastDeviceKind, CastSessionEndReason, CastLocalNetworkPermission, CastReceiverCapabilities, CastReceiver, CastRoute, CastDiscoveryState, CastSessionDiedEvent, CastLocalNetworkPermissionEvent, CastConnectOptions, CastConfigureOptions, AudioRouteKind, AudioRoute, } from './types';
export { getCastManager, getEqualizer, getTrackPlayer, getVisualizer } from './clients';
export { registerPlaybackService, unregisterPlaybackService, } from './playbackService';
export type { PlaybackServiceHandler, PlaybackServiceFactory, } from './playbackService';
export { crossfadeDurationBounds } from './types';
export { SectionIcon } from './types';
export { EQ_BAND_COUNT, EQ_BAND_FREQUENCIES_HZ, EQ_FLAT_PRESET_NAME, EQ_GAIN_MAX_DB, EQ_GAIN_MIN_DB, clampGain, gainsFromBands, gainsFromMap, isBuiltinPreset, nearestBandIndex, } from './equalizer';
export { applyQueueDelta } from './queueDelta';
export * from './hooks';
export * as Cast from './cast';
//# sourceMappingURL=index.d.ts.map