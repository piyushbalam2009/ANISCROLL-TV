package `in`.aniscroll.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.domain.model.StreamData
import `in`.aniscroll.tv.domain.model.SubtitleTrack

/**
 * Manages the single shared AndroidX Media3 ExoPlayer instance, tuned DefaultLoadControl,
 * adaptive track selection, shared OkHttp HTTP/2 connection pooling for HLS manifests/segments,
 * and deterministic lifecycle teardown.
 */
@OptIn(UnstableApi::class)
class ExoPlayerManager(private val context: Context) {

    companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 50_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_000
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
        const val BACK_BUFFER_DURATION_MS = 15_000
        private const val MIN_SEGMENT_RETRY_COUNT = 4

        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 AniScrollTV/1.0"
    }

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var attachedListener: Player.Listener? = null

    @Volatile
    private var onManifestLoadedCallback: ((Long) -> Unit)? = null

    @Volatile
    private var onFirstFrameRenderedCallback: (() -> Unit)? = null

    @Volatile
    private var onVideoFormatChangedCallback: ((Int, Int, Int) -> Unit)? = null

    private val lock = Any()

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) {
                onManifestLoadedCallback?.invoke(loadEventInfo.loadDurationMs.coerceAtLeast(0L))
            }
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            onFirstFrameRenderedCallback?.invoke()
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            onVideoFormatChangedCallback?.invoke(
                format.width.coerceAtLeast(0),
                format.height.coerceAtLeast(0),
                format.bitrate.coerceAtLeast(0)
            )
        }
    }

    fun setPlayerListener(listener: Player.Listener) {
        synchronized(lock) {
            val current = exoPlayer
            val previous = attachedListener
            if (current != null && previous != null && previous !== listener) {
                current.removeListener(previous)
            }
            attachedListener = listener
            if (current != null && previous !== listener) {
                current.addListener(listener)
            }
        }
    }

    fun setPerformanceCallbacks(
        onManifestLoaded: (Long) -> Unit,
        onFirstFrameRendered: () -> Unit,
        onVideoFormatChanged: (Int, Int, Int) -> Unit
    ) {
        onManifestLoadedCallback = onManifestLoaded
        onFirstFrameRenderedCallback = onFirstFrameRendered
        onVideoFormatChangedCallback = onVideoFormatChanged
    }

    /**
     * Returns the existing ExoPlayer without creating a new instance.
     * Always use this inside teardown, background, and polling paths.
     */
    fun peekPlayer(): ExoPlayer? = exoPlayer

    val deviceProfile: `in`.aniscroll.tv.core.device.TvDeviceProfile by lazy {
        `in`.aniscroll.tv.core.device.TvDeviceCapabilities.getDeviceProfile(context)
    }

    fun getOrCreatePlayer(): ExoPlayer {
        synchronized(lock) {
            exoPlayer?.let { return it }

            val appCtx = context.applicationContext
            val profile = deviceProfile
            val buf = profile.bufferProfile

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    buf.minBufferMs,
                    buf.maxBufferMs,
                    buf.bufferForPlaybackMs,
                    buf.bufferForPlaybackAfterRebufferMs
                )
                .setTargetBufferBytes(buf.targetBufferBytes)
                .setBackBuffer(buf.backBufferDurationMs, false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val adaptiveFactory = AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs = */ 6_000,
                /* maxDurationForQualityDecreaseMs = */ 15_000,
                /* minDurationToRetainAfterDiscardMs = */ 15_000,
                /* bandwidthFraction = */ if (profile.isLowRamDevice) 0.72f else 0.78f
            )
            val trackSelector = DefaultTrackSelector(appCtx, adaptiveFactory).apply {
                val maxH = profile.effectiveMaxPlaybackHeight
                val maxW = if (maxH >= 2160) 3840 else 1920
                parameters = buildUponParameters()
                    .setMaxVideoSize(maxW, maxH)
                    .setExceedVideoConstraintsIfNecessary(false)
                    .setExceedRendererCapabilitiesIfNecessary(false)
                    .build()
            }

            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(appCtx)
                .setEnableDecoderFallback(true)

            val bandwidthMeter = DefaultBandwidthMeter.Builder(appCtx)
                .setResetOnNetworkTypeChange(true)
                .build()

            val created = ExoPlayer.Builder(appCtx, renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setBandwidthMeter(bandwidthMeter)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .build()

            created.addAnalyticsListener(analyticsListener)
            attachedListener?.let { created.addListener(it) }
            exoPlayer = created
            return created
        }
    }

    fun loadStream(
        streamData: StreamData,
        selectedSourceUrl: String,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        playbackSpeed: Float = 1.0f
    ) {
        val player = getOrCreatePlayer()

        val okHttpFactory = OkHttpDataSource.Factory(NetworkModule.streamingOkHttpClient)
            .setUserAgent(streamData.headers["User-Agent"] ?: DEFAULT_USER_AGENT)

        if (streamData.headers.isNotEmpty()) {
            okHttpFactory.setDefaultRequestProperties(streamData.headers)
        }

        val subtitleConfigs = streamData.subtitles.map { sub ->
            buildSubtitleConfiguration(sub)
        }

        val isHls = selectedSourceUrl.contains(".m3u8", ignoreCase = true) ||
            selectedSourceUrl.contains("/proxy/hls", ignoreCase = true) ||
            streamData.sources.any { it.url == selectedSourceUrl && it.isM3u8 }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(selectedSourceUrl))
            .setSubtitleConfigurations(subtitleConfigs)

        if (isHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val mediaItem = mediaItemBuilder.build()
        val errorHandlingPolicy = DefaultLoadErrorHandlingPolicy(MIN_SEGMENT_RETRY_COUNT)

        val mediaSource = if (isHls && subtitleConfigs.isEmpty()) {
            HlsMediaSource.Factory(okHttpFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(errorHandlingPolicy)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(okHttpFactory)
                .setLoadErrorHandlingPolicy(errorHandlingPolicy)
                .createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
        player.setPlaybackSpeed(playbackSpeed.coerceIn(0.25f, 3.0f))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    private fun buildSubtitleConfiguration(sub: SubtitleTrack): MediaItem.SubtitleConfiguration {
        val lowerUrl = sub.url.lowercase()
        val mimeType = when {
            lowerUrl.endsWith(".srt") || lowerUrl.contains(".srt&") || lowerUrl.contains(".srt%") -> MimeTypes.APPLICATION_SUBRIP
            lowerUrl.endsWith(".ass") || lowerUrl.endsWith(".ssa") || lowerUrl.contains(".ass&") -> MimeTypes.TEXT_SSA
            lowerUrl.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT
        }

        val flags = if (sub.isDefault) C.SELECTION_FLAG_DEFAULT else 0

        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(mimeType)
            .setLanguage(sub.language)
            .setLabel(sub.label)
            .setSelectionFlags(flags)
            .build()
    }

    /**
     * Pauses active playback immediately without creating a player or allowing auto-resume.
     */
    fun pauseIfActive() {
        synchronized(lock) {
            val player = exoPlayer ?: return
            player.playWhenReady = false
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    /**
     * Stops playback and clears all loaded media items immediately.
     */
    fun stopAndClear() {
        synchronized(lock) {
            val player = exoPlayer ?: return
            player.playWhenReady = false
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    /**
     * Safely stops, detaches listeners, releases ExoPlayer resources, and nulls the reference.
     * Safe to invoke multiple times without crashing or leaking.
     */
    fun releasePlayer() {
        synchronized(lock) {
            val player = exoPlayer ?: return
            exoPlayer = null
            runCatching {
                player.removeAnalyticsListener(analyticsListener)
                attachedListener?.let { player.removeListener(it) }
            }
            runCatching {
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
            }
            runCatching {
                player.release()
            }
        }
    }
}
