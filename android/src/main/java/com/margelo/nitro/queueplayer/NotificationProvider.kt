package com.margelo.nitro.queueplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider

/**
 * Custom [androidx.media3.session.MediaNotification.Provider] that
 * pins the lib's NotificationChannel ID + notification ID so the
 * media-style foreground notification posts under the channel created
 * by [PlaybackService.onCreate].
 *
 * Subclassing [DefaultMediaNotificationProvider] (rather than building
 * a provider from scratch) keeps the standard prev / play-pause / next
 * compact-view rendering, the expanded-view artwork + scrub progress,
 * and Media3's default action-mapping behaviour. The compact-view
 * button priority is expressed by the session's
 * `setMediaButtonPreferences(...)` builder call; this provider reads
 * those preferences directly.
 *
 * This provider only pins the channel + notification id — it does NOT
 * restyle transport buttons. On API 33+ the System UI media control is
 * derived from the media session, so `MediaNotification.Provider` layout
 * changes take effect only pre-33; transport customization belongs in the
 * session's available commands + media button preferences, never here.
 *
 * Artwork loading is wired separately on the [androidx.media3.session.MediaSession]
 * via `setBitmapLoader(...)` — the provider routes artwork requests
 * through that loader chain
 * ([PlaceholderFallbackBitmapLoader] →
 *  [androidx.media3.session.CacheBitmapLoader] →
 *  [androidx.media3.datasource.DataSourceBitmapLoader]) so the
 * notification always receives a bitmap, never the system "music note"
 * generic.
 */
@UnstableApi
internal class NotificationProvider(context: Context) :
  DefaultMediaNotificationProvider(
    context,
    NotificationIdProvider { PlaybackService.NOTIFICATION_ID },
    PlaybackService.NOTIFICATION_CHANNEL_ID,
    R.string.rnqp_playback_channel_name,
  )
