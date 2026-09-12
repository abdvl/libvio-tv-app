package com.libvio.tv

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.libvio.tv.data.Episode
import com.libvio.tv.data.LibvioRepository
import com.libvio.tv.playback.WebPlaybackResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Verifies the WebView-to-native handoff independently of the TV's foreground UI. */
class ReportedResolverTest {
    @Test fun reportedProvidersReturnNativeMediaWithoutExposingUrls() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibvioRepository(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var resolver: WebPlaybackResolver
            scenario.onActivity { resolver = WebPlaybackResolver(it, it.findViewById<ViewGroup>(android.R.id.content)) }
            val film = repository.player(Episode(1, "1", "/play/5813774-4-1.html"))
            assertEquals("4kvm", film.provider)
            val hls = resolver.resolve(film)
            assertEquals("application/x-mpegURL", hls.mimeType)
            assertEquals(3774L, hls.internalId)
            val episode = repository.player(Episode(1, "第01集", "/play/5811975-2-1.html"))
            assertEquals("lbyy", episode.provider)
            val mp4 = resolver.resolve(episode)
            assertNull(mp4.mimeType)
            assertEquals(1975L, mp4.internalId)
        }
    }
}
