@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.libvio.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.Text
import com.libvio.tv.data.*
import kotlinx.coroutines.*

@Composable
internal fun LibvioApp(model:AppModel,initialMovie:Long?,initialSid:Int?,onExit:()->Unit){
    var screen by rememberSaveable{mutableStateOf(if(initialMovie!=null)"player"else"home")}
    var backScreen by rememberSaveable{mutableStateOf("home")}
    var category by rememberSaveable{mutableIntStateOf(1)}
    var browsePath by rememberSaveable{mutableStateOf("/show/1-----------.html")}
    var full by rememberSaveable{mutableStateOf(initialMovie!=null)}
    var openedId by rememberSaveable{mutableLongStateOf(initialMovie?:0L)}
    var openedTitle by rememberSaveable{mutableStateOf("正在加载")}
    var exit by remember{mutableStateOf(false)}
    val headers=remember{navigationItems.associate{it.key to FocusRequester()}}
    val body=remember{FocusRequester()};val holder=rememberSaveableStateHolder()
    val selected=if(screen=="category")categoryNavigationKey(category)else screen
    val header=headers[selected]?:headers.getValue("home")
    val page=remember(header){PageFocusController(header,body)}
    val favorites by model.library.favorites.collectAsState()
    val open:(Movie)->Unit={backScreen=screen;openedId=it.id;openedTitle=it.title;model.player.open(it);screen="player";full=true}
    fun back(){if(screen=="player"){if(full)full=false else{model.player.stop();screen=backScreen}}else if(screen=="home")exit=true else screen="home"}
    BackHandler(onBack=::back)
    LaunchedEffect(Unit){if(screen=="player"&&openedId>0)model.player.open(Movie(openedId,openedTitle),initialSid)else header.requestWhenAttached()}
    CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeBringIntoViewSpec,LocalPageFocus provides page){
        MaterialTheme(colorScheme=darkColorScheme(primary=Accent,onPrimary=Bg,surface=Panel,onSurface=White,background=Bg)){
            Column(Modifier.fillMaxSize().background(Bg)){
                if(!full)UnifiedHeader(selected,headers,{page.enterContent()}){target->
                    headers.getValue(target).requestFocus()
                    if(screen=="player")model.player.stop()
                    full=false
                    val id=navigationCategoryId(target)
                    if(id!=null){category=id;screen="category"}else screen=target
                    if(target=="browse"){category=1;browsePath="/show/1-----------.html"}
                }
                Box(Modifier.weight(1f).fillMaxWidth().focusRequester(body).focusGroup()){
                    holder.SaveableStateProvider(if(screen in listOf("category","browse"))"$screen:$category"else screen){ContentFocusScope{
                        when(screen){
                            "home"->HomeScreen(model,open,{screen="history"}){id->category=id;browsePath="/show/$id-----------.html";screen="browse"}
                            "category"->CategoryScreen(model,category,open){browsePath="/show/$category-----------.html";screen="browse"}
                            "browse"->BrowseScreen(model,category,browsePath,{browsePath=it},{category=it;browsePath="/show/$it-----------.html"},open)
                            "search"->SearchScreen(model,open)
                            "history"->LibraryScreen(model,false,open)
                            "favorites"->LibraryScreen(model,true,open)
                            "settings"->SiteSettings(model)
                            "player"->NativePlayer(model.player,full,{full=!full},::back,favorites.any{it.id==model.player.state.movie.id}){model.favorite(model.player.state.movie)}
                        }
                    }}
                }
            }
            if(exit)ExitConfirmationDialog({exit=false}){exit=false;onExit()}
        }
    }
}

@Composable
internal fun Notice(message:String,retry:(()->Unit)?=null){Column(Modifier.padding(36.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(message,color=Muted,fontSize=16.sp);if(retry!=null)TvAction("重试",onClick=retry)}}

@Composable
private fun HomeScreen(model:AppModel,open:(Movie)->Unit,history:()->Unit,browse:(Int)->Unit){
    val records by model.library.records.collectAsState();val list=rememberLazyListState()
    LazyColumn(state=list,modifier=Modifier.testTag("home-feed").fillMaxSize().padding(horizontal=36.dp),contentPadding=PaddingValues(top=12.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
        item("recent"){Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            SectionHeading("最近播放",more=history)
            if(records.isEmpty())Text("从一部影片开始，观看进度会自动保存在本机",color=Muted,fontSize=14.sp)
            else PosterFocusGroup("recent"){BoxWithConstraints(Modifier.fillMaxWidth()){
                val width=(maxWidth-90.dp)/6;val height=maxOf(width*1.5f,199.5.dp*LocalDensity.current.fontScale)
                var focused by rememberSaveable{mutableLongStateOf(-1)};var within by remember{mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().onFocusChanged{within=it.hasFocus}.focusGroup().horizontalScroll(rememberScrollState()).padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){
                    records.take(5).forEach{r->key(r.movie.id){val expanded=within&&focused==r.movie.id;val animated by animateDpAsState(if(expanded)396.dp else width,label="recent-width")
                        RecentTile(r,Modifier.width(animated),width,height,expanded,{focused=r.movie.id}){open(r.movie)}}}
                    HistoryShortcut(Modifier.width(width),height,history)
                }
            }}
        }}
        if(model.homeError!=null)item{Notice(model.homeError!!,model::loadHome)}
        if(model.loadingHome&&model.home==null)item{Notice("正在加载影片…")}
        model.home?.sections?.flatMap{it.movies}?.distinctBy{it.id}?.take(2)?.takeIf{it.isNotEmpty()}?.let{movies->item("recommendations"){
            PosterFocusGroup("recommendations"){Column(verticalArrangement=Arrangement.spacedBy(12.dp)){SectionHeading("精选推荐");Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){movies.forEach{m->FallbackRecommendation(m,Modifier.weight(1f)){open(m)}}}}}
        }}
        model.home?.sections?.forEach{s->item("section:${s.category}"){HomeMovieGroup(s.movies.take(10),open,s.title){browse(s.category)}}}
    }
}

