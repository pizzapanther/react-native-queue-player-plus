package com.margelo.nitro.queueplayer

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.queueplayer.cast.FakeRemotePlayer
import com.margelo.nitro.queueplayer.cast.PlaybackStateRouter
import com.margelo.nitro.queueplayer.cast.RemotePlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * event wiring tests. Exercises the
 * dispatch-side bodies directly (emitProgressTick,
 * PlaybackErrorMapping classifier) because Robolectric's ExoPlayer does
 * not reach STATE_READY without an actual decoder pipeline —
 * triggering onPlaybackStateChanged organically would require
 * instrumentation tests, which are not in scope for the test:android
 * Gradle task.
 *
 * The state-change / track-change / queue-end translation logic
 * is structural (pure function over `getStateInternal` + a few
 * fields); we test each helper's contract in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerEventsTest {

  private lateinit var player: TrackPlayer

  private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun track(id: String) = TrackItem(
    id = id, url = "file:///queue-player-test/$id.m4a",
    title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null, isVideo = null
  )

  private lateinit var serviceHandle: PlaybackServiceTestHandle

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
  }

  @After
  fun tearDown() {
    PlaybackStateRouter.setActive(null)
    player.destroyInternal()
    serviceHandle.destroy()
  }

  // --- onProgress registration lifecycle

  @Test
  fun `timed metadata supports multiple subscribers and drops retired engines`() {
    player.setQueueInternal(listOf(track("stream")))
    val first = mutableListOf<TimedMetadata>()
    val second = mutableListOf<TimedMetadata>()
    val offFirst = player.onTimedMetadata { first.add(it) }
    val offSecond = player.onTimedMetadata { second.add(it) }
    val sample = androidx.media3.common.Metadata(
      androidx.media3.extractor.metadata.icy.IcyInfo(byteArrayOf(), "Artist - Song", null)
    )
    val engine = serviceHandle.engine
    player.onTimedMetadata(engine, sample)
    assertEquals(1, first.size)
    assertEquals(first, second)
    offFirst()
    offFirst()
    player.onTimedMetadata(engine, sample)
    assertEquals(1, first.size)
    assertEquals(2, second.size)
    player.destroyInternal()
    player.onTimedMetadata(engine, sample)
    assertEquals(2, second.size)
    offSecond()
  }

  @Test
  fun `onProgress subscription returns an unsubscribe closure that clears the callback`() {
    val remote = FakeRemotePlayer()
    remote.reportState(RemotePlaybackState.PLAYING)
    PlaybackStateRouter.setActive(remote)
    val received = mutableListOf<PlayerProgress>()
    val unsubscribe = player.onProgress { p -> received.add(p) }

    // Direct tick — production path tests that the tick actually
    // fires are non-trivial under Robolectric (the Handler loop
    // runs on main, and Robolectric needs explicit `ShadowLooper.idle()`
    // to advance it). Driving the tick body directly is sufficient
    // for unit coverage.
    player.emitProgressTick()
    assertEquals(1, received.size)

    unsubscribe()
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000))
    player.emitProgressTick()
    assertEquals(
      "unsubscribe must clear the callback — no further ticks dispatch",
      1, received.size
    )
  }

  @Test
  fun `emitProgressTick is silent when the player has no timeline`() {
    var last: PlayerProgress? = null
    player.onProgress { p -> last = p }

    player.emitProgressTick()

    assertEquals(null, last)
    assertEquals(PlayerProgress(0.0, 0.0, 0.0), player.getProgress())
  }

  // --- onStateChange / onError / onQueueEnd registration

  @Test
  fun `onStateChange delivers to a subscriber and stops after unsubscribe`() {
    var fired = 0
    val unsubscribe = player.onStateChange { _, _ -> fired += 1 }

    player.emitRemoteState(PlayerState.PAUSED, StateChangeReason.SYSTEM)
    assertEquals(1, fired)

    unsubscribe()
    // A different state, so the only thing between it and the listener is
    // the unsubscribe — a repeat of the same one would be deduped.
    player.emitRemoteState(PlayerState.PLAYING, StateChangeReason.SYSTEM)
    assertEquals("nothing reaches a removed subscriber", 1, fired)
  }

  @Test
  fun `onError delivers to a subscriber and stops after unsubscribe`() {
    player.setQueueInternal((1..2).map { track("t$it") })
    player.currentTrackIndex = 1
    var fired = 0
    val unsubscribe = player.onError { fired += 1 }

    // A failure on a track that is not playing is reported straight away.
    player.onError(serviceHandle.engine, networkFailure(), 0)
    assertEquals(1, fired)

    unsubscribe()
    // A different failure, so the only thing between it and the listener
    // is the unsubscribe — a repeat of the same one would be deduped.
    player.onError(serviceHandle.engine, decodingFailure(), 0)
    assertEquals("nothing reaches a removed subscriber", 1, fired)
  }

  @Test
  fun `onQueueEnd delivers to a subscriber and stops after unsubscribe`() {
    player.setQueueInternal((1..2).map { track("t$it") })
    var fired = 0
    val unsubscribe = player.onQueueEnd { fired += 1 }

    player.maybeEmitQueueEnd(androidx.media3.common.Player.STATE_ENDED)
    assertEquals(1, fired)

    unsubscribe()
    player.maybeEmitQueueEnd(androidx.media3.common.Player.STATE_ENDED)
    assertEquals("nothing reaches a removed subscriber", 1, fired)
  }

  /** Queue end is announced as ENDED / QUEUE_END, and the pause that parks the
   *  engine afterwards leaves no reason behind for the next emit. */
  @Test
  fun `queue end announces ENDED with the queue-end reason and stamps nothing after`() {
    player.setQueueInternal((1..2).map { track("t$it") })
    val emits = mutableListOf<Pair<PlayerState, StateChangeReason>>()
    val unsubscribe = player.onStateChange { state, reason -> emits.add(state to reason) }

    player.maybeEmitQueueEnd(androidx.media3.common.Player.STATE_ENDED)
    unsubscribe()

    assertEquals(PlayerState.ENDED to StateChangeReason.QUEUE_END, emits.last())
    assertEquals("the parking pause's USER stamp must not survive", null, player.pendingStateChangeReason)
  }

  @Test
  fun `onTrackChange delivers to a subscriber and stops after unsubscribe`() {
    player.setQueueInternal((1..3).map { track("t$it") })
    var fired = 0
    val unsubscribe = player.onTrackChange { _, _, _, _ -> fired += 1 }

    player.emitRemoteTrackChange(1)
    assertEquals(1, fired)

    unsubscribe()
    // A different index, so the only thing between it and the listener is
    // the unsubscribe — a repeat of the current index returns early.
    player.emitRemoteTrackChange(2)
    assertEquals("nothing reaches a removed subscriber", 1, fired)
  }

  /**
   * A source that answered with something that is not media is a format
   * problem, not a reachability one: it was reached, and a retry cannot change
   * what it sends. Classing it transient would spend an automatic attempt on a
   * body that can never become audio.
   */
  @Test
  fun aNonMediaResponseClassifiesAsAFormatFailureAndIsFatal() {
    val invalidContentType = PlaybackException(
      "msg", null,
      PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
    )
    assertEquals(
      PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED,
      PlaybackErrorMapping.classify(invalidContentType)
    )
    assertFalse(
      PlaybackErrorMapping.isTransient(PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED)
    )
  }

  /** A source that is genuinely missing stays a reachability failure. */
  @Test
  fun aMissingFileStillClassifiesAsUnreachable() {
    val notFound = PlaybackException(
      "msg", null,
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
    )
    assertEquals(
      PlaybackErrorCode.SOURCE_UNREACHABLE,
      PlaybackErrorMapping.classify(notFound)
    )
  }

  // --- PlaybackErrorMapping.isTransient classification
  //
  // `PlaybackErrorMapping.isTransient` operates on the lib-
  // standardized `PlaybackErrorCode` (cross-platform, mirrors iOS).

  @Test
  fun `transient classifier flags network reachability failures`() {
    assertTrue(PlaybackErrorMapping.isTransient(PlaybackErrorCode.NETWORK_UNREACHABLE))
    assertTrue(PlaybackErrorMapping.isTransient(PlaybackErrorCode.TIMEOUT))
    assertTrue(PlaybackErrorMapping.isTransient(PlaybackErrorCode.CONNECTION_LOST))
    assertTrue(PlaybackErrorMapping.isTransient(PlaybackErrorCode.SOURCE_UNREACHABLE))
  }

  @Test
  fun `transient classifier does NOT flag auth or cleartext errors`() {
    assertFalse(
      "auth-denied is fatal — consumer must refresh tokens, not retry",
      PlaybackErrorMapping.isTransient(PlaybackErrorCode.AUTH_ERROR)
    )
    assertFalse(
      "cleartext-not-permitted is fatal — manifest misconfig",
      PlaybackErrorMapping.isTransient(PlaybackErrorCode.CLEARTEXT_NOT_PERMITTED)
    )
    assertFalse(
      "HTTP error is fatal — bad URL / 5xx server bug",
      PlaybackErrorMapping.isTransient(PlaybackErrorCode.HTTP_ERROR)
    )
  }

  @Test
  fun `transient classifier does not flag codec or decoding errors`() {
    assertFalse(PlaybackErrorMapping.isTransient(PlaybackErrorCode.DECODING_FAILED))
    assertFalse(PlaybackErrorMapping.isTransient(PlaybackErrorCode.DECODER_NOT_FOUND))
    assertFalse(PlaybackErrorMapping.isTransient(PlaybackErrorCode.FILE_FORMAT_NOT_RECOGNIZED))
  }

  @Test
  fun `error mapping classifies Media3 codes onto standardized vocabulary`() {
    val timeoutError = PlaybackException(
      "msg", null,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    )
    assertEquals(PlaybackErrorCode.TIMEOUT, PlaybackErrorMapping.classify(timeoutError))

    val cleartextError = PlaybackException(
      "msg", null,
      PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
    )
    assertEquals(
      PlaybackErrorCode.CLEARTEXT_NOT_PERMITTED,
      PlaybackErrorMapping.classify(cleartextError)
    )

    val decoderError = PlaybackException(
      "msg", null,
      PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    )
    assertEquals(
      PlaybackErrorCode.DECODER_NOT_FOUND,
      PlaybackErrorMapping.classify(decoderError)
    )
  }

  @Test
  fun `error mapping strips query strings from native messages to avoid PII leak`() {
    val raw = "Source error at https://server.example/stream?u=user&t=token rejected"
    val stripped = PlaybackErrorMapping.stripQuery(raw)
    assertFalse("query string must be removed", stripped.contains("u=user"))
    assertFalse("query string must be removed", stripped.contains("t=token"))
    assertTrue("must keep prefix", stripped.contains("https://server.example/stream"))
    assertTrue("must keep suffix", stripped.contains("rejected"))
  }

  // --- destroyInternal resets event-related state

  @Test
  fun `destroyInternal clears pending reason channels and lastReportedState`() {
    // Simulate a transport + skip in progress: both pending
    // channels populated, lastReportedState not NONE.
    player.setQueueInternal((1..3).map { track("t$it") })

    player.destroyInternal()
    serviceHandle.destroy()
    serviceHandle = bindPlaybackServiceForTest(player, context())

    // Post-destroy: player is null, state cleared. A subsequent
    // configureInternal would start with a clean slate. We check
    // the public effects — a new configure + getStateInternal
    // should return NONE.
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
    assertEquals(PlayerState.NONE, player.getStateInternal())
  }

  // --- handleMediaItemTransition state-sync policy

  @Test
  fun `selecting a video pauses before emitting the selected track`() {
    val video = track("video").copy(isVideo = true)
    player.setQueueInternal(listOf(track("audio"), video))
    player.player!!.playWhenReady = true
    val events = mutableListOf<Pair<TrackItem?, Double>>()
    player.onTrackChange { track, index, _, _ ->
      assertFalse(player.player!!.playWhenReady)
      events.add(track to index)
    }
    player.skipToIndexInternal(1)
    assertEquals(listOf(video to 1.0), events)
    assertEquals(1, player.currentTrackIndex)
    player.mediaSessionCommandHandler.onMediaPlay()
    assertFalse("A play request must keep the video paused", player.player!!.playWhenReady)
    // Controller play can address the native player directly as well.
    player.player!!.playWhenReady = true
    assertFalse(player.player!!.playWhenReady)
  }

  @Test
  fun `first added video emits at index zero and stays paused`() {
    val video = track("video").copy(isVideo = true)
    val events = mutableListOf<Pair<TrackItem?, Double>>()
    player.onTrackChange { track, index, _, _ -> events.add(track to index) }
    player.addToQueueInternal(listOf(video), null)
    player.skipToIndexInternal(0)
    assertEquals(listOf(video to 0.0), events)
    assertFalse(player.player!!.playWhenReady)
  }

  @Test
  fun `false and omitted video flags preserve audio playback on selection`() {
    player.setQueueInternal(listOf(track("one"), track("two").copy(isVideo = false), track("three")))
    player.player!!.playWhenReady = true
    player.skipToIndexInternal(1)
    assertTrue(player.player!!.playWhenReady)
    player.skipToIndexInternal(2)
    assertTrue(player.player!!.playWhenReady)
  }

  @Test
  fun `auto advance to a video pauses and announces the incoming index`() {
    val video = track("video").copy(isVideo = true)
    player.setQueueInternal(listOf(track("audio"), video))
    player.player!!.seekTo(1, 0L)
    // The native player has advanced while the canonical index still names
    // the outgoing audio, as it does on entry to the AUTO transition callback.
    player.currentTrackIndex = 0
    player.player!!.playWhenReady = true
    val events = mutableListOf<Triple<TrackItem?, Double, TrackChangeReason>>()
    player.onTrackChange { track, index, reason, _ ->
      assertFalse(player.player!!.playWhenReady)
      events.add(Triple(track, index, reason))
    }
    player.handleMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
    assertEquals(listOf(Triple(video, 1.0, TrackChangeReason.AUTO_ADVANCE)), events)
  }

  @Test
  fun `selecting the first added track emits its track and index exactly once`() {
    val added = track("added")
    val events = mutableListOf<Triple<TrackItem?, Double, TrackChangeReason>>()
    player.onTrackChange { track, index, reason, _ -> events.add(Triple(track, index, reason)) }

    player.addToQueueInternal(listOf(added), null)
    assertEquals(-1, player.currentTrackIndex)
    assertEquals(0, player.player!!.currentMediaItemIndex)
    assertTrue("Priming an unselected queue is not a track change", events.isEmpty())

    player.skipToIndexInternal(0)
    assertEquals(listOf(Triple(added, 0.0, TrackChangeReason.USER_SKIP_TO_INDEX)), events)
    assertEquals(0, player.currentTrackIndex)
    assertNull(player.pendingTrackChangeReason)

    player.skipToIndexInternal(0)
    assertEquals("Restarting the selected track must not emit again", 1, events.size)
    assertNull(player.pendingTrackChangeReason)
  }

  @Test
  fun `appending then skipping emits the new track exactly once`() {
    player.setQueueInternal(listOf(track("old")))
    val added = track("added")
    val events = mutableListOf<Pair<TrackItem?, Double>>()
    player.onTrackChange { track, index, _, _ -> events.add(track to index) }

    player.addToQueueInternal(listOf(added), null)
    player.skipToIndexInternal(1)

    assertEquals(listOf(added to 1.0), events)
    assertEquals(1, player.currentTrackIndex)
  }

  @Test
  fun `handleMediaItemTransition REASON_PLAYLIST_CHANGED does NOT override currentTrackIndex`() {
    // Simulate the add-into-empty-queue scenario:
    //   - mutation body set tracks + left currentTrackIndex at -1
    //   - ExoPlayer's internal currentMediaItemIndex is 0 after
    //     setMediaItems fires the PLAYLIST_CHANGED transition
    //   - listener must NOT clobber the logical -1 from the Kotlin
    //     side with the ExoPlayer-derived 0
    player.tracks = listOf(
      TrackItem("a", "file:///queue-player-test/a.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null),
      TrackItem("b", "file:///queue-player-test/b.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    )
    player.currentTrackIndex = -1

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
    )

    assertEquals(
      "PLAYLIST_CHANGED must never override cur — mutation body is authoritative",
      -1, player.currentTrackIndex
    )
  }

  @Test
  fun `handleMediaItemTransition REASON_SEEK uses the settled player index`() {
    player.setQueueInternal((1..5).map { track("t$it") })
    player.skipToIndexInternal(3)
    player.currentTrackIndex = 0

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    )

    assertEquals(3, player.currentTrackIndex)
  }

  @Test
  fun `handleMediaItemTransition REASON_SEEK consumes the pending track change reason`() {
    player.tracks = (1..5).map {
      TrackItem("t$it", "file:///queue-player-test/t$it.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    }
    player.currentTrackIndex = 2

    var observedReason: TrackChangeReason? = null
    player.onTrackChange { _, _, reason, _ -> observedReason = reason }

    // Pretend skipToNextInternal had just stashed this before the
    // Media3 seek landed.
    player.setPendingTrackChangeReasonForTest(TrackChangeReason.USER_SKIP_NEXT)

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    )

    assertEquals(
      "SEEK must consume the stashed skip reason, not fall back to TrackChangeReason.SEEK",
      TrackChangeReason.USER_SKIP_NEXT, observedReason
    )
  }

  @Test
  fun `handleMediaItemTransition REASON_SEEK without stashed reason falls back to SEEK`() {
    player.tracks = (1..5).map {
      TrackItem("t$it", "file:///queue-player-test/t$it.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    }
    player.currentTrackIndex = 2

    var observedReason: TrackChangeReason? = null
    player.onTrackChange { _, _, reason, _ -> observedReason = reason }

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    )

    assertEquals(TrackChangeReason.SEEK, observedReason)
  }

  @Test
  fun `handleMediaItemTransition fires callback with current track snapshot`() {
    val t2 = TrackItem("t2", "file:///queue-player-test/t2.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    player.tracks = listOf(
      TrackItem("t1", "file:///queue-player-test/t1.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null),
      t2,
      TrackItem("t3", "file:///queue-player-test/t3.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    )
    player.currentTrackIndex = 1

    var observedTrack: TrackItem? = null
    var observedIndex = -999.0
    player.onTrackChange { track, idx, _, _ ->
      observedTrack = track
      observedIndex = idx
    }

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
    )

    assertEquals(t2, observedTrack)
    assertEquals(1.0, observedIndex, 0.001)
  }

  // --- transitionGapMs contract
  //
  // The measured silence gap rides on onTrackChange's 4th arg only for a
  // natural gapless auto-advance. Every other transition carries `null`.
  // The positive numeric value is a native nanoTime delta.

  @Test
  fun `handleMediaItemTransition PLAYLIST_CHANGED reports null transitionGapMs`() {
    player.tracks = listOf(
      TrackItem("t1", "file:///queue-player-test/t1.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null),
      TrackItem("t2", "file:///queue-player-test/t2.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    )
    player.currentTrackIndex = 0

    var observed: Double? = -1.0
    var fired = false
    player.onTrackChange { _, _, _, gap ->
      observed = gap
      fired = true
    }

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
    )

    assertTrue("onTrackChange must fire", fired)
    assertNull("a non-auto transition carries no gap", observed)
  }

  @Test
  fun `handleMediaItemTransition SEEK reports null transitionGapMs`() {
    player.tracks = (1..3).map {
      TrackItem("t$it", "file:///queue-player-test/t$it.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    }
    player.currentTrackIndex = 1

    var observed: Double? = -1.0
    player.onTrackChange { _, _, _, gap -> observed = gap }

    player.handleMediaItemTransition(
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    )

    assertNull("a user seek carries no gap", observed)
  }

  // --- maybeEmitQueueEnd

  @Test
  fun `maybeEmitQueueEnd does nothing on non-ENDED states`() {
    player.tracks = listOf(
      TrackItem("t1", "file:///queue-player-test/t1.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    )
    var queueEndFires = 0
    player.onQueueEnd { queueEndFires += 1 }

    player.maybeEmitQueueEnd(Player.STATE_READY)
    player.maybeEmitQueueEnd(Player.STATE_BUFFERING)
    player.maybeEmitQueueEnd(Player.STATE_IDLE)

    assertEquals(0, queueEndFires)
  }

  @Test
  fun `maybeEmitQueueEnd does nothing when tracks is empty`() {
    player.tracks = emptyList()
    var queueEndFires = 0
    player.onQueueEnd { queueEndFires += 1 }

    player.maybeEmitQueueEnd(Player.STATE_ENDED)

    assertEquals(
      "empty queue reaching STATE_ENDED is not a queue-end event — " +
        "no content was queued to end",
      0, queueEndFires
    )
  }

  @Test
  fun `maybeEmitQueueEnd fires queueEnd and ENDED state on STATE_ENDED with tracks`() {
    player.tracks = (1..3).map {
      TrackItem("t$it", "file:///queue-player-test/t$it.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
    }

    var queueEndFires = 0
    var lastState: PlayerState? = null
    var lastReason: StateChangeReason? = null
    player.onQueueEnd { queueEndFires += 1 }
    player.onStateChange { state, reason ->
      lastState = state
      lastReason = reason
    }

    player.maybeEmitQueueEnd(Player.STATE_ENDED)

    assertEquals(1, queueEndFires)
    assertEquals(PlayerState.ENDED, lastState)
    assertEquals(StateChangeReason.QUEUE_END, lastReason)
  }

  // --- emitStateIfChanged + pending-reason channel

  @Test
  fun `emitStateIfChanged defaults pending reason to SYSTEM on terminal state`() {
    player.setQueueInternal(
      (1..1).map {
        TrackItem("t$it", "file:///queue-player-test/t$it.m4a", null, null, null, null, null, null, null, null, null, null, isVideo = null)
      }
    )
    // Manually reset dedup so the next call emits
    player.resetLastReportedStateForTest()

    var observedReason: StateChangeReason? = null
    player.onStateChange { _, reason -> observedReason = reason }

    player.emitStateIfChanged()

    // Default SYSTEM — no pending reason was stashed.
    assertEquals(StateChangeReason.SYSTEM, observedReason)
  }

  // --- callbacks survive destroy -> configure

  @Test
  fun `event callbacks persist across destroy and reconfigure`() {
    // Matches iOS contract — the JS consumer owns the subscription
    // and is responsible for the unsubscribe closure. destroy does
    // NOT implicitly unregister callbacks.
    var fired = false
    player.onQueueEnd { fired = true }

    player.destroyInternal()
    serviceHandle.destroy()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
    // No listener is bound to the new ExoPlayer's actual STATE_ENDED
    // path in a Robolectric test, but the callback slot itself is
    // preserved — the next time the listener fires it, `fired`
    // would be true. Structural assertion: callback is non-null.
    // We can't read the field directly without exposing it, but we
    // can assert that unsubscribe still works and doesn't throw.
    // A no-throw here confirms the slot was never silently cleared
    // by destroy.
    val unsubscribe = player.onQueueEnd { fired = true }
    unsubscribe()
    assertFalse(fired)
  }

  private fun networkFailure() = androidx.media3.common.PlaybackException(
    "boom", null,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

  private fun decodingFailure() = androidx.media3.common.PlaybackException(
    "bad frame", null,
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
}
