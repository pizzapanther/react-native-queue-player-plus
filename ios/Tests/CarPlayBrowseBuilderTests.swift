import CarPlay
import XCTest
@testable import QueuePlayer

/// Coverage for [CarPlayBrowseBuilder] — snapshot → template
/// conversion, item count + accessory mapping, pagination against
/// `CPListTemplate.maximumItemCount`. Direct method calls on the
/// builder; the scene delegate's full lifecycle is out of scope here.
final class CarPlayBrowseBuilderTests: XCTestCase {

  private func item(
    _ id: String,
    title: String? = nil,
    playable: Bool = true,
    hasChildren: Bool? = nil,
    subtitle: String? = nil,
    artworkUrl: String? = nil
  ) -> BrowseItem {
    BrowseItem(
      id: id,
      title: title ?? "T-\(id)",
      subtitle: subtitle,
      artworkUrl: artworkUrl,
      playable: playable,
      hasChildren: hasChildren
    )
  }

  private func section(
    _ id: String,
    icon: SectionIcon? = nil,
    items: [BrowseItem] = []
  ) -> BrowseSection {
    BrowseSection(id: id, title: "S-\(id)", icon: icon, artworkUrl: nil, items: items)
  }

  // MARK: - Root tab bar

  func testBuildRootTemplateMapsSectionsToTabs() {
    let builder = CarPlayBrowseBuilder()
    let provider = CarPlayBrowseDataProvider()
    let bridge = CarPlayBridge()
    let pending = PendingBrowseRequests()
    let loader = ArtworkLoader()
    let snapshot = BrowseSnapshot(
      rootId: "root",
      sections: [section("a"), section("b"), section("c")]
    )
    provider.setSnapshot(snapshot)
    let tabBar = builder.buildRootTemplate(
      provider: provider,
      bridge: bridge,
      pending: pending,
      loader: loader,
      pushTemplate: { _ in }
    )
    XCTAssertNotNil(tabBar)
    XCTAssertEqual(tabBar?.templates.count, 3)
    let tabs = tabBar?.templates.compactMap { $0 as? CPListTemplate } ?? []
    XCTAssertEqual(tabs[0].tabTitle, "S-a")
    XCTAssertEqual(tabs[1].tabTitle, "S-b")
    XCTAssertEqual(tabs[2].tabTitle, "S-c")
  }

  func testBuildRootTemplateReturnsNilWhenSnapshotMissing() {
    let builder = CarPlayBrowseBuilder()
    let provider = CarPlayBrowseDataProvider()
    let bridge = CarPlayBridge()
    let pending = PendingBrowseRequests()
    let loader = ArtworkLoader()
    let tabBar = builder.buildRootTemplate(
      provider: provider,
      bridge: bridge,
      pending: pending,
      loader: loader,
      pushTemplate: { _ in }
    )
    XCTAssertNil(tabBar)
  }

  func testBuildRootTemplateCapsSectionsAtMaximumTabCount() {
    let builder = CarPlayBrowseBuilder()
    let provider = CarPlayBrowseDataProvider()
    let bridge = CarPlayBridge()
    let pending = PendingBrowseRequests()
    let loader = ArtworkLoader()
    let cap = CarPlayBrowseBuilder.maximumTabCount
    let sections = (0..<cap + 3).map { section("s\($0)") }
    let snapshot = BrowseSnapshot(rootId: "root", sections: sections)
    provider.setSnapshot(snapshot)
    let tabBar = builder.buildRootTemplate(
      provider: provider,
      bridge: bridge,
      pending: pending,
      loader: loader,
      pushTemplate: { _ in }
    )
    XCTAssertEqual(tabBar?.templates.count, cap)
  }

