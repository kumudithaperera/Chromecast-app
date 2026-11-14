package app.tvremote.tv

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tvremote.protocol.VideoAction
import app.tvremote.protocol.VideoState
import app.tvremote.protocol.parseVideo
import java.util.Locale

/**
 * Lets the phone drive the video playing in a tab, including players inside iframes.
 *
 * A small script runs in every frame of every page. If the frame has a <video> it posts a status line
 * to [LISTENER_NAME]; the script also listens on the same object for commands. Pages can see and call
 * that object, so the TV treats everything from it as untrusted input:
 *  - a status line is only numbers, is length-capped and goes through [parseVideo], which clamps them;
 *  - nothing a page sends is ever executed, stored as text or shown;
 *  - the only thing sent back is one of the fixed commands built in [send].
 * A page can at worst show the phone a wrong position or play state.
 */
class VideoBridge(private val web: WebView, private val onChanged: () -> Unit) {

    private class Frame(var proxy: JavaScriptReplyProxy, var state: VideoState?, var seenAt: Long)

    private val frames = HashMap<String, Frame>()

    /** False on a WebView too old for frame-wide scripts; the phone then gets only fullscreen on/off. */
    val supported: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    init {
        if (supported) {
            WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, setOf("*"))
            WebViewCompat.addWebMessageListener(web, LISTENER_NAME, setOf("*")) { _, message, _, _, proxy ->
                handle(message, proxy)
            }
        }
    }

    private fun handle(message: WebMessageCompat, proxy: JavaScriptReplyProxy) {
        val data = message.data ?: return
        if (data.length > MAX_LINE) return
        // "V <frame id> <playing> <pos> <dur> <muted> <rate> <fullscreen>" or "V <frame id> -"
        val parts = data.split(' ')
        // Exactly the six fields the script sends: the extra ones (subtitles, "TV player") are the TV's own to set.
        if ((parts.size != 3 && parts.size != 8) || parts[0] != "V" || !FRAME_ID.matches(parts[1])) return
        val id = parts[1]
        val now = SystemClock.uptimeMillis()
        val update = parseVideo("VIDEO " + parts.drop(2).joinToString(" ")) ?: return
        val frame = frames[id]
        if (frame != null) {
            if (now - frame.seenAt < MIN_GAP_MS && frame.state == update.state) return
            frame.proxy = proxy
            frame.state = update.state
            frame.seenAt = now
        } else {
            if (frames.size >= MAX_FRAMES) frames.entries.minByOrNull { it.value.seenAt }?.let { frames.remove(it.key) }
            frames[id] = Frame(proxy, update.state, now)
        }
        onChanged()
    }

    /** The frame whose video the phone should control: fullscreen first, then playing, then most recent. */
    private fun active(): Frame? {
        val now = SystemClock.uptimeMillis()
        frames.values.removeAll { now - it.seenAt > STALE_MS }
        return frames.values
            .filter { it.state != null }
            .maxWithOrNull(
                compareBy<Frame>({ it.state!!.fullscreen }, { it.state!!.playing }, { it.seenAt }),
            )
    }

    /** The video the phone controls, or null if no frame has one. */
    val video: VideoState? get() = active()?.state

    /** Forgets everything, for when the page changes. */
    fun reset() {
        frames.clear()
    }

    /** Sends one command to the active player. Returns false if there is none to send it to. */
    fun send(action: VideoAction, value: Float = 0f): Boolean {
        val frame = active() ?: return false
        val text = when (action) {
            VideoAction.SUBTITLE_SHIFT -> return false // page players have no subtitle timing we can reach
            VideoAction.TOGGLE -> "toggle"
            VideoAction.MUTE -> "mute"
            VideoAction.EXIT_FULLSCREEN -> "exitfs"
            VideoAction.SEEK -> "seek ${num(value)}"
            VideoAction.SKIP -> "skip ${num(value)}"
            VideoAction.RATE -> "rate ${num(value)}"
        }
        frame.proxy.postMessage(text)
        return true
    }

    private fun num(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    companion object {
        const val LISTENER_NAME = "tvbridge"
        private const val MAX_LINE = 120
        private const val MAX_FRAMES = 12
        private const val MIN_GAP_MS = 60L
        private const val STALE_MS = 3_000L
        private val FRAME_ID = Regex("[a-z0-9]{4,12}")

        /** Runs at document start in every frame. Reports the best <video> and obeys the fixed commands. */
        private const val SCRIPT = """
(function () {
  var bridge = window.tvbridge;
  if (!bridge || window.__tvbInit) return;
  window.__tvbInit = true;
  var id = Math.random().toString(36).slice(2, 10);
  var last = '', lastAt = 0, cur = null;
  function fsEl() { return document.fullscreenElement || document.webkitFullscreenElement; }
  function pick() {
    var fs = fsEl();
    if (fs) {
      if (fs.tagName === 'VIDEO') return fs;
      var inner = fs.querySelector && fs.querySelector('video');
      if (inner) return inner;
    }
    var best = null, bestScore = -1, vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.readyState === 0 && v.paused) continue;
      var r = v.getBoundingClientRect(), score = (v.paused ? 0 : 1e9) + r.width * r.height;
      if (score > bestScore) { best = v; bestScore = score; }
    }
    return best;
  }
  // Players such as YouTube's keep their own speed setting and put it back when the video element is
  // changed from outside. So a speed from the phone goes through the player's own API where there is
  // one, and for a few seconds any other change to that video is undone.
  var want = 0, wantEl = null, wantUntil = 0, undone = 0;
  function setRate(v, n) {
    n = Math.min(4, Math.max(0.25, n));
    want = n; wantEl = v; wantUntil = Date.now() + 8000; undone = 0;
    try {
      var yt = document.getElementById('movie_player');
      if (yt && yt.setPlaybackRate && yt.contains(v)) yt.setPlaybackRate(n);
    } catch (err) {}
    v.playbackRate = n;
  }
  function holdRate(e) {
    var v = e.target;
    if (v !== wantEl || Date.now() > wantUntil || undone >= 20 || Math.abs(v.playbackRate - want) < 0.001) return;
    undone++;
    setTimeout(function () { if (Math.abs(v.playbackRate - want) >= 0.001) v.playbackRate = want; }, 0);
  }
  function num(x) { return isFinite(x) ? Math.round(x * 10) / 10 : -1; }
  function snapshot(v) {
    return ['V', id, (v.paused || v.ended) ? 0 : 1, num(v.currentTime), num(v.duration), v.muted ? 1 : 0, v.playbackRate, fsEl() ? 1 : 0].join(' ');
  }
  function tick() {
    cur = pick();
    if (!cur) { if (last) { last = ''; bridge.postMessage('V ' + id + ' -'); } return; }
    var s = snapshot(cur), now = Date.now();
    // Also repeat once a second while paused: the TV drops a frame that has been silent for 3 s.
    if (s !== last || !cur.paused || now - lastAt > 1000) { last = s; lastAt = now; bridge.postMessage(s); }
  }
  bridge.onmessage = function (e) {
    var c = String(e.data).split(' '), v = cur || pick(), n = parseFloat(c[1]);
    try {
      if (c[0] === 'exitfs') {
        var exit = document.exitFullscreen || document.webkitExitFullscreen;
        if (exit) exit.call(document);
      } else if (v) {
        if (c[0] === 'toggle') { if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else { v.pause(); } }
        else if (c[0] === 'seek' && isFinite(n)) { v.currentTime = Math.max(0, n); }
        else if (c[0] === 'skip' && isFinite(n)) { v.currentTime = Math.max(0, v.currentTime + n); }
        else if (c[0] === 'mute') { v.muted = !v.muted; }
        else if (c[0] === 'rate' && isFinite(n)) { setRate(v, n); }
      }
    } catch (err) {}
    setTimeout(tick, 50);
  };
  setInterval(tick, 500);
  ['play', 'pause', 'seeked', 'volumechange', 'ratechange', 'ended', 'fullscreenchange'].forEach(function (t) {
    document.addEventListener(t, function () { setTimeout(tick, 0); }, true);
  });
  document.addEventListener('ratechange', holdRate, true);
})();
"""
    }
}
