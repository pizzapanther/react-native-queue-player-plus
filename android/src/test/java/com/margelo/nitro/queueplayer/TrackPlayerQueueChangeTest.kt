package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `onQueueChange` emission coverage against a live ExoPlayer via
 * Robolectric. Every queue-content/order mutation fires exactly one
 * event carrying the post-mutation `(queue, index, reason)`; a no-op
 * mutation fires nothing. `emitQueueChange` runs synchronously inside
 * the mutation on the test (main) thread, so no looper idle is needed to
 * observe the fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class TrackPlayerQueueChangeTest {

  private lateinit var player: TrackPlayer
  private lateinit var serviceHandle: PlaybackServiceTestHandle

  private data class QueueEvent(
    /** The mirror after applying this event's delta. */
    val queue: List<TrackItem>,
    val index: Int,
    val reason: QueueChangeReason,
    val delta: QueueChangeDelta,
  )

  private fun context() =
    ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun track(id: String) = TrackItem(
    id = id, url = "https://example.com/$id.m4a",
    title = null, artist = null, album = null,
    duration = null, artworkUrl = null, extras = null,
    replayGainTrackGain = null, replayGainTrackPeak = null,
    replayGainAlbumGain = null, replayGainAlbumPeak = null,
  )

  @Before
  fun setUp() {
    player = TrackPlayer()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
    player.setQueueInternal((1..5).map { track("t$it") })
  }

  @After
  fun tearDown() {
    player.destroyInternal()
    serviceHandle.destroy()
  }

  /** Register a capturing listener AFTER the @Before setQueue (which is
   *  therefore not captured — onQueueChange does not auto-fire). */
  private fun capture(): Pair<MutableList<QueueEvent>, () -> Unit> {
    val events = mutableListOf<QueueEvent>()
    // Mirror the queue by applying each delta, the way a consumer must. Every
    // assertion below reads that mirror, so it doubles as proof the delta
    // carries enough to reconstruct the queue.
    var mirror = player.tracks.toList()
    val dispose = player.onQueueChange { delta, idx, reason ->
      mirror = applyDelta(mirror, delta, reason)
      events.add(QueueEvent(mirror, idx.toInt(), reason, delta))
    }
    return events to dispose
  }

  /** The reference delta application, mirroring what `useQueue()` does in JS. */
  private fun applyDelta(
    previous: List<TrackItem>,
    delta: QueueChangeDelta,
    reason: QueueChangeReason
  ): List<TrackItem> = when (reason) {
    QueueChangeReason.SET_QUEUE -> delta.inserted.toList()
    QueueChangeReason.CLEAR -> emptyList()
    QueueChangeReason.ADD -> previous.toMutableList().apply {
      addAll(delta.insertedAt.toInt(), delta.inserted.toList())
    }
    QueueChangeReason.REMOVE -> {
      val drop = delta.removed.map { it.toInt() }.toSet()
      previous.filterIndexed { i, _ -> i !in drop }
    }
    QueueChangeReason.MOVE -> previous.toMutableList().apply {
      add(delta.movedTo.toInt(), removeAt(delta.movedFrom.toInt()))
    }
    QueueChangeReason.SHUFFLE -> delta.order.map { previous[it.toInt()] }
  }

  @Test
  fun `a mirror built only from deltas matches the live queue`() {
    val (events, dispose) = capture()
    player.setQueueInternal((1..6).map { track("s$it") }, startAtIndex = 0)
    player.addToQueueInternal(listOf(track("added")), insertBefore = 2)
    player.removeFromQueueInternal(listOf(4, 0))
    player.moveInQueueInternal(3, 1)
    dispose()

    assertEquals(
      "the delta stream must reconstruct the queue exactly",
      player.tracks.map { it.id },
      events.last().queue.map { it.id }
    )
  }

  @Test
  fun `each emit advances the revision by one`() {
    val (events, dispose) = capture()
    player.setQueueInternal((1..3).map { track("r$it") })
    player.addToQueueInternal(listOf(track("r4")), insertBefore = null)
    player.clearQueueInternal()
    dispose()

    val revisions = events.map { it.delta.revision.toInt() }
    val first = revisions.first()
    assertEquals(revisions.indices.map { first + it }, revisions)
  }

  /**
   * `destroy` and `configure` clear the queue without an emit; the revision
   * the next emit carries skips one, which is the signal a delta mirror
   * re-reads on.
   */
  @Test
  fun `a silent clear leaves a revision gap for the next emit`() {
    val (events, dispose) = capture()
    player.setQueueInternal((1..3).map { track("g$it") })
    val before = events.last().delta.revision.toInt()

    player.destroyInternal()
    serviceHandle.destroy()
    serviceHandle = bindPlaybackServiceForTest(player, context())
    player.configureInternal(
      PlayerConfig(enableDiagnosticLogs = null, httpHeaders = null, userAgent = null, autoRetries = null, retryBackoffMs = null, networkTimeoutMs = null, audioContentType = null, audioCategoryOptions = null, voiceVocabularyDonationEnabled = null, allowsExternalPlayback = null,  visualizationEnabled = null, clampSeekToBuffered = null, lookaheadCacheMaxSizeMb = null, lookaheadCacheEvictionPolicy = null, progressUpdateIntervalMs = null, backgroundProgressUpdateIntervalMs = null, placeholderArtworkUri = null, skipToPreviousBehavior = null), context()
    )
    player.setQueueInternal(listOf(track("after")))
    dispose()

    assertEquals(before + 2, events.last().delta.revision.toInt())
  }

  // --- one emission per mutation, with the post-mutation snapshot

  @Test
  fun `setQueue fires SET_QUEUE with the new queue + resolved index`() {
    val (events, dispose) = capture()
    player.setQueueInternal((1..3).map { track("n$it") }, startAtIndex = 1)
    dispose()
    assertEquals(1, events.size)
    assertEquals(QueueChangeReason.SET_QUEUE, events[0].reason)
    assertEquals(3, events[0].queue.size)
    assertEquals(1, events[0].index)
  }

  @Test
  fun `addToQueue fires ADD with the grown queue`() {
    val (events, dispose) = capture()
    player.addToQueueInternal(listOf(track("n1")), insertBefore = null)
    dispose()
    assertEquals(1, events.size)
    assertEquals(QueueChangeReason.ADD, events[0].reason)
    assertEquals(6, events[0].queue.size)
    assertEquals("n1", events[0].queue[5].id)
  }

  @Test
  fun `removeFromQueue fires REMOVE with the shrunk queue`() {
    val (events, dispose) = capture()
    player.removeFromQueueInternal(listOf(3)) // remove t4; cur=0 pinned
    dispose()
    assertEquals(1, events.size)
    assertEquals(QueueChangeReason.REMOVE, events[0].reason)
    assertEquals(listOf("t1", "t2", "t3", "t5"), events[0].queue.map { it.id })
  }

  @Test
  fun `moveInQueue fires MOVE`() {
    val (events, dispose) = capture()
    player.moveInQueueInternal(from = 3, to = 1) // cur=0 not moved
    dispose()
    assertEquals(1, events.size)
    assertEquals(QueueChangeReason.MOVE, events[0].reason)
    // t4 (index 3) removed + reinserted at 1 → t1,t4,t2,t3,t5.
    assertEquals(listOf("t1", "t4", "t2", "t3", "t5"), events[0].queue.map { it.id })
  }

  @Test
  fun `clearQueue fires CLEAR with an empty queue + index -1`() {
    val (events, dispose) = capture()
    player.clearQueueInternal()
    dispose()
    assertEquals(1, events.size)
    assertEquals(QueueChangeReason.CLEAR, events[0].reason)
    assertEquals(0, events[0].queue.size)
    assertEquals(-1, events[0].index)
  }

  @Test
  fun `shuffleQueue fires SHUFFLE with a full queue at index 0`() {
    val (events, dispose) = capture()
    player.shuffleQueueInternal()
    dispose()
    assertEquals(1, events.size)
    assertEquals(QueueChangeReason.SHUFFLE, events[0].reason)
    assertEquals(5, events[0].queue.size)
    assertEquals(0, events[0].index)
  }

  // --- no-op mutations do not fire

  @Test
  fun `empty addToQueue does not fire`() {
    val (events, dispose) = capture()
    player.addToQueueInternal(emptyList(), insertBefore = null)
    dispose()
    assertTrue(events.isEmpty())
  }

  @Test
  fun `no-op move (from == to) does not fire`() {
    val (events, dispose) = capture()
    player.moveInQueueInternal(from = 2, to = 2)
    dispose()
    assertTrue(events.isEmpty())
  }

  @Test
  fun `out-of-range remove does not fire`() {
    val (events, dispose) = capture()
    player.removeFromQueueInternal(listOf(99))
    dispose()
    assertTrue(events.isEmpty())
  }

  @Test
  fun `clearing an already-empty queue does not fire`() {
    player.clearQueueInternal() // empties (fires; no listener yet)
    val (events, dispose) = capture()
    player.clearQueueInternal() // already empty → no-op
    dispose()
    assertTrue(events.isEmpty())
  }

  @Test
  fun `unsubscribe stops emissions`() {
    val (events, dispose) = capture()
    dispose()
    player.addToQueueInternal(listOf(track("n1")), insertBefore = null)
    assertTrue(events.isEmpty())
  }
}
