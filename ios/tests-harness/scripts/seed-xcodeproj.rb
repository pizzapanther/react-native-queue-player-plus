#!/usr/bin/env ruby
#
# ONE-SHOT SEED — not a regenerator.
#
# This script emits a minimal TestHost.xcodeproj the first time the harness
# is set up. The canonical pbxproj is the *checked-in file* produced by
# running this seed once and then letting `pod install` augment it with
# the CocoaPods-managed targets, build phases, xcconfig references, the
# `PrivacyInfo.xcprivacy` resource, and the QueuePlayer + QueuePlayer-Unit-
# Tests schemes.
#
# Re-running this script would clobber those CocoaPods augmentations and
# drift on several build-config settings (notably around
# `SWIFT_ACTIVE_COMPILATION_CONDITIONS`, `GCC_OPTIMIZATION_LEVEL`, the
# `gnu++20` vs `c++20` language-standard flip). If you actually need to
# regenerate the harness project from scratch:
#
#   1. Delete TestHost.xcodeproj + TestHost.xcworkspace + Pods/ + Podfile.lock.
#   2. Run this script.
#   3. Run `pod install`.
#   4. Spot-check git diff on project.pbxproj — re-apply any hand-tuned
#      build-config overrides from git history.
#
# Kept in-repo as documentation of the initial project shape, not as an
# idempotent build step. It intentionally refuses to overwrite an existing
# TestHost.xcodeproj so accidental invocation is a no-op.
#
# Usage: ruby scripts/seed-xcodeproj.rb   (from tests-harness/)

require 'xcodeproj'
require 'fileutils'

HARNESS_ROOT = File.expand_path('..', __dir__)
PROJECT_PATH = File.join(HARNESS_ROOT, 'TestHost.xcodeproj')

if File.exist?(PROJECT_PATH)
  abort "TestHost.xcodeproj already exists. This script is a one-shot " \
        "seed; refusing to overwrite. Delete the project directory first " \
        "if you really want to regenerate (read the header for caveats)."
end

project = Xcodeproj::Project.new(PROJECT_PATH)

target = project.new_target(:application, 'TestHost', :ios, '16.0', nil, :swift)

host_group = project.new_group('TestHost', 'TestHost')
app_ref = host_group.new_reference('App.swift')
_plist_ref = host_group.new_reference('Info.plist')
target.source_build_phase.add_file_reference(app_ref)

project.build_configurations.each do |config|
  config.build_settings.merge!(
    'ALWAYS_SEARCH_USER_PATHS' => 'NO',
    'CLANG_ANALYZER_NONNULL' => 'YES',
    'CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION' => 'YES_AGGRESSIVE',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++20',
    'CLANG_ENABLE_MODULES' => 'YES',
    'CLANG_ENABLE_OBJC_ARC' => 'YES',
    'CLANG_ENABLE_OBJC_WEAK' => 'YES',
    'COPY_PHASE_STRIP' => 'NO',
    'DEBUG_INFORMATION_FORMAT' => 'dwarf',
    'ENABLE_STRICT_OBJC_MSGSEND' => 'YES',
    'ENABLE_USER_SCRIPT_SANDBOXING' => 'YES',
    'GCC_C_LANGUAGE_STANDARD' => 'gnu17',
    'GCC_DYNAMIC_NO_PIC' => 'NO',
    'GCC_NO_COMMON_BLOCKS' => 'YES',
    'IPHONEOS_DEPLOYMENT_TARGET' => '16.0',
    'MTL_FAST_MATH' => 'YES',
    'ONLY_ACTIVE_ARCH' => 'YES',
    'SDKROOT' => 'iphoneos',
    'SWIFT_VERSION' => '5.0',
  )
end

# Debug-only: `-Onone`, `ENABLE_TESTABILITY`, `DEBUG` compilation condition.
project.build_configurations.find { |c| c.name == 'Debug' }&.tap do |debug|
  debug.build_settings.merge!(
    'ENABLE_TESTABILITY' => 'YES',
    'GCC_OPTIMIZATION_LEVEL' => '0',
    'MTL_ENABLE_DEBUG_INFO' => 'INCLUDE_SOURCE',
    'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => 'DEBUG',
    'SWIFT_OPTIMIZATION_LEVEL' => '-Onone',
  )
end

project.build_configurations.find { |c| c.name == 'Release' }&.tap do |release|
  release.build_settings.merge!(
    'MTL_ENABLE_DEBUG_INFO' => 'NO',
    'SWIFT_COMPILATION_MODE' => 'wholemodule',
  )
end

target.build_configurations.each do |config|
  config.build_settings.merge!(
    'ASSETCATALOG_COMPILER_APPICON_NAME' => 'AppIcon',
    'CODE_SIGN_STYLE' => 'Automatic',
    'CURRENT_PROJECT_VERSION' => '1',
    'GENERATE_INFOPLIST_FILE' => 'NO',
    'INFOPLIST_FILE' => 'TestHost/Info.plist',
    'LD_RUNPATH_SEARCH_PATHS' => '$(inherited) @executable_path/Frameworks',
    'MARKETING_VERSION' => '1.0',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'org.ghenry22.RNQueuePlayer.TestHost',
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'SWIFT_EMIT_LOC_STRINGS' => 'YES',
    'TARGETED_DEVICE_FAMILY' => '1,2',
  )
end

schemes_dir = File.join(PROJECT_PATH, 'xcshareddata', 'xcschemes')
FileUtils.mkdir_p(schemes_dir)
scheme = Xcodeproj::XCScheme.new
scheme.configure_with_targets(target, target)
scheme.save_as(PROJECT_PATH, 'TestHost', true)

project.save

puts "Seeded #{PROJECT_PATH}"
puts "Next: run `pod install` to register CocoaPods targets + the QueuePlayer-Unit-Tests scheme."
