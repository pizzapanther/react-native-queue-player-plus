const fs = require('fs');
const path = require('path');
const {
  withDangerousMod,
  withEntitlementsPlist,
  withInfoPlist,
} = require('@expo/config-plugins');

/**
 * react-native-queue-player Expo config plugin.
 *
 * Always-on:
 *  - `AVInitialRouteSharingPolicy = LongFormAudio` so a cold-start
 *    (Siri, CarPlay launch, lock-screen widget) routes to the user's
 *    last-active long-form output (AirPods, AirPlay 2, CarPlay)
 *    BEFORE the lib's Swift code runs. Without this, the first
 *    ~150ms route to the device speaker.
 *  - `UIBackgroundModes` includes `audio` so playback continues when
 *    the app is backgrounded / the screen locks (required for any
 *    background audio). Enabled by default; opt out with
 *    `{ enableIosBGAudio: false }` if your app declares it elsewhere.
 *
 * Requires Expo SDK 58 or later and throws on an older `sdkVersion`. With
 * `carplay` or `siri` on, the app's `SceneDelegate.swift` must subclass
 * `ExpoAppSceneDelegate`.
 *
 * Scene launch (`carplay` or `siri`):
 *  - Sets `UIApplicationSupportsMultipleScenes` to true in the template's
 *    `UIApplicationSceneManifest` (Siri's `application(_:handlerFor:)` and
 *    a CarPlay scene beside the phone scene both need it). The phone scene
 *    entry is left as the template wrote it.
 *  - AppDelegate: starts React Native into an unattached window when the
 *    lib posts `QueuePlayerJavaScriptRequested` for a CarPlay or Siri launch
 *    before a phone scene exists.
 *  - SceneDelegate: moves that root into the phone scene's window when one
 *    connects, registers the window Expo creates on a normal launch, and
 *    refuses a second phone window. All three go through the lib's
 *    `QueuePlayerSceneHost` class.
 *
 * Opt-in CarPlay (`{ carplay: true }`):
 *  - Adds the `com.apple.developer.carplay-audio` entitlement.
 *  - Adds the CarPlay scene (`CPTemplateApplicationSceneSessionRoleApplication`,
 *    delegate `CarPlaySceneDelegate`) to the scene manifest.
 *
 * Opt-in Siri (`{ siri: true }`):
 *  - Adds the `com.apple.developer.siri` entitlement.
 *  - Declares SiriKit `INPlayMediaIntent` support via Info.plist
 *    (`INIntentsSupported`, `INSupportedMediaCategories`,
 *    `NSUserActivityTypes`).
 *  - Adds the music-domain `LSApplicationCategoryType` + a default
 *    `NSSiriUsageDescription` placeholder.
 *  - Injects an `application(_:handlerFor:)` override into the
 *    AppDelegate that routes `INPlayMediaIntent` through the lib's
 *    `QueuePlayerMediaIntents.handler(forIntent:)` selector. Free-text
 *    "Hey Siri play X on MyApp" lands in the JS resolver wired via
 *    `onPlayFromSearchRequest`. The lib stays opinion-free about which
 *    music search results to play; the consumer's JS does its own
 *    catalog search + setQueue + play.
 *  - Siri is a self-serve capability on developer.apple.com — EAS
 *    Build auto-toggles the App ID flag; local Xcode builds need a
 *    one-time Capabilities-pane toggle if running without automatic
 *    signing.
 *
 * Usage in app.json:
 *
 *     "plugins": [
 *       ["react-native-queue-player", { "carplay": true, "siri": true }]
 *     ]
 */

const CARPLAY_SCENE_DELEGATE = 'CarPlaySceneDelegate';
const CARPLAY_CONFIG_NAME = 'CarPlay';
const CARPLAY_ROLE = 'CPTemplateApplicationSceneSessionRoleApplication';

/**
 * Turns on multiple scenes in the template's scene manifest and, with
 * `carplay`, adds the CarPlay scene. Throws when the manifest has no phone
 * scene entry, which an SDK 58 prebuild always writes. Introspection
 * (`expo config --type introspect`, which EAS uses to read entitlements) can
 * start from Expo's built-in Info.plist, which has no scene manifest; it
 * returns the plist unchanged.
 */
