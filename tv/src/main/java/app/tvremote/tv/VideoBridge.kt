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

    // A full-screen request in flight: when it was asked and who wants the tap point.
    private var fullscreenAskedAt = 0L
    private var onFullscreenPoint: ((x: Float, y: Float, viewportWidth: Float) -> Unit)? = null

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
        if (data.startsWith("F ")) {
            fullscreenPoint(data)
            return
        }
        // "V <frame id> <playing> <pos> <dur> <muted> <rate> <fullscreen>" or "V <frame id> -"
        val parts = data.split(' ')
        // Exactly the six fields the script sends.
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

    /**
     * Starts showing the active video full screen. A page may only go full screen in answer to a real
     * tap, so this asks the video's frame where the video is on screen and arms it to turn the next tap
     * into a full-screen request; [onPoint] then gets that point (CSS pixels in the top page, plus the
     * page's width in CSS pixels) and must tap there. Returns false when there is no video to ask.
     */
    fun requestFullscreen(onPoint: (x: Float, y: Float, viewportWidth: Float) -> Unit): Boolean {
        val frame = active() ?: return false
        fullscreenAskedAt = SystemClock.uptimeMillis()
        onFullscreenPoint = onPoint
        frame.proxy.postMessage("fs")
        return true
    }

    /**
     * "F <frame id> <x> <y> <page width>" from the top page. Any frame could send one, so it is only
     * acted on once, within a few seconds of [requestFullscreen], and the caller keeps the tap on the page.
     */
    private fun fullscreenPoint(data: String) {
        val callback = onFullscreenPoint ?: return
        if (SystemClock.uptimeMillis() - fullscreenAskedAt > FULLSCREEN_WINDOW_MS) {
            onFullscreenPoint = null
            return
        }
        val p = data.split(' ')
        if (p.size != 5 || !FRAME_ID.matches(p[1])) return
        val x = p[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return
        val y = p[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: return
        val width = p[4].toFloatOrNull()?.takeIf { it.isFinite() && it in 100f..10_000f } ?: return
        onFullscreenPoint = null
        callback(x, y, width)
    }

    /** Forgets everything, for when the page changes. */
    fun reset() {
        frames.clear()
    }

    /** Sends one command to the active player. Returns false if there is none to send it to. */
    fun send(action: VideoAction, value: Float = 0f): Boolean {
        val frame = active() ?: return false
        val text = when (action) {
            VideoAction.FULLSCREEN -> return false // needs a tap: see requestFullscreen
            VideoAction.TOGGLE -> "toggle"
            VideoAction.MUTE -> "mute"
            VideoAction.EXIT_FULLSCREEN -> "exitfs"
            VideoAction.SEEK -> "seek ${num(value)}"
            VideoAction.SKIP -> "skip ${num(value)}"
            VideoAction.RATE -> "rate ${num(value)}"
            VideoAction.VOLUME -> "vol ${num(value)}"
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
        const val FULLSCREEN_WINDOW_MS = 3_000L
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
  // Many players keep their own speed and put it back on every 'ratechange' (YouTube, and players that
  // snap the rate back to "what the viewer picked"). Fighting them in a loop loses, so once the phone
  // sets a speed, that video's playbackRate property is shadowed: the page reads back its own value and
  // its writes of that same value are ignored, while the real rate stays where the phone put it. A page
  // write of a new value is a speed picked in the page itself, and it wins.
  var nativeRate = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'playbackRate');
  function rateOf(v) { return nativeRate.get.call(v); }
  function lockFor(v) {
    if (v.__tvbRate) return v.__tvbRate;
    var lock = { want: 0, page: rateOf(v), undone: 0 };
    v.__tvbRate = lock;
    try {
      Object.defineProperty(v, 'playbackRate', {
        configurable: true,
        get: function () { return lock.want ? lock.page : rateOf(v); },
        set: function (x) {
          x = +x;
          if (lock.want && Math.abs(x - lock.page) < 0.001) return;
          lock.want = 0;
          lock.page = x;
          nativeRate.set.call(v, x);
        }
      });
    } catch (err) {}
    return lock;
  }
  function setRate(v, n) {
    n = Math.min(4, Math.max(0.25, n));
    var lock = lockFor(v);
    try {
      var yt = document.getElementById('movie_player');
      if (yt && yt.setPlaybackRate && yt.contains(v)) { lock.want = 0; yt.setPlaybackRate(n); }
    } catch (err) {}
    // The page's own speed: nothing to hold.
    lock.want = Math.abs(n - lock.page) < 0.001 ? 0 : n;
    lock.undone = 0;
    nativeRate.set.call(v, n);
  }
  // The browser itself resets the rate when a new source loads; put the phone's speed back.
  function holdRate(e) {
    var v = e.target, lock = v && v.__tvbRate;
    if (!lock || !lock.want || lock.undone >= 50 || Math.abs(rateOf(v) - lock.want) < 0.001) return;
    lock.undone++;
    setTimeout(function () { if (lock.want && Math.abs(rateOf(v) - lock.want) >= 0.001) nativeRate.set.call(v, lock.want); }, 0);
  }
  function setVolume(v, step) {
    var to = Math.min(1, Math.max(0, Math.round((v.volume + step) * 100) / 100));
    try {
      var yt = document.getElementById('movie_player');
      if (yt && yt.setVolume && yt.contains(v)) { yt.setVolume(Math.round(to * 100)); if (step > 0 && yt.unMute) yt.unMute(); }
    } catch (err) {}
    v.volume = to;
    if (step > 0) v.muted = false;
  }
  // Full screen from the phone. A page may only go full screen in answer to a tap, so the video's frame
  // reports where its video is (passed up through each parent frame, which adds where that frame sits),
  // the TV taps there, and this frame turns that one tap into a full-screen request instead of letting
  // the page's player see it.
  var armedEl = null, armedUntil = 0;
  function reportFs(x, y) {
    if (window === window.top) {
      bridge.postMessage('F ' + id + ' ' + Math.round(x) + ' ' + Math.round(y) + ' ' + Math.round(window.innerWidth));
    } else {
      try { window.parent.postMessage({ __tvbFs: [x, y] }, '*'); } catch (err) {}
    }
  }
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || !d.__tvbFs || !e.source) return;
    var fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) {
      if (fr[i].contentWindow === e.source) {
        var r = fr[i].getBoundingClientRect();
        reportFs(r.left + fr[i].clientLeft + (+d.__tvbFs[0] || 0), r.top + fr[i].clientTop + (+d.__tvbFs[1] || 0));
        return;
      }
    }
  });
  // The player's box: the largest ancestor exactly the video's size, so its own controls and
  // subtitles come along; the video itself when there is none.
  function fsTarget(v) {
    var el = v, r = v.getBoundingClientRect();
    while (el.parentElement && el.parentElement !== document.body && el.parentElement !== document.documentElement) {
      var p = el.parentElement.getBoundingClientRect();
      if (Math.abs(p.width - r.width) > 4 || Math.abs(p.height - r.height) > 4) break;
      el = el.parentElement;
    }
    return el;
  }
  function armFullscreen(v) {
    try { v.scrollIntoView({ block: 'center', inline: 'center' }); } catch (err) {}
    setTimeout(function () {
      var r = v.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return;
      armedEl = fsTarget(v);
      armedUntil = Date.now() + 3000;
      reportFs(r.left + r.width / 2, r.top + r.height / 2);
    }, 150);
  }
  function armed() { return armedEl && Date.now() < armedUntil; }
  ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend'].forEach(function (t) {
    window.addEventListener(t, function (e) { if (armed()) e.stopImmediatePropagation(); }, true);
  });
  window.addEventListener('click', function (e) {
    if (!armed()) return;
    var el = armedEl;
    armedEl = null;
    e.stopImmediatePropagation();
    e.preventDefault();
    var req = el.requestFullscreen || el.webkitRequestFullscreen;
    try { var p = req && req.call(el); if (p && p.catch) p.catch(function () {}); } catch (err) {}
  }, true);
  function num(x) { return isFinite(x) ? Math.round(x * 10) / 10 : -1; }
  function snapshot(v) {
    return ['V', id, (v.paused || v.ended) ? 0 : 1, num(v.currentTime), num(v.duration), v.muted ? 1 : 0, rateOf(v), fsEl() ? 1 : 0].join(' ');
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
        else if (c[0] === 'vol' && isFinite(n)) { setVolume(v, n); }
        else if (c[0] === 'fs') { armFullscreen(v); }
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
