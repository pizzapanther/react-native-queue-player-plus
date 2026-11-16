import CarPlay
import UIKit
import os.log

/// Convert a `BrowseSnapshot` into a CarPlay browse template.
/// Template-by-category restrictions per the CarPlay Developer Guide:
///
///  - Audio apps allow these templates: Action sheet, Alert, Grid,
///    List, Tab bar, Now playing. Search is NOT permitted; voice-
///    search routes via SiriKit instead.
///  - `CPNowPlayingTemplate.shared` is NOT a valid `CPTabBarTemplate`
///    member — embedding it raises `NSInvalidArgumentException` at
///    init. Push it onto the nav stack via
///    `CPInterfaceController.pushTemplate(_:animated:completion:)`
///    after a row-tap-driven playback start so the user lands on the
///    Now Playing surface immediately (Spotify / Apple Music UX).
///
/// Root template = `CPTabBarTemplate` matching the Apple Music /
/// Spotify shape: one tab per `BrowseSection`. Each tab's content is
/// a `CPListTemplate` built from that section's items. Tab labels
/// come from `section.title`; tab images map from the
/// `section.icon` enum to SF Symbols via `iconToSFSymbol(_:)`.
/// `CPTabBarTemplate.maximumTabCount` caps tab count at 4 for audio
/// apps; over-cap snapshots are truncated with a one-time warning.
/// Snapshot updates after the tab bar is live should be applied via
/// `CPTabBarTemplate.updateTemplates(_:)` (scene delegate's
/// responsibility) — never re-`setRootTemplate` because that
/// tears down the live UI.
///
/// Stateless helper. The owning scene delegate holds references to
/// the live templates so they survive controller-driven re-pushes.
final class CarPlayBrowseBuilder {

  /// Closure invoked by playable row taps after the JS play callback
  /// resolves successfully. Wired from `CarPlaySceneDelegate`; pushes
  /// `CPNowPlayingTemplate.shared` onto the interface controller,
  /// guarded against double-push by a `topTemplate` check.
  /// Tests leave this nil to keep the tap path free of system push
  /// machinery.
  var pushNowPlaying: (() -> Void)?

  /// Maximum item count per `CPListTemplate`. CarPlay enforces a
  /// runtime cap that the framework reports via
  /// `CPListTemplate.maximumItemCount`. Reading the property guards
  /// against future SDKs raising or lowering the cap. Apple's
  /// minimum-guarantee is 12; current iOS 17+ value is documented as
  /// up to several hundred for audio apps.
  static var maximumItemsPerSection: Int { CPListTemplate.maximumItemCount }

  /// Maximum number of tabs the audio-entitlement `CPTabBarTemplate`
  /// permits. Apple ships 4 today; reading the SDK property keeps the
  /// cap future-proof. Snapshots with more sections are truncated to
  /// the first N with a one-time warning.
  static var maximumTabCount: Int { CPTabBarTemplate.maximumTabCount }

  /// Build the root browse `CPTabBarTemplate`. Returns nil when the
  /// provider has no snapshot yet — the scene delegate should set up
  /// a placeholder tab bar via [buildPlaceholderRootTabBar] until the
  /// consumer calls `setBrowseSnapshot`.
  ///
  /// `pushTemplate` is invoked any time the user drills into a
  /// browsable item (album row → tracks, playlist row → tracks). The
  /// scene delegate hooks this to `interfaceController.pushTemplate`,
  /// which CarPlay routes onto the active tab's navigation stack so
  /// each tab maintains its own drilldown history.
  func buildRootTemplate(
    provider: CarPlayBrowseDataProvider,
    bridge: CarPlayBridge,
    pending: PendingBrowseRequests,
    loader: ArtworkLoader,
    pushTemplate: @escaping (CPListTemplate) -> Void
  ) -> CPTabBarTemplate? {
    guard let snapshot = provider.getSnapshot() else { return nil }
    let tabs = buildTabs(
      snapshot: snapshot,
      bridge: bridge,
      loader: loader,
      pushTemplate: pushTemplate
    )
    return CPTabBarTemplate(templates: tabs)
  }

  /// Build the per-section tab templates. Returns one `CPListTemplate`
  /// per section, each with `tabTitle`, `tabImage` (resolved from the
  /// section's `icon` enum), and section content as list rows.
  func buildTabs(
    snapshot: BrowseSnapshot,
    bridge: CarPlayBridge,
    loader: ArtworkLoader,
    pushTemplate: @escaping (CPListTemplate) -> Void
  ) -> [CPListTemplate] {
    if snapshot.sections.count > Self.maximumTabCount {
      logTruncation(
        "BrowseSnapshot has \(snapshot.sections.count) sections; CarPlay tab bar caps at \(Self.maximumTabCount) — truncating to the first \(Self.maximumTabCount). Ship fewer top sections + use in-tab drilldown."
      )
    }
    let truncated = Array(snapshot.sections.prefix(Self.maximumTabCount))
    return truncated.map { section in
      let template = buildSectionTemplate(
        section: section,
        bridge: bridge,
        loader: loader,
        pushTemplate: pushTemplate
      )
      template.tabTitle = section.title
      template.tabImage = Self.iconToSFSymbol(section.icon)
      return template
    }
  }

