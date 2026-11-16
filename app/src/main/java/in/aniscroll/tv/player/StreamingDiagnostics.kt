package `in`.aniscroll.tv.player

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class StreamingSessionMetrics(
    val sessionId: Long,
    val anilistId: Long? = null,
    val episodeNumber: Int = 1,
    val sourceProvider: String? = null,
    val isEpisodeSwitch: Boolean = false,
    val playRequestStartElapsedMs: Long = SystemClock.elapsedRealtime(),
    val serverResolutionDurationMs: Long? = null,
    val apiRequestDurationMs: Long? = null,
    val timeToPlayableUrlMs: Long? = null,
    val hlsManifestLoadDurationMs: Long? = null,
    val firstSegmentLoadDurationMs: Long? = null,
    val timeToFirstSegmentMs: Long? = null,
    val playerPreparationStartElapsedMs: Long? = null,
    val playerPreparationDurationMs: Long? = null,
    val decoderInitDurationMs: Long? = null,
    val timeToFirstFrameMs: Long? = null,
    val totalStartupDurationMs: Long? = null,
    val episodeSwitchDurationMs: Long? = null,
    val bufferingDurationMs: Long = 0L,
    val rebufferCount: Int = 0,
    val selectedResolution: String? = null,
    val selectedBitrateKbps: Int? = null,
    val videoCodecMimeType: String? = null,
    val videoCodecProfile: String? = null,
    val videoFrameRate: Float? = null,
    val activeDecoderName: String? = null,
    val droppedFramesTotal: Int = 0,
    val videoRecoveryAttempts: Int = 0,
    val lastSurfaceState: String? = null,
    val lastDecoderError: String? = null,
    val networkTier: String? = null
)

/**
 * Performance and Media3 video-pipeline diagnostics logger for ANISCROLL-TV.
 * All metrics and diagnostic events are emitted under Logcat tag `ANISCROLL_PERFORMANCE`.
 * Never logs authorization tokens, cookies, API keys, or sensitive headers.
 */
object StreamingDiagnostics {

    const val TAG = "ANISCROLL_PERFORMANCE"
    private const val MAX_RECENT_EVENTS = 64

    private val sessionSequence = AtomicInteger(0)
    private val recentEventsBuffer = CopyOnWriteArrayList<String>()

    @Volatile
    var latestMetrics: StreamingSessionMetrics? = null
        private set

    fun getRecentDiagnosticEvents(): List<String> = recentEventsBuffer.toList()

    fun startSession(
        episodeNumber: Int,
        isEpisodeSwitch: Boolean,
        networkTier: String
    ): StreamingSessionMetrics {
        val metrics = StreamingSessionMetrics(
            sessionId = sessionSequence.incrementAndGet().toLong(),
            episodeNumber = episodeNumber,
            isEpisodeSwitch = isEpisodeSwitch,
            playRequestStartElapsedMs = SystemClock.elapsedRealtime(),
            networkTier = networkTier
        )
        latestMetrics = metrics
        logInfo(
            "session_start id=${metrics.sessionId} ep=$episodeNumber switch=$isEpisodeSwitch networkTier=$networkTier"
        )
        return metrics
    }

    fun recordServerResolution(
        session: StreamingSessionMetrics,
        anilistId: Long?,
        provider: String,
        durationMs: Long
    ): StreamingSessionMetrics {
        val updated = session.copy(
            anilistId = anilistId,
            sourceProvider = provider,
            serverResolutionDurationMs = durationMs
        )
        latestMetrics = updated
        logInfo(
            "server_resolution id=${updated.sessionId} provider=$provider anilistId=${anilistId ?: -1} duration_ms=$durationMs"
        )
        return updated
    }

    fun recordApiAndPlayableUrl(
        session: StreamingSessionMetrics,
        apiDurationMs: Long,
        cacheHit: Boolean
    ): StreamingSessionMetrics {
        val totalToUrlMs = (SystemClock.elapsedRealtime() - session.playRequestStartElapsedMs).coerceAtLeast(0L)
        val updated = session.copy(
            apiRequestDurationMs = apiDurationMs,
            timeToPlayableUrlMs = totalToUrlMs
        )
        latestMetrics = updated
        logInfo(
            "playable_url_ready id=${updated.sessionId} provider=${updated.sourceProvider ?: "unknown"} " +
                "api_duration_ms=$apiDurationMs time_to_playable_url_ms=$totalToUrlMs cache_hit=$cacheHit"
        )
        return updated
    }

