require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "QueuePlayer"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  # Declared explicitly rather than inherited from
  # `min_ios_version_supported`, which resolves lower: AVPlayer and
  # AVPlayerItem only drop their main-thread requirement at iOS 16.
  s.platforms    = { :ios => '16.0' }
  s.source       = { :git => "https://github.com/pizzapanther/react-native-queue-player-plus.git", :tag => "#{s.version}" }

  # NOTE on cross-pod consumer-Swift access:
  #
  # Earlier in Phase 5.5 we set `s.static_framework = true` so consumer
  # Swift code could `import QueuePlayer` directly. That works in
  # isolation but trips a Nitro-pod interop bug: NitroModules's umbrella
  # header (`NitroModules-umbrella.h`) `#import`s C++ `.hpp` files
  # unconditionally — when the consumer's Swift compiler tries to walk
  # the QueuePlayer module's transitive header graph, it hits those
  # `.hpp` files via the umbrella, doesn't know it's in C++ mode, and
  # fails to find `<functional>` etc. Until upstream NitroModules wraps
  # its .hpp imports in `#ifdef __cplusplus` (or ships a private
  # modulemap that excludes them from the public Swift module), the
  # static-framework approach is a build-breaker for any consumer that
  # does `import QueuePlayer` from Swift.
  #
  # Workaround: consumers reach `QueuePlayerMediaIntents` via
  # `NSClassFromString("QueuePlayerMediaIntents")` + `handlerForIntent:`
  # selector dispatch (Objective-C runtime). The class is `@objc` so
  # this works without a module import. The lib's Expo plugin emits
  # the snippet automatically when `{ siri: true }` is enabled; see
  # docs/guides/siri-setup.md for the consumer-side pattern.
  #
  # Reference: NitroModules pod public-header layout (their
  # `*Spec.hpp` files unconditionally `#include <functional>` /
  # `<memory>` / etc.).

  s.source_files = [
    "ios/**/*.{swift}",
    "ios/**/*.{m,mm}",
  ]
  # Exclusions from the main library source set:
  #  - ios/Tests/**/*           — XCTest bundle, picked up by the
  #                               test_spec below.
  #  - ios/tests-harness/**/*   — standalone XCTest host project +
  #                               its CocoaPods/codegen outputs.
  #  - ios/Cast/Chromecast/**/* — opt-in via the `QueuePlayer/
  #                               Chromecast` subspec below. Stays
  #                               out of the default build so consumer
  #                               apps that don't ship Chromecast
  #                               aren't forced to link the ~25 MB
  #                               GoogleCast SDK. Files internally
  #                               `#if canImport(GoogleCast)` guarded
  #                               in case the subspec is missed.
  s.exclude_files = [
    "ios/Tests/**/*",
    "ios/tests-harness/**/*",
    "ios/Cast/Chromecast/**/*",
  ]

  # Opt-in Chromecast subspec. Consumer Podfile adds:
  #   pod 'QueuePlayer/Chromecast', :path => '...'
  # alongside the default `pod 'QueuePlayer'`. The 16.0 floor tracks the
  # library's own and sits above Google Cast SDK 4.8.x's iOS 15.0 minimum.
  s.subspec 'Chromecast' do |ss|
    ss.source_files = 'ios/Cast/Chromecast/**/*.swift'
    ss.ios.deployment_target = '16.0'
    ss.dependency 'google-cast-sdk', '~> 4.8.4'
  end

  # Lock-screen / NowPlayingInfo fallback artwork. Looked up at lib
  # init by ArtworkResolver when the consumer-supplied artworkUrl is
  # null/empty/unreachable. CocoaPods packages this as
  # `QueuePlayer.bundle/RNQP-placeholder.jpg` in the consuming app.
  s.resource_bundles = {
    # `RNQP-placeholder.jpg` — ArtworkResolver fallback.
    # `silence.m4a` — muted loop that holds `AVAudioSession` active
    #   during a Chromecast session so the lock-screen Now Playing
    #   slot stays with the host app (see CastNowPlayingController).
    "QueuePlayer" => ["ios/Resources/**/*"]
  }

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'

  # The shared local-HTTP media server (`ios/MediaServer/MediaHTTPConnection`)
  # serves media over a native `Network.framework` listener — no third-party
  # HTTP server dependency, no `:modular_headers` requirement on the consumer.
  # Two servers use it: Cast's `LocalMediaServer` serves consumer-supplied
  # `file://` URLs to pull-only receivers (Chromecast / future DLNA), and the
  # lookahead cache's `ReadThroughServer` serves the local player from the
  # cache or, on a miss, the origin.

  load 'nitrogen/generated/ios/QueuePlayer+autolinking.rb'
  add_nitrogen_files(s)

  install_modules_dependencies(s)

  # XCTest target for the library. CocoaPods generates a
  # QueuePlayer-Unit-Tests scheme in the consuming workspace; run with:
  #   xcodebuild test -workspace <consumer>.xcworkspace \
  #     -scheme QueuePlayer-Unit-Tests -destination 'platform=iOS Simulator,…'
  s.test_spec "Tests" do |test_spec|
    test_spec.source_files = "ios/Tests/**/*.{swift}"
    # MP3/AAC fixtures consumed by NowPlayingFormatExtractorTests via
    # `Bundle(for: …).url(forResource:withExtension:)`. CocoaPods
    # packages these into the test bundle's resource bundle so the
    # tests can locate them at runtime.
    test_spec.resources = "ios/Tests/Fixtures/*"
    test_spec.dependency "React-Core"
  end
end
