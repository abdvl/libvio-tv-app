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

internal val LocalPosterFocus=compositionLocalOf<MutableState<Long>?>{null}

@Composable
internal fun PosterFocusGroup(identity:String,content:@Composable ()->Unit){
    val lastPoster=rememberSaveable(identity){mutableLongStateOf(-1)}
    CompositionLocalProvider(LocalPosterFocus provides lastPoster, LocalFocusSection provides identity){content()}
}

internal object EdgeBringIntoViewSpec: BringIntoViewSpec {
    override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float):Float = when {
        offset<0f -> offset
        offset+size>containerSize -> offset+size-containerSize
        else -> 0f
    }
}

@Composable
internal fun TvAction(label: String, icon: ImageVector? = null, selected: Boolean=false, modifier: Modifier=Modifier, enabled:Boolean=true,onClick:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val color by animateColorAsState(if(focused)Accent else if(selected)Color(0xFF243746) else Color.Transparent,label="focus")
    Row(modifier.semantics{this.selected=selected}.clip(RoundedCornerShape(50)).background(color).border(if(selected&&!focused)1.dp else 0.dp,if(selected&&!focused)Accent.copy(alpha=.35f) else Color.Transparent,RoundedCornerShape(50)).clickable(enabled=enabled,interactionSource=interaction,indication=null,onClick=onClick).padding(horizontal=14.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val tint=if(!enabled)Muted.copy(alpha=.55f)else if(focused)Bg else if(selected)Accent else White
        if(icon!=null)Icon(icon,null,Modifier.size(17.dp),tint=tint)
        Text(label,color=tint,fontSize=14.sp,fontWeight=if(focused||selected)FontWeight.Bold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LibvioLogo(modifier:Modifier=Modifier) {
    Row(modifier,verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Rounded.LiveTv,null,Modifier.size(28.dp),tint=Accent)
        Spacer(Modifier.width(6.dp))
        Text("LIBVIO",color=White,fontSize=20.sp,fontWeight=FontWeight.Bold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomeMovieGroup(movies:List<Movie>,open:(Movie)->Unit,title:String="电影",more:()->Unit) {
    PosterFocusGroup("latest:$title") { Column(Modifier.focusGroup().padding(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionHeading(title,"最近更新 · ${movies.size} 部",more)
        movies.chunked(5).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                row.forEach { movie ->PosterCard(movie,Modifier.weight(1f),posterRatio=TvDesign.posterRatio){open(movie)} }
                repeat(5-row.size){Spacer(Modifier.weight(1f))}
            }
        }
    }
}}

@Composable
internal fun HeroCard(hero:Hero,modifier:Modifier,onClick:()->Unit) {
    Card(onClick=onClick,modifier=modifier.restoreContentFocus("hero:${hero.id}").height(138.dp),shape=CardDefaults.shape(RoundedCornerShape(12.dp)),scale=CardDefaults.scale(focusedScale=1f),border=CardDefaults.border(focusedBorder=Border(androidx.compose.foundation.BorderStroke(2.dp,Accent)))) {
        Box(Modifier.fillMaxSize().background(Panel)) {
            AsyncImage(hero.image,hero.title,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(hero.title,color=White,fontSize=28.sp,lineHeight=34.sp,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold)
                Text(hero.note,color=White.copy(alpha=.85f),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun SectionHeading(title:String,subtitle:String="",more:(()->Unit)?=null,moreModifier:Modifier=Modifier) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(4.dp,21.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
        Spacer(Modifier.width(10.dp));Text(title,color=White,fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(12.dp));Text(subtitle,color=Muted,fontSize=13.sp,lineHeight=18.sp)
        Spacer(Modifier.weight(1f));if(more!=null)TvAction("查看全部",Icons.Rounded.ChevronRight,modifier=moreModifier,onClick=more)
    }
}

@Composable
internal fun PosterCard(movie:Movie,modifier:Modifier=Modifier,posterRatio:Float=TvDesign.posterRatio,onFocused:()->Unit={},focusIdentity:Long=movie.id,subtitle:String?=null,onClick:()->Unit) {
    // Legacy call sites keep compiling while pages migrate; artwork always uses the v2 portrait ratio.
    PosterTile(movie,modifier,onFocused,focusIdentity,subtitle,onClick)
}

@Composable private fun FilterLine(values:List<String>,selected:String,choose:(String)->Unit) { LazyRow(horizontalArrangement=Arrangement.spacedBy(3.dp)){items(values){TvAction(it,selected=it==selected){choose(it)}}} }

@Composable internal fun KeyButton(label:String,modifier:Modifier,onClick:()->Unit) { Button(onClick=onClick,modifier=modifier.height(36.dp*maxOf(1f,LocalDensity.current.fontScale)),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.colors(containerColor=Panel,contentColor=White,focusedContainerColor=Accent,focusedContentColor=Bg),shape=ButtonDefaults.shape(RoundedCornerShape(7.dp))){Text(label,fontSize=16.sp)} }
@Composable internal fun InputBox(value:String,change:(String)->Unit,hint:String,modifier:Modifier=Modifier,password:Boolean=false,editRequest:Int=0,onEditingFinished:()->Unit={},digitSlots:Boolean=false) {
    val interaction=remember{MutableInteractionSource()}
    val focused by interaction.collectIsFocusedAsState()
    var editing by remember{mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(editRequest){if(editRequest>0)editing=true}
    Box(modifier.fillMaxWidth().border(1.dp,if(focused)Accent else Muted.copy(alpha=.3f),RoundedCornerShape(8.dp)).background(Panel,RoundedCornerShape(8.dp)).clickable(interactionSource=interaction,indication=null){editing=true}.padding(13.dp)) {
        if(digitSlots&&value.codePointCount(0,value.length)<=4)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val digits=value.codePoints().toArray()
            repeat(4){index->Box(Modifier.weight(1f).height(36.dp).background(Bg,RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center){
                Text(digits.getOrNull(index)?.let{String(Character.toChars(it))}?:"",color=White,fontSize=20.sp,lineHeight=26.sp)
            }}
        }else Text(if(value.isBlank())hint else if(password)"•".repeat(value.length)else value,color=if(value.isBlank())Muted else White,fontSize=15.sp,maxLines=1)
    }
    if(editing)androidx.compose.ui.window.Dialog(onDismissRequest={keyboard?.hide();editing=false;onEditingFinished()}) {
        val input=remember{FocusRequester()}
        Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(hint,color=White,fontSize=20.sp)
            BasicTextField(value,change,Modifier.fillMaxWidth().focusRequester(input).border(1.dp,Accent,RoundedCornerShape(6.dp)).padding(12.dp),textStyle=TextStyle(color=White,fontSize=18.sp),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done,keyboardType=if(password)androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={keyboard?.hide();editing=false;onEditingFinished()}),cursorBrush=androidx.compose.ui.graphics.SolidColor(Accent),visualTransformation=if(password)PasswordVisualTransformation()else VisualTransformation.None)
            TvAction("完成"){keyboard?.hide();editing=false;onEditingFinished()}
        }
        LaunchedEffect(Unit){input.requestFocus();keyboard?.show()}
    }
}
