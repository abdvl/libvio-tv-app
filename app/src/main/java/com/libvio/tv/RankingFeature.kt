@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.libvio.tv

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.time.Year

@Composable
internal fun RankingFeature(movie:Movie,rank:Int,sort:String,modifier:Modifier=Modifier,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()};val focused by interaction.collectIsFocusedAsState()
    val bring=remember{BringIntoViewRequester()}
    val height=202.dp*maxOf(1f,LocalDensity.current.fontScale)
    LaunchedEffect(focused){if(focused){withFrameNanos{};bring.bringIntoView()}}
    val context=LocalContext.current
    val backdrop=remember(context,movie.image){ImageRequest.Builder(context).data(movie.image)
        .size(160,160).allowHardware(false).transformations(RankingBackdropBlur).build()}
    val shape=RoundedCornerShape(10.dp)
    Box(modifier.height(height).bringIntoViewRequester(bring).restoreContentFocus("rank:$rank:${movie.id}")
        .testTag("ranking:$sort:$rank").semantics(mergeDescendants=true){contentDescription="第 $rank 名，${movie.title}"}
        .clip(shape).background(Panel).border(2.dp,if(focused)Accent else Color.Transparent,shape)
        .clickable(interactionSource=interaction,indication=null,onClick=onClick)) {
        AsyncImage(backdrop,null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Bg.copy(alpha=.14f),.30f to Bg.copy(alpha=.72f),1f to Bg.copy(alpha=.82f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color.Transparent,Bg.copy(alpha=.30f)))))
        Row(Modifier.fillMaxSize().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PosterArtwork(movie,Modifier.width(126.dp).fillMaxHeight())
            Column(Modifier.weight(1f).padding(top=6.dp,end=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("TOP $rank",color=Accent,fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold)
                Text(movie.title,color=White,fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(listOf(movie.year,movie.area).filter(String::isNotBlank).joinToString(" · "),color=White.copy(alpha=.80f),fontSize=13.sp,lineHeight=18.sp,maxLines=2)
                if(movie.note.isNotBlank())Text(movie.note,color=White,fontSize=13.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}
