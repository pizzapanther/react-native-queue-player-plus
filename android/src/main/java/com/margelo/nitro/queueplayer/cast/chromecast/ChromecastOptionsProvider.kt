package com.margelo.nitro.queueplayer.cast.chromecast

import android.content.Context
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.margelo.nitro.queueplayer.R

/**
 * [OptionsProvider] — Cast SDK reflectively instantiates this via the
 * `OPTIONS_PROVIDER_CLASS_NAME` meta-data entry in our AndroidManifest.
 *
 * - **Receiver app ID** from `R.string.queue_player_chromecast_receiver_app_id`.
 *   Default is Google's Default Media Receiver (`CC1AD845`). Host apps
 *   override by declaring their own string resource of the same name.
 * - `setAndroidReceiverCompatible(true)` for Stream Transfer support.
 * - `setRemoteToLocalEnabled(true)` for resume-on-phone.
 * - `setResumeSavedSession(false)` — avoids auto-reconnect complications.
 * - `setMediaSessionEnabled(false)` — Media3 MediaSession owns the
 *   session surface; duplicate would confuse system UI.
 */
class ChromecastOptionsProvider : OptionsProvider {

  override fun getCastOptions(context: Context): CastOptions {
    val appId = context.getString(R.string.queue_player_chromecast_receiver_app_id)

    val notificationOptions = NotificationOptions.Builder().build()
    val mediaOptions = CastMediaOptions.Builder()
      .setNotificationOptions(notificationOptions)
      .setMediaSessionEnabled(false)
      .build()

    val launchOptions = LaunchOptions.Builder()
      .setAndroidReceiverCompatible(true)
      .build()

    return CastOptions.Builder()
      .setReceiverApplicationId(appId)
      .setLaunchOptions(launchOptions)
      .setCastMediaOptions(mediaOptions)
      .setResumeSavedSession(false)
      .setEnableReconnectionService(false)
      .setRemoteToLocalEnabled(true)
      .build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
