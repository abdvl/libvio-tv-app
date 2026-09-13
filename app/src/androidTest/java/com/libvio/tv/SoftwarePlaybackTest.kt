package com.libvio.tv

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.test.platform.app.InstrumentationRegistry
import com.libvio.tv.playback.DecodeMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Runs the original HD3 source, including an already-paused native-to-software handoff. */
class SoftwarePlaybackTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun waitFor(label: String, timeout: Long = 100_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            ui.runOnIdle {
                ui.activity.model.player.state.error?.let { fail("$label: $it") }
                done = predicate()
            }
            if (done) return
            SystemClock.sleep(300)
        }
        capture("timeout")
        fail("Timed out: $label")
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val image = i.uiAutomation.takeScreenshot()
        val dir = File(i.targetContext.getExternalFilesDir(null), "verification/software-decoding").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun originalHd3SoftwareFramesSeekPauseSourceSwitchAndFavoriteResume() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio") == "true")
        if (InstrumentationRegistry.getArguments().getString("seedEmulatorFavorite") == "true") {
            check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
            runBlocking {
                val library = ui.activity.model.library
                library.load()
                if (library.favorites.value.none { it.id == 5811975L }) library.favorite(Movie(5811975L, "权力的游戏第八季"))
            }
        }
        waitFor("existing favorite") { ui.activity.model.library.favorites.value.any { it.id == 5811975L } }
        val favorites = ui.activity.model.library.favorites.value
        ui.onNodeWithTag("nav:favorites").performClick()
        ui.onNodeWithText("权力的游戏第八季").performClick()
        waitFor("initial frame") { ui.activity.model.player.state.renderedFrame && ui.activity.model.player.state.note.isBlank() }
        ui.runOnIdle {
            val p = ui.activity.model.player
            val hd3 = p.state.detail!!.sources.indexOfFirst { it.sid == 2 }
            assertTrue(hd3 >= 0)
            if (p.state.detail!!.selectedSource != hd3) p.chooseSource(hd3)
        }
        waitFor("original HD3 software frame") {
            val p = ui.activity.model.player
            p.softwareDecoding && p.state.renderedFrame && p.state.note.isBlank() && p.sourceName == "HD3播放" && p.state.playing
        }
        ui.runOnIdle {
            val p = ui.activity.model.player
            if (p.state.detail!!.episodes[p.state.episode].index != 1) p.chooseEpisode(p.state.detail!!.episodes.first { it.index == 1 })
        }
        waitFor("first episode HD3 software") { val p=ui.activity.model.player; p.softwareDecoding && p.state.renderedFrame && p.state.note.isBlank() && p.state.detail!!.episodes[p.state.episode].index == 1 }
        ui.runOnIdle {
            val p = ui.activity.model.player
            assertTrue(p.decodeMetrics!!.displayed > 0)
            assertTrue(p.player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO))
            assertEquals(1920, p.player.videoSize.width)
            assertTrue(p.state.duration > 3_000_000)
            p.player.seekTo(90_000)
        }
        waitFor("software seek") { ui.activity.model.player.state.position in 92_000..100_000 }
        capture("got-hd3-software")
        var before = DecodeMetrics(0, 0, 0)
        var startPosition = 0L
        ui.runOnIdle { before = ui.activity.model.player.decodeMetrics!!; startPosition = ui.activity.model.player.state.position }
        // Sustained decoding, not just a first-frame check. Metrics contain no media URLs.
        waitFor("60 seconds of continuous HD3 software decoding", 90_000) {
            ui.activity.model.player.state.position >= startPosition + 60_000
        }
        ui.runOnIdle {
            val p = ui.activity.model.player
            val after = p.decodeMetrics!!
            val shown = after.displayed - before.displayed
            val lost = after.lost - before.lost
            android.util.Log.i("LibvioSoftwareTest", "model=${android.os.Build.MODEL} displayed=$shown lost=$lost decoded=${after.decoded-before.decoded} elapsedMediaMs=${p.state.position-startPosition}")
            assertTrue("Decoded pictures must reach the video output", shown > 1000)
            assertTrue("Software decoding drops too many frames: shown=$shown lost=$lost", lost.toDouble() / (shown + lost).coerceAtLeast(1) < 0.15)
            p.toggle()
        }
        waitFor("paused") { !ui.activity.model.player.state.playRequested }
        var pausedAt = 0L
        ui.runOnIdle {
            val p = ui.activity.model.player
            pausedAt = p.player.currentPosition
            p.speed(1.25f)
            val bd1 = p.state.detail!!.sources.indexOfFirst { it.sid == 11 }
            assertTrue(bd1 >= 0)
            p.chooseSource(bd1)
        }
        waitFor("paused native BD1 frame") {
            val p = ui.activity.model.player
            !p.softwareDecoding && p.state.renderedFrame && p.state.note.isBlank() && p.sourceName == "BD1播放"
        }
        ui.runOnIdle {
            val p = ui.activity.model.player
            assertFalse(p.state.playRequested)
            assertTrue(kotlin.math.abs(p.player.currentPosition-pausedAt) < 5000)
            p.chooseSource(p.state.detail!!.sources.indexOfFirst { it.sid == 2 })
        }
        waitFor("paused same-source software fallback") {
            val p = ui.activity.model.player
            p.softwareDecoding && p.state.renderedFrame && p.state.note.isBlank() && p.sourceName == "HD3播放"
        }
        ui.runOnIdle {
            val p = ui.activity.model.player
            assertFalse(p.state.playRequested)
            assertEquals(1.25f, p.player.playbackParameters.speed)
            assertTrue(kotlin.math.abs(p.player.currentPosition-pausedAt) < 5000)
            p.toggle()
        }
        waitFor("software resumed") { ui.activity.model.player.state.position > pausedAt + 3000 && ui.activity.model.player.state.playing }
        capture("got-hd3-resumed")
        var displayedBeforeLayout = 0
        var itemBeforeLayout = ""
        ui.runOnIdle { displayedBeforeLayout = ui.activity.model.player.decodeMetrics!!.displayed; itemBeforeLayout = ui.activity.model.player.player.currentMediaItem!!.mediaId }
        ui.onNodeWithTag("player-action:0").performClick()
        waitFor("software output survives normal layout") { (ui.activity.model.player.decodeMetrics?.displayed ?: 0) > displayedBeforeLayout + 24 }
        ui.runOnIdle { assertEquals(itemBeforeLayout, ui.activity.model.player.player.currentMediaItem!!.mediaId) }
        capture("got-hd3-normal")
        ui.onNodeWithTag("player-action:0").performClick()
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("HD3 progress persisted") { ui.activity.model.library.records.value.any { it.movie.id == 5811975L && it.sid == 2 && it.positionMs > pausedAt } }
        ui.onNodeWithText("权力的游戏第八季").performClick()
        waitFor("favorite reopened into HD3 software playback") {
            val p = ui.activity.model.player
            p.softwareDecoding && p.state.renderedFrame && p.sourceName == "HD3播放" && p.state.position >= pausedAt
        }
        ui.runOnIdle { assertEquals(favorites, ui.activity.model.library.favorites.value) }
    }
    @Test fun softwareSurfaceSurvivesFullAndNormalLayouts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio") == "true")
        waitFor("existing favorite") { ui.activity.model.library.favorites.value.any { it.id == 5811975L } }
        ui.onNodeWithTag("nav:favorites").performClick()
        ui.onNodeWithText("权力的游戏第八季").performClick()
        waitFor("initial frame") { ui.activity.model.player.state.renderedFrame && ui.activity.model.player.state.note.isBlank() }
        ui.runOnIdle {
            val p = ui.activity.model.player
            val hd3 = p.state.detail!!.sources.indexOfFirst { it.sid == 2 }
            if (p.state.detail!!.selectedSource != hd3) p.chooseSource(hd3)
        }
        waitFor("HD3 software") { ui.activity.model.player.softwareDecoding && ui.activity.model.player.state.renderedFrame }
        ui.runOnIdle { ui.activity.model.player.speed(1.25f) }
        var displayed = 0
        var originalItem = ""
        ui.runOnIdle { displayed = ui.activity.model.player.decodeMetrics!!.displayed; originalItem = ui.activity.model.player.player.currentMediaItem!!.mediaId }
        ui.onNodeWithTag("player-action:0").performClick()
        waitFor("normal layout remains on original HD3", 50_000) {
            val p = ui.activity.model.player
            assertEquals("Layout must not restart or advance the episode", originalItem, p.player.currentMediaItem?.mediaId)
            (p.decodeMetrics?.displayed ?: 0) > displayed + 24
        }
        capture("got-hd3-normal")
        ui.onNodeWithTag("player-action:0").performClick()
        waitFor("fullscreen resumed") { ui.activity.model.player.softwareDecoding && ui.activity.model.player.state.playing }
        capture("got-hd3-fullscreen")
    }

}
