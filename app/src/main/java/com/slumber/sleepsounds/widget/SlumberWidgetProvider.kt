package com.slumber.sleepsounds.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.slumber.sleepsounds.MainActivity
import com.slumber.sleepsounds.R
import com.slumber.sleepsounds.audio.NoiseType
import com.slumber.sleepsounds.service.SleepSoundService

/**
 * Home-screen widget: shows what's currently playing and a Play/Pause button, same as the
 * notification. Tapping Play/Pause talks straight to SleepSoundService -- it does NOT open
 * the app, which is what US/UK/AU/CA users expect from a widget.
 *
 * SleepSoundService calls updateAllWidgets() every time playback state changes, so this
 * widget always reflects the truth without polling.
 */
class SlumberWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Called by SleepSoundService whenever play state or the sound mix changes. */
        fun updateAllWidgets(context: Context, isPlaying: Boolean, activeSounds: Set<NoiseType>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SlumberWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                manager.updateAppWidget(id, buildRemoteViews(context, isPlaying, activeSounds))
            }
        }

        private fun buildRemoteViews(context: Context, isPlaying: Boolean, activeSounds: Set<NoiseType>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_slumber)

            val label = when {
                activeSounds.isEmpty() -> "Tap a sound to start"
                activeSounds.size == 1 -> "${activeSounds.first().emoji} ${activeSounds.first().displayName}"
                else -> "${activeSounds.size} sounds mixing"
            }
            views.setTextViewText(R.id.widgetLabel, label)
            views.setImageViewResource(
                R.id.widgetPlayPause,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            val playPauseIntent = PendingIntent.getService(
                context, 10,
                Intent(context, SleepSoundService::class.java).setAction(SleepSoundService.ACTION_PLAY_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetPlayPause, playPauseIntent)

            // Tapping the label/background opens the app, same convention as most widgets.
            val openAppIntent = PendingIntent.getActivity(
                context, 11,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent)

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // A freshly-placed widget has no live state to show yet; ask the service directly
        // isn't possible without binding, so show a sensible default until the next real
        // state change republishes it (which happens immediately if a sound is playing,
        // since the service always calls updateAllWidgets() on every state change).
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, isPlaying = false, activeSounds = emptySet()))
        }
    }
}
