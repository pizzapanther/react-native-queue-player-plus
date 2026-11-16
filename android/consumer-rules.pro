# React Native Queue Player — consumer R8 / ProGuard rules.
#
# Packaged into the AAR via `consumerProguardFiles` and applied
# automatically to a consuming app's R8 run, so consumers need no manual rules
# for this library's native bindings or its transitive dependencies.
#
# The keeps below are deliberately whole-class rather than member-scoped. The
# saving from narrowing them is a few kilobytes; the cost of getting one wrong
# is a silent runtime failure in someone else's shipped app.

# libraop's JNI_OnLoad binds this class by literal descriptor: src/main/cpp/
# airplay_jni.cpp calls FindClass on the string
# "com/margelo/nitro/queueplayer/cast/airplay/AirplayJNI", then resolves all 19
# entries of its JNINativeMethod table by name and signature. React Native's own
# rules pin the native method names but let the class be renamed, and the rule
# that pins the class name ships only in AGP's optional default ProGuard file.
# When the class is renamed, FindClass returns null, RegisterNatives never runs,
# and AirPlay 1 reports itself unavailable rather than failing loudly.
-keep class com.margelo.nitro.queueplayer.cast.airplay.AirplayJNI { *; }

# libairplay2 binds this class the same way (src/main/cpp/airplay2_jni.cpp) and
# additionally calls back into Kotlin: the audio drain thread invokes
# onNativeSessionDied(long, int), resolved through GetStaticMethodID on that
# literal name. Nothing in Kotlin calls that method, so without this rule R8
# deletes it as unreachable — the native side then logs that upcalls are
# disabled and carries on. Connect and streaming still work; only the receiver's
# feedback timeout stops arriving, so a dead receiver never tears its session
# down and never reverts routing to local. Playback stops with the UI still
# showing the remote device.
-keep class com.margelo.nitro.queueplayer.cast.airplay.AirPlay2JNI { *; }

# libsignalsmith_stretch has no JNI_OnLoad; src/main/cpp/stretch_jni.cpp relies
# on implicit JNI name mangling, so its exported symbols spell out both the
# package path and the method name (Java_com_margelo_nitro_queueplayer_
# SignalsmithStretchNative_nativeCreate). Renaming either half breaks the lookup
# and the `music` pitch-correction mode throws UnsatisfiedLinkError on first use.
# The native methods live on this class, not on its Companion.
-keep class com.margelo.nitro.queueplayer.SignalsmithStretchNative { *; }

# Named as a string in this library's own AndroidManifest.xml, through the cast
# OPTIONS_PROVIDER_CLASS_NAME meta-data. aapt2 generates keep rules for manifest
# components but does not descend into meta-data values, so nothing automatic
# protects this one. The Play Services Cast AAR happens to keep it today via
# `-keep public class * implements OptionsProvider`; this rule means correctness
# does not depend on another artifact's rule surviving.
-keep class com.margelo.nitro.queueplayer.cast.chromecast.ChromecastOptionsProvider { *; }

# Named as a string in this library's own AndroidManifest.xml, as the
# androidx.startup meta-data entry that registers it with
# InitializationProvider. Same shape as the cast options provider above and
# equally invisible to aapt2, which generates keep rules for manifest
# components but not for meta-data values. androidx.startup keeps it today via
# `-keepnames class * extends androidx.startup.Initializer`; this rule means
# correctness does not depend on that.
-keep class com.margelo.nitro.queueplayer.cast.airplay.AirPlayBackendInitializer { *; }

# The Nitrogen-generated enums are read from C++ by constant name — e.g.
# getStaticField<JCastProtocol>("AIRPLAY") — so losing a constant's name breaks
# every conversion across the bridge. They survive today only because
# androidx.annotation ships a keep rule for @Keep-annotated classes and Nitrogen
# annotates what it generates, which makes correctness depend on that artifact
# staying in the resolution graph. `**` also matches the enums internal to this
# library that native code never touches; keeping them is accepted collateral.
-keep enum com.margelo.nitro.queueplayer.** { *; }

# Constructed from C++ during Nitro's onLoad via getConstructor<javaobject()>().
# R8's compatibility FAQ states that full mode does not implicitly keep a kept
# class's no-arg constructor; AGP's keep-rule documentation states that it does.
# Current R8 keeps it. These rules make the library correct under either.
-keep class com.margelo.nitro.queueplayer.TrackPlayer { <init>(); }
-keep class com.margelo.nitro.queueplayer.Equalizer { <init>(); }
-keep class com.margelo.nitro.queueplayer.Visualizer { <init>(); }
-keep class com.margelo.nitro.queueplayer.CastManager { <init>(); }

# The embedded Ktor CIO HTTP server (the Chromecast local media server, which
# serves local files to a receiver) ships
# io.ktor.util.debug.IntellijIdeaDebugDetector, which references
# java.lang.management.ManagementFactory and RuntimeMXBean to detect an attached
# IntelliJ debugger. Those JMX classes are JVM-only and absent on Android, so a
# consumer's R8 release build (full mode treats missing classes as errors) fails
# on the missing references. The detector is debug tooling that never executes
# at runtime on Android; ignore the missing classes rather than fail the build.
-dontwarn java.lang.management.**
