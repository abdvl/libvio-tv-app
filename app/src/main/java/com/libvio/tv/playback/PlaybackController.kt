package com.libvio.tv.playback

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import com.libvio.tv.*
import com.libvio.tv.data.*
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackController(context:Context,private val repo:LibvioRepository,private val library:LocalLibrary,
                         private val resolver:WebPlaybackResolver,private val scope:CoroutineScope) {
    private val selector=DefaultTrackSelector(context).apply { setParameters(buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false)) }
    val player=ExoPlayer.Builder(context).setTrackSelector(selector).setAudioAttributes(AudioAttributes.DEFAULT,true).setHandleAudioBecomingNoisy(true).build()
    private val session=MediaSession.Builder(context,player).build()
    var state by mutableStateOf(PlayerUiState(Movie(0,"正在加载")));private set
    var sourceMenu by mutableStateOf(false)
    var sourceName by mutableStateOf("");private set
    private var job:Job?=null
    private var generation=0L
    private var pending:Attempt?=null
    private var active:Attempt?=null
    private var lastSave=0L
    private var foreground=true
    private val attemptedSources=mutableSetOf<Int>()
    private data class Attempt(val generation:Long,val detail:Detail,val sourceIndex:Int,val episode:Episode,val media:ResolvedMedia,val desiredPlay:Boolean,val resumeAt:Long){val id get()=generation.toString()}
    private fun eventId(time:AnalyticsListener.EventTime):String?=runCatching{time.timeline.getWindow(time.windowIndex,Timeline.Window()).mediaItem.mediaId}.getOrNull()
    init {
        player.addListener(object:Player.Listener{
            override fun onIsPlayingChanged(isPlaying:Boolean){state=state.copy(playing=isPlaying);if(!isPlaying)save()}
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){state=state.copy(playRequested=playWhenReady)}
            override fun onPlaybackStateChanged(value:Int){
                state=state.copy(buffering=value==Player.STATE_BUFFERING,ended=value==Player.STATE_ENDED,seekable=player.isCurrentMediaItemSeekable)
                if(value==Player.STATE_ENDED){save();val a=active?:return;if(pending!=null||player.currentMediaItem?.mediaId!=a.id)return;val list=a.detail.sources[a.sourceIndex].episodes;val next=list.getOrNull(list.indexOf(a.episode)+1);if(next!=null)play(a.detail,a.sourceIndex,next,0,true)}
            }
            override fun onPlayerError(error:PlaybackException){
                if(!tryCompatibleSource("该线路播放失败")){pending=null;state=state.copy(error="视频暂时无法播放，请重试或切换线路",buffering=false)}
            }
        })
        player.addAnalyticsListener(object:AnalyticsListener{
            override fun onTracksChanged(eventTime:AnalyticsListener.EventTime,tracks:Tracks){
                if(BuildConfig.DEBUG)android.util.Log.d("LibvioPlayback","tracks event=${eventId(eventTime)} pending=${pending?.id} video=${tracks.containsType(C.TRACK_TYPE_VIDEO)} supported=${tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)}")
                val attempt=pending?:return
                if(eventId(eventTime)!=attempt.id||attempt.generation!=generation)return
                if(tracks.containsType(C.TRACK_TYPE_VIDEO)&&!tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)){
                    if(!tryCompatibleSource("电视不支持此线路的视频格式")){
                        pending=null;player.stop();state=state.copy(error="这些线路的视频格式暂不受本机支持，请手动选择其他线路",buffering=false)
                    }
                }
            }
            override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long){
                if(BuildConfig.DEBUG)android.util.Log.d("LibvioPlayback","firstFrame event=${eventId(eventTime)} pending=${pending?.id} generation=$generation")
                val attempt=pending?:return
                if(eventId(eventTime)!=attempt.id||attempt.generation!=generation)return
                active=attempt;pending=null;sourceName=attempt.detail.sources[attempt.sourceIndex].name
                val detail=attempt.detail.copy(movie=attempt.detail.movie.copy(internalId=attempt.media.internalId),selectedSource=attempt.sourceIndex)
                state=state.copy(movie=detail.movie,detail=detail,episode=detail.episodes.indexOf(attempt.episode),group=detail.episodes.indexOf(attempt.episode)/10,renderedFrame=true,error=null,note="",buffering=false)
            }
            override fun onVideoInputFormatChanged(eventTime:AnalyticsListener.EventTime,format:Format,decoderReuseEvaluation:androidx.media3.exoplayer.DecoderReuseEvaluation?){
                if(eventId(eventTime)==player.currentMediaItem?.mediaId)state=state.copy(trackInfo=videoTrackInfo(format.width,format.height,format.averageBitrate,format.peakBitrate))
            }
        })
        scope.launch{while(isActive){
            state=state.copy(position=player.currentPosition.coerceAtLeast(0),duration=player.duration.takeIf{it>0&&it!=C.TIME_UNSET}?:0,seekable=player.isCurrentMediaItemSeekable)
            if(player.isPlaying&&System.currentTimeMillis()-lastSave>=10_000)save()
            delay(500)
        }}
    }
    fun open(movie:Movie,preferredSid:Int?=null){
        save();job?.cancel();generation++;pending=null;active=null;player.stop();player.clearMediaItems();sourceName=""
        attemptedSources.clear()
        val attempt=generation;state=PlayerUiState(movie,buffering=true)
        job=scope.launch{try{
            library.load()
            val detail=repo.detail(movie);ensureActive();if(attempt!=generation)return@launch
            if(detail.sources.isEmpty())throw SiteException("该影片暂无在线线路")
            val record=library.records.value.firstOrNull{it.movie.id==movie.id}
            val sourceIndex=detail.sources.indexOfFirst{if(preferredSid!=null)it.sid==preferredSid else it.name==record?.source}.takeIf{it>=0}?:0
            val source=detail.sources[sourceIndex]
            val episode=record?.let{r->source.episodes.singleOrNull{it.title==r.label}?:source.episodes.singleOrNull{episodeNumber(it.title)!=null&&episodeNumber(it.title)==episodeNumber(r.label)}}?:source.episodes.first()
            val position=if(record!=null&&(episode.title==record.label||episodeNumber(episode.title)!=null&&episodeNumber(episode.title)==episodeNumber(record.label)))resumePosition(record)else 0
            state=state.copy(detail=detail.copy(selectedSource=sourceIndex),episode=source.episodes.indexOf(episode))
            prepare(detail,sourceIndex,episode,position,true,attempt)
        }catch(e:Exception){if(e is CancellationException)throw e;if(attempt==generation)state=state.copy(error=userError(e),buffering=false)}}
    }
    private suspend fun prepare(detail:Detail,sourceIndex:Int,episode:Episode,position:Long,desiredPlay:Boolean,attempt:Long){
        attemptedSources+=sourceIndex
        val config=repo.player(episode)
        if(config.sid!=detail.sources[sourceIndex].sid||config.nid!=episode.index)throw SiteException("线路信息已变化，请重新打开影片")
        val media=resolver.resolve(config)
        currentCoroutineContext().ensureActive();if(attempt!=generation)return
        val http=DefaultHttpDataSource.Factory().setUserAgent(media.userAgent).setDefaultRequestProperties(media.referer.takeIf{it.isNotBlank()}?.let{mapOf("Referer" to it)}?:emptyMap()).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val item=MediaItem.Builder().setMediaId(attempt.toString()).setUri(media.url).setMimeType(media.mimeType).setMediaMetadata(MediaMetadata.Builder().setTitle(detail.movie.title).build()).build()
        pending=Attempt(attempt,detail,sourceIndex,episode,media,desiredPlay,position)
        state=state.copy(error=null,renderedFrame=false,buffering=true,trackInfo=videoTrackInfo(-1,-1,-1,-1))
        // The bundled MP4 extractor crashes on HD2's HEVC prefix SEI with no layer info.
        // Android's native MediaParser handles this sample on API 30+ without rewriting media.
        val source=if(config.provider=="rrmj"&&android.os.Build.VERSION.SDK_INT>=30)
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(http,androidx.media3.exoplayer.source.MediaParserExtractorAdapter.Factory()).createMediaSource(item)
        else DefaultMediaSourceFactory(http).createMediaSource(item)
        player.setMediaSource(source,position.coerceAtLeast(0))
        player.setPlaybackSpeed(state.speed);player.prepare();player.playWhenReady=desiredPlay&&foreground
    }
    private fun tryCompatibleSource(reason:String):Boolean {
        val current=pending?:return false
        if(current.generation!=generation||player.currentMediaItem?.mediaId!=current.id)return false
        val next=current.detail.sources.indices.firstNotNullOfOrNull{index->
            if(index in attemptedSources)null else matchingEpisode(current.episode,current.detail.sources[index].episodes,current.detail.sources[current.sourceIndex].episodes.size)?.let{index to it}
        }?:return false
        player.stop()
        play(current.detail,next.first,next.second,current.resumeAt,current.desiredPlay,automatic=true)
        state=state.copy(note="$reason，正在切换到 ${current.detail.sources[next.first].name}…")
        return true
    }
    private fun play(detail:Detail,sourceIndex:Int,episode:Episode,position:Long,desiredPlay:Boolean,automatic:Boolean=false){
        if(!automatic)attemptedSources.clear()
        save();job?.cancel();generation++;pending=null;val attempt=generation
        state=state.copy(note="正在加载 ${detail.sources[sourceIndex].name}…",error=null)
        job=scope.launch{try{prepare(detail,sourceIndex,episode,position,desiredPlay,attempt)}catch(e:Exception){if(e is CancellationException)throw e;if(attempt==generation)state=state.copy(error=userError(e),buffering=false,note="")}}
    }
    fun chooseSource(index:Int){
        sourceMenu=false;val d=state.detail?:return;val target=d.sources.getOrNull(index)?:return
        val current=active?.episode?:d.episodes.getOrNull(state.episode)?:return
        if(index==d.selectedSource&&state.error==null)return
        val mapped=matchingEpisode(current,target.episodes,d.episodes.size)
        if(mapped==null){state=state.copy(note="此线路没有当前集，请先选择其他集数");return}
        play(d,index,mapped,player.currentPosition.coerceAtLeast(0),player.playWhenReady||active==null)
    }
    fun chooseEpisode(episode:Episode){val d=state.detail?:return;play(d,d.selectedSource,episode,0,true)}
    fun chooseGroup(group:Int){state=state.copy(group=group)}
    fun seek(delta:Long){if(player.isCurrentMediaItemSeekable)player.seekTo(jumpPosition(player.currentPosition,player.duration,delta))}
    fun toggle(){if(player.playWhenReady)player.pause()else player.play()}
    fun speed(value:Float){state=state.copy(speed=value);player.setPlaybackSpeed(value)}
    fun retry(){val d=state.detail;if(d==null)open(state.movie)else d.episodes.getOrNull(state.episode)?.let{play(d,d.selectedSource,it,player.currentPosition.coerceAtLeast(0),true)}}
    fun save(){
        val a=active?:return
        if(player.currentMediaItem?.mediaId!=a.id||!state.renderedFrame)return
        val duration=player.duration.takeIf{it>0&&it!=C.TIME_UNSET}?:0
        val record=WatchRecord(a.detail.movie.copy(internalId=a.media.internalId),a.detail.sources[a.sourceIndex].name,a.detail.sources[a.sourceIndex].sid,a.episode.index,a.episode.title,player.currentPosition.coerceAtLeast(0),duration,System.currentTimeMillis())
        lastSave=System.currentTimeMillis();library.enqueue(record)
    }
    fun pauseForBackground(){foreground=false;save();player.pause()}
    fun foreground(){foreground=true}
    fun stop(){save();generation++;job?.cancel();pending=null;active=null;player.stop();sourceMenu=false}
    fun release(){stop();session.release();player.release()}
}
fun resumePosition(record:WatchRecord):Long=if(record.durationMs>0&&record.positionMs>=record.durationMs-10_000)0 else record.positionMs.coerceAtLeast(0)
