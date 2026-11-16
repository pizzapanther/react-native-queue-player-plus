package com.margelo.nitro.queueplayer

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for [BrowseDataProvider]. No Robolectric —
 * the provider is a pure data structure with atomic snapshot
 * replacement; testing it as plain Kotlin keeps the matrix fast and
 * the failures contained.
 */
@UnstableApi
class BrowseDataProviderTest {

  private fun item(
    id: String,
    title: String = "T-$id",
    playable: Boolean = true,
    hasChildren: Boolean? = null
  ) = BrowseItem(
    id = id,
    title = title,
    subtitle = null,
    artworkUrl = null,
    playable = playable,
    hasChildren = hasChildren
  )

  private fun section(
    id: String,
    title: String = "S-$id",
    vararg items: BrowseItem
  ) = BrowseSection(id = id, title = title, icon = null, artworkUrl = null, items = arrayOf(*items))

  private fun snapshot(rootId: String, vararg sections: BrowseSection) =
    BrowseSnapshot(rootId = rootId, sections = arrayOf(*sections))

  // --- empty cache --------------------------------------------------

  @Test
  fun `empty cache returns null snapshot`() {
    val provider = BrowseDataProvider()
    assertNull(provider.getSnapshot())
  }

  @Test
  fun `empty cache reports no parentId is root`() {
    val provider = BrowseDataProvider()
    assertFalse(provider.isRoot("anything"))
    assertFalse(provider.isRoot("root"))
    assertFalse(provider.isRoot(""))
  }

  @Test
  fun `empty cache returns null for getSection getItem getChildren`() {
    val provider = BrowseDataProvider()
    assertNull(provider.getSection("any"))
    assertNull(provider.getItem("any"))
    assertTrue(provider.getChildren("any", page = 0, pageSize = 50).isEmpty())
  }

  // --- setSnapshot atomic replace + change-set -----------------------

  @Test
  fun `setSnapshot of an empty cache returns root + every section as changed`() {
    val provider = BrowseDataProvider()
    val s = snapshot(
      "root",
      section("recents", items = arrayOf(item("a"), item("b"))).let { it },
      section("by-genre")
    )
    val changed = provider.setSnapshot(s)
    assertTrue(changed.contains("root"))
    assertTrue(changed.contains("recents"))
    assertTrue(changed.contains("by-genre"))
    assertEquals(3, changed.size)
  }

  @Test
  fun `setSnapshot to null clears cache and reports prior parents as changed`() {
    val provider = BrowseDataProvider()
    val s = snapshot("root", section("a"), section("b"))
    provider.setSnapshot(s)
    val changed = provider.setSnapshot(null)
    assertTrue(changed.contains("root"))
    assertTrue(changed.contains("a"))
    assertTrue(changed.contains("b"))
    assertEquals(3, changed.size)
    assertNull(provider.getSnapshot())
  }

  @Test
  fun `setSnapshot replaces atomically — getSnapshot returns the new instance`() {
    val provider = BrowseDataProvider()
    val first = snapshot("root", section("a"))
    val second = snapshot("root2", section("b"))
    provider.setSnapshot(first)
    provider.setSnapshot(second)
    assertEquals("root2", provider.getSnapshot()!!.rootId)
    assertEquals(1, provider.getSnapshot()!!.sections.size)
    assertEquals("b", provider.getSnapshot()!!.sections[0].id)
  }