  /// Single-tab placeholder used when the consumer hasn't pushed a
  /// snapshot yet. `CPTabBarTemplate` requires at least one tab at
  /// init, so we seed a "Loading library…" row inside one
  /// `CPListTemplate` and let the scene delegate swap real tabs in
  /// via `updateTemplates(_:)` once `setBrowseSnapshot` lands.
  func buildPlaceholderRootTabBar() -> CPTabBarTemplate {
    let placeholderItem = CPListItem(
      text: rootTitle,
      detailText: nil,
      image: nil
    )
    placeholderItem.accessoryType = .none
    let template = CPListTemplate(
      title: rootTitle,
      sections: [CPListSection(items: [placeholderItem])]
    )
    template.tabTitle = rootTitle
    template.tabSystemItem = .more
    return CPTabBarTemplate(templates: [template])
  }

  /// Map our typed `SectionIcon` enum to the SF Symbol that CarPlay
  /// renders on the tab bar. Returns nil when no icon is supplied;
  /// the tab then falls back to its `tabSystemItem` or a label-only
  /// presentation. The closed enum set lets us cache the resolved
  /// UIImage once per case — a 4-tab rebuild on snapshot push then
  /// pays zero per-call allocation.
  static func iconToSFSymbol(_ icon: SectionIcon?) -> UIImage? {
    guard let icon = icon else { return nil }
    return iconCache[icon]
  }

  private static let iconCache: [SectionIcon: UIImage] = {
    let mapping: [SectionIcon: String] = [
      .home:           "house.fill",
      .library:        "square.stack.fill",
      .playlists:      "music.note.list",
      .favourites:     "heart.fill",
      .albums:         "square.grid.2x2.fill",
      .artists:        "music.mic",
      .songs:          "music.note",
      .genres:         "guitars.fill",
      .recentlyPlayed: "clock.fill",
      .radio:          "radio",
      .search:         "magnifyingglass",
      .downloaded:     "arrow.down.circle.fill",
      .podcasts:       "mic.fill",
    ]
    var result: [SectionIcon: UIImage] = [:]
    for (icon, name) in mapping {
      if let image = UIImage(systemName: name) {
        result[icon] = image
      }
    }
    return result
  }()

  /// Build the child `CPListTemplate` for a single section. Tap on a
  /// playable row dispatches `onPlayFromIdRequest`; tap on a
  /// browsable row dispatches `onBrowseRequest` to load deeper
  /// children, then pushes a fresh `CPListTemplate` built from the
  /// resolved children via [pushTemplate].
  func buildSectionTemplate(
    section: BrowseSection,
    bridge: CarPlayBridge,
    loader: ArtworkLoader,
    pushTemplate: @escaping (CPListTemplate) -> Void
  ) -> CPListTemplate {
    if section.items.count > Self.maximumItemsPerSection {
      logTruncation(
        "Section \(section.id) has \(section.items.count) items; CarPlay caps a list at \(Self.maximumItemsPerSection) — truncating. Fold large catalogs into a hierarchy (A-Z / by-artist)."
      )
    }
    let truncated = Array(section.items.prefix(Self.maximumItemsPerSection))
    let items = truncated.map { item in
      makeListItem(
        for: item, bridge: bridge, loader: loader, pushTemplate: pushTemplate
      )
    }
    return CPListTemplate(
      title: section.title,
      sections: [CPListSection(items: items)]
    )
  }

  /// Build a `CPListTemplate` from an arbitrary `[BrowseItem]` list
  /// resolved by an `onBrowseRequest` dispatch. Used for nested
  /// drilldowns (album row → tracks, playlist row → tracks). Each
  /// item is recursively wired via [makeListItem] so deeper levels
  /// drill correctly through the same [pushTemplate] callback.
  func buildChildrenTemplate(
    title: String,
    items: [BrowseItem],
    bridge: CarPlayBridge,
    loader: ArtworkLoader,
    pushTemplate: @escaping (CPListTemplate) -> Void
  ) -> CPListTemplate {
    if items.count > Self.maximumItemsPerSection {
      logTruncation(
        "Children list for \(title) has \(items.count) items; CarPlay caps a list at \(Self.maximumItemsPerSection) — truncating. Fold large catalogs into a hierarchy (A-Z / by-artist)."
      )
    }
    let truncated = Array(items.prefix(Self.maximumItemsPerSection))
    let listItems = truncated.map { item in
      makeListItem(
        for: item, bridge: bridge, loader: loader, pushTemplate: pushTemplate
      )
    }
    return CPListTemplate(
      title: title,
      sections: [CPListSection(items: listItems)]
    )
  }

