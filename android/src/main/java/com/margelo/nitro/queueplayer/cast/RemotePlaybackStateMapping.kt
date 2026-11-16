package com.margelo.nitro.queueplayer.cast

import com.margelo.nitro.queueplayer.PlayerState

/**
 * Map the protocol-neutral receiver state to the JS-facing [PlayerState].
 * Shared by the cast event fan-out and `TrackPlayer.getState`, so a state read
 * and the event stream can't disagree about the same receiver.
 */
internal fun mapRemotePlaybackState(state: RemotePlaybackState): PlayerState = when (state) {
  RemotePlaybackState.PLAYING -> PlayerState.PLAYING
  RemotePlaybackState.PAUSED -> PlayerState.PAUSED
  RemotePlaybackState.LOADING -> PlayerState.BUFFERING
  RemotePlaybackState.BUFFERING -> PlayerState.BUFFERING
  RemotePlaybackState.IDLE -> PlayerState.NONE
  RemotePlaybackState.ENDED -> PlayerState.ENDED
  RemotePlaybackState.ERROR -> PlayerState.ERROR
}
