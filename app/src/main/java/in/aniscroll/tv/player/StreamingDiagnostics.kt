package `in`.aniscroll.tv.player

import android.os.SystemClock
import android.util.Log
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
    val timeToFirstFrameMs: Long? = null,
    val episodeSwitchDurationMs: Long? = null,
    val bufferingDurationMs: Long = 0L,
    val rebufferCount: Int = 0,
    val selectedResolution: String? = null,
    val selectedBitrateKbps: Int? = null,
    val networkTier: String? = null
)

/**
 * Performance diagnostics logger for the ANISCROLL-TV streaming pipeline.
 * All metrics are emitted under Logcat tag `ANISCROLL_PERFORMANCE`.
 * Never logs authorization tokens, cookies, API keys, or sensitive headers.
 */
object StreamingDiagnostics {

    const val TAG = "ANISCROLL_PERFORMANCE"

    private val sessionSequence = AtomicInteger(0)

    @Volatile
    var latestMetrics: StreamingSessionMetrics? = null
        private set

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

    fun recordFirstFrameRendered(session: StreamingSessionMetrics): StreamingSessionMetrics {
        if (session.timeToFirstFrameMs != null) return session
        val ttffMs = (SystemClock.elapsedRealtime() - session.playRequestStartElapsedMs).coerceAtLeast(0L)
        val switchMs = if (session.isEpisodeSwitch) ttffMs else null
        val updated = session.copy(
            timeToFirstFrameMs = ttffMs,
            episodeSwitchDurationMs = switchMs
        )
        latestMetrics = updated
        logInfo(
            "first_frame_rendered id=${updated.sessionId} time_to_first_frame_ms=$ttffMs" +
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
        bitrateBps: Int
    ): StreamingSessionMetrics {
        val res = if (width > 0 && height > 0) "${width}x${height}" else session.selectedResolution
        val kbps = if (bitrateBps > 0) bitrateBps / 1000 else session.selectedBitrateKbps
        val updated = session.copy(
            selectedResolution = res,
            selectedBitrateKbps = kbps
        )
        latestMetrics = updated
        logInfo(
            "video_format id=${updated.sessionId} resolution=${res ?: "unknown"} bitrate_kbps=${kbps ?: -1}"
        )
        return updated
    }

    fun logEvent(message: String) {
        logInfo(message)
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
