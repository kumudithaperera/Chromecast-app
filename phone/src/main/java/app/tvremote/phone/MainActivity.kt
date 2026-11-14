package app.tvremote.phone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.tvremote.phone.atv.Keys
import app.tvremote.phone.ui.AppRoot
import app.tvremote.phone.ui.TvRemoteTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as RemoteApp

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@registerForActivityResult
        app.controller.text(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always the dark canvas palette, so the system bar icons stay light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            TvRemoteTheme {
                AppRoot(
                    controller = app.controller,
                    discovery = app.discovery,
                    browser = app.browser,
                    prefs = app.prefs,
                    notes = app.notes,
                    onVoice = ::startVoiceTyping,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.controller.onForeground()
    }

    override fun onStop() {
        super.onStop()
        app.controller.onBackground()
    }

    private fun startVoiceTyping() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV")
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No speech recognition app on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    // The phone's volume buttons change the TV's volume while the remote is open.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val tvKey = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Keys.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Keys.VOLUME_DOWN
            else -> null
        }
        if (tvKey != null && app.controller.isConnected) {
            app.controller.key(tvKey)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            app.controller.isConnected
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
