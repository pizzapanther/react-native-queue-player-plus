import React, { useEffect } from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { afterEach, beforeEach, expect, jest, test } from '@jest/globals';
import { getTrackPlayer } from '../../clients';
import { usePlaybackState, type PlaybackStateSnapshot } from '../usePlaybackState';
import type { PlayerState, StateChangeReason } from '../../types';

jest.mock('../../clients', () => ({ getTrackPlayer: jest.fn() }));

let listener: (state: PlayerState, reason: StateChangeReason) => void;
let renderer: ReactTestRenderer;
let current: PlaybackStateSnapshot;
let commits: PlaybackStateSnapshot[];
const dispose = jest.fn();

function Probe() {
  current = usePlaybackState();
  useEffect(() => { commits.push(current); }, [current]);
  return null;
}

beforeEach(() => {
  commits = [];
  dispose.mockClear();
  jest.mocked(getTrackPlayer).mockReturnValue({
    getState: () => 'paused',
    onStateChange: (callback: typeof listener) => {
      listener = callback;
      return dispose;
    },
  } as unknown as ReturnType<typeof getTrackPlayer>);
  act(() => { renderer = create(<Probe />); });
});

afterEach(() => { act(() => renderer.unmount()); });

test('duplicate states preserve the snapshot and original reason', () => {
  const initial = current;
  act(() => listener('paused', 'user'));
  act(() => listener('paused', 'interruption'));
  expect(current).toBe(initial);
  expect(current.reason).toBe('system');
  expect(commits).toHaveLength(1);

  act(() => listener('playing', 'user'));
  const playing = current;
  expect(playing).toEqual({ state: 'playing', reason: 'user' });
  act(() => listener('playing', 'system'));
  act(() => listener('playing', 'route-change'));
  expect(current).toBe(playing);
  expect(commits).toHaveLength(2);
});

test('a state can recur after a real transition, including batched events', () => {
  act(() => {
    listener('playing', 'user');
    listener('playing', 'system');
    listener('paused', 'interruption');
    listener('paused', 'system');
  });
  expect(current).toEqual({ state: 'paused', reason: 'interruption' });
  act(() => listener('playing', 'system'));
  expect(current).toEqual({ state: 'playing', reason: 'system' });
});

test('unmount disposes the subscription', () => {
  act(() => renderer.unmount());
  expect(dispose).toHaveBeenCalledTimes(1);
});