@Composable
private fun CategoryScreen(model:AppModel,category:Int,open:(Movie)->Unit,browse:()->Unit){
    var allYears by rememberSaveable(category){mutableStateOf(false)}
    val year=if(allYears)""else java.time.Year.now().value.toString()
    val hot=remember(category,year){model.feed("/show/$category--hits---------$year.html",category)}
    val score=remember(category,year){model.feed("/show/$category--score---------$year.html",category)}
    LaunchedEffect(hot,score){if(hot.items.isEmpty())hot.load();if(score.items.isEmpty())score.load()}
    DisposableEffect(hot,score){onDispose{hot.cancel();score.cancel()}}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=36.dp),contentPadding=PaddingValues(top=12.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(categoryLabel(category),color=White,fontSize=28.sp);TvAction(if(allYears)"全部年份"else"$year 年"){allYears=!allYears}}}
        listOf("人气排行" to hot,"评分排行" to score).forEach{(label,feed)->item(label){
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                SectionHeading(label)
                if(feed.error!=null)Notice(feed.error!!,feed::load)
                else if(feed.items.isEmpty())Notice(if(feed.loading)"正在加载…"else"暂无影片，可浏览全部年份",browse)
                else PosterFocusGroup("rank:$label"){Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){feed.items.take(2).forEachIndexed{i,m->RankingFeature(m,i+1,label,Modifier.weight(1f)){open(m)}}}
                    feed.items.drop(2).take(10).chunked(5).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){row.forEach{m->PosterCard(m,Modifier.weight(1f)){open(m)}};repeat(5-row.size){Spacer(Modifier.weight(1f))}}}
                }}
            }
        }}
        item{TvAction("浏览全部年份",onClick=browse)}
    }
}

