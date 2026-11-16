import CarPlay
import Foundation

/// Lib-side `CPTemplateApplicationSceneDelegate` that drives the
/// CarPlay browse + Now Playing surfaces. Lifetime matches the
/// CarPlay scene's — UIKit constructs an instance per scene attach,
/// retains it until the scene tears down.
///
/// Hybrid scene strategy: the delegate runs alongside the consumer's
/// existing `UIApplicationDelegate` rather than replacing it. The
/// phone app continues to use legacy
/// `didFinishLaunchingWithOptions`; this delegate only activates
/// when CarPlay is connected.
///
/// The delegate reaches into [CarPlayCoordinator.shared] to access
/// the live `BrowseDataProvider`, `CarPlayBridge`,
/// `PendingBrowseRequests`, and `ArtworkLoader` — all of which are
/// also written from the JS-side `TrackPlayer` Hybrid path, so the
/// scene delegate sees fresh data without anything being injected
/// through its initialiser.
/// **Class is `internal` (not `public`) and uses `@objc(<bare-name>)`:**
/// the auto-generated `<Module>-Swift.h` only emits public Swift
/// classes; `internal` keeps this one out of the bridging header so
/// its `CPInterfaceController` / `CPTemplateApplicationScene`
/// parameter types do not trigger an import-CarPlay-fail in the
/// lib's auto-generated Obj-C bridging header. The `@objc(<bare-name>)`
/// annotation makes the runtime name resolvable via
/// `NSClassFromString("CarPlaySceneDelegate")` from Info.plist
/// regardless of the lib pod's Swift module name. Apple Developer
/// docs: "Using @objc inference" — explicit `@objc(<name>)` is the
/// only safe way to fix a Swift class's Obj-C runtime name across
/// module boundaries.
@available(iOS 14.0, *)
@objc(CarPlaySceneDelegate)
internal class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate,
  CPInterfaceControllerDelegate {

  /// Live interface controller for the connected CarPlay scene.
  /// Stashed so the delegate can push child templates on row tap +
  /// swap the root template when the consumer pushes a fresh
  /// snapshot. Cleared on scene disconnect.
  private var interfaceController: CPInterfaceController?

  /// Live root `CPTabBarTemplate`. Held so snapshot updates can swap
  /// the tab list via `updateTemplates(_:)` instead of
  /// `setRootTemplate` (which tears down the live UI). Built once at
  /// `didConnect` — placeholder tab if the snapshot hasn't landed, or
  /// real tabs if it has — and never replaced for this scene's
  /// lifetime; only the inner templates swap.
  private var rootTabBar: CPTabBarTemplate?

  /// Snapshot-update observer. Removed on scene disconnect so the
  /// delegate doesn't leak past its scene's lifetime.
  private var snapshotObserver: NSObjectProtocol?

  /// Queue-mirror observer. Fires when the play queue or current index
  /// changes so the Up Next button's enabled state re-evaluates.
  /// Removed on scene disconnect.
  private var queueObserver: NSObjectProtocol?

  /// Cached scene-construction template builder. Stateless; the
  /// live tab-bar reference above is what survives across snapshot
  /// updates.
  private let builder = CarPlayBrowseBuilder()

  /// Stateless builder for the Up Next queue list template.
  private let queueBuilder = CarPlayQueueBuilder()

  // MARK: - CPTemplateApplicationSceneDelegate

  public func templateApplicationScene(
    _ templateApplicationScene: CPTemplateApplicationScene,
    didConnect interfaceController: CPInterfaceController
  ) {
    self.interfaceController = interfaceController
    interfaceController.delegate = self
    // Configure the shared Now Playing template at didConnect so the
    // template carries its full layout the moment it appears on screen
    // (cover art, scrub bar, duration, stacked title/artist). Apple
    // Developer Forums: "configure CPNowPlayingTemplate at the time of
    // the didConnect delegate method, not when you push to it, because
    // CPNowPlayingTemplate may be triggered by 'playing' and the Now
    // Playing Button rather than by an active push."
    configureNowPlayingTemplate()
    // Wire row-tap auto-nav: after a playable row's play callback
    // resolves, push the shared Now Playing template onto the nav
    // stack so the user lands on the playback surface immediately
    // (Spotify / Apple Music UX). Guarded against double-push by a
    // `topTemplate` check inside the closure.
    builder.pushNowPlaying = { [weak self] in
      guard let controller = self?.interfaceController else { return }
      if controller.topTemplate === CPNowPlayingTemplate.shared { return }
      controller.pushTemplate(
        CPNowPlayingTemplate.shared,
        animated: true,
        completion: nil
      )
    }

    let coordinator = CarPlayCoordinator.shared
    let initialTabBar = builder.buildRootTemplate(
      provider: coordinator.dataProvider,
      bridge: coordinator.bridge,
      pending: coordinator.pending,
      loader: coordinator.loader,
      pushTemplate: { [weak self] template in
        self?.pushNextTemplate(template)
      }
    ) ?? builder.buildPlaceholderRootTabBar()
    self.rootTabBar = initialTabBar

    DiagnosticLog.carPlay.log(
      "didConnect usingPlaceholder=\(coordinator.dataProvider.getSnapshot() == nil)")
    NSLog("[RNQP-CarPlay] scene didConnect; usingPlaceholder=%@",
          coordinator.dataProvider.getSnapshot() == nil ? "true" : "false")
    interfaceController.setRootTemplate(initialTabBar, animated: false) { [weak self] ok, err in
      NSLog("[RNQP-CarPlay] setRootTemplate(initial) ok=%@ err=%@",
            ok ? "true" : "false",
            err.map { String(describing: $0) } ?? "nil")
      // A snapshot push that landed AFTER scene attach but DURING this
      // in-flight install couldn't update the tab bar yet (we hadn't
      // set the root). Run one catch-up swap on completion so the
      // latest snapshot wins without depending on a future snapshot
      // push. Uses `updateTemplates` (not setRootTemplate) — the root
      // tab bar stays the same instance for the scene's lifetime.
      self?.refreshRootTemplate()
      if ok { self?.openNowPlayingIfPlaying(on: interfaceController) }
    }

    snapshotObserver = NotificationCenter.default.addObserver(
      forName: CarPlayBrowseDataProvider.didUpdateNotification,
      object: coordinator.dataProvider,
      queue: .main
    ) { [weak self] _ in
      self?.refreshRootTemplate()
    }

    queueObserver = NotificationCenter.default.addObserver(
      forName: CarPlayQueueProvider.didUpdateNotification,
      object: coordinator.queueProvider,
      queue: .main
    ) { [weak self] _ in
      self?.refreshUpNextButton()
    }

    coordinator.setCarConnected(true)
    coordinator.bridge.carConnectCallback()?()
    // Last, so a React Native start run synchronously by an observer cannot reorder the setup above.
    QueuePlayerSceneHost.requestJavaScript()
  }

  /// Opens on Now Playing when audio is playing at connect, pushed over the
  /// root so Back returns to the browse lists. Read once, after the root is
  /// installed, so a resume the car triggers during the install still counts.
  /// The push happens only if the reply is fresh and the stack is still just
  /// the root: jumping to Now Playing over a list the user opened would be
  /// worse than missing a late resume.
  private func openNowPlayingIfPlaying(on controller: CPInterfaceController) {
    guard let reader = CarPlayCoordinator.shared.playerStateReader else {
      NSLog("[RNQP-CarPlay] connect landing: reader=nil → browse")
      return
    }
    let askedAt = ProcessInfo.processInfo.systemUptime
    reader { [weak self] state in
      guard let self, self.interfaceController === controller else {
        NSLog("[RNQP-CarPlay] connect landing: skipped, scene changed")
        return
      }
      guard CarPlayCoordinator.opensNowPlayingAtConnect(state: state) else {
        NSLog("[RNQP-CarPlay] connect landing: state=%@ → browse", state.stringValue)
        return
      }
      guard ProcessInfo.processInfo.systemUptime - askedAt < 1 else {
        NSLog("[RNQP-CarPlay] connect landing: skipped, state reply arrived late")
        return
      }
      // With a tab bar root, the stack the controller reports may be the tab
      // bar itself or the selected tab's own stack; either counts as the root
      // while nothing has been pushed over it.
      let top = controller.topTemplate
      NSLog("[RNQP-CarPlay] connect landing: stack=%@ top=%@",
            controller.templates.map { String(describing: type(of: $0)) }.joined(separator: ","),
            top.map { String(describing: type(of: $0)) } ?? "nil")
      guard top !== CPNowPlayingTemplate.shared else {
        NSLog("[RNQP-CarPlay] connect landing: skipped, Now Playing already on top")
        return
      }
      guard let root = self.rootTabBar,
            top === root || root.templates.contains(where: { $0 === top }) else {
        NSLog("[RNQP-CarPlay] connect landing: skipped, the user has moved off the root")
        return
      }
      NSLog("[RNQP-CarPlay] connect landing: state=%@ → nowPlaying", state.stringValue)
      controller.pushTemplate(CPNowPlayingTemplate.shared, animated: false) { ok, err in
        NSLog("[RNQP-CarPlay] pushTemplate(nowPlaying, connect) ok=%@ err=%@",
              ok ? "true" : "false", err.map { String(describing: $0) } ?? "nil")
      }
    }
  }

  private func pushNextTemplate(_ template: CPListTemplate) {
    interfaceController?.pushTemplate(template, animated: true) { _, _ in }
  }

  public func templateApplicationScene(
    _ templateApplicationScene: CPTemplateApplicationScene,
    didDisconnectInterfaceController interfaceController: CPInterfaceController
  ) {
    DiagnosticLog.carPlay.log("didDisconnect")
    if let observer = snapshotObserver {
      NotificationCenter.default.removeObserver(observer)
      snapshotObserver = nil
    }
    if let observer = queueObserver {
      NotificationCenter.default.removeObserver(observer)
      queueObserver = nil
    }
    CPNowPlayingTemplate.shared.remove(self)
    self.interfaceController = nil
    self.rootTabBar = nil
    CarPlayCoordinator.shared.setCarConnected(false)
    CarPlayCoordinator.shared.bridge.carDisconnectCallback()?()
    // Pending entries from this scene's session are stale — the
    // next scene attach starts from a fresh snapshot read, so
    // dropping the queue avoids a phantom "loading…" rebuild on
    // reconnect.
    CarPlayCoordinator.shared.pending.clear()
  }

  // MARK: - Scene and template visibility

  public func sceneWillEnterForeground(_ scene: UIScene) {
    DiagnosticLog.carPlay.log("sceneWillEnterForeground")
  }

  public func sceneDidBecomeActive(_ scene: UIScene) {
    DiagnosticLog.carPlay.log("sceneDidBecomeActive")
  }

  public func sceneWillResignActive(_ scene: UIScene) {
    DiagnosticLog.carPlay.log("sceneWillResignActive")
  }

  public func sceneDidEnterBackground(_ scene: UIScene) {
    DiagnosticLog.carPlay.log("sceneDidEnterBackground")
  }

  public func templateDidAppear(_ aTemplate: CPTemplate, animated: Bool) {
    DiagnosticLog.carPlay.log("templateDidAppear \(type(of: aTemplate))")
  }

  public func templateDidDisappear(_ aTemplate: CPTemplate, animated: Bool) {
    DiagnosticLog.carPlay.log("templateDidDisappear \(type(of: aTemplate))")
  }

  // MARK: - Internal

  private func refreshRootTemplate() {
    guard interfaceController != nil else {
      NSLog("[RNQP-CarPlay] refreshRootTemplate skipped — no interfaceController")
      return
    }
    guard let rootTabBar = rootTabBar else {
      NSLog("[RNQP-CarPlay] refreshRootTemplate skipped — root tab bar not installed yet")
      return
    }
    let coordinator = CarPlayCoordinator.shared
    guard let snapshot = coordinator.dataProvider.getSnapshot() else {
      NSLog("[RNQP-CarPlay] refreshRootTemplate: snapshot still null, leaving placeholder")
      return
    }
    let newTabs = builder.buildTabs(
      snapshot: snapshot,
      bridge: coordinator.bridge,
      loader: coordinator.loader,
      pushTemplate: { [weak self] template in
        self?.pushNextTemplate(template)
      }
    )
    rootTabBar.updateTemplates(newTabs)
    NSLog("[RNQP-CarPlay] refreshRootTemplate: updateTemplates(count=%d)", newTabs.count)
    // updateTemplates is the catch-all rebuild — every connected
    // controller re-fetches the affected hierarchy, so any pending
    // requests that landed before this snapshot are implicitly
    // serviced by the new tab state. Clearing here ensures a
    // subsequent partial-update path doesn't see stale entries.
    coordinator.pending.clear()
  }

  /// Touch `CPNowPlayingTemplate.shared` at scene didConnect so the
  /// singleton is alive when a row-tap push lands. Pushing onto the
  /// nav stack via `pushTemplate(CPNowPlayingTemplate.shared, ...)`
  /// is the supported path for `com.apple.developer.carplay-audio`
  /// apps; embedding the same template in a `CPTabBarTemplate` root
  /// is NOT — that raises `NSInvalidArgumentException`.
  ///
  /// Registers self as a `CPNowPlayingTemplateObserver` so the Up Next
  /// button tap routes into [nowPlayingTemplateUpNextButtonTapped].
  /// Enables only the Up Next flag + title (when the mirrored queue is
  /// non-empty); every other Now Playing button keeps baseline defaults.
  private func configureNowPlayingTemplate() {
    CPNowPlayingTemplate.shared.add(self)
    refreshUpNextButton()
    NSLog("[RNQP-CarPlay] CPNowPlayingTemplate.shared configured")
  }

  /// Enable the Up Next button when the mirrored queue has entries.
  /// Re-run from the queue-mirror observer so the button reflects the
  /// live queue.
  private func refreshUpNextButton() {
    let hasQueue = !(
      CarPlayCoordinator.shared.queueProvider.getSnapshot()?.entries.isEmpty ?? true
    )
    CPNowPlayingTemplate.shared.isUpNextButtonEnabled = hasQueue
    CPNowPlayingTemplate.shared.upNextTitle = upNextTitle
  }

  private var upNextTitle: String {
    NSLocalizedString("Up Next", comment: "CarPlay queue button title")
  }

}

