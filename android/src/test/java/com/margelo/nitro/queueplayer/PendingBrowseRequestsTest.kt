package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class PendingBrowseRequestsTest {

  @Test
  fun `drainAll on a fresh queue returns empty`() {
    val queue = PendingBrowseRequests()
    assertTrue(queue.drainAll().isEmpty())
  }

  @Test
  fun `enqueue records the parentId`() {
    val queue = PendingBrowseRequests()
    queue.enqueue("root")
    assertEquals(setOf("root"), queue.snapshotForTest())
  }

  @Test
  fun `duplicate enqueues collapse to one entry`() {
    val queue = PendingBrowseRequests()
    queue.enqueue("root")
    queue.enqueue("root")
    queue.enqueue("section-a")
    queue.enqueue("section-a")
    assertEquals(setOf("root", "section-a"), queue.drainAll())
  }

  @Test
  fun `drainAll empties the queue`() {
    val queue = PendingBrowseRequests()
    queue.enqueue("root")
    queue.enqueue("section-a")
    val drained = queue.drainAll()
    assertEquals(setOf("root", "section-a"), drained)
    assertTrue(queue.drainAll().isEmpty())
    assertTrue(queue.snapshotForTest().isEmpty())
  }

  @Test
  fun `clear empties without returning anything`() {
    val queue = PendingBrowseRequests()
    queue.enqueue("root")
    queue.enqueue("section-a")
    queue.clear()
    assertTrue(queue.drainAll().isEmpty())
  }

  @Test
  fun `concurrent enqueues preserve every unique parent`() {
    val queue = PendingBrowseRequests()
    val parents = (0 until 200).map { "parent-$it" }
    val threads = parents.map { parent ->
      Thread { queue.enqueue(parent) }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertEquals(parents.toSet(), queue.drainAll())
  }

  @Test
  fun `re-enqueue after drain accumulates fresh entries`() {
    val queue = PendingBrowseRequests()
    queue.enqueue("a")
    queue.drainAll()
    queue.enqueue("b")
    queue.enqueue("c")
    assertEquals(setOf("b", "c"), queue.drainAll())
  }

  @Test
  fun `enqueue with an empty string is treated as a regular parent id`() {
    val queue = PendingBrowseRequests()
    queue.enqueue("")
    assertEquals(setOf(""), queue.drainAll())
  }
}
