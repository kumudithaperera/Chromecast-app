package app.tvremote.phone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.tvremote.phone.MainActivity
import app.tvremote.phone.R
import app.tvremote.phone.RemoteApp
import app.tvremote.phone.atv.Keys
import app.tvremote.phone.data.Connection
import app.tvremote.phone.data.Shortcut
import app.tvremote.phone.data.UiState
import app.tvremote.phone.ui.LOGOS
import app.tvremote.phone.ui.appLabel
import app.tvremote.phone.ui.brandOf

/** What a widget button does. Only these names travel in intents; nothing else is accepted. */
enum class WidgetAction(val key: Int?) {
    POWER(Keys.POWER),
    PLAY_PAUSE(Keys.MEDIA_PLAY_PAUSE),
    VOLUME_DOWN(Keys.VOLUME_DOWN),
    VOLUME_UP(Keys.VOLUME_UP),
    MUTE(Keys.MUTE),
    HOME(Keys.HOME),

    /** Opens shortcut number EXTRA_INDEX from the Apps tab. */
    APP(null),
}

/**
 * The home-screen remote. This provider is exported because the launcher has to reach it, so it only
 * draws; presses go to [WidgetActionReceiver], which no other app can call.
 */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, manager, it)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        manager.updateAppWidget(id, views(context, manager, id))
    }

    companion object {
        /** Redraws every placed widget with the remote's current state. Cheap when none are placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RemoteWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context, manager, it)) }
        }

        private fun views(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
            val app = context.applicationContext as RemoteApp
            val state = app.controller.state.value
            val shortcuts = app.controller.shortcuts.value
            val small = build(context, R.layout.widget_remote_small, state, shortcuts)
            val medium = build(context, R.layout.widget_remote_medium, state, shortcuts)
            val full = build(context, R.layout.widget_remote_full, state, shortcuts)
            if (Build.VERSION.SDK_INT >= 31) {
                // The launcher picks the biggest layout that fits as the widget is resized.
                return RemoteViews(
                    mapOf(
                        SizeF(180f, 110f) to small,
                        SizeF(250f, 190f) to medium,
                        SizeF(250f, 300f) to full,
                    ),
                )
            }
            val h = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            return when {
                h >= 300 -> full
                h >= 190 -> medium
                else -> small
            }
        }

        private fun build(context: Context, layout: Int, state: UiState, shortcuts: List<Shortcut>): RemoteViews {
            val v = RemoteViews(context.packageName, layout)
            val tv = state.tv
            val connected = state.connection is Connection.Connected

            v.setTextViewText(R.id.widget_tv_name, tv?.name ?: context.getString(R.string.widget_no_tv))
            val lamp = when {
                tv == null -> R.color.widget_dim
                connected -> R.color.widget_ok
                state.connection is Connection.Failed -> R.color.widget_fault
                else -> R.color.widget_dim
            }
            v.setInt(R.id.widget_dot, "setColorFilter", context.getColor(lamp))
            val onTv = state.currentApp?.let(::appLabel)?.takeIf { connected }
            v.setTextViewText(
                R.id.widget_status,
                when {
                    tv == null -> "Tap to set up"
                    onTv != null -> onTv
                    connected -> "Connected"
                    // Not an error: the app connects on the first press and lets go 30 s later.
                    else -> "Tap a button"
                },
            )
            v.setTextViewText(R.id.sub_power, if (connected && state.poweredOn == false) "Off" else if (connected && state.poweredOn == true) "On" else "TV")
            v.setTextViewText(R.id.sub_play, onTv ?: "Media")

            v.setOnClickPendingIntent(R.id.widget_header, openApp(context))
            val buttons = mapOf(
                R.id.tile_power to WidgetAction.POWER,
                R.id.tile_play to WidgetAction.PLAY_PAUSE,
                R.id.tile_vol_down to WidgetAction.VOLUME_DOWN,
                R.id.tile_vol_up to WidgetAction.VOLUME_UP,
                R.id.tile_mute to WidgetAction.MUTE,
                R.id.tile_home to WidgetAction.HOME,
            )
            buttons.forEach { (viewId, action) ->
                // With no TV chosen yet, every button opens the app to choose one.
                v.setOnClickPendingIntent(viewId, if (tv == null) openApp(context) else press(context, action, 0))
            }

            val slots = listOf(
                Triple(R.id.app_0, R.id.app_0_logo, R.id.app_0_text),
                Triple(R.id.app_1, R.id.app_1_logo, R.id.app_1_text),
                Triple(R.id.app_2, R.id.app_2_logo, R.id.app_2_text),
                Triple(R.id.app_3, R.id.app_3_logo, R.id.app_3_text),
            )
            slots.forEachIndexed { i, (slot, logo, text) ->
                val s = shortcuts.getOrNull(i)
                if (s == null) {
                    v.setViewVisibility(slot, View.INVISIBLE)
                    return@forEachIndexed
                }
                v.setViewVisibility(slot, View.VISIBLE)
                val res = LOGOS[brandOf(s.name) ?: brandOf(s.link)]
                if (res != null) {
                    v.setImageViewResource(logo, res)
                    v.setViewVisibility(logo, View.VISIBLE)
                    v.setViewVisibility(text, View.GONE)
                } else {
                    v.setViewVisibility(logo, View.GONE)
                    v.setViewVisibility(text, View.VISIBLE)
                    v.setTextViewText(text, s.name)
                }
                v.setContentDescription(slot, "Open ${s.name} on the TV")
                v.setOnClickPendingIntent(slot, if (tv == null) openApp(context) else press(context, WidgetAction.APP, i))
            }
            return v
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** An explicit, immutable intent to the app's own private receiver. */
        private fun press(context: Context, action: WidgetAction, index: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            // Each button needs its own request code, or the system would reuse one PendingIntent for all.
            action.ordinal * 16 + index + 1,
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_PRESS)
                .putExtra(WidgetActionReceiver.EXTRA_ACTION, action.name)
                .putExtra(WidgetActionReceiver.EXTRA_INDEX, index),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
