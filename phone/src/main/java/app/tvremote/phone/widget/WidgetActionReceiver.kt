package app.tvremote.phone.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.tvremote.phone.RemoteApp
import app.tvremote.phone.data.TvController
import kotlinx.coroutines.launch

/**
 * Handles widget button presses. Not exported (see the manifest): only the app's own PendingIntents,
 * fired by the launcher on the app's behalf, can reach it.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRESS) return
        val action = WidgetAction.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ACTION) } ?: return
        val app = context.applicationContext as RemoteApp
        val controller = app.controller

        // The link is read from the app's own list at press time; the intent only says which one.
        val link = if (action == WidgetAction.APP) {
            controller.shortcuts.value.getOrNull(intent.getIntExtra(EXTRA_INDEX, -1))?.link ?: return
        } else {
            null
        }

        val pending = goAsync()
        controller.scope.launch {
            try {
                val outcome = controller.whenReady {
                    if (link != null) launch(link) else action.key?.let(::key)
                }
                when (outcome) {
                    TvController.Outcome.Sent -> Unit
                    TvController.Outcome.NoTv -> toast(app, "Open TV Remote to choose a TV")
                    TvController.Outcome.NeedsPairing -> toast(app, "Open TV Remote to pair with the TV again")
                    TvController.Outcome.Unreachable -> toast(app, "Can't reach the TV. Is it on the same Wi-Fi?")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val ACTION_PRESS = "app.tvremote.phone.widget.PRESS"
        const val EXTRA_ACTION = "action"
        const val EXTRA_INDEX = "index"
    }
}
