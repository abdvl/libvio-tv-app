@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.libvio.tv

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Runs against public catalog pages only when explicitly enabled. Screenshots contain no media URLs. */
class TvJourneyTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String){
        ui.waitForIdle()
        android.os.SystemClock.sleep(1500)
        val i=InstrumentationRegistry.getInstrumentation()
        val bitmap=i.uiAutomation.takeScreenshot()
        val dir=File(i.targetContext.getExternalFilesDir(null),"verification").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun browseAndReturnThroughNativePages(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio")=="true")
        ui.waitUntil(60_000){ui.activity.model.home!=null||ui.activity.model.homeError!=null}
        ui.onNodeWithTag("nav:home").assertIsFocused();capture("home")
        ui.onNodeWithTag("nav:home").performKeyInput{pressKey(androidx.compose.ui.input.key.Key.DirectionDown)}
        capture("recent-focused")
        ui.onNodeWithTag("nav:movie").performClick()
        ui.waitUntil(45_000){ui.onAllNodesWithTag("ranking:人气排行:1").fetchSemanticsNodes().isNotEmpty()};capture("category")
        ui.onNodeWithTag("nav:browse").performClick()
        ui.waitUntil(45_000){ui.onAllNodesWithText("年份：全部").fetchSemanticsNodes().isNotEmpty()};capture("catalog")
        ui.onNodeWithText("年份：全部").performClick();capture("filter-year")
        ui.onNodeWithText("2026",useUnmergedTree=true).performClick()
        ui.onNodeWithTag("nav:search").performClick();capture("search")
        ui.onNodeWithTag("nav:history").performClick();capture("history")
        ui.onNodeWithTag("nav:favorites").performClick();capture("favorites")
        ui.onNodeWithTag("nav:settings").performClick();capture("settings")
    }
}