  func testBuildRootTemplateAssignsTabImagesFromSectionIcons() {
    let builder = CarPlayBrowseBuilder()
    let provider = CarPlayBrowseDataProvider()
    let bridge = CarPlayBridge()
    let pending = PendingBrowseRequests()
    let loader = ArtworkLoader()
    let snapshot = BrowseSnapshot(
      rootId: "root",
      sections: [
        section("a", icon: .albums),
        section("p", icon: .playlists),
        section("noicon"),
      ]
    )
    provider.setSnapshot(snapshot)
    let tabBar = builder.buildRootTemplate(
      provider: provider,
      bridge: bridge,
      pending: pending,
      loader: loader,
      pushTemplate: { _ in }
    )
    let tabs = tabBar?.templates.compactMap { $0 as? CPListTemplate } ?? []
    XCTAssertNotNil(tabs[0].tabImage)
    XCTAssertNotNil(tabs[1].tabImage)
    XCTAssertNil(tabs[2].tabImage)
  }

  func testBuildPlaceholderRootTabBarReturnsOneTab() {
    let builder = CarPlayBrowseBuilder()
    let tabBar = builder.buildPlaceholderRootTabBar()
    XCTAssertEqual(tabBar.templates.count, 1)
  }

  // MARK: - Icon mapping

  func testIconToSFSymbolMapsEveryCase() {
    let allCases: [SectionIcon] = [
      .home, .library, .playlists, .favourites,
      .albums, .artists, .songs, .genres,
      .recentlyPlayed, .radio, .search, .downloaded, .podcasts,
    ]
    for icon in allCases {
      XCTAssertNotNil(
        CarPlayBrowseBuilder.iconToSFSymbol(icon),
        "Missing SF Symbol mapping for \(icon)"
      )
    }
    XCTAssertNil(CarPlayBrowseBuilder.iconToSFSymbol(nil))
  }

  // MARK: - Section template

  func testSectionTemplateMapsItemsWithCorrectAccessory() {
    let builder = CarPlayBrowseBuilder()
    let bridge = CarPlayBridge()
    let loader = ArtworkLoader()
    let sectionItems: [BrowseItem] = [
      item("a-1", playable: true),
      item("a-2", playable: false, hasChildren: true),
      item("a-3", playable: false, hasChildren: false),
    ]
    let template = builder.buildSectionTemplate(
      section: BrowseSection(id: "a", title: "A", icon: nil, artworkUrl: nil, items: sectionItems),
      bridge: bridge,
      loader: loader,
      pushTemplate: { _ in }
    )
    let items = (template.sections.first?.items ?? []).compactMap { $0 as? CPListItem }
    XCTAssertEqual(items.count, 3)
    XCTAssertEqual(items[0].accessoryType, .none)
    XCTAssertEqual(items[1].accessoryType, .disclosureIndicator)
    XCTAssertEqual(items[2].accessoryType, .none)
  }

  func testSectionTemplateCapsItemsAtMaximumItemCount() {
    let builder = CarPlayBrowseBuilder()
    let bridge = CarPlayBridge()
    let loader = ArtworkLoader()
    let cap = CarPlayBrowseBuilder.maximumItemsPerSection
    let sectionItems = (0..<cap + 10).map { item("i\($0)") }
    let template = builder.buildSectionTemplate(
      section: BrowseSection(id: "a", title: "A", icon: nil, artworkUrl: nil, items: sectionItems),
      bridge: bridge,
      loader: loader,
      pushTemplate: { _ in }
    )
    let items = template.sections.first?.items ?? []
    XCTAssertEqual(items.count, cap)
  }

  // MARK: - Per-item construction

  func testMakeListItemPropagatesTitleAndSubtitle() {
    let builder = CarPlayBrowseBuilder()
    let bridge = CarPlayBridge()
    let loader = ArtworkLoader()
    let listItem = builder.makeListItem(
      for: item("song", title: "Hold On", subtitle: "Wilco"),
      bridge: bridge,
      loader: loader,
      pushTemplate: { _ in }
    )
    XCTAssertEqual(listItem.text, "Hold On")
    XCTAssertEqual(listItem.detailText, "Wilco")
  }

  func testMakeListItemNonTappableForHeaderRow() {
    let builder = CarPlayBrowseBuilder()
    let bridge = CarPlayBridge()
    let loader = ArtworkLoader()
    let listItem = builder.makeListItem(
      for: item("hdr", playable: false, hasChildren: false),
      bridge: bridge,
      loader: loader,
      pushTemplate: { _ in }
    )
    XCTAssertEqual(listItem.accessoryType, .none)
  }
}
