import XCTest
@testable import QueuePlayer

/// Coverage for [CarPlayBrowseDataProvider]. Mirrors the Android
/// `BrowseDataProviderTest` cases — atomic snapshot replacement,
/// root + section + item lookup, paginated children, change-set
/// computation across snapshot transitions, and the
/// `didUpdateNotification` dispatch contract.
final class CarPlayBrowseDataProviderTests: XCTestCase {

  private func item(
    _ id: String,
    title: String? = nil,
    playable: Bool = true,
    hasChildren: Bool? = nil
  ) -> BrowseItem {
    BrowseItem(
      id: id,
      title: title ?? "T-\(id)",
      subtitle: nil,
      artworkUrl: nil,
      playable: playable,
      hasChildren: hasChildren
    )
  }

  private func section(_ id: String, items: [BrowseItem] = []) -> BrowseSection {
    BrowseSection(id: id, title: "S-\(id)", icon: nil, artworkUrl: nil, items: items)
  }

  // MARK: - Snapshot lifecycle

  func testInitiallyEmpty() {
    let p = CarPlayBrowseDataProvider()
    XCTAssertNil(p.getSnapshot())
    XCTAssertFalse(p.isRoot("root"))
    XCTAssertNil(p.getSection("a"))
    XCTAssertNil(p.getItem("a-1"))
    XCTAssertTrue(p.getChildren(parentId: "root", page: 0, pageSize: 50).isEmpty)
  }

  func testSetSnapshotMakesSnapshotReadable() {
    let p = CarPlayBrowseDataProvider()
    let snap = BrowseSnapshot(
      rootId: "root",
      sections: [section("a", items: [item("a-1")])]
    )
    p.setSnapshot(snap)
    XCTAssertEqual(p.getSnapshot()?.rootId, "root")
    XCTAssertTrue(p.isRoot("root"))
  }

  func testSetSnapshotNilClearsTheCache() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(BrowseSnapshot(rootId: "root", sections: []))
    p.setSnapshot(nil)
    XCTAssertNil(p.getSnapshot())
    XCTAssertFalse(p.isRoot("root"))
  }

  // MARK: - Section + item lookup

  func testGetSectionResolvesById() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(
        rootId: "root",
        sections: [
          section("recents", items: [item("r-1")]),
          section("favourites", items: [item("f-1")])
        ]
      )
    )
    XCTAssertEqual(p.getSection("favourites")?.id, "favourites")
    XCTAssertNil(p.getSection("missing"))
  }

  func testGetItemWalksEverySection() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(
        rootId: "root",
        sections: [
          section("a", items: [item("a-1"), item("a-2")]),
          section("b", items: [item("b-1")])
        ]
      )
    )
    XCTAssertEqual(p.getItem("b-1")?.id, "b-1")
    XCTAssertNil(p.getItem("missing"))
  }

  // MARK: - Children + pagination

  func testGetChildrenOfRootReturnsOneRowPerSection() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(
        rootId: "root",
        sections: [
          section("recents", items: [item("r-1")]),
          section("favourites", items: [item("f-1")])
        ]
      )
    )
    let children = p.getChildren(parentId: "root", page: 0, pageSize: 50)
    XCTAssertEqual(children.map { $0.id }, ["recents", "favourites"])
    XCTAssertTrue(children.allSatisfy { $0.playable == false })
    XCTAssertTrue(children.allSatisfy { $0.hasChildren == true })
  }

  func testGetChildrenOfSectionReturnsItsItems() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(
        rootId: "root",
        sections: [
          section("a", items: [item("a-1"), item("a-2"), item("a-3")])
        ]
      )
    )
    let children = p.getChildren(parentId: "a", page: 0, pageSize: 50)
    XCTAssertEqual(children.map { $0.id }, ["a-1", "a-2", "a-3"])
  }

  func testGetChildrenOfUnknownParentReturnsEmpty() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(rootId: "root", sections: [section("a", items: [item("a-1")])])
    )
    XCTAssertTrue(p.getChildren(parentId: "missing", page: 0, pageSize: 50).isEmpty)
  }

  func testGetChildrenPaginates() {
    let p = CarPlayBrowseDataProvider()
    let items = (0..<20).map { item("i-\($0)") }
    p.setSnapshot(BrowseSnapshot(rootId: "root", sections: [section("a", items: items)]))

    let page0 = p.getChildren(parentId: "a", page: 0, pageSize: 5)
    XCTAssertEqual(page0.map { $0.id }, ["i-0", "i-1", "i-2", "i-3", "i-4"])

    let page2 = p.getChildren(parentId: "a", page: 2, pageSize: 5)
    XCTAssertEqual(page2.map { $0.id }, ["i-10", "i-11", "i-12", "i-13", "i-14"])
  }

  func testGetChildrenPageBeyondEndReturnsEmpty() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(rootId: "root",
                     sections: [section("a", items: [item("a-1"), item("a-2")])])
    )
    XCTAssertTrue(p.getChildren(parentId: "a", page: 5, pageSize: 5).isEmpty)
  }

  func testGetChildrenPageSizeZeroIsUnbounded() {
    let p = CarPlayBrowseDataProvider()
    let items = (0..<3).map { item("i-\($0)") }
    p.setSnapshot(BrowseSnapshot(rootId: "root", sections: [section("a", items: items)]))
    XCTAssertEqual(
      p.getChildren(parentId: "a", page: 0, pageSize: 0).map { $0.id },
      ["i-0", "i-1", "i-2"]
    )
  }

  func testGetChildrenNegativePageReturnsEmpty() {
    let p = CarPlayBrowseDataProvider()
    p.setSnapshot(
      BrowseSnapshot(rootId: "root",
                     sections: [section("a", items: [item("a-1")])])
    )
    XCTAssertTrue(p.getChildren(parentId: "a", page: -1, pageSize: 5).isEmpty)
  }

  // MARK: - Changed-parent computation

  func testSetSnapshotReturnsAffectedParents() {
    let p = CarPlayBrowseDataProvider()
    let initial = BrowseSnapshot(
      rootId: "root",
      sections: [section("a"), section("b")]
    )
    let firstChanged = p.setSnapshot(initial)
    XCTAssertEqual(firstChanged, ["root", "a", "b"])

    // Replace one section + add a new one. Returns root + a (gone) +
    // b (still present in old + new) + c (new) — every parentId
    // touched by either snapshot.
    let next = BrowseSnapshot(
      rootId: "root",
      sections: [section("b"), section("c")]
    )
    let secondChanged = p.setSnapshot(next)
    XCTAssertEqual(secondChanged, ["root", "a", "b", "c"])
  }

  func testSetSnapshotNilOnNilReturnsEmpty() {
    let p = CarPlayBrowseDataProvider()
    let changed = p.setSnapshot(nil)
    XCTAssertTrue(changed.isEmpty)
  }

  // MARK: - Notification dispatch

  func testSetSnapshotPostsDidUpdateNotification() {
    let p = CarPlayBrowseDataProvider()
    let exp = expectation(forNotification: CarPlayBrowseDataProvider.didUpdateNotification,
                          object: p,
                          handler: { note in
      let payload = note.userInfo?[CarPlayBrowseDataProvider.changedParentsKey]
        as? Set<String>
      return payload == ["root", "a"]
    })
    DispatchQueue.global().async {
      p.setSnapshot(BrowseSnapshot(rootId: "root", sections: [self.section("a")]))
    }
    wait(for: [exp], timeout: 2)
  }
}
