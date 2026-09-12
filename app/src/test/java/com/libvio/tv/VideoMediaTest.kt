package com.libvio.tv

import com.libvio.tv.playback.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoMediaTest {
    private fun snapshot(url: String, blob: Boolean = false, type: String = "") =
        JSONObject().put("url", url).put("blob", blob).put("type", type).put("duration", 120).put("ready", 1)

    @Test fun hlsFromOwningPlayerSurvivesExtensionlessManifest() {
        val media = videoMedia(snapshot("https://media.test/playlist?token=private", true, "m3u8"))!!
        assertEquals(HLS_MIME_TYPE, media.mimeType)
        assertFalse(media.toString().contains("private"))
    }
    @Test fun encryptedOptionUsesThisAttemptsObservedManifestOnlyForReadyHls() {
        val observed = "https://media.test/master.m3u8?token=private"
        val ready = snapshot("encrypted-placeholder", true, "m3u8")
        assertEquals(observed, videoMedia(ready, observed)!!.url)
        assertNull(videoMedia(ready))
        assertNull(videoMedia(ready.put("ready", 0), observed))
        assertNull(videoMedia(snapshot("encrypted-placeholder", true, "flv"), observed))
    }
    @Test fun manifestExtensionAlsoIdentifiesHls() {
        assertEquals(HLS_MIME_TYPE, videoMedia(snapshot("https://media.test/index.m3u8?auth=x", true))!!.mimeType)
    }
    @Test fun directMp4RemainsProgressive() {
        assertNull(videoMedia(snapshot("https://media.test/video.mp4"))!!.mimeType)
    }
    @Test fun blobAndUnsupportedMseAreNeverPassedToNativePlayer() {
        assertNull(videoMedia(snapshot("blob:https://site.test/handle", true, "m3u8")))
        assertNull(videoMedia(snapshot("https://media.test/video.flv", true, "flv")))
    }
    @Test fun unreadyAndUnsafeSourcesAreRejected() {
        assertNull(videoMedia(snapshot("https://media.test/index.m3u8", true).put("ready", 0)))
        assertNull(videoMedia(snapshot("https://media.test/video.mp4").put("duration", 0)))
        assertNull(videoMedia(snapshot("http://media.test/index.m3u8", true)))
        assertNull(videoMedia(snapshot("https://user:password@media.test/video.mp4")))
    }
}
