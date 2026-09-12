@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.libvio.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import org.json.JSONArray
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.libvio.tv.data.WatchRecord
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*

@Composable
internal fun RecentTile(record:WatchRecord,modifier:Modifier,posterWidth:androidx.compose.ui.unit.Dp,
                       posterHeight:androidx.compose.ui.unit.Dp,expanded:Boolean,onFocused:()->Unit,onClick:()->Unit) {
    val source=remember{MutableInteractionSource()}
    val focused by source.collectIsFocusedAsState()
    val bring=remember{BringIntoViewRequester()}
    LaunchedEffect(focused){if(focused){withFrameNanos{};bring.bringIntoView();delay(200);bring.bringIntoView()}}
    Column(modifier.height(posterHeight+with(LocalDensity.current){22.sp.toDp()}).bringIntoViewRequester(bring).restoreContentFocus("${record.movie.id}:resume")
        .onFocusChanged{if(it.isFocused)onFocused()}.testTag("recent:${record.movie.id}")
        .semantics(mergeDescendants=true){contentDescription="${record.movie.title}，${resumeLabel(record)}，${resumeActionLabel(record)}"}
        .border(2.dp,if(focused)Accent else Color.Transparent,RoundedCornerShape(8.dp))
        .clickable(interactionSource=source,indication=null,onClick=onClick)) {
        Row(Modifier.height(posterHeight),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(posterWidth).fillMaxHeight()) {
                PosterArtwork(record.movie,Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.25f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.9f)))))
                Text(resumeLabel(record),color=White,fontSize=13.sp,lineHeight=18.sp,maxLines=1,
                    modifier=Modifier.align(Alignment.BottomCenter).padding(horizontal=4.dp,vertical=8.dp))
            }
            if(expanded)Column(Modifier.weight(1f).padding(top=8.dp,end=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(record.movie.title,color=White,fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(listOf(record.movie.year,record.movie.area,record.movie.score.takeIf(String::isNotBlank)?.let{"评分 $it"}.orEmpty())
                    .filter(String::isNotBlank).joinToString(" · "),color=Muted,fontSize=13.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text((if(record.episode>0)"第 ${record.episode} 集 · "else"")+"已看 ${clock(record.positionMs)}"+(if(record.durationMs>0)" / ${clock(record.durationMs)}"else""),color=Muted,fontSize=13.sp,lineHeight=18.sp,maxLines=2)
                WatchProgress(record)
                Row(Modifier.background(Accent,RoundedCornerShape(8.dp)).padding(horizontal=14.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.PlayArrow,null,Modifier.size(22.dp),tint=Bg);Text(resumeActionLabel(record),color=Bg,fontSize=14.sp,lineHeight=19.sp,fontWeight=FontWeight.Bold)}
            }
        }
        Text(if(expanded)""else record.movie.title,color=White,fontSize=14.sp,lineHeight=22.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=3.dp))
    }
}

@Composable
internal fun HistoryShortcut(modifier:Modifier,height:androidx.compose.ui.unit.Dp,onClick:()->Unit) {
    val source=remember{MutableInteractionSource()};val focused by source.collectIsFocusedAsState()
    Column(modifier.restoreContentFocus("all-history").testTag("all-history")
        .clickable(interactionSource=source,indication=null,onClick=onClick)) {
        Column(Modifier.fillMaxWidth().height(height).border(if(focused)2.dp else 1.dp,if(focused)Accent else TvDesign.border,RoundedCornerShape(8.dp))
            .background(Panel,RoundedCornerShape(8.dp)),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            Icon(Icons.Rounded.History,null,Modifier.size(42.dp),tint=if(focused)Accent else White)
            Spacer(Modifier.height(16.dp));Text("全部历史",color=White,fontSize=18.sp,fontWeight=FontWeight.Medium)
            Spacer(Modifier.height(6.dp));Text("查看观看记录",color=Muted,fontSize=13.sp)
        }
    }
}
internal fun resumeLabel(record:WatchRecord):String = "${record.label} · ${clock(record.positionMs)}"
internal fun resumeActionLabel(record:WatchRecord):String=if(record.durationMs>0&&record.positionMs>=record.durationMs-10000)"重新播放"else"继续播放"
@Composable internal fun WatchProgress(record:WatchRecord,modifier:Modifier=Modifier) {
    if(record.durationMs>0)Box(modifier.fillMaxWidth().height(3.dp).background(Muted.copy(alpha=.25f))) {
        Box(Modifier.fillMaxWidth((record.positionMs.toDouble()/record.durationMs).coerceIn(0.0,1.0).toFloat()).fillMaxHeight().background(Accent))
    }
}
@Composable internal fun ErrorNotice(message:String,retry:()->Unit){Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(message,color=TvDesign.error,fontSize=15.sp);TvAction("重试",onClick=retry)}}

@Composable
internal fun FallbackRecommendation(movie:Movie,modifier:Modifier,onClick:()->Unit){
    val source=remember{MutableInteractionSource()};val focused by source.collectIsFocusedAsState()
    Row(modifier.height(138.dp*maxOf(1f,LocalDensity.current.fontScale)).restoreContentFocus("fallback:${movie.id}")
        .background(Panel,RoundedCornerShape(10.dp)).border(2.dp,if(focused)Accent else Color.Transparent,RoundedCornerShape(10.dp))
        .clickable(interactionSource=source,indication=null,onClick=onClick).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
        PosterArtwork(movie,Modifier.width(81.dp).fillMaxHeight())
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(movie.title,color=White,fontSize=22.sp,lineHeight=28.sp,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold)
            Text(listOf(movie.year,movie.area,movie.note).filter(String::isNotBlank).joinToString(" · "),color=Muted,fontSize=13.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