  /// Build a `CPListItem` for [item]. Tap-on-playable fires
  /// `onPlayFromIdRequest`, navigates to Now Playing on completion.
  /// Tap-on-browsable fires `onBrowseRequest`, builds a fresh
  /// `CPListTemplate` from the resolved children list, and pushes it
  /// via [pushTemplate]. Without the push the row tap appeared to
  /// no-op even though the JS callback ran successfully.
  func makeListItem(
    for item: BrowseItem,
    bridge: CarPlayBridge,
    loader: ArtworkLoader,
    pushTemplate: @escaping (CPListTemplate) -> Void
  ) -> CPListItem {
    let listItem = CPListItem(
      text: item.title,
      detailText: item.subtitle,
      image: nil
    )
    if let artworkUrl = item.artworkUrl, !artworkUrl.isEmpty {
      loader.load(uri: artworkUrl) { [weak listItem] image in
        guard let listItem = listItem, let image = image else { return }
        listItem.setImage(image)
      }
    }
    if item.playable {
      listItem.accessoryType = .none
      listItem.handler = { [weak self] _, completion in
        guard let callback = bridge.playFromIdRequestCallback() else {
          completion()
          return
        }
        let mediaId = item.id
        let label = "onPlayFromIdRequest"
        Task.detached { [weak self] in
          _ = await self?.runDispatch(label: label) { () -> Void in
            _ = try await callback(mediaId).await().await()
          }
          await MainActor.run { [weak self] in
            self?.pushNowPlaying?()
            completion()
          }
        }
      }
    } else if item.hasChildren == true {
      listItem.accessoryType = .disclosureIndicator
      let capturedTitle = item.title
      listItem.handler = { [weak self] _, completion in
        guard let self = self else { completion(); return }
        guard let callback = bridge.browseRequestCallback() else {
          completion()
          return
        }
        let parentId = item.id
        let label = "onBrowseRequest"
        Task.detached { [weak self] in
          let resolved: [BrowseItem] = await self?.runDispatch(
            label: label
          ) { () -> [BrowseItem] in
            try await callback(parentId).await().await()
          } ?? []
          await MainActor.run { [weak self] in
            guard let self = self else { completion(); return }
            let template = self.buildChildrenTemplate(
              title: capturedTitle,
              items: resolved,
              bridge: bridge,
              loader: loader,
              pushTemplate: pushTemplate
            )
            pushTemplate(template)
            completion()
          }
        }
      }
    } else {
      // Header / spacer row — non-tappable.
      listItem.accessoryType = .none
    }
    return listItem
  }

  /// Wrap a Nitro Promise dispatch with a [Self.dispatchTimeoutNs]
  /// hard timeout. Mirrors Android's
  /// `HEADLESS_DISPATCH_TIMEOUT_MS = 12_000` so a buggy consumer
  /// Promise that never resolves doesn't leave the CarPlay row
  /// spinner hanging forever. Errors (timeout, throw, reject) are
  /// logged via os_log so the developer can correlate with their
  /// JS-side handler. Returns the operation's value on success or
  /// `nil` on timeout / throw / reject.
  private func runDispatch<T: Sendable>(
    label: String,
    operation: @escaping @Sendable () async throws -> T
  ) async -> T? {
    do {
      return try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
          try await Task.sleep(nanoseconds: Self.dispatchTimeoutNs)
          throw CarPlayDispatchError.timeout
        }
        let result = try await group.next()
        group.cancelAll()
        return result
      }
    } catch CarPlayDispatchError.timeout {
      os_log(
        "%{public}@ timed out after 12s — CarPlay row spinner cleared",
        log: dispatchLog, type: .error, label
      )
      return nil
    } catch {
      os_log(
        "%{public}@ failed: %{public}@",
        log: dispatchLog, type: .error,
        label, String(describing: error)
      )
      return nil
    }
  }

  private enum CarPlayDispatchError: Error {
    case timeout
  }

  private static let dispatchTimeoutNs: UInt64 = 12_000_000_000

  private let dispatchLog = OSLog(
    subsystem: "com.margelo.nitro.queueplayer",
    category: "CarPlay.Dispatch"
  )

  /// Loud (`.error`) so an over-cap browse surface is visible in Console
  /// rather than silently dropping items. Each call-site message names the
  /// offending node + count and the remedy specific to that cap (the tab cap
  /// and the per-list cap have different remedies).
  private func logTruncation(_ message: String) {
    os_log("%{public}@", log: dispatchLog, type: .error, message)
  }

  // MARK: - Localized strings

  private var rootTitle: String {
    NSLocalizedString("Library", comment: "CarPlay browse root title")
  }
}
