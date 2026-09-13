package com.libvio.tv

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Explicitly enabled regression for the two user-reported titles; never changes favorites. */
class ReportedPlaybackTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio") == "true")
    private fun waitFor(label: String, timeout: Long = 100_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        var captured=false
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            // Let the Compose test clock apply navigation before polling the native surface.
            ui.runOnIdle {
                done = predicate()
                ui.activity.model.player.state.error?.let { fail("$label: $it") }
            }
            if (done) return
            if(!captured && label.contains("native first") && SystemClock.elapsedRealtime() > until-timeout+10_000){
                capture("waiting")
                ui.runOnUiThread {val p=ui.activity.model.player;android.util.Log.i("LibvioRegression","movie=${p.state.movie.id} source=${p.sourceName} frame=${p.state.renderedFrame} playing=${p.state.playing} position=${p.state.position} item=${p.player.currentMediaItem?.mediaId} size=${p.player.videoSize.width}x${p.player.videoSize.height}")}
                captured=true
            }
            SystemClock.sleep(500)
        }
        capture("timeout")
        var diagnostic=""
        ui.runOnUiThread {
            val p=ui.activity.model.player
            diagnostic="id=${p.state.movie.id}, source=${p.sourceName}, frame=${p.state.renderedFrame}, playing=${p.state.playing}, position=${p.state.position}, size=${p.player.videoSize.width}x${p.player.videoSize.height}, supported=${p.player.currentTracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO)}"
        }
        fail("Timed out: $label ($diagnostic)")
    }
    private fun nativePlayback(source: String? = null) = waitFor("$source native first frame and advancing time") {
        val p = ui.activity.model.player
        p.state.renderedFrame && p.state.note.isBlank() && (source == null || p.sourceName == source) && p.state.position >= 5_000 && p.state.playing
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val bitmap = i.uiAutomation.takeScreenshot()
        val dir = File(i.targetContext.getExternalFilesDir(null), "verification/playback-fix").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun moanaHomeDefaultBd1RendersHlsAndSeeks() {
        enabled()
        waitFor("home catalog") { ui.activity.model.home != null }
        ui.onNodeWithTag("home-feed").performScrollToNode(hasText("海洋奇缘：启航"))
        ui.onNodeWithText("海洋奇缘：启航").performClick()
        nativePlayback("BD1播放")
        var renderedBeforeSeek = 0
        ui.runOnUiThread {
            val p = ui.activity.model.player
            assertEquals(5813774L, p.state.movie.id)
            assertTrue(p.state.duration > 6_000_000)
            assertEquals("application/x-mpegURL", p.player.currentMediaItem?.localConfiguration?.mimeType)
            assertTrue(p.state.seekable)
            val counters = (p.player as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters!!
            counters.ensureUpdated()
            renderedBeforeSeek = counters.renderedOutputBufferCount
            p.player.seekTo(90_000)
        }
        waitFor("HLS seek and continued video output") {
            val p = ui.activity.model.player
            val counters = (p.player as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters!!
            counters.ensureUpdated()
            p.state.position in 92_000..105_000 && p.state.playing &&
                counters.renderedOutputBufferCount > renderedBeforeSeek + 48
        }
        capture("moana-bd1-native")
        // Hardware overlays can be absent from system screenshots; inspect the video Surface directly.
        val pixelCopyDone = java.util.concurrent.CountDownLatch(1)
        var pixelCopyStatus = -1
        var videoImage: Bitmap? = null
        ui.runOnUiThread {
            fun playerView(view: android.view.View): androidx.media3.ui.PlayerView? {
                if (view is androidx.media3.ui.PlayerView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    playerView(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val surface = playerView(ui.activity.window.decorView)!!.videoSurfaceView as android.view.SurfaceView
            videoImage = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(surface, videoImage!!, { result ->
                pixelCopyStatus = result
                pixelCopyDone.countDown()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        assertTrue("Video Surface capture must finish", pixelCopyDone.await(10, java.util.concurrent.TimeUnit.SECONDS))
        android.util.Log.i("LibvioRegression", "moanaPixelCopyStatus=$pixelCopyStatus")
        if (pixelCopyStatus == android.view.PixelCopy.SUCCESS) {
            val dir = File(ui.activity.getExternalFilesDir(null), "verification/playback-fix")
            File(dir, "moana-video-surface.png").outputStream().use { videoImage!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        videoImage?.recycle()
    }
    @Test fun gameOfThronesExistingFavoriteSelectsCompatibleSourceAndReopens() {
        enabled()
        if (InstrumentationRegistry.getArguments().getString("seedEmulatorFavorite") == "true") {
            check(android.os.Build.MODEL.contains("sdk", ignoreCase = true)) { "Fixture seeding is only allowed on the emulator" }
            runBlocking {
                val library = ui.activity.model.library
                library.load()
                if (library.favorites.value.none { it.id == 5811975L }) library.favorite(Movie(5811975L, "权力的游戏第八季"))
            }
        }
        waitFor("existing favorite") { ui.activity.model.library.favorites.value.any { it.id == 5811975L } }
        val original = ui.activity.model.library.favorites.value
        var selectedSource=""
        ui.onNodeWithTag("nav:favorites").performClick()
        ui.onNodeWithText("权力的游戏第八季").performClick()
        nativePlayback()
        ui.runOnUiThread {
            val p = ui.activity.model.player
            assertEquals(5811975L, p.state.movie.id)
            assertTrue(p.player.currentTracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO))
            selectedSource=p.sourceName
            assertTrue(p.state.duration > 3_000_000)
            assertTrue(p.state.seekable)
            p.player.seekTo(45_000)
        }
        waitFor("HD3 seek and continued playback") { ui.activity.model.player.state.position > 47_000 && ui.activity.model.player.state.playing }
        capture("game-of-thrones-compatible-native")
        ui.runOnUiThread {
            ui.activity.model.player.pauseForBackground()
            ui.activity.onBackPressedDispatcher.onBackPressed()
        }
        ui.waitForIdle()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed(); ui.activity.model.player.foreground() }
        waitFor("saved compatible source progress") { ui.activity.model.library.records.value.any { it.movie.id == 5811975L && it.positionMs >= 47_000 && it.source == selectedSource } }
        ui.onNodeWithText("权力的游戏第八季").performClick()
        nativePlayback()
        ui.runOnUiThread {
            assertEquals(selectedSource,ui.activity.model.player.sourceName)
            assertTrue(ui.activity.model.player.state.position >= 47_000)
            assertEquals(original, ui.activity.model.library.favorites.value)
        }
    }
}
