import CarPlay
import XCTest
@testable import QueuePlayer

/// Coverage for [CarPlayQueueBuilder] — snapshot → Up Next template
/// conversion, row count + title/detail mapping, truncation at
/// `CPListTemplate.maximumItemCount`, current-index marking, and the
/// row-tap skip closure — plus a [CarPlayQueueProvider] round-trip.
final class CarPlayQueueBuilderTests: XCTestCase {

  private func entry(
    _ title: String,
    subtitle: String? = nil,
    artworkUrl: String? = nil
  ) -> CarPlayQueueEntry {
    CarPlayQueueEntry(title: title, subtitle: subtitle, artworkUrl: artworkUrl)
  }

  private func snapshot(
    _ entries: [CarPlayQueueEntry],
    currentIndex: Int
  ) -> CarPlayQueueSnapshot {
    CarPlayQueueSnapshot(entries: entries, currentIndex: currentIndex)
  }

  // MARK: - Template construction

  func testBuildQueueTemplateMapsEntriesToRows() {
    let builder = CarPlayQueueBuilder()
    let loader = ArtworkLoader()
    let template = builder.buildQueueTemplate(
      snapshot: snapshot(
        [
          entry("Everlong", subtitle: "Foo Fighters"),
          entry("Monkey Wrench", subtitle: "Foo Fighters"),
        ],
        currentIndex: 0
      ),
      loader: loader,
      skip: { _ in }
    )
    let items = (template.sections.first?.items ?? []).compactMap { $0 as? CPListItem }
    XCTAssertEqual(items.count, 2)
    XCTAssertEqual(items[0].text, "Everlong")
    XCTAssertEqual(items[0].detailText, "Foo Fighters")
    XCTAssertEqual(items[1].text, "Monkey Wrench")
  }

  func testBuildQueueTemplateCapsRowsAtMaximumItemCount() {
    let builder = CarPlayQueueBuilder()
    let loader = ArtworkLoader()
    let cap = 5
    let entries = (0..<cap + 4).map { entry("T-\($0)") }
    let template = builder.buildQueueTemplate(
      snapshot: snapshot(entries, currentIndex: 0),
      loader: loader,
      maximumItemCount: cap,
      skip: { _ in }
    )
    let items = template.sections.first?.items ?? []
    XCTAssertEqual(items.count, cap)
  }

  func testBuildQueueTemplateMarksCurrentIndex() {
    let builder = CarPlayQueueBuilder()
    let loader = ArtworkLoader()
    let template = builder.buildQueueTemplate(
      snapshot: snapshot(
        [entry("A"), entry("B"), entry("C")],
        currentIndex: 1
      ),
      loader: loader,
      skip: { _ in }
    )
    let items = (template.sections.first?.items ?? []).compactMap { $0 as? CPListItem }
    XCTAssertFalse(items[0].isPlaying)
    XCTAssertTrue(items[1].isPlaying)
    XCTAssertFalse(items[2].isPlaying)
  }

  func testBuildQueueTemplateMarksNothingWhenIndexOutOfRange() {
    let builder = CarPlayQueueBuilder()
    let loader = ArtworkLoader()
    let template = builder.buildQueueTemplate(
      snapshot: snapshot([entry("A"), entry("B")], currentIndex: -1),
      loader: loader,
      skip: { _ in }
    )
    let items = (template.sections.first?.items ?? []).compactMap { $0 as? CPListItem }
    XCTAssertTrue(items.allSatisfy { !$0.isPlaying })
  }

  // MARK: - Row-tap skip

  func testRowTapInvokesSkipWithRowIndex() {
    let builder = CarPlayQueueBuilder()
    let loader = ArtworkLoader()
    var skippedTo: Int?
    let template = builder.buildQueueTemplate(
      snapshot: snapshot([entry("A"), entry("B"), entry("C")], currentIndex: 0),
      loader: loader,
      skip: { skippedTo = $0 }
    )
    let items = (template.sections.first?.items ?? []).compactMap { $0 as? CPListItem }
    let exp = expectation(description: "row handler completes")
    items[2].handler?(items[2]) { exp.fulfill() }
    wait(for: [exp], timeout: 2)
    XCTAssertEqual(skippedTo, 2)
  }

  // MARK: - Provider round-trip

  func testProviderSetSnapshotMakesSnapshotReadable() {
    let provider = CarPlayQueueProvider()
    XCTAssertNil(provider.getSnapshot())
    provider.setSnapshot(
      snapshot([entry("A", subtitle: "sub")], currentIndex: 0)
    )
    let read = provider.getSnapshot()
    XCTAssertEqual(read?.entries.count, 1)
    XCTAssertEqual(read?.entries.first?.title, "A")
    XCTAssertEqual(read?.entries.first?.subtitle, "sub")
    XCTAssertEqual(read?.currentIndex, 0)
  }

  func testProviderSetSnapshotNilClearsCache() {
    let provider = CarPlayQueueProvider()
    provider.setSnapshot(snapshot([entry("A")], currentIndex: 0))
    provider.setSnapshot(nil)
    XCTAssertNil(provider.getSnapshot())
  }

  func testProviderSetSnapshotPostsDidUpdateNotification() {
    let provider = CarPlayQueueProvider()
    let exp = expectation(
      forNotification: CarPlayQueueProvider.didUpdateNotification,
      object: provider,
      handler: nil
    )
    DispatchQueue.global().async {
      provider.setSnapshot(self.snapshot([self.entry("A")], currentIndex: 0))
    }
    wait(for: [exp], timeout: 2)
  }
}