@Composable
private fun BrowseScreen(model:AppModel,category:Int,path:String,changePath:(String)->Unit,changeCategory:(Int)->Unit,open:(Movie)->Unit){
    val feed=remember(path){model.feed(path,category)}
    var filter by remember{mutableStateOf<FilterGroup?>(null)}
    var categories by remember{mutableStateOf(false)}
    var restore by remember{mutableStateOf<String?>(null)}
    val triggers=remember{mutableMapOf<String,FocusRequester>()}
    LaunchedEffect(restore){restore?.let{triggers[it]?.requestWhenAttached();restore=null}}
    LaunchedEffect(feed){if(feed.items.isEmpty())feed.load()}
    DisposableEffect(feed){onDispose{feed.cancel()}}
    Column(Modifier.fillMaxSize().padding(horizontal=36.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("影片目录",color=White,fontSize=28.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            TvAction(categoryLabel(category),modifier=Modifier.focusRequester(triggers.getOrPut("category"){FocusRequester()})){categories=true}
            feed.filters.forEach{group->TvAction(group.label.removePrefix("按")+"："+(group.options.firstOrNull{it.selected}?.label?:"全部"),modifier=Modifier.focusRequester(triggers.getOrPut(group.label){FocusRequester()})){filter=group}}
        }
        key(path){FeedGrid(feed,open)}
    }
    filter?.let{group->OptionPopover(group.label.removePrefix("按"),group.options.map{FilterOption(it.path,it.label)},group.options.firstOrNull{it.selected}?.path.orEmpty(),columns=if(group.options.size>8)4 else 1,onDismiss={filter=null;restore=group.label}){filter=null;restore=group.label;changePath(it)}}
    if(categories)OptionPopover("影片分类",(1..5).map{FilterOption(it.toString(),categoryLabel(it))},category.toString(),onDismiss={categories=false;restore="category"}){categories=false;restore="category";changeCategory(it.toInt())}
}

@Composable
private fun FeedGrid(feed:Feed,open:(Movie)->Unit,columns:Int=6){
    val list=rememberLazyGridState()
    LaunchedEffect(feed,list){snapshotFlow{list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?:-1}.collect{last->if(last>=feed.items.size-columns&&feed.items.isNotEmpty()&&feed.error==null)feed.load()}}
    LazyVerticalGrid(GridCells.Fixed(columns),state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=64.dp,top=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        items(feed.items,key={it.id}){movie->PosterCard(movie){open(movie)}}
        if(feed.error!=null)item(span={GridItemSpan(maxLineSpan)}){Notice(feed.error!!,feed::load)}
        else if(feed.loading)item(span={GridItemSpan(maxLineSpan)}){Notice("正在加载…")}
        else if(feed.items.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Notice("没有找到相关影片")}
    }
}

@Composable
private fun SearchScreen(model:AppModel,open:(Movie)->Unit){
    var draft by rememberSaveable{mutableStateOf("")};var submitted by rememberSaveable{mutableStateOf("")}
    var edit by remember{mutableIntStateOf(0)}
    val history by model.library.searches.collectAsState()
    val input=remember{FocusRequester()};var inInput by remember{mutableStateOf(true)}
    BackHandler(!inInput){input.requestFocus()}
    val feed=remember(submitted){if(submitted.isBlank())null else model.feed("/search/-------------.html",query=submitted)}
    fun submit(text:String=draft){val value=text.trim();if(value.isNotEmpty()){draft=value;submitted=value;model.library.rememberSearch(value)}}
    LaunchedEffect(feed){if(feed?.items?.isEmpty()==true)feed.load()}
    DisposableEffect(feed){onDispose{feed?.cancel()}}
    Row(Modifier.fillMaxSize().padding(start=36.dp,end=36.dp,top=12.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){
        Column(Modifier.width(254.dp).onFocusChanged{inInput=it.hasFocus}.verticalScroll(rememberScrollState()).padding(bottom=64.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            InputBox(draft,{draft=it.take(80)},"输入片名",modifier=Modifier.focusRequester(input),editRequest=edit,onEditingFinished={submit();input.requestFocus()})
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".toList().chunked(6).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){row.forEach{key->KeyButton(key.toString(),Modifier.weight(1f)){draft+=key}}}}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TvAction("退格"){if(draft.isNotEmpty())draft=draft.substring(0,draft.offsetByCodePoints(draft.length,-1))};TvAction("清空"){draft=""};TvAction("中文"){edit++}}
            TvAction("搜索"){submit()}
        }
        Column(Modifier.width(170.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("搜索记录",color=White,fontSize=18.sp)
            if(history.isEmpty())Text("输入片名后确认搜索",color=Muted,fontSize=14.sp)
            (history+model.home?.sections.orEmpty().flatMap{it.movies}.map{it.title}).distinct().take(20).forEach{word->TvAction(word){submit(word)}}
        }
        Box(Modifier.weight(1f)){if(feed!=null)key(submitted){FeedGrid(feed,open,2)}else Notice("搜索想看的电影或电视剧")}
    }
}

@Composable
private fun LibraryScreen(model:AppModel,favorites:Boolean,open:(Movie)->Unit){
    val records by model.library.records.collectAsState();val saved by model.library.favorites.collectAsState()
    var remove by remember{mutableStateOf<Long?>(null)};var clear by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(horizontal=36.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(favorites)"本机收藏"else"观看历史",color=White,fontSize=28.sp);if(!favorites&&records.isNotEmpty())TvAction("清空历史"){clear=true}}
        if(favorites){LazyVerticalGrid(GridCells.Fixed(6),contentPadding=PaddingValues(bottom=64.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            items(saved,key={it.id}){m->Column{PosterCard(m){open(m)};TvAction("取消收藏"){model.favorite(m)}}}
            if(saved.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Notice("还没有收藏，播放页可收藏影片")}
        }}else LazyVerticalGrid(GridCells.Fixed(2),contentPadding=PaddingValues(bottom=64.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            items(records,key={it.movie.id}){r->HistoryRecordCard(r,onDelete={remove=r.movie.id},onOpen=open)}
            if(records.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Notice("本机还没有观看记录")}
        }
    }
    if(remove!=null||clear)TvConfirmDialog(if(clear)"清空观看历史？"else"删除观看记录？","仅删除本机记录。",onCancel={remove=null;clear=false}){val id=if(clear)null else remove;model.scope.launch{model.library.remove(id)};remove=null;clear=false}
}

@Composable
private fun SiteSettings(model:AppModel){
    val host by model.repo.host.collectAsState();val hosts by model.repo.hosts.collectAsState()
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    fun run(action:suspend()->Unit){if(busy)return;busy=true;error=null;model.scope.launch{try{action()}catch(e:Exception){if(e is CancellationException)throw e;error=userError(e)}finally{busy=false}}}
    Column(Modifier.fillMaxSize().padding(36.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Text("站点与应用",color=White,fontSize=28.sp)
        Text("当前站点："+host.ifBlank{"尚未连接"},color=White,fontSize=18.sp)
        Text("LIBVIO TV ${BuildConfig.VERSION_NAME} · 历史与收藏保存在本机",color=Muted,fontSize=14.sp)
        TvAction(if(busy)"正在检测…"else"重新检测可用站点",enabled=!busy){run{model.repo.ensureHost(true);model.loadHome()}}
        hosts.forEach{item->TvAction(item.removePrefix("https://"),selected=item==host,enabled=!busy){run{model.repo.chooseHost(item);model.loadHome()}}}
        error?.let{Text(it,color=TvDesign.error,fontSize=16.sp)}
    }
}
