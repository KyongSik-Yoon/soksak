package com.voicerewriter

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * One-tap dictation widget (home screen, and the lock screen where the launcher accepts
 * `keyguard` widgets). A tap does exactly what tapping the bubble does: start a take, or stop
 * the one in progress. It goes through [BubbleService] rather than an activity because a
 * widget click lets the app start a foreground service from the background, which is the
 * same moment [BubbleService.enterForeground] can (re)acquire the microphone type.
 */
class DictateWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    private fun views(ctx: Context): RemoteViews {
        val intent = Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_DICTATE)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(ctx, 0, intent, flags)
        } else {
            PendingIntent.getService(ctx, 0, intent, flags)
        }
        return RemoteViews(ctx.packageName, R.layout.widget_dictate).apply {
            setOnClickPendingIntent(R.id.widget_root, tap)
        }
    }
}
