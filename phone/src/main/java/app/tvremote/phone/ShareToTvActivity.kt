package app.tvremote.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives "Share" from other phone apps and opens the link on the TV.
 * The TV opens it in whichever app handles that link (a YouTube link opens
 * YouTube). Plain web pages open in the TV Browser app when it is installed.
 */
class ShareToTvActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val link = shared?.let(::extractLink)
        if (link == null) {
            Toast.makeText(this, "No link found to open on the TV", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = application as RemoteApp
        val context = applicationContext
        if (app.controller.state.value.tv == null) {
            Toast.makeText(this, "Open TV Remote and pick your TV first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, "Sending to TV…", Toast.LENGTH_SHORT).show()
        // Runs in the app-wide scope so it finishes even after this screen closes.
        app.controller.scope.launch {
            val ok = app.controller.launchWhenReady(link)
            withContext(Dispatchers.Main) {
                val msg = if (ok) "Opened on TV" else "Couldn't reach the TV. Open TV Remote to check the connection."
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")

        fun extractLink(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
    }
}
