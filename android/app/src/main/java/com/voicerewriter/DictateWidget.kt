package com.voicerewriter

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
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

    companion object {
        /**
         * Hand every placed widget its views again. After an app update the lock screen (One UI)
         * was left on "loading widget info" until the widget was removed and added back; the
         * views a host holds from the old APK are not ones to rely on, so the update, a reboot,
         * the bubble service starting and the app opening all push fresh ones.
         */
        fun refreshAll(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(ctx, DictateWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(ctx))
            } catch (e: Exception) {
                android.util.Log.w("DictateWidget", "refresh failed", e)
            }
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
}
