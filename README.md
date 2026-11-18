# react-native-queue-player

A complete audio playback library for React Native — gapless and crossfade engines, a 10-band equalizer, an FFT visualizer, ReplayGain loudness normalization, look-ahead disk caching, casting (Chromecast + AirPlay), automotive (CarPlay + Android Auto), and voice control (SiriKit + Google Assistant), all in one [Nitro](https://nitro.margelo.com/)-powered package.

[![npm](https://img.shields.io/npm/v/react-native-queue-player.svg)](https://www.npmjs.com/package/react-native-queue-player)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
![Platforms: iOS · Android](https://img.shields.io/badge/platforms-iOS%20%C2%B7%20Android-lightgrey.svg)

📖 **Documentation: https://ghenry22.github.io/react-native-queue-player/**

## Features

- **Gapless and crossfade playback** — switch engines at runtime; configurable crossfade duration.
- **Queue management** — set / add / insert / move / remove, jump to index, skip next/previous, start-at-index ("play album from track N").
- **Repeat and shuffle** — off / queue / track repeat; one-shot shuffle.
- **10-band equalizer** — ISO-frequency bands, built-in and custom presets.
- **FFT visualizer** — real-time frequency/waveform frames for custom visualizations.
- **ReplayGain** — track and album loudness normalization for tagged media.
- **Look-ahead cache** — proactive disk prefetch of upcoming streamed tracks, with a configurable size budget.
- **Casting** — Chromecast and AirPlay, including a native Android AirPlay sender.
- **Automotive** — CarPlay and Android Auto browse trees and playback.
- **Voice** — SiriKit (`INPlayMediaIntent`) on iOS and Google Assistant on Android.
- **Background playback** — lock-screen / Now Playing controls and remote commands handled natively.
- **Remote-control skip mode** — choose track skip or interval skip (jump ±N seconds) for the lock screen / notification, adjustable at runtime.
- **Sleep timer** — pause after a duration or at the end of the current track, with a volume fade; runs on a native wall-clock deadline.
- **Now Playing format** — codec, container, sample rate, bitrate, and ReplayGain metadata for the active track.
- **React hooks** — `useProgress`, `usePlaybackState`, `useActiveTrack`, `useQueue`, `useCanSkip`, `useSleepTimer`, `useBufferState`, `useFullyBuffered`, `useEqualizer`, `useVisualizerState`, `useLookaheadCache`, `useNowPlayingFormat`, `useCast`, `useAudioRoute`.

## Requirements

- React Native 0.88 or later, with the New Architecture enabled.
- With Expo: version 3 (3.0.0 or later) on Expo SDK 58 and later. Expo SDK 58 starts React Native from its scene delegate when a window connects, and its app delegate no longer creates a window. Version 2's CarPlay scene setup takes the phone window's content from the app delegate's window, so it does not work on SDK 58, and version 2 is not supported on SDK 58 in any configuration. Version 3 is built for SDK 58's start-up and, with the `carplay` or `siri` option, also starts JavaScript for CarPlay and Siri when no phone window is open. On Expo SDK 57 and earlier, or React Native older than 0.88, use `2.0.4-rc.6`, the last version 2 release (tested with Expo SDK 57 and React Native 0.86). It is a prerelease, so install it by exact version: `npm install react-native-queue-player@2.0.4-rc.6`. See [Upgrading to v3](https://ghenry22.github.io/react-native-queue-player/docs/migrations/v3).
- [`react-native-nitro-modules`](https://nitro.margelo.com/) (peer dependency).
- iOS 16+ (Chromecast subspec included).
- Android `minSdk` 24+.
- Expo config-plugin support (the native setup is driven by the bundled Expo plugin).

## Installation

```sh
npm install react-native-queue-player react-native-nitro-modules
```

`react-native-nitro-modules` is required because this library is built on [Nitro Modules](https://nitro.margelo.com/).

Add the Expo config plugin to your app config and enable the optional native integrations you need:

```json
{
  "expo": {
    "plugins": [
      ["react-native-queue-player", { "carplay": false, "siri": true, "chromecast": true }]
    ]
  }
}
```

The plugin adds the iOS Info.plist keys, entitlements, AppDelegate and SceneDelegate code, and the Chromecast pod for the integrations you enable. Run `npx expo prebuild --clean` after adding it or changing its options. On Android the library's manifest (services, permissions, media-session components) is merged in for you. See **[Setup & manual configuration](https://ghenry22.github.io/react-native-queue-player/docs/guides/manual-setup)** for the steps you still need to do by hand.

## Quick start

```ts
import { getTrackPlayer } from 'react-native-queue-player';

const player = getTrackPlayer();

// One-time setup at app start.
await player.configure({ audioContentType: 'music' });

// Load a queue and play. Only `url` is required on each track.
await player.setQueue([
  { url: 'https://stream.example/track1.mp3', title: 'One', artist: 'Artist', album: 'Album', duration: 180 },
  { url: 'https://stream.example/track2.mp3', title: 'Two', artist: 'Artist', album: 'Album', duration: 200 },
]);
await player.play();
```

Set `isVideo: true` on a track to pause audio when that entry becomes current.
`onTrackChange` still reports the video track and its queue index, so your app
can open its video player. The entry stays selected, and audio play requests
remain paused until you select an audio track. Omit `isVideo` or set it to
`false` for normal audio playback.

In React components, hooks subscribe to native event streams directly:

```tsx
import {
  useActiveTrack,
  usePlaybackState,
  useProgress,
  useCanSkip,
} from 'react-native-queue-player';

function NowPlaying() {
  const { track } = useActiveTrack();
  const { state } = usePlaybackState();
  const { position, duration } = useProgress();
  const { canSkipNext, canSkipPrevious } = useCanSkip();

  if (track == null) return <Text>No track loaded</Text>;
  return (
    <View>
      <Text>{track.title} — {track.artist}</Text>
      <Text>{state} · {position.toFixed(1)} / {duration.toFixed(1)}</Text>
      {/* transport buttons read canSkipNext / canSkipPrevious */}
    </View>
  );
}
```

The player is a singleton — `getTrackPlayer()` always returns the same instance. There is no class to instantiate. The same pattern applies to `getEqualizer()`, `getVisualizer()`, and `getCastManager()`.

### Reading progress

Read the current progress without subscribing to events:

```ts
const { position, duration, buffered } = getTrackPlayer().getProgress();
```

The synchronous snapshot has the same shape and units as `onProgress`: all
values are seconds, and `buffered` is the amount buffered ahead of `position`.
It works while paused and uses receiver progress during casting. Unknown
duration or buffered amount is `0`; an inactive local queue returns all zeros.

`onProgress` emits only while a track is actively playing, locally or on a
cast receiver. Pausing, buffering, stopping, ending, or seeking while paused
does not emit progress; use `getProgress()` for those snapshots.

### Timed stream metadata

Subscribe to title, artist, album title, and artwork updates carried in the
stream (for example HLS ID3 or ICY):

```ts
const unsubscribe = getTrackPlayer().onTimedMetadata((metadata) => {
  const { title, artist, albumTitle, artworkUrl } = metadata;
  // Update your stream UI. Each field is optional.
});

// When the subscriber is no longer needed:
unsubscribe();
```

Events follow the native playback clock, so downloading or buffering future
metadata does not publish it early. JavaScript callbacks can still be delayed
by a busy JS thread. During crossfade, events follow the incoming track from
fade-start. Remote cast receivers do not supply these local playback events.

Each event contains the fields supplied by that sample, not a merged snapshot.
Static queue metadata is not emitted, and events do not modify the queue or
system Now Playing metadata. Standard ID3 title/artist/album fields and custom
`artworkUrl` / `coverArtUrl` text or URL fields are supported. JPEG/PNG embedded
artwork is exposed as a data URI. ICY `Artist - Title` is split at the first
separator; a bare title remains a title. ICY `StreamUrl` is not treated as art.

### Updating queue metadata

Patch any queue entry without restarting playback:

```ts
await getTrackPlayer().updateMetadata(0, {
  title: 'New title',
  artist: 'New artist',
  albumTitle: 'New album',
  artworkUrl: 'https://example.com/artwork.jpg',
});
```

All fields are optional; omitted fields keep their previous values. Use an
empty string to clear a field (`artworkUrl: ''` restores the placeholder).
`albumTitle` updates the queue item's `album` field. Invalid indices, empty
patches, and unchanged values are ignored.

Updates refresh `useQueue`, `useActiveTrack`, and the active item's system
Now Playing metadata. `onQueueChange` reports `update-metadata`; neither
`onTrackChange` nor `onTimedMetadata` fires. Cast receivers retain their own
metadata until their media is reloaded.

## Manual configuration

Most native setup is automatic (Nitro autolinking + the Android manifest merge + the Expo plugin). A few things you must do by hand — see the [Setup & manual configuration guide](https://ghenry22.github.io/react-native-queue-player/docs/guides/manual-setup) for full detail:

- **iOS background audio** — set automatically by the Expo plugin: it adds `audio` to `UIBackgroundModes` (on by default; pass `{ enableIosBGAudio: false }` only if your app declares `UIBackgroundModes` elsewhere). If you opt out, or use bare React Native (no plugin), set it yourself:

  ```json
  { "expo": { "ios": { "infoPlist": { "UIBackgroundModes": ["audio"] } } } }
  ```

- **Enable the integrations you need** via the plugin flags (`carplay`, `siri`, `chromecast`).
- **Initialize the player early** — call `configure()` once at app start. If you use automotive or voice, the system can cold-start your app's JS without mounting any UI, so run `configure()` (and `registerPlaybackService()`) from a bootstrap module imported **before** your app entry:

  ```js
  // index.js
  import './src/playerBootstrap'; // runs configure() + registerPlaybackService() on every cold start
  import 'expo-router/entry';
  ```

  ```ts
  // src/playerBootstrap.ts
  import { getTrackPlayer, registerPlaybackService } from 'react-native-queue-player';

  void (async () => {
    await getTrackPlayer().configure({ audioContentType: 'music' });
    registerPlaybackService(() => ({
      // automotive / voice browse + search handlers (see the automotive & voice guides)
    }));
  })();
  ```

- **Android runtime permissions** — request `POST_NOTIFICATIONS` (and, on newer Android, local-network access for casting) at runtime from your app.

Lock-screen / Now Playing controls and remote commands work automatically once the player is configured — you do not register a service to handle them.

## Common UX patterns

### Restart-or-previous skip-back (the "tap Previous restarts, then goes back" model)

The standard music-player behaviour — a skip-back restarts the current track when it's past a few seconds, otherwise goes to the previous track — is built in. Enable it with `skipToPreviousBehavior`:

```ts
await player.configure({ skipToPreviousBehavior: 'restart-or-previous' });
```

Now every `skipToPrevious()` — from your UI, the lock screen, Bluetooth, Android Auto, or CarPlay — restarts the current track (seeks to 0) when the position is past ~3&nbsp;s, and goes to the previous track under it. Because it's native, it stays consistent across every surface (a JS handler can't cleanly intercept the OS Previous button), and the restart is a seek so it does **not** fire `onTrackChange`. With the option enabled, `canSkipPrevious` stays `true` while a track is loaded, so the Previous control never greys out — even on the first track. The default `'previous'` keeps skip-back always going to the previous track. (Local playback only; during cast, Previous routes to the receiver.)

For a different threshold or bespoke logic on **your own** in-app button (the OS controls follow `skipToPreviousBehavior`), leave the option at its default and branch on the live position:

```tsx
import { getTrackPlayer, useProgress } from 'react-native-queue-player';

function PreviousButton() {
  const { position } = useProgress();
  const onPress = async () => {
    const player = getTrackPlayer();
    if (position > 5) await player.seekTo(0);
    else await player.skipToPrevious();
  };
  return /* your <Button> here */;
}
```

### Skip-button enable/disable from `useCanSkip`

`canSkipNext` / `canSkipPrevious` account for repeat mode: under `off` they go false at queue boundaries; under `queue` they wrap and stay true; under `track` they stay true. With `skipToPreviousBehavior: 'restart-or-previous'`, `canSkipPrevious` stays true whenever a track is loaded (the Previous control never greys out).

```tsx
const { canSkipNext, canSkipPrevious } = useCanSkip();

<Button onPress={onPrevious} disabled={!canSkipPrevious} />
<Button onPress={onNext}     disabled={!canSkipNext} />
```

If you adopt the >3s rewind pattern above, you may prefer to keep Previous enabled whenever there's an active track (so the user can rewind from track 1) — drive `disabled` from `track == null` instead.

## Documentation

Full guides and API reference: **https://ghenry22.github.io/react-native-queue-player/**

- [Quick start](https://ghenry22.github.io/react-native-queue-player/docs/guides/quick-start)
- [Setup & manual configuration](https://ghenry22.github.io/react-native-queue-player/docs/guides/manual-setup)
- [CarPlay](https://ghenry22.github.io/react-native-queue-player/docs/guides/carplay) · [Android Auto](https://ghenry22.github.io/react-native-queue-player/docs/guides/android-auto) · [Voice](https://ghenry22.github.io/react-native-queue-player/docs/guides/voice) · [Casting](https://ghenry22.github.io/react-native-queue-player/docs/guides/casting)
- [API reference](https://ghenry22.github.io/react-native-queue-player/docs/api/track-player)
- [Migrating from react-native-track-player v4.x](https://ghenry22.github.io/react-native-queue-player/docs/migrations/rntp)

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

Apache-2.0