const withSceneManifest = (config, { carplay }) =>
  withInfoPlist(config, (cfg) => {
    const manifest = cfg.modResults.UIApplicationSceneManifest;
    const configurations = manifest && manifest.UISceneConfigurations;
    const phone = configurations && configurations.UIWindowSceneSessionRoleApplication;
    if (!Array.isArray(phone) || phone.length === 0) {
      if (cfg.modRequest.introspect) return cfg;
      throw new Error(
        '[react-native-queue-player] Info.plist has no UIApplicationSceneManifest entry for ' +
          'UIWindowSceneSessionRoleApplication. Run `npx expo prebuild --clean` on Expo SDK 58 or later.'
      );
    }
    manifest.UIApplicationSupportsMultipleScenes = true;
    if (carplay) {
      const existing = Array.isArray(configurations[CARPLAY_ROLE])
        ? configurations[CARPLAY_ROLE]
        : [];
      configurations[CARPLAY_ROLE] = [
        ...existing.filter((entry) => entry.UISceneConfigurationName !== CARPLAY_CONFIG_NAME),
        {
          UISceneClassName: 'CPTemplateApplicationScene',
          UISceneConfigurationName: CARPLAY_CONFIG_NAME,
          UISceneDelegateClassName: CARPLAY_SCENE_DELEGATE,
        },
      ];
    }
    return cfg;
  });

const withCarPlayEntitlement = (config) =>
  withEntitlementsPlist(config, (cfg) => {
    cfg.modResults['com.apple.developer.carplay-audio'] = true;
    return cfg;
  });

const withSiriEntitlement = (config) =>
  withEntitlementsPlist(config, (cfg) => {
    cfg.modResults['com.apple.developer.siri'] = true;
    return cfg;
  });

const withSiriInfoPlist = (config) =>
  withInfoPlist(config, (cfg) => {
    // App Store category — Apple recommends setting it for any
    // music-domain app and Siri's audio routing heuristics read it.
    if (!cfg.modResults.LSApplicationCategoryType) {
      cfg.modResults.LSApplicationCategoryType = 'public.app-category.music';
    }
    // App Store Review historically rejects builds that hold the
    // `com.apple.developer.siri` entitlement without a usage-
    // description string. Defensive placeholder; consumers should
    // override with their own user-facing copy.
    if (!cfg.modResults.NSSiriUsageDescription) {
      cfg.modResults.NSSiriUsageDescription =
        'Voice playback uses Siri to find and play music in this app.';
    }
    // iOS rejects `INPlayMediaIntent` dispatch to a third-party
    // `application(_:handlerFor:)` when `NSAppleMusicUsageDescription`
    // is absent, even when the app never touches the Apple Music
    // library. Defensive placeholder; consumers should override with
    // their own user-facing copy.
    if (!cfg.modResults.NSAppleMusicUsageDescription) {
      cfg.modResults.NSAppleMusicUsageDescription =
        'Voice playback resolves search queries through this app to play music.';
    }
    // Declares the SiriKit intents the app handles. INPlayMediaIntent
    // is the music-domain intent that backs "Hey Siri play X on
    // MyApp" — Siri parses the user's free-text utterance into a
    // structured `INMediaSearch` and routes it to our handler.
    const ensureInArray = (key, value) => {
      const existing = Array.isArray(cfg.modResults[key])
        ? cfg.modResults[key]
        : [];
      if (!existing.includes(value)) {
        cfg.modResults[key] = [...existing, value];
      }
    };
    ensureInArray('INIntentsSupported', 'INPlayMediaIntent');
    ensureInArray('NSUserActivityTypes', 'INPlayMediaIntent');
    // INSupportedMediaCategories declares which SiriKit media-domain
    // taxonomies the app's INPlayMediaIntent handler can serve. The
    // string values are the exact `INMediaCategory…` enum case names;
    // lowercased keywords are silently rejected. `INMediaCategoryMusic`
    // is the primary library category; `INMediaCategoryGeneral` is the
    // catch-all that lets Siri route any audio-domain "play X on
    // MyApp" utterance through our handler.
    if (!cfg.modResults.INSupportedMediaCategories) {
      cfg.modResults.INSupportedMediaCategories = [
        'INMediaCategoryMusic',
        'INMediaCategoryGeneral',
      ];
    }
    return cfg;
  });

/**
 * Inject `application(_:handlerFor:)` into the consumer's AppDelegate
 * so SiriKit `INPlayMediaIntent` dispatches route to the lib's
 * `QueuePlayerMediaIntents.handler(forIntent:)`. Idempotent — re-running
 * `expo prebuild` replaces the existing block in-place rather than
 * appending duplicates.
 */
