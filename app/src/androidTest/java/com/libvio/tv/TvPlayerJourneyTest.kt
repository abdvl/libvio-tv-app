@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.libvio.tv

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class TvPlayerJourneyTest {
    @get:Rule val ui=createEmptyComposeRule()
    private fun capture(name:String){
        ui.waitForIdle();SystemClock.sleep(800)
        val i=InstrumentationRegistry.getInstrumentation();val bitmap=i.uiAutomation.takeScreenshot()
        val dir=File(i.targetContext.getExternalFilesDir(null),"verification").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun sourcePickerFullscreenAndNormalLayout(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio")=="true")
        val i=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<MainActivity>(Intent(i.targetContext,MainActivity::class.java).putExtra("movieId",5813548L).putExtra("sourceId",4)).use{scenario->
            fun ready(source:String){ui.waitUntil(90_000){var ready=false;scenario.onActivity{a->a.model.player.state.error?.let{fail(it)};ready=a.model.player.state.renderedFrame&&a.model.player.sourceName==source};ready}}
            ready("HD7播放")
            scenario.onActivity{it.model.player.toggle()}
            ui.onNodeWithTag("player-video").performKeyInput{pressKey(androidx.compose.ui.input.key.Key.DirectionDown)}
            capture("player-fullscreen")
            ui.onNodeWithTag("player-sources").performClick();capture("player-sources")
            ui.onNodeWithText("HD2播放 · 10 集").performClick();ready("HD2播放")
            scenario.onActivity{a->assertFalse(a.model.player.state.playRequested)}
            ui.onNodeWithTag("player-action:0").performClick();capture("player-normal")
            ui.onNodeWithTag("player-action:6").performClick();capture("player-speed")
            ui.onNodeWithText("1.25×").performClick()
            scenario.onActivity{a->assertEquals(1.25f,a.model.player.state.speed)}
            ui.onNodeWithTag("player-description").performClick();capture("player-description")
            ui.onNodeWithText("返回播放").performClick()
        }
    }
}
