package `in`.aniscroll.tv.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import `in`.aniscroll.tv.core.device.TvDeviceCapabilities
import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.domain.model.StreamData
import `in`.aniscroll.tv.domain.model.SubtitleTrack
import java.io.IOException

data class VideoRenderingHealthSnapshot(
    val hasVideoTrackGroup: Boolean,
    val isVideoTrackSelected: Boolean,
    val isVideoTrackSupported: Boolean,
    val renderedOutputBufferCount: Int,
    val skippedOutputBufferCount: Int,
    val droppedBufferCount: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val activeDecoderName: String?,
    val lastVideoCodecError: String?,
    val isUsingSoftwareDecoderFallback: Boolean
)

/**
 * Manages the single shared AndroidX Media3 ExoPlayer instance, tuned DefaultLoadControl,
 * adaptive track selection with non-disabling video constraints, hardware/software decoder
 * compatibility, shared OkHttp HTTP/2 connection pooling for HLS manifests/segments,
 * real-time video rendering health inspection, and deterministic lifecycle teardown.
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
    private var onVideoFormatChangedCallback: ((Int, Int, Int, String?, String?, Float?) -> Unit)? = null

    @Volatile
    private var onVideoCodecErrorCallback: ((String) -> Unit)? = null

    @Volatile
    private var activeVideoDecoderCounters: DecoderCounters? = null

    @Volatile
    private var activeVideoDecoderName: String? = null

    @Volatile
    private var lastVideoCodecError: String? = null

    @Volatile
    private var lastVideoWidth: Int = 0

    @Volatile
    private var lastVideoHeight: Int = 0

    @Volatile
    private var lastSurfaceWidth: Int = 0

    @Volatile
    private var lastSurfaceHeight: Int = 0

    @Volatile
    var preferSoftwareDecoderFallback: Boolean = false
        private set

    private val lock = Any()

    private val analyticsListener = object : AnalyticsListener {
        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int
        ) {
            val stateName = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($state)"
            }
            StreamingDiagnostics.logEvent(
                "playback_state_changed state=$stateName pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onIsPlayingChanged(
            eventTime: AnalyticsListener.EventTime,
            isPlaying: Boolean
        ) {
            StreamingDiagnostics.logEvent(
                "is_playing_changed is_playing=$isPlaying pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException
        ) {
            StreamingDiagnostics.logEvent(
                "analytics_player_error code=${error.errorCodeName} message=${error.message ?: "none"} pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onPlayerErrorChanged(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException?
        ) {
            StreamingDiagnostics.logEvent(
                "player_error_changed code=${error?.errorCodeName ?: "cleared"} pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) {
                onManifestLoadedCallback?.invoke(loadEventInfo.loadDurationMs.coerceAtLeast(0L))
            } else if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
                StreamingDiagnostics.logEvent(
                    "hls_video_segment_loaded duration_ms=${loadEventInfo.loadDurationMs} " +
                        "bytes=${loadEventInfo.bytesLoaded} start_ms=${mediaLoadData.mediaStartTimeMs} end_ms=${mediaLoadData.mediaEndTimeMs}"
                )
            }
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            val dataTypeName = when (mediaLoadData.dataType) {
                C.DATA_TYPE_MANIFEST -> "MANIFEST"
                C.DATA_TYPE_MEDIA -> "MEDIA_SEGMENT"
                else -> "OTHER(${mediaLoadData.dataType})"
            }
            StreamingDiagnostics.logEvent(
                "hls_load_error type=$dataTypeName trackType=${mediaLoadData.trackType} " +
                    "canceled=$wasCanceled error=${error.javaClass.simpleName}:${error.message ?: "none"}"
            )
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData
        ) {
            if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
                val fmt = mediaLoadData.trackFormat
                if (fmt != null) {
                    StreamingDiagnostics.logEvent(
                        "downstream_video_format mime=${fmt.sampleMimeType ?: "unknown"} " +
                            "codecs=${fmt.codecs ?: "default"} res=${fmt.width}x${fmt.height} " +
                            "fps=${fmt.frameRate} bitrate=${fmt.bitrate}"
                    )
                }
            }
        }

        override fun onVideoEnabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters
        ) {
            activeVideoDecoderCounters = decoderCounters
            StreamingDiagnostics.logEvent(
                "video_renderer_enabled pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onVideoDisabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters
        ) {
            decoderCounters.ensureUpdated()
            StreamingDiagnostics.logEvent(
                "video_renderer_disabled rendered_frames=${decoderCounters.renderedOutputBufferCount} " +
                    "dropped_frames=${decoderCounters.droppedBufferCount} pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
            if (activeVideoDecoderCounters === decoderCounters) {
                activeVideoDecoderCounters = null
            }
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            activeVideoDecoderName = decoderName
            lastVideoCodecError = null
            StreamingDiagnostics.latestMetrics?.let { current ->
                StreamingDiagnostics.recordDecoderInitialized(current, decoderName, initializationDurationMs)
            } ?: StreamingDiagnostics.logEvent(
                "video_decoder_init decoder=$decoderName init_duration_ms=$initializationDurationMs"
            )
        }

        override fun onVideoDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String
        ) {
            if (activeVideoDecoderName == decoderName) {
                activeVideoDecoderName = null
            }
            StreamingDiagnostics.latestMetrics?.let { current ->
                StreamingDiagnostics.recordDecoderReleased(current, decoderName)
            } ?: StreamingDiagnostics.logEvent("video_decoder_released decoder=$decoderName")
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            lastVideoCodecError = null
            StreamingDiagnostics.logEvent(
                "rendered_first_frame surface=${output.javaClass.simpleName} pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
            onFirstFrameRenderedCallback?.invoke()
        }

        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: VideoSize
        ) {
            lastVideoWidth = videoSize.width
            lastVideoHeight = videoSize.height
            StreamingDiagnostics.logEvent(
                "video_size_changed width=${videoSize.width} height=${videoSize.height} " +
                    "pixel_ratio=${videoSize.pixelWidthHeightRatio} pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onSurfaceSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            width: Int,
            height: Int
        ) {
            lastSurfaceWidth = width
            lastSurfaceHeight = height
            StreamingDiagnostics.latestMetrics?.let { current ->
                StreamingDiagnostics.recordSurfaceState(current, "${width}x${height}")
            } ?: StreamingDiagnostics.logEvent(
                "surface_size_changed width=$width height=$height pos_ms=${eventTime.currentPlaybackPositionMs}"
            )
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            val w = format.width.coerceAtLeast(0)
            val h = format.height.coerceAtLeast(0)
            if (w > 0 && h > 0) {
                lastVideoWidth = w
                lastVideoHeight = h
            }
            val hwSupported = TvDeviceCapabilities.canHardwareDecodeFormat(
                profile = deviceProfile,
                mimeType = format.sampleMimeType,
                width = w,
                height = h,
                codecs = format.codecs
            )
            StreamingDiagnostics.logEvent(
                "video_input_format mime=${format.sampleMimeType ?: "unknown"} codecs=${format.codecs ?: "default"} " +
                    "res=${w}x${h} fps=${format.frameRate} bitrate=${format.bitrate} " +
                    "hw_supported=$hwSupported reuse_result=${decoderReuseEvaluation?.result ?: -1}"
            )
            onVideoFormatChangedCallback?.invoke(
                w,
                h,
                format.bitrate.coerceAtLeast(0),
                format.sampleMimeType,
                format.codecs,
                format.frameRate.takeIf { it > 0f }
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            StreamingDiagnostics.latestMetrics?.let { current ->
                StreamingDiagnostics.recordDroppedFrames(current, droppedFrames, elapsedMs)
            } ?: StreamingDiagnostics.logEvent(
                "video_dropped_frames dropped=$droppedFrames elapsed_ms=$elapsedMs"
            )
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (frameCount > 0) {
                val avgOffsetMs = (totalProcessingOffsetUs / frameCount) / 1000L
                if (avgOffsetMs > 40L) {
                    StreamingDiagnostics.logEvent(
                        "video_frame_processing_offset avg_offset_ms=$avgOffsetMs frames=$frameCount"
                    )
                }
            }
        }

        override fun onVideoCodecError(
            eventTime: AnalyticsListener.EventTime,
            videoCodecError: Exception
        ) {
            val msg = "${videoCodecError.javaClass.simpleName}: ${videoCodecError.message ?: "unknown"}"
            lastVideoCodecError = msg
            StreamingDiagnostics.latestMetrics?.let { current ->
                StreamingDiagnostics.recordDecoderError(current, msg)
            } ?: StreamingDiagnostics.logEvent("video_codec_error detail=$msg")
            onVideoCodecErrorCallback?.invoke(msg)
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
        onVideoFormatChanged: (Int, Int, Int) -> Unit,
        onVideoCodecError: ((String) -> Unit)? = null
    ) {
        onManifestLoadedCallback = onManifestLoaded
        onFirstFrameRenderedCallback = onFirstFrameRendered
        onVideoFormatChangedCallback = { w, h, bitrate, _, _, _ ->
            onVideoFormatChanged(w, h, bitrate)
        }
        onVideoCodecErrorCallback = onVideoCodecError
    }

    /**
     * Returns the existing ExoPlayer without creating a new instance.
     * Always use this inside teardown, background, and polling paths.
     */
    fun peekPlayer(): ExoPlayer? = exoPlayer

    val deviceProfile: `in`.aniscroll.tv.core.device.TvDeviceProfile by lazy {
        TvDeviceCapabilities.getDeviceProfile(context)
    }

    fun resetDecoderFallbackForNewSession() {
        preferSoftwareDecoderFallback = false
        lastVideoCodecError = null
    }

    /**
     * Enables software decoder preference when a hardware MediaCodec decoder fails mid-stream,
     * and recreates the ExoPlayer instance cleanly so the next loadStream uses the fallback selector.
     */
    fun enableSoftwareDecoderFallbackAndRecreate(): ExoPlayer {
        synchronized(lock) {
            preferSoftwareDecoderFallback = true
            lastVideoCodecError = null
            StreamingDiagnostics.logEvent("decoder_fallback_activated mode=prefer_software_decoder")
            releasePlayer()
            return getOrCreatePlayer()
        }
    }

    fun getVideoRenderingHealthSnapshot(player: ExoPlayer = getOrCreatePlayer()): VideoRenderingHealthSnapshot {
        val counters = activeVideoDecoderCounters
        counters?.ensureUpdated()

        val tracks = player.currentTracks
        var hasVideoGroup = false
        var isVideoSelected = false
        var isVideoSupported = false

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO && group.length > 0) {
                hasVideoGroup = true
                if (group.isSelected) {
                    isVideoSelected = true
                }
                if (group.isSupported) {
                    isVideoSupported = true
                }
            }
        }

        val currentVideoSize = player.videoSize
        val width = if (currentVideoSize.width > 0) currentVideoSize.width else lastVideoWidth
        val height = if (currentVideoSize.height > 0) currentVideoSize.height else lastVideoHeight

        return VideoRenderingHealthSnapshot(
            hasVideoTrackGroup = hasVideoGroup,
            isVideoTrackSelected = isVideoSelected,
            isVideoTrackSupported = isVideoSupported,
            renderedOutputBufferCount = counters?.renderedOutputBufferCount ?: 0,
            skippedOutputBufferCount = counters?.skippedOutputBufferCount ?: 0,
            droppedBufferCount = counters?.droppedBufferCount ?: 0,
            videoWidth = width,
            videoHeight = height,
            surfaceWidth = lastSurfaceWidth,
            surfaceHeight = lastSurfaceHeight,
            activeDecoderName = activeVideoDecoderName,
            lastVideoCodecError = lastVideoCodecError,
            isUsingSoftwareDecoderFallback = preferSoftwareDecoderFallback
        )
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
                /* minDurationForQualityIncreaseMs = */ 8_000,
                /* maxDurationForQualityDecreaseMs = */ 20_000,
                /* minDurationToRetainAfterDiscardMs = */ 15_000,
                /* bandwidthFraction = */ if (profile.isLowRamDevice) 0.75f else 0.80f
            )

            // CRITICAL FIX: setExceedVideoConstraintsIfNecessary(true) and
            // setExceedRendererCapabilitiesIfNecessary(true) ensure DefaultTrackSelector NEVER
            // disables the video renderer (causing black screen while audio plays) when an HLS
            // stream's available video variant exceeds a soft resolution or profile constraint.
            val trackSelector = DefaultTrackSelector(appCtx, adaptiveFactory).apply {
                val maxH = profile.effectiveMaxPlaybackHeight
                val maxW = if (maxH >= 2160) 3840 else 1920
                parameters = buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .setMaxVideoSize(maxW, maxH)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .build()
            }

            val adaptiveCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
                if (!mimeType.startsWith("video/") || defaultList.size <= 1) {
                    defaultList
                } else if (preferSoftwareDecoderFallback && !requiresSecureDecoder && !requiresTunnelingDecoder) {
                    // Controlled fallback mode: prefer software video decoders when hardware decoder failed
                    val swFirst = defaultList.sortedBy { info ->
                        if (TvDeviceCapabilities.isSoftwareCodecName(info.name)) 0 else 1
                    }
                    swFirst
                } else {
                    // Normal mode: prefer hardware decoders first, with software decoders retained as fallback
                    val hwFirst = defaultList.sortedBy { info: MediaCodecInfo ->
                        if (TvDeviceCapabilities.isSoftwareCodecName(info.name)) 1 else 0
                    }
                    hwFirst
                }
            }

            val renderersFactory = DefaultRenderersFactory(appCtx)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(adaptiveCodecSelector)

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
        lastVideoCodecError = null

        val okHttpFactory = OkHttpDataSource.Factory(NetworkModule.streamingOkHttpClient)
            .setUserAgent(streamData.headers["User-Agent"] ?: DEFAULT_USER_AGENT)

        if (streamData.headers.isNotEmpty()) {
            okHttpFactory.setDefaultRequestProperties(streamData.headers)
        }

        // Deduplicate external subtitle tracks by language/URL and only attach valid HTTP/HTTPS subtitle URLs
        val subtitleConfigs = streamData.subtitles
            .filter { it.url.startsWith("http://", ignoreCase = true) || it.url.startsWith("https://", ignoreCase = true) }
            .distinctBy { "${it.language.lowercase()}_${it.url}" }
            .map { sub -> buildSubtitleConfiguration(sub) }

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

        // Ensure video track is explicitly enabled before preparing the new media source
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()

        StreamingDiagnostics.logEvent(
            "load_stream is_hls=$isHls start_pos_ms=$startPositionMs subs=${subtitleConfigs.size} sw_fallback=$preferSoftwareDecoderFallback"
        )

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
            activeVideoDecoderCounters = null
            lastVideoCodecError = null
            lastVideoWidth = 0
            lastVideoHeight = 0
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
            activeVideoDecoderCounters = null
            activeVideoDecoderName = null
            lastVideoCodecError = null
            lastVideoWidth = 0
            lastVideoHeight = 0
            lastSurfaceWidth = 0
            lastSurfaceHeight = 0
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