  @Test
  fun `setSnapshot reports union of prior + new section ids`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot("root", section("keep"), section("removed")))
    val changed = provider.setSnapshot(snapshot("root", section("keep"), section("added")))
    assertTrue("root in changed set", changed.contains("root"))
    assertTrue("kept section in changed set", changed.contains("keep"))
    assertTrue("removed section reported", changed.contains("removed"))
    assertTrue("added section reported", changed.contains("added"))
  }

  // --- isRoot / getSection / getItem ---------------------------------

  @Test
  fun `isRoot matches the snapshot rootId`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot("the-root", section("a")))
    assertTrue(provider.isRoot("the-root"))
    assertFalse(provider.isRoot("a"))
    assertFalse(provider.isRoot("root"))
  }

  @Test
  fun `getSection returns the matching section by id`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      section("recents", title = "Recently Played", items = arrayOf(item("x"))),
      section("favorites", title = "Favorites")
    ))
    val recents = provider.getSection("recents")
    assertNotNull(recents)
    assertEquals("Recently Played", recents!!.title)
    assertEquals(1, recents.items.size)
  }

  @Test
  fun `getSection returns null for the rootId`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot("root", section("a")))
    assertNull(provider.getSection("root"))
  }

  @Test
  fun `getItem walks every section to find the item by id`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      section("a", items = arrayOf(item("a-1"))),
      section("b", items = arrayOf(item("b-1"), item("b-2")))
    ))
    assertEquals("T-a-1", provider.getItem("a-1")!!.title)
    assertEquals("T-b-2", provider.getItem("b-2")!!.title)
    assertNull(provider.getItem("missing"))
  }

  @Test
  fun `getItem returns null when no snapshot is set`() {
    val provider = BrowseDataProvider()
    assertNull(provider.getItem("any"))
  }

  // --- getChildren — root + section + paginated ---------------------

  @Test
  fun `getChildren of rootId returns synthetic browsable items per section`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      section("recents", title = "Recently Played"),
      section("genres", title = "Genres")
    ))
    val children = provider.getChildren("root", page = 0, pageSize = 50)
    assertEquals(2, children.size)
    assertEquals("recents", children[0].id)
    assertEquals("Recently Played", children[0].title)
    assertFalse("synthetic root children are NEVER playable", children[0].playable)
    assertEquals(true, children[0].hasChildren)
    assertEquals("genres", children[1].id)
  }

  @Test
  fun `getChildren of a section id returns that section's items`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      section("recents", items = arrayOf(item("a"), item("b"), item("c")))
    ))
    val children = provider.getChildren("recents", page = 0, pageSize = 50)
    assertEquals(3, children.size)
    assertEquals("a", children[0].id)
    assertEquals("c", children[2].id)
  }

  @Test
  fun `getChildren of an unknown parentId returns empty`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot("root", section("a")))
    assertTrue(provider.getChildren("unknown", page = 0, pageSize = 50).isEmpty())
  }

  @Test
  fun `getChildren paginates by page + pageSize`() {
    val items = (1..10).map { item("i$it") }.toTypedArray()
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      BrowseSection(id = "all", title = "All", icon = null, artworkUrl = null, items = items)
    ))
    val page0 = provider.getChildren("all", page = 0, pageSize = 4)
    assertEquals(4, page0.size)
    assertEquals("i1", page0[0].id)
    assertEquals("i4", page0[3].id)

    val page1 = provider.getChildren("all", page = 1, pageSize = 4)
    assertEquals(4, page1.size)
    assertEquals("i5", page1[0].id)
    assertEquals("i8", page1[3].id)

    val page2 = provider.getChildren("all", page = 2, pageSize = 4)
    assertEquals("last partial page", 2, page2.size)
    assertEquals("i9", page2[0].id)
    assertEquals("i10", page2[1].id)

    val page3 = provider.getChildren("all", page = 3, pageSize = 4)
    assertTrue("out-of-range page is empty", page3.isEmpty())
  }

  @Test
  fun `getChildren with pageSize lt 1 returns the full list (treated as unbounded)`() {
    val items = (1..5).map { item("i$it") }.toTypedArray()
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      BrowseSection(id = "all", title = "All", icon = null, artworkUrl = null, items = items)
    ))
    assertEquals(5, provider.getChildren("all", page = 0, pageSize = 0).size)
    assertEquals(5, provider.getChildren("all", page = 0, pageSize = -1).size)
  }

  @Test
  fun `getChildren with negative page returns empty`() {
    val items = (1..3).map { item("i$it") }.toTypedArray()
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      BrowseSection(id = "all", title = "All", icon = null, artworkUrl = null, items = items)
    ))
    assertTrue(provider.getChildren("all", page = -1, pageSize = 5).isEmpty())
  }

  @Test
  fun `replaced snapshot — old children no longer reachable`() {
    val provider = BrowseDataProvider()
    provider.setSnapshot(snapshot(
      "root",
      section("v1-only", items = arrayOf(item("v1-only-item")))
    ))
    provider.setSnapshot(snapshot(
      "root",
      section("v2", items = arrayOf(item("v2-item")))
    ))
    assertNull(provider.getSection("v1-only"))
    assertNull(provider.getItem("v1-only-item"))
    assertNotNull(provider.getSection("v2"))
    assertNotNull(provider.getItem("v2-item"))
  }
}
