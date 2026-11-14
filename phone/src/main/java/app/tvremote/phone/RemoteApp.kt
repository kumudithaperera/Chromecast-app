package app.tvremote.phone

import android.app.Application
import app.tvremote.phone.data.BrowserLink
import app.tvremote.phone.data.Prefs
import app.tvremote.phone.data.TvController
import app.tvremote.phone.data.TvDiscovery
import app.tvremote.phone.data.WatchNotes
import app.tvremote.phone.widget.RemoteWidget
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class RemoteApp : Application() {
    val controller by lazy { TvController(this) }
    val discovery by lazy { TvDiscovery(this) }
    val prefs by lazy { Prefs(this) }
    val browser by lazy { BrowserLink(prefs) }
    val notes by lazy { WatchNotes(this) }

    override fun onCreate() {
        super.onCreate()
        // Keep any home-screen widget in step with the remote: TV name, connected lamp, app on the TV,
        // shortcuts. Only what the widget shows counts, so volume changes don't redraw it.
        controller.scope.launch {
            combine(controller.state, controller.shortcuts) { s, shortcuts ->
                listOf(s.tv, s.connection::class, s.currentApp, s.poweredOn, shortcuts)
            }.distinctUntilChanged().collect { RemoteWidget.refresh(this@RemoteApp) }
        }
    }
}