const SIRI_HANDLER_MARKER_BEGIN =
  '// react-native-queue-player: SiriKit INPlayMediaIntent handler begin';
const SIRI_HANDLER_MARKER_END = '// react-native-queue-player: SiriKit INPlayMediaIntent handler end';
const SIRI_HANDLER_BLOCK = `  ${SIRI_HANDLER_MARKER_BEGIN} (auto-generated, do not edit; see react-native-queue-player/app.plugin.js)
  @objc(application:handlerForIntent:)
  public func application(
    _ application: UIApplication,
    handlerFor intent: INIntent
  ) -> Any? {
    guard let cls = NSClassFromString("QueuePlayerMediaIntents") else {
      return nil
    }
    let selector = NSSelectorFromString("handlerForIntent:")
    let meta: AnyClass? = object_getClass(cls)
    guard meta?.instancesRespond(to: selector) ?? false else {
      return nil
    }
    return (cls as AnyObject).perform(selector, with: intent)?.takeUnretainedValue()
  }
  ${SIRI_HANDLER_MARKER_END}
`;

const SIRI_IMPORT_MARKER = '// react-native-queue-player: Intents import';
const SIRI_IMPORT_LINE = `${SIRI_IMPORT_MARKER}\nimport Intents`;

const withSiriAppDelegate = (config) =>
  withDangerousMod(config, [
    'ios',
    async (cfg) => {
      const appDelegatePath = path.join(
        cfg.modRequest.platformProjectRoot,
        cfg.modRequest.projectName,
        'AppDelegate.swift'
      );
      let contents = fs.readFileSync(appDelegatePath, 'utf8');

      // Ensure `import Intents` near the top.
      if (!contents.includes(SIRI_IMPORT_MARKER)) {
        contents = contents.replace(
          /^(internal import Expo)/m,
          `${SIRI_IMPORT_LINE}\n$1`
        );
      }

      // Strip every prior generated block before re-inserting.
      contents = stripMarkedBlock(contents, SIRI_HANDLER_MARKER_BEGIN, SIRI_HANDLER_MARKER_END);

      // Insert the block just before the AppDelegate class's closing
      // brace. The location is found via a balanced-brace scan starting
      // from the `class AppDelegate { … }` header so reordering or
      // adding sibling classes doesn't push the insertion into the
      // wrong class body.
      const insertionPoint = findClassClosingBraceIndex(contents, APP_DELEGATE_HEADER);
      if (insertionPoint === -1) {
        throw new Error(
          '[react-native-queue-player] Failed to locate AppDelegate class body — Expo template likely changed; update with-siri-app-delegate logic.'
        );
      }
      contents =
        contents.slice(0, insertionPoint) +
        '\n' +
        SIRI_HANDLER_BLOCK +
        contents.slice(insertionPoint);

      fs.writeFileSync(appDelegatePath, contents);
      return cfg;
    },
  ]);

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const APP_DELEGATE_HEADER =
  /^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|internal|open|final)\s+)*class\s+AppDelegate\b[^{]*\{/m;

/**
 * Locate the closing `}` of the class whose declaration `header` matches
 * (the match must end at the opening brace) via a balanced-brace scan that
 * skips Swift string literals and line / block comments. Returns the index
 * of the closing brace, or -1 if the header is not found or the braces are
 * unbalanced.
 */
function findClassClosingBraceIndex(contents, header) {
  const headerMatch = contents.match(header);
  if (!headerMatch || headerMatch.index === undefined) return -1;
  let depth = 1;
  let inString = false;
  let inLineComment = false;
  let inBlockComment = false;
  for (let i = headerMatch.index + headerMatch[0].length; i < contents.length; i++) {
    const ch = contents[i];
    const next = contents[i + 1];
    if (inLineComment) {
      if (ch === '\n') inLineComment = false;
      continue;
    }
    if (inBlockComment) {
      if (ch === '*' && next === '/') { inBlockComment = false; i++; }
      continue;
    }
    if (inString) {
      if (ch === '\\') { i++; continue; }
      if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') { inString = true; continue; }
    if (ch === '/' && next === '/') { inLineComment = true; i++; continue; }
    if (ch === '/' && next === '*') { inBlockComment = true; i++; continue; }
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/**
 * Opt-in Chromecast support. Adds the `QueuePlayer/Chromecast` subspec
 * to the consumer Podfile (pulls in `google-cast-sdk ~> 4.8.4`), plus
 * the Info.plist keys Cast SDK 4.8.4+ requires:
 *
 *  - `NSBonjourServices` — both `_googlecast._tcp` (generic discovery)
 *    and `_<receiverAppId>._googlecast._tcp` (custom receiver).
 *  - `NSLocalNetworkUsageDescription` — iOS 14+ LNA gate copy.
 *  - `QueuePlayerChromecastReceiverApplicationID` — receiver app ID
 *    consumed by `ChromecastSupport.installWithDefaults()`. Defaults
 *    to `CC1AD845` (Google Default Media Receiver).
 *
 * Injects an `application(_:didFinishLaunchingWithOptions:)` hook
 * into the consumer's AppDelegate calling
 * `ChromecastSupport.installWithDefaults()` so the Cast SDK
 * initialises at app launch. The selector is dispatched via
 * `NSClassFromString` so the AppDelegate doesn't directly `import
 * QueuePlayer` (avoids static_framework + NitroModules umbrella-
 * header issues — see NOTES.md section 26).
 */
const withChromecastSubspecPodfile = (config) =>
  withDangerousMod(config, [
    'ios',
    async (cfg) => {
      const podfilePath = path.join(cfg.modRequest.platformProjectRoot, 'Podfile');
      if (!fs.existsSync(podfilePath)) return cfg;
      let contents = fs.readFileSync(podfilePath, 'utf8');
      const marker = '# react-native-queue-player: Chromecast subspec';
      if (contents.includes(marker)) return cfg;
      // Resolve the lib's filesystem path the same way `node` does
      // (`require.resolve` walks the package tree the consumer app has
      // installed). Convert it to a path relative to the iOS Podfile
      // so the CocoaPods `:path =>` resolution matches autolinking,
      // regardless of monorepo layout (sibling, hoisted, pnpm, etc.).
      const libPackagePath = path.dirname(
        require.resolve('react-native-queue-player/package.json', {
          paths: [cfg.modRequest.projectRoot],
        })
      );
      const relLibPath = path
        .relative(cfg.modRequest.platformProjectRoot, libPackagePath)
        .replace(/\\/g, '/');
      // Insert AFTER use_react_native! so autolinking has resolved
      // the main QueuePlayer pod by the time the subspec reference
      // is evaluated. Anchor on the closing `)` of `use_react_native!`.
      const line = `\n  ${marker}\n  pod 'QueuePlayer/Chromecast', :path => '${relLibPath}'\n`;
      const anchor = contents.match(/use_react_native!\([\s\S]*?\n  \)/);
      if (anchor) {
        const idx = anchor.index + anchor[0].length;
        contents = contents.slice(0, idx) + line + contents.slice(idx);
      } else {
        // Fallback: insert before post_install.
        contents = contents.replace(/(  post_install do)/, `${line}$1`);
      }
      fs.writeFileSync(podfilePath, contents);
      return cfg;
    },
  ]);

/**
 * Bonjour type the local-network permission probe advertises and browses.
 * Mirrors `LocalNetworkPermissionProbe.serviceType`; the two must agree or the
 * probe is blocked with nothing to indicate why.
 */
const LOCAL_NETWORK_PROBE_SERVICE_TYPE = '_rnqp-lnp._tcp';

const withChromecastInfoPlist = (config, receiverAppId) =>
  withInfoPlist(config, (cfg) => {
    const appId = (receiverAppId || 'CC1AD845').toUpperCase();
    cfg.modResults.QueuePlayerChromecastReceiverApplicationID = appId;

    if (!cfg.modResults.NSLocalNetworkUsageDescription) {
      cfg.modResults.NSLocalNetworkUsageDescription =
        'Discover Chromecast and AirPlay receivers on your local network.';
    }

    const existingBonjour = Array.isArray(cfg.modResults.NSBonjourServices)
      ? cfg.modResults.NSBonjourServices
      : [];
    // The permission probe browses its own service type. iOS refuses to browse
    // a type that is not declared here, so without this entry the probe can
    // never succeed and every consumer reads `undetermined` forever.
    // Must match `LocalNetworkPermissionProbe.serviceType` in
    // ios/Cast/Core/LocalNetworkPermissionProbe.swift.
    const additions = [
      '_googlecast._tcp',
      `_${appId}._googlecast._tcp`,
      LOCAL_NETWORK_PROBE_SERVICE_TYPE,
    ];
    for (const s of additions) {
      if (!existingBonjour.includes(s)) existingBonjour.push(s);
    }
    cfg.modResults.NSBonjourServices = existingBonjour;
    return cfg;
  });

const CHROMECAST_INSTALL_MARKER_BEGIN =
  '// react-native-queue-player: Chromecast install begin';
const CHROMECAST_INSTALL_MARKER_END =
  '// react-native-queue-player: Chromecast install end';
const CHROMECAST_INSTALL_SNIPPET = `    ${CHROMECAST_INSTALL_MARKER_BEGIN} (auto-generated, do not edit; see react-native-queue-player/app.plugin.js)
    if let cls = NSClassFromString("ChromecastSupport") {
      let selector = NSSelectorFromString("installWithDefaults")
      let meta: AnyClass? = object_getClass(cls)
      if meta?.instancesRespond(to: selector) ?? false {
        _ = (cls as AnyObject).perform(selector)
      }
    }
    ${CHROMECAST_INSTALL_MARKER_END}
`;

/**
 * Find the `return super.application(application, didFinishLaunchingWithOptions: launchOptions)`
 * line inside the AppDelegate's existing `didFinishLaunchingWithOptions`
 * override and insert the Chromecast install snippet immediately before it.
 *
 * The Expo-Router managed AppDelegate already declares the
 * `application(_:didFinishLaunchingWithOptions:)` override; declaring it
 * a second time is a Swift compile error. Inlining the
 * `ChromecastSupport.installWithDefaults()` call inside the existing
 * method body sidesteps the conflict and runs at the same point in the
 * launch sequence.
 */
const withChromecastAppDelegate = (config) =>
  withDangerousMod(config, [
    'ios',
    async (cfg) => {
      const appDelegatePath = path.join(
        cfg.modRequest.platformProjectRoot,
        cfg.modRequest.projectName,
        'AppDelegate.swift'
      );
      if (!fs.existsSync(appDelegatePath)) return cfg;
      let contents = fs.readFileSync(appDelegatePath, 'utf8');

      // Idempotent strip of any prior begin..end block + any legacy
      // duplicate-override block from earlier plugin versions.
      const beginEndStrip = new RegExp(
        `[ \\t]*${escapeRegex(CHROMECAST_INSTALL_MARKER_BEGIN)}[\\s\\S]*?${escapeRegex(CHROMECAST_INSTALL_MARKER_END)}\\n`,
        'g'
      );
      contents = contents.replace(beginEndStrip, '');
      const legacyDuplicateOverride = new RegExp(
        `\\n\\s*// react-native-queue-player: Chromecast install[^\\n]*\\n\\s*@objc public override func application\\([\\s\\S]*?\\n\\s*\\}\\n`,
        'g'
      );
      contents = contents.replace(legacyDuplicateOverride, '\n');

      // Insert the install snippet on its own lines immediately before the
      // existing `return super.application(...)` line so it runs as part of
      // the established launch hook.
      const returnLine =
        /(\n[ \t]*)return super\.application\(application, didFinishLaunchingWithOptions: launchOptions\)/;
      if (!returnLine.test(contents)) {
        // No didFinishLaunchingWithOptions present yet; nothing to inject.
        fs.writeFileSync(appDelegatePath, contents);
        return cfg;
      }
      // `indent` is the newline + leading whitespace ahead of the return; reuse
      // it to indent the snippet's first line, and keep the matched return
      // statement intact after it.
      contents = contents.replace(
        returnLine,
        (returnStatement, indent) =>
          `${indent}${CHROMECAST_INSTALL_SNIPPET.trim()}${returnStatement}`
      );
      fs.writeFileSync(appDelegatePath, contents);
      return cfg;
    },
  ]);

const SCENE_LAUNCH_MARKER_BEGIN = '// react-native-queue-player: scene launch begin';
const SCENE_LAUNCH_MARKER_END = '// react-native-queue-player: scene launch end';
const SCENE_LAUNCH_BLOCK = `    ${SCENE_LAUNCH_MARKER_BEGIN} (auto-generated, do not edit; see react-native-queue-player/app.plugin.js)
    // Starts React Native into an unattached window when CarPlay or Siri needs JavaScript before a phone
    // scene has connected. SceneDelegate moves that root into the phone scene's window when one connects.
    NotificationCenter.default.addObserver(
      forName: Notification.Name("QueuePlayerJavaScriptRequested"), object: nil, queue: nil
    ) { [weak self] _ in
      MainActor.assumeIsolated {
        guard let self, let factory = self.reactNativeFactory,
              let cls = NSClassFromString("QueuePlayerSceneHost"),
              let meta = object_getClass(cls),
              meta.instancesRespond(to: NSSelectorFromString("hasReactRoot")),
              meta.instancesRespond(to: NSSelectorFromString("registerHostWindow:")),
              let hasRoot = (cls as AnyObject).perform(NSSelectorFromString("hasReactRoot"))?
                .takeUnretainedValue() as? NSNumber,
              !hasRoot.boolValue
        else { return }
        let window = UIWindow(frame: .zero)
        self.window = window
        _ = (cls as AnyObject).perform(NSSelectorFromString("registerHostWindow:"), with: window)
        factory.startReactNative(
          withModuleName: self.reactNativeFactoryModuleName, in: window, launchOptions: nil)
      }
    }
    ${SCENE_LAUNCH_MARKER_END}
`;

const SCENE_DELEGATE_MARKER_BEGIN = '// react-native-queue-player: scene delegate begin';
const SCENE_DELEGATE_MARKER_END = '// react-native-queue-player: scene delegate end';
const SCENE_DELEGATE_BLOCK = `  ${SCENE_DELEGATE_MARKER_BEGIN} (auto-generated, do not edit; see react-native-queue-player/app.plugin.js)
  // Keeps one React Native root for the app. A root started for CarPlay or Siri before any phone scene
  // moves into the phone scene's window, and a second phone window is refused. Without the lib's
  // QueuePlayerSceneHost class, phone scenes and life-cycle events go to ExpoAppSceneDelegate unchanged.
  private func queuePlayerSceneHost(responding selectors: [String]) -> AnyObject? {
    guard let cls = NSClassFromString("QueuePlayerSceneHost"),
          let meta = object_getClass(cls),
          selectors.allSatisfy({ meta.instancesRespond(to: NSSelectorFromString($0)) })
    else { return nil }
    return cls as AnyObject
  }

  // React Native is already running, so a scene's connection options go through the same methods UIKit
  // calls for a running app instead of through startReactNative's launch options.
  private func queuePlayerDeliver(
    _ connectionOptions: UIScene.ConnectionOptions, from scene: UIWindowScene
  ) {
    if !connectionOptions.urlContexts.isEmpty {
      self.scene(scene, openURLContexts: connectionOptions.urlContexts)
    }
    connectionOptions.userActivities.forEach { self.scene(scene, continue: $0) }
    if let shortcutItem = connectionOptions.shortcutItem {
      self.windowScene(scene, performActionFor: shortcutItem) { _ in }
    }
  }

  private func queuePlayerIsRefused(_ scene: UIScene) -> Bool {
    guard let host = queuePlayerSceneHost(responding: ["isRefusedScene:"]) else { return false }
    let refused = host.perform(NSSelectorFromString("isRefusedScene:"), with: scene)?
      .takeUnretainedValue() as? NSNumber
    return refused?.boolValue ?? false
  }

  override func scene(
    _ scene: UIScene,
    willConnectTo session: UISceneSession,
    options connectionOptions: UIScene.ConnectionOptions
  ) {
    // Only the phone role gets a window; ExpoAppSceneDelegate starts React Native for any window scene.
    guard session.role == .windowApplication, let windowScene = scene as? UIWindowScene else { return }
    guard let host = queuePlayerSceneHost(responding: [
      "refuseSceneIfDuplicate:", "adoptRootIntoScene:", "registerHostWindow:",
    ]) else {
      super.scene(scene, willConnectTo: session, options: connectionOptions)
      return
    }
    let refused = host.perform(NSSelectorFromString("refuseSceneIfDuplicate:"), with: scene)?
      .takeUnretainedValue() as? NSNumber
    if refused?.boolValue == true {
      // A link or activity that opened the refused window still reaches the existing UI.
      queuePlayerDeliver(connectionOptions, from: windowScene)
      return
    }
    if let adopted = host.perform(NSSelectorFromString("adoptRootIntoScene:"), with: windowScene)?
      .takeUnretainedValue() as? UIWindow {
      window = adopted
      (UIApplication.shared.delegate as? ExpoReactNativeFactoryProvider)?.window = adopted
      queuePlayerDeliver(connectionOptions, from: windowScene)
      return
    }
    super.scene(scene, willConnectTo: session, options: connectionOptions)
    if let window {
      _ = host.perform(NSSelectorFromString("registerHostWindow:"), with: window)
    }
  }

  // A refused scene's life-cycle events are not forwarded to the app delegate.
  override func sceneDidDisconnect(_ scene: UIScene) {
    if queuePlayerIsRefused(scene) {
      if let host = queuePlayerSceneHost(responding: ["forgetRefusedScene:"]) {
        _ = host.perform(NSSelectorFromString("forgetRefusedScene:"), with: scene)
      }
      return
    }
    super.sceneDidDisconnect(scene)
  }

  override func sceneDidBecomeActive(_ scene: UIScene) {
    if queuePlayerIsRefused(scene) { return }
    super.sceneDidBecomeActive(scene)
  }

  override func sceneWillResignActive(_ scene: UIScene) {
    if queuePlayerIsRefused(scene) { return }
    super.sceneWillResignActive(scene)
  }

  override func sceneWillEnterForeground(_ scene: UIScene) {
    if queuePlayerIsRefused(scene) { return }
    super.sceneWillEnterForeground(scene)
  }

  override func sceneDidEnterBackground(_ scene: UIScene) {
    if queuePlayerIsRefused(scene) { return }
    super.sceneDidEnterBackground(scene)
  }
  ${SCENE_DELEGATE_MARKER_END}
`;

const UIKIT_IMPORT_MARKER = '// react-native-queue-player: UIKit import';

const SCENE_DELEGATE_HEADER =
  /^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|internal|open|final)\s+)*class\s+\w+\s*:\s*ExpoAppSceneDelegate\b[^{]*\{/m;

const SDK_VERSION_MESSAGE =
  '[react-native-queue-player] Version 3 requires Expo SDK 58 or later. For Expo SDK 57 and ' +
  'earlier, use react-native-queue-player 2 (2.0.4-rc.6).';

/** Methods the scene delegate block overrides; another plugin overriding one would not compile. */
const SCENE_DELEGATE_OVERRIDES = [
  ['scene(_:willConnectTo:options:)', /\bfunc\s+scene\s*\([^)]*\bwillConnectTo\b/],
  ['sceneDidDisconnect(_:)', /\bfunc\s+sceneDidDisconnect\s*\(/],
  ['sceneDidBecomeActive(_:)', /\bfunc\s+sceneDidBecomeActive\s*\(/],
  ['sceneWillResignActive(_:)', /\bfunc\s+sceneWillResignActive\s*\(/],
  ['sceneWillEnterForeground(_:)', /\bfunc\s+sceneWillEnterForeground\s*\(/],
  ['sceneDidEnterBackground(_:)', /\bfunc\s+sceneDidEnterBackground\s*\(/],
];

/** Removes every generated block between `begin` and `end`, with the newline inserted ahead of it. */
function stripMarkedBlock(contents, begin, end) {
  const block = new RegExp(
    `\\n[ \\t]*${escapeRegex(begin)}[\\s\\S]*?${escapeRegex(end)}\\n`,
    'g'
  );
  return contents.replace(block, '');
}

/**
 * Injects the scene launch blocks into AppDelegate.swift and SceneDelegate.swift. Throws when
 * SceneDelegate.swift does not subclass ExpoAppSceneDelegate, when it already overrides a method
 * the block overrides, or when an anchor is missing.
 */
const withSceneLaunch = (config) =>
  withDangerousMod(config, [
    'ios',
    async (cfg) => {
      const sourceDir = path.join(cfg.modRequest.platformProjectRoot, cfg.modRequest.projectName);
      const sceneDelegatePath = path.join(sourceDir, 'SceneDelegate.swift');
      const appDelegatePath = path.join(sourceDir, 'AppDelegate.swift');

      // Every check runs before either file is written, so a failure here leaves both files untouched.
      let scene = fs.existsSync(sceneDelegatePath)
        ? fs.readFileSync(sceneDelegatePath, 'utf8')
        : '';
      if (!SCENE_DELEGATE_HEADER.test(scene)) {
        throw new Error(
          '[react-native-queue-player] Failed to locate a SceneDelegate class subclassing ' +
            'ExpoAppSceneDelegate in SceneDelegate.swift; the Expo template has likely changed.'
        );
      }
      scene = stripMarkedBlock(scene, SCENE_DELEGATE_MARKER_BEGIN, SCENE_DELEGATE_MARKER_END);
      scene = scene.split(`${UIKIT_IMPORT_MARKER}\nimport UIKit\n`).join('');
      const sceneClose = findClassClosingBraceIndex(scene, SCENE_DELEGATE_HEADER);
      if (sceneClose === -1) {
        throw new Error(
          '[react-native-queue-player] Failed to locate the SceneDelegate class body; the Expo ' +
            'template has likely changed.'
        );
      }
      const sceneBody = scene.slice(scene.match(SCENE_DELEGATE_HEADER).index, sceneClose);
      for (const [name, pattern] of SCENE_DELEGATE_OVERRIDES) {
        if (pattern.test(sceneBody)) {
          throw new Error(
            `[react-native-queue-player] SceneDelegate.swift already overrides ${name}, which ` +
              'react-native-queue-player overrides for CarPlay and Siri launches. Remove that ' +
              'override or the plugin that adds it.'
          );
        }
      }

      if (!fs.existsSync(appDelegatePath)) {
        throw new Error(
          '[react-native-queue-player] AppDelegate.swift not found; the Expo template has likely changed.'
        );
      }
      let app = fs.readFileSync(appDelegatePath, 'utf8');
      app = stripMarkedBlock(app, SCENE_LAUNCH_MARKER_BEGIN, SCENE_LAUNCH_MARKER_END);
      const factoryMatch = app.match(/^[ \t]*reactNativeFactory = factory\n/m);
      if (!factoryMatch || factoryMatch.index === undefined) {
        throw new Error(
          '[react-native-queue-player] Failed to locate `reactNativeFactory = factory` in ' +
            'AppDelegate.swift; the Expo template has likely changed.'
        );
      }

      scene = `${scene.slice(0, sceneClose)}\n${SCENE_DELEGATE_BLOCK}${scene.slice(sceneClose)}`;
      scene = `${UIKIT_IMPORT_MARKER}\nimport UIKit\n${scene}`;
      const afterFactory = factoryMatch.index + factoryMatch[0].length;
      app = `${app.slice(0, afterFactory)}\n${SCENE_LAUNCH_BLOCK}${app.slice(afterFactory)}`;
      fs.writeFileSync(sceneDelegatePath, scene);
      fs.writeFileSync(appDelegatePath, app);
      return cfg;
    },
  ]);

const withQueuePlayer = (config, props = {}) => {
  // `@expo/config` sets `sdkVersion` before plugins run: the app config's value, else the installed
  // `expo` package's major. A missing or non-numeric value skips the check.
  if (parseInt(String(config.sdkVersion), 10) < 58) {
    throw new Error(SDK_VERSION_MESSAGE);
  }
  // Background audio is on by default; a consumer that manages
  // UIBackgroundModes itself can opt out with `enableIosBGAudio: false`.
  const enableIosBGAudio = !props || props.enableIosBGAudio !== false;
  let next = withInfoPlist(config, (cfg) => {
    cfg.modResults.AVInitialRouteSharingPolicy = 'LongFormAudio';
    if (enableIosBGAudio) {
      const modes = Array.isArray(cfg.modResults.UIBackgroundModes)
        ? cfg.modResults.UIBackgroundModes
        : [];
      if (!modes.includes('audio')) modes.push('audio');
      cfg.modResults.UIBackgroundModes = modes;
    }
    return cfg;
  });

  const carplay = Boolean(props && props.carplay === true);
  const siri = Boolean(props && props.siri === true);

  if (carplay || siri) {
    next = withSceneManifest(next, { carplay });
  }

  if (carplay) {
    next = withCarPlayEntitlement(next);
  }

  if (siri) {
    next = withSiriInfoPlist(next);
    next = withSiriEntitlement(next);
    next = withSiriAppDelegate(next);
  }

  if (props && props.chromecast === true) {
    next = withChromecastSubspecPodfile(next);
    next = withChromecastInfoPlist(next, props.chromecastReceiverAppId);
    next = withChromecastAppDelegate(next);
  }

  // Registered last because the mod registered last runs first: its checks then throw before the
  // Siri and Chromecast mods have edited AppDelegate.swift or the Podfile.
  if (carplay || siri) {
    next = withSceneLaunch(next);
  }

  return next;
};

module.exports = withQueuePlayer;
