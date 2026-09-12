package com.libvio.tv

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in explicitly. Never logs resolved URLs, response bodies, or browser sessions. */
@RunWith(AndroidJUnit4::class)
class LivePlaybackTest {
    @Test fun twoSourcesRenderSeekAndPersist(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio")=="true")
        val intent=Intent(instrumentation.targetContext,MainActivity::class.java).putExtra("movieId",5813548L).putExtra("sourceId",4)
        ActivityScenario.launch<MainActivity>(intent).use{scenario->
            fun waitFor(label:String,timeout:Long=100_000,predicate:(MainActivity)->Boolean){
                val until=SystemClock.elapsedRealtime()+timeout
                while(SystemClock.elapsedRealtime()<until){var ready=false;var error:String?=null
                    scenario.onActivity{a->ready=predicate(a);error=a.model.player.state.error}
                    if(ready)return
                    if(error!=null)fail("$label: $error")
                    SystemClock.sleep(500)
                };fail("Timed out waiting for $label")
            }
            waitFor("HD7 first native frame"){it.model.player.state.renderedFrame&&it.model.player.sourceName=="HD7播放"&&it.model.player.state.position>2_000}
            scenario.onActivity{a->assertTrue(a.model.player.state.duration>60_000);assertTrue(a.model.player.state.seekable);a.model.player.seek(30_000)}
            waitFor("native seek",30_000){it.model.player.state.position>30_000}
            scenario.onActivity{a->a.model.player.toggle()}
            waitFor("pause",10_000){!it.model.player.state.playRequested}
            scenario.onActivity{a->a.model.player.toggle();val index=a.model.player.state.detail!!.sources.indexOfFirst{it.name=="HD2播放"};assertTrue(index>=0);a.model.player.chooseSource(index)}
            waitFor("HD2 native frame and resume"){it.model.player.state.renderedFrame&&it.model.player.sourceName=="HD2播放"&&it.model.player.state.position>30_000}
            scenario.onActivity{a->a.model.player.save()}
            waitFor("local source and progress",10_000){a->a.model.library.records.value.any{it.movie.id==5813548L&&it.source=="HD2播放"&&it.positionMs>30_000&&it.movie.internalId==3548L}}
        }
    }
}
