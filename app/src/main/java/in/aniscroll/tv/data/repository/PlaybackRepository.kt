package `in`.aniscroll.tv.data.repository

import android.os.SystemClock
import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.WatchHistoryEntity
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.AniSkipApi
import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.domain.converter.HoshikaIdConverter
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.SkipIntervals
import `in`.aniscroll.tv.domain.model.StreamData
import `in`.aniscroll.tv.domain.model.TimeRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed class StreamLoadResult {
    data class Success(
        val streamData: StreamData,
        val skipIntervals: SkipIntervals,
        val savedPositionMs: Long,
        val provider: StreamingProvider = StreamingProvider.MEGA_PLAY,
        val serverResolutionDurationMs: Long = 0L,
        val apiRequestDurationMs: Long = 0L,
        val fromShortLivedCache: Boolean = false
    ) : StreamLoadResult()

    data class Error(
        val userMessage: String,
        val provider: StreamingProvider? = null,
        val httpStatusCode: Int? = null
    ) : StreamLoadResult()
}

private data class CachedStreamEntry(
    val streamData: StreamData,
    val provider: StreamingProvider,
    val cachedAtElapsedMs: Long
)

class PlaybackRepository(
    private val aniScrollApi: AniScrollApi,
    private val aniSkipApi: AniSkipApi,
    private val dao: AniScrollDao,
    private val idConverter: HoshikaIdConverter
) {

    companion object {
        private const val COMPLETION_THRESHOLD = 0.90f
        // Short-lived cache (90s) avoids duplicate watch requests without keeping expiring CDN tokens stale
        private const val STREAM_CACHE_TTL_MS = 90_000L

        /**
         * Delegates to [StreamingSourceResolver.resolveSource] to select the mandatory provider:
         * - MOVIE -> ZokoAnime ("zokoanime")
         * - TV, TV_SHORT, OVA, ONA, SPECIAL -> MegaPlay ("megaplay")
         */
        fun selectProviderForFormat(format: String?): StreamingProvider {
            return StreamingSourceResolver.resolveSource(format)
        }
    }

    private val shortLivedStreamCache = ConcurrentHashMap<String, CachedStreamEntry>()
    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<StreamData?>>()

    fun invalidateCachedStream(anilistId: Long, episodeNumber: Int, language: String) {
        val normalizedLang = if (language.equals("dub", ignoreCase = true)) "dub" else "sub"
        shortLivedStreamCache.keys.removeIf { key ->
            key.endsWith(":$anilistId:$episodeNumber:$normalizedLang")
        }
    }

    /**
     * Fast-start playback resolution flow:
     * 1. Convert Hoshika ID -> AniList ID and resolve anime format using HoshikaIdConverter.
     * 2. Select the streaming provider via [StreamingSourceResolver] BEFORE making the API request:
     *    - MOVIE -> GET https://anivault-scraper-five.vercel.app/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}
     *    - Series -> GET https://anivault-scraper-five.vercel.app/api/watch/megaplay/{anilist_id}/{episode}/{sub|dub}
     * 3. Deduplicate concurrent in-flight requests and reuse short-lived (90s) cached stream data when valid.
     * 4. Extract embedded skip intervals immediately and do NOT block stream startup on external AniSkip HTTP calls.
     * 5. Retrieve saved playback progress from Room.
     */
    suspend fun resolveEpisodeStream(
        anime: Anime,
        episodeNumber: Int,
        language: String,
        forceRefresh: Boolean = false
    ): StreamLoadResult = withContext(Dispatchers.IO) {
        val normalizedLang = if (language.equals("dub", ignoreCase = true)) "dub" else "sub"

        // Step 1: Convert Hoshika ID -> AniList ID and resolve format metadata
        val resolveStartMs = SystemClock.elapsedRealtime()
        val resolvedTarget = idConverter.resolveTarget(anime)
        val serverResolutionMs = (SystemClock.elapsedRealtime() - resolveStartMs).coerceAtLeast(0L)
        val anilistId = resolvedTarget?.anilistId

        if (anilistId == null || anilistId <= 0L) {
            return@withContext StreamLoadResult.Error("Unable to find a compatible streaming ID for this anime.")
        }

        // Step 2: Select provider and normalize episode parameter strictly from anime format
        val effectiveFormat = resolvedTarget.format ?: anime.format
        val provider = StreamingSourceResolver.resolveSource(effectiveFormat)
        val targetEpisode = StreamingSourceResolver.resolveEpisodeNumber(effectiveFormat, episodeNumber)
        val cacheKey = "${provider.sourceId}:$anilistId:$targetEpisode:$normalizedLang"

        if (forceRefresh) {
            shortLivedStreamCache.remove(cacheKey)
        } else {
            val nowElapsed = SystemClock.elapsedRealtime()
            val cached = shortLivedStreamCache[cacheKey]
            if (cached != null && (nowElapsed - cached.cachedAtElapsedMs) < STREAM_CACHE_TTL_MS) {
                val savedHistory = dao.getEpisodeProgress(anime.hoshikaId, targetEpisode)
                val resumePositionMs = if (savedHistory != null && !savedHistory.completed) {
                    savedHistory.position
                } else {
                    0L
                }
                return@withContext StreamLoadResult.Success(
                    streamData = cached.streamData,
                    skipIntervals = extractEmbeddedSkipIntervals(cached.streamData),
                    savedPositionMs = resumePositionMs,
                    provider = provider,
                    serverResolutionDurationMs = serverResolutionMs,
                    apiRequestDurationMs = 0L,
                    fromShortLivedCache = true
                )
            }
        }

        // Step 3: Deduplicate concurrent identical requests
        val existingDeferred = inFlightRequests[cacheKey]
        if (existingDeferred != null && !forceRefresh) {
            val awaitedStart = SystemClock.elapsedRealtime()
            val sharedStream = runCatching { existingDeferred.await() }.getOrNull()
            val awaitedMs = (SystemClock.elapsedRealtime() - awaitedStart).coerceAtLeast(0L)
            if (sharedStream != null) {
                val savedHistory = dao.getEpisodeProgress(anime.hoshikaId, targetEpisode)
                val resumePositionMs = if (savedHistory != null && !savedHistory.completed) {
                    savedHistory.position
                } else {
                    0L
                }
                return@withContext StreamLoadResult.Success(
                    streamData = sharedStream,
                    skipIntervals = extractEmbeddedSkipIntervals(sharedStream),
                    savedPositionMs = resumePositionMs,
                    provider = provider,
                    serverResolutionDurationMs = serverResolutionMs,
                    apiRequestDurationMs = awaitedMs,
                    fromShortLivedCache = true
                )
            }
        }

        val deferred = CompletableDeferred<StreamData?>()
        inFlightRequests[cacheKey] = deferred

        try {
            val apiStartMs = SystemClock.elapsedRealtime()
            val response = runCatching {
                aniScrollApi.getWatchStreams(
                    source = provider.sourceId,
                    anilistId = anilistId,
                    episode = targetEpisode,
                    language = normalizedLang
                )
            }.getOrElse {
                deferred.complete(null)
                return@withContext StreamLoadResult.Error(
                    userMessage = "Unable to connect. Check your internet connection.",
                    provider = provider
                )
            }
            val apiDurationMs = (SystemClock.elapsedRealtime() - apiStartMs).coerceAtLeast(0L)

            if (!response.isSuccessful) {
                deferred.complete(null)
                val code = response.code()
                val msg = when {
                    normalizedLang == "dub" && code == 404 ->
                        "No DUB streaming source is available for this title."
                    code == 403 ->
                        "Stream access was denied (HTTP 403). Please retry."
                    code == 404 ->
                        "No playable streaming source was found for this title."
                    code == 429 ->
                        "Too many stream requests (HTTP 429). Please wait a moment and retry."
                    code in 500..599 ->
                        "Streaming server error (HTTP $code). Please retry."
                    else ->
                        "No playable streaming source was found (HTTP $code)."
                }
                return@withContext StreamLoadResult.Error(
                    userMessage = msg,
                    provider = provider,
                    httpStatusCode = code
                )
            }

            val rawBody = response.body()?.string()
            if (rawBody.isNullOrBlank()) {
                deferred.complete(null)
                return@withContext StreamLoadResult.Error(
                    userMessage = "No playable streaming source was found.",
                    provider = provider
                )
            }

            val streamData = JsonParsers.parseAniScrollStreamResponse(
                anilistId = anilistId,
                episodeNumber = targetEpisode,
                language = normalizedLang,
                rawBody = rawBody
            )
            if (streamData == null) {
                deferred.complete(null)
                return@withContext StreamLoadResult.Error(
                    userMessage = if (normalizedLang == "dub") {
                        "No DUB streaming source is available for this title."
                    } else {
                        "No playable streaming source was found."
                    },
                    provider = provider
                )
            }

            shortLivedStreamCache[cacheKey] = CachedStreamEntry(
                streamData = streamData,
                provider = provider,
                cachedAtElapsedMs = SystemClock.elapsedRealtime()
            )
            deferred.complete(streamData)

            // Extract embedded skip intervals immediately; external AniSkip v2 is fetched non-blockingly by PlaybackController
            val initialSkipIntervals = extractEmbeddedSkipIntervals(streamData)

            // Check saved playback position in Room
            val savedHistory = dao.getEpisodeProgress(anime.hoshikaId, targetEpisode)
            val resumePositionMs = if (savedHistory != null && !savedHistory.completed) {
                savedHistory.position
            } else {
                0L
            }

            return@withContext StreamLoadResult.Success(
                streamData = streamData,
                skipIntervals = initialSkipIntervals,
                savedPositionMs = resumePositionMs,
                provider = provider,
                serverResolutionDurationMs = serverResolutionMs,
                apiRequestDurationMs = apiDurationMs,
                fromShortLivedCache = false
            )
        } finally {
            inFlightRequests.remove(cacheKey, deferred)
            if (!deferred.isCompleted) {
                deferred.complete(null)
            }
        }
    }

    fun extractEmbeddedSkipIntervals(streamData: StreamData): SkipIntervals {
        val fallbackIntro = if (streamData.introStartSec != null && streamData.introEndSec != null &&
            streamData.introEndSec > streamData.introStartSec
        ) {
            TimeRange(startTimeSec = streamData.introStartSec, endTimeSec = streamData.introEndSec)
        } else null

        val fallbackOutro = if (streamData.outroStartSec != null && streamData.outroEndSec != null &&
            streamData.outroEndSec > streamData.outroStartSec
        ) {
            TimeRange(startTimeSec = streamData.outroStartSec, endTimeSec = streamData.outroEndSec)
        } else null

        return SkipIntervals(intro = fallbackIntro, outro = fallbackOutro)
    }

    /**
     * Non-blocking AniSkip v2 interval lookup.
     * If the stream payload already contains both intro and outro intervals, skips the network call.
     */
    suspend fun fetchAniSkipIntervals(
        anilistId: Long,
        episodeNumber: Int,
        streamData: StreamData
    ): SkipIntervals = withContext(Dispatchers.IO) {
        val embedded = extractEmbeddedSkipIntervals(streamData)
        if (embedded.intro != null && embedded.outro != null) {
            return@withContext embedded
        }

        val apiResp = runCatching {
            aniSkipApi.getSkipTimes(animeId = anilistId, episodeNumber = episodeNumber)
        }.getOrNull()

        if (apiResp != null && apiResp.isSuccessful) {
            val raw = apiResp.body()?.string()
            if (!raw.isNullOrBlank()) {
                val parsed = JsonParsers.parseAniSkipResponse(raw)
                if (parsed.intro != null || parsed.outro != null) {
                    return@withContext SkipIntervals(
                        intro = parsed.intro ?: embedded.intro,
                        outro = parsed.outro ?: embedded.outro
                    )
                }
            }
        }

        embedded
    }

    /**
     * Saves playback progress in Room.
     * Marks episode as completed when progress >= 90% so completed episodes are not left in Continue Watching.
     */
    suspend fun savePlaybackProgress(
        anime: Anime,
        anilistId: Long,
        episodeNumber: Int,
        language: String,
        positionMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        if (durationMs <= 0L || positionMs < 0L) return@withContext

        val fraction = positionMs.toFloat() / durationMs.toFloat()
        val isCompleted = fraction >= COMPLETION_THRESHOLD
        val storedPosition = if (isCompleted) durationMs else positionMs

        dao.upsertWatchHistory(
            WatchHistoryEntity(
                hoshikaId = anime.hoshikaId,
                anilistId = anilistId,
                episodeNumber = episodeNumber,
                language = language,
                position = storedPosition,
                duration = durationMs,
                completed = isCompleted,
                lastWatched = System.currentTimeMillis(),
                animeTitle = anime.preferredTitle,
                posterUrl = anime.posterUrl,
                bannerUrl = anime.bannerUrl,
                logoUrl = anime.logoUrl
            )
        )
    }
}
