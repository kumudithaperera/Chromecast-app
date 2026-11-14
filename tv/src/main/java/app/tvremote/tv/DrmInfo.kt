package app.tvremote.tv

import android.media.MediaDrm
import java.util.UUID

object DrmInfo {
    private val WIDEVINE = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    /** e.g. "Widevine L1 (v18.0.0)", or "No Widevine". Blocking; call off the main thread. */
    fun widevineSummary(): String = try {
        val drm = MediaDrm(WIDEVINE)
        try {
            val level = drm.getPropertyString("securityLevel")
            val version = drm.getPropertyString("version")
            "Widevine $level (v$version)"
        } finally {
            drm.release()
        }
    } catch (_: Exception) {
        "No Widevine"
    }
}
