package com.margelo.nitro.queueplayer.cast

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.queueplayer.AudioRoute
import com.margelo.nitro.queueplayer.AudioRouteKind
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide observer of the currently active audio output. Bridges
 * Android's [AudioManager.registerAudioDeviceCallback] +
 * [AudioManager.getDevices] surface to the lib's cross-platform
 * `AudioRoute` shape.
 *
 * Android does not expose a single "where is music going right now"
 * API for the idle-player case. The active output is inferred from the
 * connected device list via a priority scan:
 *   LE Audio  →  classic A2DP  →  USB  →  wired  →  HDMI  →  dock /
 *   line  →  TYPE_BUS (AAOS)  →  built-in speaker.
 *
 * `AudioTrack.getRoutedDevice()` would give a perfect answer for the
 * actively-playing case but requires deep ExoPlayer hooks
 * (`AnalyticsListener.onAudioSessionIdChanged` + reflection); the
 * prioritized scan covers ~95% of cases without that coupling.
 *
 * Bluetooth product names degrade to a generic string on Android 12+
 * without the `BLUETOOTH_CONNECT` runtime permission — consumer apps
 * decide whether to request it; the route still reports the right
 * kind regardless.
 */
internal class AudioRouteState(private val context: Context) {

  private val audioManager: AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private val mainHandler = Handler(Looper.getMainLooper())

  private val listeners = CopyOnWriteArraySet<(AudioRoute) -> Unit>()

  @Volatile
  private var cached: AudioRoute = computeCurrent()

  private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
      recompute()
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
      recompute()
    }
  }

  private var isObserving = false

  fun start() {
    if (isObserving) return
    isObserving = true
    audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    recompute()
  }

  /** Snapshot the current route. Safe to call from any thread. */
  fun currentRoute(): AudioRoute = cached

  /**
   * Subscribe to route changes. Fires once on the main thread with the
   * current route (consistent with the threading of subsequent
   * updates), then on every subsequent change. Returns a disposer that
   * removes the subscription.
   */
  fun addListener(callback: (AudioRoute) -> Unit): () -> Unit {
    listeners.add(callback)
    val snap = cached
    mainHandler.post { callback(snap) }
    return { listeners.remove(callback) }
  }

  private fun recompute() {
    val next = computeCurrent()
    cached = next
    val snap = next
    mainHandler.post {
      for (l in listeners) l(snap)
    }
  }

  private fun computeCurrent(): AudioRoute {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val active = pickPriority(outputs) ?: return LOCAL_SPEAKER
    return audioRouteFrom(active)
  }

  /**
   * Pick the highest-priority output from the connected list. Order
   * matches Android's own internal music-routing preference: external
   * devices win over built-in speaker, with BT high-quality codecs
   * preferred over wired.
   */
  private fun pickPriority(outputs: Array<AudioDeviceInfo>): AudioDeviceInfo? {
    val order = intArrayOf(
      AudioDeviceInfo.TYPE_BLE_HEADSET,
      AudioDeviceInfo.TYPE_BLE_SPEAKER,
      AudioDeviceInfo.TYPE_BLE_BROADCAST,
      AudioDeviceInfo.TYPE_HEARING_AID,
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      AudioDeviceInfo.TYPE_USB_HEADSET,
      AudioDeviceInfo.TYPE_USB_DEVICE,
      AudioDeviceInfo.TYPE_USB_ACCESSORY,
      AudioDeviceInfo.TYPE_LINE_DIGITAL,
      AudioDeviceInfo.TYPE_WIRED_HEADSET,
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
      AudioDeviceInfo.TYPE_LINE_ANALOG,
      AudioDeviceInfo.TYPE_AUX_LINE,
      AudioDeviceInfo.TYPE_HDMI,
      AudioDeviceInfo.TYPE_HDMI_ARC,
      AudioDeviceInfo.TYPE_HDMI_EARC,
      AudioDeviceInfo.TYPE_DOCK,
      AudioDeviceInfo.TYPE_DOCK_ANALOG,
      AudioDeviceInfo.TYPE_BUS,
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
      AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )
    for (preferredType in order) {
      val match = outputs.firstOrNull { it.type == preferredType }
      if (match != null) return match
    }
    return null
  }

  private fun audioRouteFrom(device: AudioDeviceInfo): AudioRoute {
    val kind = mapKind(device.type)
    val name = device.productName?.toString()?.takeIf { it.isNotEmpty() }
      ?: defaultName(kind)
    val identifier = device.address ?: ""
    return AudioRoute(
      kind = kind,
      name = name,
      identifier = identifier,
      isExternal = kind != AudioRouteKind.SPEAKER && kind != AudioRouteKind.RECEIVER,
      isSystemManaged = true,
    )
  }

  private fun mapKind(type: Int): AudioRouteKind = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioRouteKind.SPEAKER
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.RECEIVER
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_AUX_LINE,
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_DOCK_ANALOG -> AudioRouteKind.WIRED
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioRouteKind.USB
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC -> AudioRouteKind.HDMI
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRouteKind.BLUETOOTHA2DP
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRouteKind.BLUETOOTHHFP
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID -> AudioRouteKind.BLUETOOTHLE
    AudioDeviceInfo.TYPE_BUS -> AudioRouteKind.ANDROIDAUTO
    else -> AudioRouteKind.UNKNOWN
  }

  private fun defaultName(kind: AudioRouteKind): String = when (kind) {
    AudioRouteKind.SPEAKER -> "Phone speaker"
    AudioRouteKind.RECEIVER -> "Phone earpiece"
    AudioRouteKind.WIRED -> "Wired headphones"
    AudioRouteKind.USB -> "USB audio"
    AudioRouteKind.HDMI -> "HDMI audio"
    AudioRouteKind.BLUETOOTHA2DP -> "Bluetooth audio"
    AudioRouteKind.BLUETOOTHLE -> "Bluetooth LE audio"
    AudioRouteKind.BLUETOOTHHFP -> "Bluetooth headset"
    AudioRouteKind.ANDROIDAUTO -> "Android Auto"
    AudioRouteKind.AIRPLAY -> "AirPlay"
    AudioRouteKind.CHROMECAST -> "Chromecast"
    AudioRouteKind.CARPLAY -> "CarPlay"
    AudioRouteKind.UNKNOWN -> "Audio output"
  }

  internal companion object {
    val LOCAL_SPEAKER: AudioRoute = AudioRoute(
      kind = AudioRouteKind.SPEAKER,
      name = "Phone speaker",
      identifier = "",
      isExternal = false,
      isSystemManaged = true,
    )
  }
}
