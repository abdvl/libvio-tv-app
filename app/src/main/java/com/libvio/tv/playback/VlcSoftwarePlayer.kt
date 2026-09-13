package com.libvio.tv.playback

import android.content.*
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

/** A single-source software decoder exposed through the same Media3 controls and session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VlcSoftwarePlayer(
    context: Context,
    private val item: MediaItem,
    resolved: ResolvedMedia,
    startPositionMs: Long,
    speed: Float,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val appContext = context.applicationContext
    // Never enable verbose native logging: stream URLs contain short-lived credentials.
    private val lib = LibVLC(appContext, arrayListOf("--quiet", "--stats", "--no-video-title-show").apply {
        val arm32 = !android.os.Process.is64Bit() && android.os.Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") }
        // ARM32 High 10 decoding needs a throughput-oriented profile on the tested Chromecast.
        // Disabling deblocking can expose compression blocks; keep the normal VLC Android
        // non-reference filter setting on other architectures. Never deliberately skip frames.
        add("--avcodec-skiploopfilter=${if (arm32) 4 else 1}")
        if (arm32) add("--avcodec-threads=${Runtime.getRuntime().availableProcessors().coerceIn(1, 4)}")
    })
    private val media = Media(lib, Uri.parse(resolved.url)).apply {
        setHWDecoderEnabled(false, false)
        addOption(":codec=avcodec")
        addOption(":network-caching=1500")
        addOption(":http-user-agent=${resolved.userAgent}")
        if (resolved.referer.isNotBlank()) addOption(":http-referrer=${resolved.referer}")
        if (startPositionMs > 0) addOption(":start-time=${startPositionMs / 1000.0}")
    }
    private val vlc = VlcMediaPlayer(lib).apply { setMedia(this@VlcSoftwarePlayer.media) }
    private val handler = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            if (!released) when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> vlc.setVolume(20)
                AudioManager.AUDIOFOCUS_GAIN -> vlc.setVolume(if (wanted) 100 else 0)
            }
        }, handler).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (!released) pause() }
    }
    private var output: Any? = null
    private val resize = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        if (!released && view.width > 0 && view.height > 0) vlc.vlcVout.setWindowSize(view.width, view.height)
    }
    private var prepared = false
    private var started = false
    private var released = false
    private var wanted = false
    private var status = Player.STATE_IDLE
    private var failure: PlaybackException? = null
    private var position = startPositionMs
    private var length = C.TIME_UNSET
    private var seekable = false
    private var parameters = PlaybackParameters(speed)
    private var size = VideoSize.UNKNOWN
    private var tracks = Tracks.EMPTY
    private var firstFrame = false
    private var announceFrame = false
    private var lastDiagnostic = 0L
    var metrics = DecodeMetrics(0, 0, 0)
        private set

    init {
        androidx.core.content.ContextCompat.registerReceiver(appContext, noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        vlc.setEventListener { event ->
            if (!released && prepared) {
                if (com.libvio.tv.BuildConfig.DEBUG && event.type in setOf(VlcMediaPlayer.Event.Playing, VlcMediaPlayer.Event.EncounteredError, VlcMediaPlayer.Event.Vout, VlcMediaPlayer.Event.EndReached)) android.util.Log.i("LibvioSoftware", "event=${event.type}")
                when (event.type) {
                    VlcMediaPlayer.Event.Playing -> { status = Player.STATE_READY; vlc.rate = parameters.speed }
                    VlcMediaPlayer.Event.Buffering -> status = if (event.buffering < 100f) Player.STATE_BUFFERING else Player.STATE_READY
                    VlcMediaPlayer.Event.EndReached -> {
                        if (isCompletePlayback(vlc.time.coerceAtLeast(position), vlc.length)) status = Player.STATE_ENDED
                        else {
                            status = Player.STATE_IDLE
                            failure = PlaybackException("Video stream ended before its duration", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                        }
                        audio.abandonAudioFocusRequest(focus)
                    }
                    VlcMediaPlayer.Event.EncounteredError -> {
                        status = Player.STATE_IDLE
                        failure = PlaybackException("Software video decoding failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
                    }
                }
                refresh()
            }
        }
        handler.post(tick)
    }

    private val tick: Runnable get() = object : Runnable {
        override fun run() {
            if (released) return
            if (prepared) refresh()
            handler.postDelayed(this, 250)
        }
    }

    private fun refresh() {
        if (released) return
        if (started) {
            vlc.time.takeIf { it >= 0 }?.let { position = it }
            vlc.length.takeIf { it > 0 }?.let { length = it }
            seekable = vlc.isSeekable
            vlc.currentVideoTrack?.let { track ->
                size = VideoSize(track.width, track.height,
                    if (track.sarDen > 0) track.sarNum.toFloat() / track.sarDen else 1f)
                val format = Format.Builder().setSampleMimeType(when (track.codec) {
                    "h264" -> MimeTypes.VIDEO_H264
                    "hevc", "h265" -> MimeTypes.VIDEO_H265
                    else -> MimeTypes.VIDEO_UNKNOWN
                }).setWidth(track.width).setHeight(track.height).setAverageBitrate(track.bitrate).build()
                tracks = Tracks(listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
            }
            media.stats?.let { stats ->
                metrics = DecodeMetrics(stats.decodedVideo, stats.displayedPictures, stats.lostPictures)
                // Vout creation and an advancing playhead do not prove that video was displayed.
                if (!firstFrame && stats.displayedPictures > 0 && size.width > 0 && output != null) {
                    firstFrame = true
                    announceFrame = true
                    if (!wanted) vlc.pause()
                }
            }
        }
        if (com.libvio.tv.BuildConfig.DEBUG && android.os.SystemClock.elapsedRealtime() - lastDiagnostic > 5000) {
            lastDiagnostic = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("LibvioSoftware", "prepared=$prepared started=$started surface=${output != null} attached=${vlc.vlcVout.areViewsAttached()} status=$status position=$position size=${size.width}x${size.height} decoded=${metrics.decoded} shown=${metrics.displayed} lost=${metrics.lost}")
        }
        invalidateState()
    }

    override fun getState(): State {
        val rendered = announceFrame
        announceFrame = false
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TRACKS, Player.COMMAND_GET_VOLUME, Player.COMMAND_GET_AUDIO_ATTRIBUTES
        ).build()
        return State.Builder().setAvailableCommands(commands)
            .setPlaylist(listOf(MediaItemData.Builder(item.mediaId).setMediaItem(item).setTracks(tracks)
                .setIsSeekable(seekable).setDurationUs(if (length == C.TIME_UNSET) C.TIME_UNSET else length * 1000).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(PositionSupplier { position })
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(status).setPlayerError(failure).setPlaybackParameters(parameters)
            .setVideoSize(size).setNewlyRenderedFirstFrame(rendered).build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        status = Player.STATE_BUFFERING
        maybeStart()
        return Futures.immediateVoidFuture()
    }

    private fun maybeStart() {
        if (released || !prepared || started || output == null) return
        started = true
        vlc.setVolume(if (wanted) 100 else 0)
        // Prime one silent frame when a source was switched while paused.
        vlc.play()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady && audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!wanted) audio.abandonAudioFocusRequest(focus)
        vlc.setVolume(if (wanted) 100 else 0)
        if (started && firstFrame) { if (wanted) vlc.play() else vlc.pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters
        vlc.rate = parameters.speed
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        position = positionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        vlc.setTime(position, false)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (released || output === videoOutput) return Futures.immediateVoidFuture()
        detachOutput()
        output = videoOutput
        val vout = vlc.vlcVout
        when (videoOutput) {
            is SurfaceView -> vout.setVideoView(videoOutput)
            is TextureView -> vout.setVideoView(videoOutput)
            is SurfaceHolder -> vout.setVideoSurface(videoOutput.surface, videoOutput)
            is Surface -> vout.setVideoSurface(videoOutput, null)
        }
        (videoOutput as? View)?.let { view ->
            view.addOnLayoutChangeListener(resize)
            if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
        }
        vout.attachViews()
        maybeStart()
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (!released && (videoOutput == null || videoOutput === output)) detachOutput()
        return Futures.immediateVoidFuture()
    }

    private fun detachOutput() {
        (output as? View)?.removeOnLayoutChangeListener(resize)
        vlc.vlcVout.detachViews()
        output = null
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        started = false
        wanted = false
        status = Player.STATE_IDLE
        vlc.stop()
        audio.abandonAudioFocusRequest(focus)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        handler.removeCallbacksAndMessages(null)
        vlc.setEventListener(null)
        audio.abandonAudioFocusRequest(focus)
        appContext.unregisterReceiver(noisy)
        detachOutput()
        vlc.release()
        media.release()
        lib.release()
        return Futures.immediateVoidFuture()
    }
}

internal data class DecodeMetrics(val decoded: Int, val displayed: Int, val lost: Int)