    fun recordPlayerPreparationStarted(
        session: StreamingSessionMetrics
    ): StreamingSessionMetrics {
        val prepStartMs = SystemClock.elapsedRealtime()
        val updated = session.copy(playerPreparationStartElapsedMs = prepStartMs)
        latestMetrics = updated
        logInfo(
            "player_preparation_start id=${updated.sessionId} elapsed_since_play_ms=${(prepStartMs - session.playRequestStartElapsedMs).coerceAtLeast(0L)}"
        )
        return updated
    }

    fun recordPlayerPrepared(
        session: StreamingSessionMetrics,
        preparationDurationMs: Long? = null
    ): StreamingSessionMetrics {
        if (session.playerPreparationDurationMs != null) return session
        val now = SystemClock.elapsedRealtime()
        val computedPrepMs = preparationDurationMs
            ?: session.playerPreparationStartElapsedMs?.let { (now - it).coerceAtLeast(0L) }
            ?: (now - session.playRequestStartElapsedMs).coerceAtLeast(0L)
        val updated = session.copy(playerPreparationDurationMs = computedPrepMs)
        latestMetrics = updated
        logInfo(
            "player_prepared id=${updated.sessionId} preparation_duration_ms=$computedPrepMs"
        )
        return updated
    }

    fun recordHlsManifestLoaded(
        session: StreamingSessionMetrics,
        manifestLoadDurationMs: Long
    ): StreamingSessionMetrics {
        val updated = session.copy(hlsManifestLoadDurationMs = manifestLoadDurationMs)
        latestMetrics = updated
        logInfo(
            "hls_manifest_loaded id=${updated.sessionId} manifest_load_duration_ms=$manifestLoadDurationMs"
        )
        return updated
    }

    fun recordFirstSegmentLoaded(
        session: StreamingSessionMetrics,
        segmentLoadDurationMs: Long
    ): StreamingSessionMetrics {
        if (session.firstSegmentLoadDurationMs != null) return session
        val timeToFirstSegMs = (SystemClock.elapsedRealtime() - session.playRequestStartElapsedMs).coerceAtLeast(0L)
        val updated = session.copy(
            firstSegmentLoadDurationMs = segmentLoadDurationMs.coerceAtLeast(0L),
            timeToFirstSegmentMs = timeToFirstSegMs
        )
        latestMetrics = updated
        logInfo(
            "first_segment_loaded id=${updated.sessionId} segment_load_duration_ms=${updated.firstSegmentLoadDurationMs} " +
                "time_to_first_segment_ms=$timeToFirstSegMs"
        )
        return updated
    }

    fun recordFirstFrameRendered(session: StreamingSessionMetrics): StreamingSessionMetrics {
        if (session.timeToFirstFrameMs != null) return session
        val ttffMs = (SystemClock.elapsedRealtime() - session.playRequestStartElapsedMs).coerceAtLeast(0L)
        val switchMs = if (session.isEpisodeSwitch) ttffMs else null
        val updated = session.copy(
            timeToFirstFrameMs = ttffMs,
            totalStartupDurationMs = ttffMs,
            episodeSwitchDurationMs = switchMs
        )
        latestMetrics = updated
        logInfo(
            "first_frame_rendered id=${updated.sessionId} time_to_first_frame_ms=$ttffMs " +
                "total_startup_ms=$ttffMs " +
                "server_res_ms=${updated.serverResolutionDurationMs ?: -1} " +
                "api_req_ms=${updated.apiRequestDurationMs ?: -1} " +
                "url_ready_ms=${updated.timeToPlayableUrlMs ?: -1} " +
                "manifest_ms=${updated.hlsManifestLoadDurationMs ?: -1} " +
                "first_seg_ms=${updated.firstSegmentLoadDurationMs ?: -1} " +
                "player_prep_ms=${updated.playerPreparationDurationMs ?: -1} " +
                "decoder_init_ms=${updated.decoderInitDurationMs ?: -1}" +
                (switchMs?.let { " episode_switch_duration_ms=$it" } ?: "")
        )
        return updated
    }

    fun recordBufferingCompleted(
        session: StreamingSessionMetrics,
        stallDurationMs: Long,
        isRebuffer: Boolean
    ): StreamingSessionMetrics {
        val updated = session.copy(
            bufferingDurationMs = session.bufferingDurationMs + stallDurationMs.coerceAtLeast(0L),
            rebufferCount = if (isRebuffer) session.rebufferCount + 1 else session.rebufferCount
        )
        latestMetrics = updated
        logInfo(
            "buffering_sample id=${updated.sessionId} stall_ms=$stallDurationMs " +
                "total_buffering_ms=${updated.bufferingDurationMs} rebuffer_count=${updated.rebufferCount}"
        )
        return updated
    }

