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
internal class PlaybackController(private val context: Context, private val repo: LibvioRepository,
    private val library: LocalLibrary, private val resolver: WebPlaybackResolver, private val scope: CoroutineScope) {
    private val selector = DefaultTrackSelector(context).apply {
        setParameters(buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false))
    }
    private val nativePlayer = ExoPlayer.Builder(context).setTrackSelector(selector)
        .setAudioAttributes(AudioAttributes.DEFAULT, true).setHandleAudioBecomingNoisy(true).build()
    var player: Player by mutableStateOf(nativePlayer)
        private set
    private val session = MediaSession.Builder(context, player).build()
    var state by mutableStateOf(PlayerUiState(Movie(0, "正在加载")))
        private set
    var sourceMenu by mutableStateOf(false)
    var sourceName by mutableStateOf("")
        private set
    val softwareDecoding get() = player is VlcSoftwarePlayer
    val decodeMetrics get() = (player as? VlcSoftwarePlayer)?.metrics
    private var job: Job? = null
    private var recovery: Job? = null
    private var watchdog: Job? = null
    private var generation = 0L
    private var pending: Attempt? = null
    private var active: Attempt? = null
    private var lastSave = 0L
    private var foreground = true
    private val attempts = PlaybackAttempts()
    private data class Attempt(val generation: Long, val detail: Detail, val sourceIndex: Int,
        val episode: Episode, val media: ResolvedMedia, val desiredPlay: Boolean, val resumeAt: Long) {
        val id get() = generation.toString()
    }
    private fun eventId(time: AnalyticsListener.EventTime): String? = runCatching {
        time.timeline.getWindow(time.windowIndex, Timeline.Window()).mediaItem.mediaId
    }.getOrNull()
    private fun current(engine: Player) = engine === player &&
        (pending ?: active)?.id == engine.currentMediaItem?.mediaId

    init {
        observe(nativePlayer)
        nativePlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                if (!current(nativePlayer) || eventId(eventTime) != pending?.id) return
                if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
                    requestRecovery("正在尝试兼容播放")
                }
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (current(nativePlayer) && eventId(eventTime) == pending?.id) commitFirstFrame(nativePlayer)
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (current(nativePlayer) && eventId(eventTime) == (pending ?: active)?.id)
                    requestRecovery("该播放方式暂不可用")
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (current(nativePlayer) && eventId(eventTime) == nativePlayer.currentMediaItem?.mediaId)
                    state = state.copy(trackInfo = videoTrackInfo(format.width, format.height, format.averageBitrate, format.peakBitrate))
            }
        })
        scope.launch {
            while (isActive) {
                state = state.copy(position = player.currentPosition.coerceAtLeast(0), duration = duration(),
                    seekable = player.isCurrentMediaItemSeekable)
                if (player.isPlaying && System.currentTimeMillis() - lastSave >= 10_000) save()
                delay(500)
            }
        }
    }

    private fun observe(engine: Player) {
        engine.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (engine !== player) return
                state = state.copy(playing = isPlaying)
                if (!isPlaying) save()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (engine === player) state = state.copy(playRequested = playWhenReady)
            }
            override fun onPlaybackStateChanged(value: Int) {
                if (engine !== player) return
                state = state.copy(buffering = value == Player.STATE_BUFFERING, ended = value == Player.STATE_ENDED,
                    seekable = engine.isCurrentMediaItemSeekable)
                if (value == Player.STATE_ENDED && pending == null && current(engine)) {
                    save()
                    val a = active ?: return
                    val list = a.detail.sources[a.sourceIndex].episodes
                    list.getOrNull(list.indexOf(a.episode) + 1)?.let { play(a.detail, a.sourceIndex, it, 0, true) }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (engine !== nativePlayer && current(engine)) requestRecovery("该播放方式暂不可用")
            }
            override fun onRenderedFirstFrame() {
                if (engine !== nativePlayer) commitFirstFrame(engine)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (engine === player && engine is VlcSoftwarePlayer) {
                    val format = engine.currentTracks.groups.firstOrNull()?.getTrackFormat(0)
                    state = state.copy(trackInfo = videoTrackInfo(videoSize.width, videoSize.height, format?.averageBitrate ?: -1, -1))
                }
            }
        })
    }

    private fun commitFirstFrame(engine: Player) {
        if (!current(engine)) return
        val a = pending ?: return
        if (a.generation != generation) return
        active = a
        pending = null
        watchdog?.cancel()
        sourceName = a.detail.sources[a.sourceIndex].name
        val detail = a.detail.copy(movie = a.detail.movie.copy(internalId = a.media.internalId), selectedSource = a.sourceIndex)
        state = state.copy(movie = detail.movie, detail = detail, episode = detail.episodes.indexOf(a.episode),
            group = detail.episodes.indexOf(a.episode) / 10, renderedFrame = true, error = null, note = "", buffering = false)
        if (BuildConfig.DEBUG) android.util.Log.i("LibvioPlayback", "firstFrame source=${a.detail.sources[a.sourceIndex].sid} software=$softwareDecoding")
    }

    fun open(movie: Movie, preferredSid: Int? = null) {
        if (BuildConfig.DEBUG) android.util.Log.i("LibvioPlayback", "open movie=${movie.id} source=$preferredSid")
        save()
        cancelAttempt()
        active = null
        useNative()
        nativePlayer.clearMediaItems()
        sourceName = ""
        attempts.clear()
        val token = generation
        state = PlayerUiState(movie, buffering = true)
        job = scope.launch {
            try {
                library.load()
                val detail = repo.detail(movie)
                ensureActive()
                if (token != generation) return@launch
                if (detail.sources.isEmpty()) throw SiteException("该影片暂无在线线路")
                val record = library.records.value.firstOrNull { it.movie.id == movie.id }
                val index = detail.sources.indexOfFirst { if (preferredSid != null) it.sid == preferredSid else it.name == record?.source }.takeIf { it >= 0 } ?: 0
                val source = detail.sources[index]
                val episode = record?.let { r -> source.episodes.singleOrNull { it.title == r.label }
                    ?: source.episodes.singleOrNull { episodeNumber(it.title) != null && episodeNumber(it.title) == episodeNumber(r.label) } } ?: source.episodes.first()
                val position = if (record != null && (episode.title == record.label || episodeNumber(episode.title) != null && episodeNumber(episode.title) == episodeNumber(record.label))) resumePosition(record) else 0
                prepare(detail, index, episode, position, true, token)
            } catch (e: Exception) { failed(e, token) }
        }
    }

    private suspend fun prepare(detail: Detail, index: Int, episode: Episode, position: Long, desiredPlay: Boolean, token: Long) {
        attempts.native(index)
        state = state.copy(detail = detail.copy(selectedSource = index), episode = detail.sources[index].episodes.indexOf(episode))
        val config = repo.player(episode)
        if (config.sid != detail.sources[index].sid || config.nid != episode.index) throw SiteException("线路信息已变化，请重新打开影片")
        val media = resolver.resolve(config)
        currentCoroutineContext().ensureActive()
        if (token != generation) return
        val http = DefaultHttpDataSource.Factory().setUserAgent(media.userAgent)
            .setDefaultRequestProperties(media.referer.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap())
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val item = MediaItem.Builder().setMediaId(token.toString()).setUri(media.url).setMimeType(media.mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(detail.movie.title).build()).build()
        pending = Attempt(token, detail, index, episode, media, desiredPlay, position)
        state = state.copy(error = null, renderedFrame = false, buffering = true, trackInfo = videoTrackInfo(-1, -1, -1, -1))
        // Android MediaParser handles HD2's unusual HEVC prefix SEI without rewriting the media.
        val source = if (config.provider == "rrmj" && android.os.Build.VERSION.SDK_INT >= 30)
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(http, androidx.media3.exoplayer.source.MediaParserExtractorAdapter.Factory()).createMediaSource(item)
        else DefaultMediaSourceFactory(http).createMediaSource(item)
        nativePlayer.setMediaSource(source, position.coerceAtLeast(0))
        nativePlayer.setPlaybackSpeed(state.speed)
        nativePlayer.playWhenReady = desiredPlay && foreground
        nativePlayer.prepare()
        watchFirstFrame(nativePlayer, token)
    }

    private fun requestRecovery(reason: String) {
        val a = pending ?: active ?: return
        val engine = player
        if (BuildConfig.DEBUG) android.util.Log.i("LibvioPlayback", "recovery source=${a.sourceIndex} software=$softwareDecoding reason=$reason")
        if (!current(engine) || recovery?.isActive == true) return
        recovery = scope.launch {
            // Do not release a native player from inside one of its own callbacks.
            yield()
            if (a.generation != generation || engine !== player || !current(engine)) return@launch
            val retry = a.copy(resumeAt = if (pending == null) engine.currentPosition.coerceAtLeast(0) else a.resumeAt,
                desiredPlay = engine.playWhenReady)
            save()
            watchdog?.cancel()
            pending = retry
            active = null
            state = state.copy(renderedFrame = false, buffering = true, error = null)
            if (engine === nativePlayer && attempts.software(a.sourceIndex)) {
                val item = checkNotNull(engine.currentMediaItem)
                try {
                    val software = VlcSoftwarePlayer(context, item, a.media, retry.resumeAt, state.speed)
                    player = software
                    nativePlayer.stop()
                    observe(software)
                    session.setPlayer(software)
                    state = state.copy(note = "正在尝试兼容播放…")
                    software.playWhenReady = retry.desiredPlay && foreground
                    software.prepare()
                    watchFirstFrame(software, a.generation)
                    return@launch
                } catch (_: Exception) { /* Try another source only after software setup also failed. */ }
                catch (_: LinkageError) { /* A missing native library must not crash source recovery. */ }
            }
            val next = a.detail.sources.indices.firstNotNullOfOrNull { index ->
                if (attempts.tried(index)) null else matchingEpisode(a.episode, a.detail.sources[index].episodes,
                    a.detail.sources[a.sourceIndex].episodes.size)?.let { index to it }
            }
            if (next != null) {
                // Clear the recovery job before play cancels the old attempt.
                recovery = null
                play(a.detail, next.first, next.second, retry.resumeAt, retry.desiredPlay, automatic = true)
                state = state.copy(note = "$reason，正在切换到 ${a.detail.sources[next.first].name}…")
            } else {
                pending = null
                player.stop()
                state = state.copy(error = "当前影片暂时无法播放，请重试或手动选择线路", note = "", buffering = false)
            }
        }
    }

    private fun watchFirstFrame(engine: Player, token: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var waiting = 0
            while (pending?.generation == token && player === engine) {
                delay(1000)
                if (foreground) waiting++
                if (waiting >= 40) { requestRecovery("该播放方式等待超时"); return@launch }
            }
        }
    }

    private fun useNative() {
        val previous = player
        player = nativePlayer
        if (previous !== nativePlayer) { session.setPlayer(nativePlayer); previous.release() }
        nativePlayer.stop()
    }
    private fun cancelAttempt() {
        job?.cancel(); recovery?.cancel(); watchdog?.cancel()
        generation++
        pending = null
    }
    private fun failed(e: Exception, token: Long) {
        if (e is CancellationException) throw e
        if (token == generation) state = state.copy(error = userError(e), buffering = false, note = "")
    }
    private fun play(detail: Detail, index: Int, episode: Episode, position: Long, desiredPlay: Boolean, automatic: Boolean = false) {
        if (BuildConfig.DEBUG) android.util.Log.i("LibvioPlayback", "play episode=${episode.index} source=$index automatic=$automatic")
        save()
        cancelAttempt()
        active = null
        useNative()
        if (!automatic) attempts.clear()
        val token = generation
        state = state.copy(note = "正在加载 ${detail.sources[index].name}…", error = null, renderedFrame = false)
        job = scope.launch { try { prepare(detail, index, episode, position, desiredPlay, token) } catch (e: Exception) { failed(e, token) } }
    }
    fun chooseSource(index: Int) {
        sourceMenu = false
        val d = state.detail ?: return
        val target = d.sources.getOrNull(index) ?: return
        val episode = (active ?: pending)?.episode ?: d.episodes.getOrNull(state.episode) ?: return
        if (index == d.selectedSource && state.error == null) return
        val mapped = matchingEpisode(episode, target.episodes, d.episodes.size)
        if (mapped == null) { state = state.copy(note = "此线路没有当前集，请先选择其他集数"); return }
        play(d, index, mapped, player.currentPosition.coerceAtLeast(0), player.playWhenReady || active == null && pending == null)
    }
    fun chooseEpisode(episode: Episode) { val d = state.detail ?: return; play(d, d.selectedSource, episode, 0, true) }
    fun chooseGroup(group: Int) { state = state.copy(group = group) }
    fun seek(delta: Long) { if (player.isCurrentMediaItemSeekable) player.seekTo(jumpPosition(player.currentPosition, player.duration, delta)) }
    fun toggle() { if (player.playWhenReady) player.pause() else player.play() }
    fun speed(value: Float) { state = state.copy(speed = value); player.setPlaybackSpeed(value) }
    fun retry() { val d = state.detail; if (d == null) open(state.movie) else d.episodes.getOrNull(state.episode)?.let { play(d, d.selectedSource, it, player.currentPosition.coerceAtLeast(0), true) } }
    private fun duration() = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
    fun save() {
        val a = active ?: return
        if (pending != null || !current(player) || !state.renderedFrame) return
        val record = WatchRecord(a.detail.movie.copy(internalId = a.media.internalId), a.detail.sources[a.sourceIndex].name,
            a.detail.sources[a.sourceIndex].sid, a.episode.index, a.episode.title, player.currentPosition.coerceAtLeast(0), duration(), System.currentTimeMillis())
        lastSave = System.currentTimeMillis()
        library.enqueue(record)
    }
    fun pauseForBackground() { foreground = false; save(); player.pause() }
    fun foreground() { foreground = true }
    fun stop() { save(); cancelAttempt(); active = null; player.stop(); sourceMenu = false }
    fun release() { stop(); session.release(); if (player !== nativePlayer) player.release(); nativePlayer.release() }
}
fun resumePosition(record: WatchRecord): Long = if (record.durationMs > 0 && record.positionMs >= record.durationMs - 10_000) 0 else record.positionMs.coerceAtLeast(0)