// MARK: - CPNowPlayingTemplateObserver

@available(iOS 14.0, *)
extension CarPlaySceneDelegate: CPNowPlayingTemplateObserver {

  /// Build the Up Next list from the mirrored queue snapshot and push
  /// it. Each row taps back to Now Playing after routing the skip
  /// through the coordinator's `skipToIndexHandler`, which `TrackPlayer`
  /// registers. No-op when no queue is mirrored yet.
  func nowPlayingTemplateUpNextButtonTapped(
    _ nowPlayingTemplate: CPNowPlayingTemplate
  ) {
    guard let controller = interfaceController else { return }
    // Only push from the Now Playing surface — guards against a
    // spurious double-push if the tap re-fires while the list is up.
    guard controller.topTemplate === CPNowPlayingTemplate.shared else { return }
    guard let snapshot = CarPlayCoordinator.shared.queueProvider.getSnapshot(),
          !snapshot.entries.isEmpty else {
      return
    }
    let list = queueBuilder.buildQueueTemplate(
      snapshot: snapshot,
      loader: CarPlayCoordinator.shared.loader,
      skip: { [weak self] index in
        CarPlayCoordinator.shared.skipToIndexHandler?(index)
        // Return to Now Playing — pop the Up Next list off the nav
        // stack; the Now Playing surface is already beneath it.
        self?.interfaceController?.popTemplate(animated: true) { _, _ in }
      }
    )
    controller.pushTemplate(list, animated: true) { _, _ in }
  }
}