    fun recordVideoFormat(
        session: StreamingSessionMetrics,
        width: Int,
        height: Int,
        bitrateBps: Int,
        sampleMimeType: String? = null,
        codecs: String? = null,
        frameRate: Float? = null
    ): StreamingSessionMetrics {
        val res = if (width > 0 && height > 0) "${width}x${height}" else session.selectedResolution
        val kbps = if (bitrateBps > 0) bitrateBps / 1000 else session.selectedBitrateKbps
        val mime = sampleMimeType?.takeIf { it.isNotBlank() } ?: session.videoCodecMimeType
        val profile = codecs?.takeIf { it.isNotBlank() } ?: session.videoCodecProfile
        val fps = frameRate?.takeIf { it > 0f } ?: session.videoFrameRate
        val updated = session.copy(
            selectedResolution = res,
            selectedBitrateKbps = kbps,
            videoCodecMimeType = mime,
            videoCodecProfile = profile,
            videoFrameRate = fps
        )
        latestMetrics = updated
        logInfo(
            "video_format id=${updated.sessionId} resolution=${res ?: "unknown"} " +
                "bitrate_kbps=${kbps ?: -1} mime=${mime ?: "unknown"} codecs=${profile ?: "default"} fps=${fps ?: -1f}"
        )
        return updated
    }

    fun recordDecoderInitialized(
        session: StreamingSessionMetrics,
        decoderName: String,
        initDurationMs: Long
    ): StreamingSessionMetrics {
        val updated = session.copy(
            activeDecoderName = decoderName,
            decoderInitDurationMs = initDurationMs.coerceAtLeast(0L)
        )
        latestMetrics = updated
        logInfo(
            "video_decoder_init id=${updated.sessionId} decoder=$decoderName init_duration_ms=$initDurationMs"
        )
        return updated
    }

    fun recordDecoderReleased(
        session: StreamingSessionMetrics,
        decoderName: String
    ): StreamingSessionMetrics {
        logInfo(
            "video_decoder_released id=${session.sessionId} decoder=$decoderName"
        )
        return session
    }

    fun recordDroppedFrames(
        session: StreamingSessionMetrics,
        droppedCount: Int,
        elapsedMs: Long
    ): StreamingSessionMetrics {
        val updated = session.copy(
            droppedFramesTotal = session.droppedFramesTotal + droppedCount.coerceAtLeast(0)
        )
        latestMetrics = updated
        logInfo(
            "video_dropped_frames id=${updated.sessionId} dropped=$droppedCount " +
                "elapsed_ms=$elapsedMs total_dropped=${updated.droppedFramesTotal}"
        )
        return updated
    }

    fun recordDecoderError(
        session: StreamingSessionMetrics,
        errorDescription: String
    ): StreamingSessionMetrics {
        val updated = session.copy(lastDecoderError = errorDescription)
        latestMetrics = updated
        logInfo(
            "video_codec_error id=${updated.sessionId} detail=$errorDescription"
        )
        return updated
    }

    fun recordSurfaceState(
        session: StreamingSessionMetrics,
        stateDescription: String
    ): StreamingSessionMetrics {
        val updated = session.copy(lastSurfaceState = stateDescription)
        latestMetrics = updated
        logInfo(
            "video_surface_state id=${updated.sessionId} state=$stateDescription"
        )
        return updated
    }

    fun recordVideoRecoveryAttempt(
        session: StreamingSessionMetrics,
        attempt: Int,
        reason: String,
        positionMs: Long
    ): StreamingSessionMetrics {
        val updated = session.copy(videoRecoveryAttempts = attempt)
        latestMetrics = updated
        logInfo(
            "video_recovery_attempt id=${updated.sessionId} attempt=$attempt pos_ms=$positionMs reason=$reason"
        )
        return updated
    }

    fun logEvent(message: String) {
        logInfo(message)
    }

    private fun logInfo(message: String) {
        if (recentEventsBuffer.size >= MAX_RECENT_EVENTS) {
            runCatching { recentEventsBuffer.removeAt(0) }
        }
        recentEventsBuffer.add(message)
        runCatching { Log.i(TAG, message) }
    }
}
