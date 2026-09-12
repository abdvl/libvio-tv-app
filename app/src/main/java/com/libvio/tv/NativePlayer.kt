package com.libvio.tv

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.libvio.tv.playback.PlaybackController

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun NativePlayer(controller:PlaybackController,full:Boolean,toggleFull:()->Unit,onBack:()->Unit,favorite:Boolean,onFavorite:()->Unit){
    val state=controller.state.copy(favorite=favorite,sourceName=controller.sourceName)
    PlayerContent(state,full,PlayerActions(back=onBack,toggleFull=toggleFull,togglePlay=controller::toggle,
        seek=controller::seek,setSpeed=controller::speed,favorite=onFavorite,chooseGroup=controller::chooseGroup,
        playEpisode=controller::chooseEpisode,retry=controller::retry,episodeFocus={},sources={controller.sourceMenu=true}),menuOpen=controller.sourceMenu){
        AndroidView(factory={PlayerView(it).apply{player=controller.player;useController=false;isFocusable=false}},modifier=Modifier.fillMaxSize(),update={it.player=controller.player;it.keepScreenOn=state.playing})
    }
    if(controller.sourceMenu){
        val d=controller.state.detail
        OptionPopover("播放线路",d?.sources.orEmpty().mapIndexed{i,s->FilterOption(i.toString(),s.name+" · ${s.episodes.size} 集")},d?.selectedSource.toString(),onDismiss={controller.sourceMenu=false}){controller.chooseSource(it.toInt())}
    }
}
internal fun clock(ms:Long):String {val seconds=ms/1000;return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)else "%02d:%02d".format(seconds/60,seconds%60)}
internal fun jumpPosition(position:Long,duration:Long,delta:Long):Long=(position+delta).coerceAtLeast(0).coerceAtMost(duration.takeIf{it>0}?:Long.MAX_VALUE)
