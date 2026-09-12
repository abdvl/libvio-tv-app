package com.libvio.tv.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

// These provider scripts were verified to use the site's same-origin Artplayer entry.
internal val ARTPLAYER_PROVIDERS = setOf("BBA", "rrmj", "NBY", "4kvm", "lbyy", "bhyy")
internal const val HLS_MIME_TYPE = "application/x-mpegURL"

internal class VideoMedia(val url: String, val mimeType: String?) {
    override fun toString() = "VideoMedia(mimeType=$mimeType)"
}

/** Reject a blob handle itself, unready players, and formats we cannot hand to Media3. */
internal fun videoMedia(snapshot: JSONObject, observedManifest: String? = null): VideoMedia? {
    val duration = snapshot.optDouble("duration", 0.0)
    if (!duration.isFinite() || duration <= 0 || snapshot.optInt("ready") < 1) return null
    val blob = snapshot.optBoolean("blob")
    val hlsPlayer = snapshot.optString("type").equals("m3u8", ignoreCase = true)
    val configured = snapshot.optString("url").toHttpUrlOrNull()
    // Artplayer can retain the encrypted placeholder in option.url. The first actual
    // manifest request belongs to this fresh, single-player resolver, never a previous film.
    val url = configured ?: if (blob && hlsPlayer) observedManifest?.toHttpUrlOrNull() else null
    if (url == null) return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val hls = hlsPlayer ||
        url.encodedPath.endsWith(".m3u8", ignoreCase = true)
    if (blob && !hls) return null
    return VideoMedia(url.toString(), if (hls) HLS_MIME_TYPE else null)
}
