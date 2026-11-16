import CarPlay
import UIKit

/// Convert a [CarPlayQueueSnapshot] into the CarPlay "Up Next" list
/// template shown when the user taps the Now Playing queue button.
/// Single-section `CPListTemplate` — one `CPListItem` per queue entry,
/// with the currently-playing entry marked via `isPlaying`.
///
/// Audio-app list cap: CarPlay reports the runtime row limit via
/// `CPListTemplate.maximumItemCount`. Over-cap queues are truncated to
/// the first N rows; the current entry's index still maps 1:1 to the
/// list row as long as it falls inside the cap.
///
/// Stateless helper. The owning scene delegate pushes the returned
/// template onto the interface controller.
final class CarPlayQueueBuilder {

  /// Maximum rows per Up Next list. Reads the SDK property so the cap
  /// tracks whatever the running CarPlay framework enforces.
  static var maximumItemCount: Int { CPListTemplate.maximumItemCount }

  /// Build the Up Next `CPListTemplate` from [snapshot]. Each row's
  /// tap handler invokes [skip] with that row's queue index, then the
  /// caller pops back to Now Playing. The entry at
  /// `snapshot.currentIndex` is marked `isPlaying`. Rows beyond
  /// [maximumItemCount] are dropped.
  func buildQueueTemplate(
    snapshot: CarPlayQueueSnapshot,
    loader: ArtworkLoader,
    maximumItemCount: Int = CarPlayQueueBuilder.maximumItemCount,
    skip: @escaping (Int) -> Void
  ) -> CPListTemplate {
    let cap = max(0, maximumItemCount)
    let visible = snapshot.entries.prefix(cap)
    let items = visible.enumerated().map { index, entry in
      makeQueueItem(
        entry: entry,
        index: index,
        isCurrent: index == snapshot.currentIndex,
        loader: loader,
        skip: skip
      )
    }
    return CPListTemplate(
      title: upNextTitle,
      sections: [CPListSection(items: items)]
    )
  }

  /// Build a single Up Next row. Text = entry title, detail = subtitle.
  /// Tap fires [skip] with [index]. The current row shows the playing
  /// indicator; artwork loads asynchronously via [loader].
  func makeQueueItem(
    entry: CarPlayQueueEntry,
    index: Int,
    isCurrent: Bool,
    loader: ArtworkLoader,
    skip: @escaping (Int) -> Void
  ) -> CPListItem {
    let listItem = CPListItem(
      text: entry.title,
      detailText: entry.subtitle,
      image: nil
    )
    listItem.isPlaying = isCurrent
    if let artworkUrl = entry.artworkUrl, !artworkUrl.isEmpty {
      loader.load(uri: artworkUrl) { [weak listItem] image in
        guard let listItem = listItem, let image = image else { return }
        listItem.setImage(image)
      }
    }
    listItem.handler = { _, completion in
      skip(index)
      completion()
    }
    return listItem
  }

  private var upNextTitle: String {
    NSLocalizedString("Up Next", comment: "CarPlay queue list title")
  }
}
