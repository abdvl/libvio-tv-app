package com.libvio.tv

import android.app.Application
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.libvio.tv.data.LocalLibrary
import com.libvio.tv.data.LibvioRepository
import com.libvio.tv.playback.PlaybackController
import com.libvio.tv.playback.WebPlaybackResolver

class LibvioApplication:Application(){val library by lazy{LocalLibrary(this)}}
class MainActivity:ComponentActivity(){
    internal lateinit var model:AppModel
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=FrameLayout(this);setContentView(root)
        val library=(application as LibvioApplication).library
        val repo=LibvioRepository(applicationContext)
        val player=PlaybackController(this,repo,library,WebPlaybackResolver(this,root),lifecycleScope)
        model=AppModel(repo,library,player,lifecycleScope)
        val initialMovie=if(BuildConfig.DEBUG)intent.getLongExtra("movieId",0).takeIf{it>0}else null
        val initialSid=if(BuildConfig.DEBUG)intent.getIntExtra("sourceId",0).takeIf{it>0}else null
        root.addView(ComposeView(this).apply{setContent{LibvioApp(model,initialMovie,initialSid){finish()}}},FrameLayout.LayoutParams(-1,-1))
        WindowCompat.getInsetsController(window,window.decorView).apply{hide(WindowInsetsCompat.Type.systemBars());systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
    }
    override fun onStart(){super.onStart();if(::model.isInitialized)model.player.foreground()}
    override fun onStop(){if(::model.isInitialized)model.player.pauseForBackground();super.onStop()}
    override fun onDestroy(){if(::model.isInitialized)model.player.release();super.onDestroy()}
}
