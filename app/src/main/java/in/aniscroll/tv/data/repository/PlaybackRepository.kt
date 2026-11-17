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
import kotlinx.coroutines.async
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
    val cachedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long
)

class PlaybackRepository(
    private val aniScrollApi: AniScrollApi,
    private val aniSkipApi: AniSkipApi,
    private val dao: AniScrollDao,
    private val idConverter: HoshikaIdConverter
) {

    companion object {
        private const val COMPLETION_THRESHOLD = 0.90f
        // Short-lived cache (180s) keeps prewarmed detail-page and next-episode streams warm without keeping expiring CDN tokens stale
        private const val STREAM_CACHE_TTL_MS = 180_000L
        private const val SIGNED_URL_EXPIRY_SAFETY_BUFFER_MS = 15_000L
        private val SIGNED_EXPIRY_PARAM_REGEX = Regex("""(?:[?&])(exp|expires|expire)=(\d{10,13})""", RegexOption.IGNORE_CASE)

        /**
         * Delegates to [StreamingSourceResolver.resolveSource] to select the mandatory provider:
         * - MOVIE -> ZokoAnime ("zokoanime")
         * - TV, TV_SHORT, OVA, ONA, SPECIAL -> MegaPlay ("megaplay")
         */
        fun selectProviderForFormat(format: String?): StreamingProvider {
            return StreamingSourceResolver.resolveSource(format)
        }

        internal fun computeCacheExpiresAtElapsedMs(
            streamData: StreamData,
            cachedAtElapsedMs: Long,
            nowEpochMs: Long = System.currentTimeMillis()
        ): Long {
            val defaultExpiry = cachedAtElapsedMs + STREAM_CACHE_TTL_MS
            var earliestSignedRemainingMs: Long? = null

            for (source in streamData.sources) {
                val match = SIGNED_EXPIRY_PARAM_REGEX.find(source.url) ?: continue
                val rawTimestamp = match.groupValues.getOrNull(2)?.toLongOrNull() ?: continue
                val expiryEpochMs = if (rawTimestamp < 10_000_000_000L) rawTimestamp * 1000L else rawTimestamp
                val remainingMs = (expiryEpochMs - nowEpochMs - SIGNED_URL_EXPIRY_SAFETY_BUFFER_MS).coerceAtLeast(0L)
                earliestSignedRemainingMs = if (earliestSignedRemainingMs == null) {
                    remainingMs
                } else {
                    minOf(earliestSignedRemainingMs, remainingMs)
                }
            }

            return if (earliestSignedRemainingMs != null) {
                minOf(defaultExpiry, cachedAtElapsedMs + earliestSignedRemainingMs)
            } else {
                defaultExpiry
            }
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

    fun hasFreshCachedStream(
        format: String?,
        anilistId: Long,
        episodeNumber: Int,
        language: String,
        hoshikaId: Long? = null
    ): Boolean {
        if (anilistId <= 0L) return false
        val normalizedLang = if (language.equals("dub", ignoreCase = true)) "dub" else "sub"
        val provider = StreamingSourceResolver.resolveSource(format)
        val targetEpisode = StreamingSourceResolver.resolveEpisodeNumber(format, episodeNumber)
        val nowElapsed = SystemClock.elapsedRealtime()
        val exactKey = hoshikaId?.takeIf { it > 0L }?.let {
            "${provider.sourceId}:$it:$anilistId:$targetEpisode:$normalizedLang"
        }
        if (exactKey != null) {
            val cached = shortLivedStreamCache[exactKey] ?: return false
            return nowElapsed < cached.expiresAtElapsedMs
        }
        return shortLivedStreamCache.entries.any { (key, entry) ->
            key.startsWith("${provider.sourceId}:") &&
                key.endsWith(":$anilistId:$targetEpisode:$normalizedLang") &&
                nowElapsed < entry.expiresAtElapsedMs
        }
    }

    /**
     * Pre-resolves the AniList ID and pre-warms the stream metadata in [shortLivedStreamCache]
     * without downloading video segments, so pressing Play on the detail page or advancing to
     * the next episode resolves in 0ms.
     */
    suspend fun prewarmEpisodeStream(
        anime: Anime,
        episodeNumber: Int,
        language: String = "sub"
    ): StreamLoadResult = withContext(Dispatchers.IO) {
        `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
            "prewarm_stream_start hoshika_id=${anime.hoshikaId} ep=$episodeNumber lang=$language"
        )
        resolveEpisodeStream(
            anime = anime,
            episodeNumber = episodeNumber,
            language = language,
            forceRefresh = false
        )
    }

    /**
     * Fast-start playback resolution flow:
     * 1. Convert Hoshika ID -> AniList ID and resolve anime format using HoshikaIdConverter while concurrently querying Room for saved progress.
     * 2. Select the streaming provider via [StreamingSourceResolver] BEFORE making the API request:
     *    - MOVIE -> GET https://anivault-scraper-five.vercel.app/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}
     *    - Series -> GET https://anivault-scraper-five.vercel.app/api/watch/megaplay/{anilist_id}/{episode}/{sub|dub}
     * 3. Deduplicate concurrent in-flight requests and reuse short-lived (180s) cached stream data when valid.
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
        val preliminaryEpisode = StreamingSourceResolver.resolveEpisodeNumber(anime.format, episodeNumber)
        val preliminaryProgressDeferred = async {
            dao.getEpisodeProgress(anime.hoshikaId, preliminaryEpisode)
        }

        // Step 1: Convert Hoshika ID -> AniList ID and resolve format metadata
        val resolveStartMs = SystemClock.elapsedRealtime()
        val resolvedTarget = idConverter.resolveTarget(anime)
        val serverResolutionMs = (SystemClock.elapsedRealtime() - resolveStartMs).coerceAtLeast(0L)
        val anilistId = resolvedTarget?.anilistId

        if (anilistId == null || anilistId <= 0L) {
            preliminaryProgressDeferred.cancel()
            return@withContext StreamLoadResult.Error("Unable to find a compatible streaming ID for this anime.")
        }

        // Step 2: Select provider and normalize episode parameter strictly from anime format
        val effectiveFormat = resolvedTarget.format ?: anime.format
        val provider = StreamingSourceResolver.resolveSource(effectiveFormat)
        val targetEpisode = StreamingSourceResolver.resolveEpisodeNumber(effectiveFormat, episodeNumber)
        val cacheKey = "${provider.sourceId}:${anime.hoshikaId}:$anilistId:$targetEpisode:$normalizedLang"

        suspend fun awaitResumePositionMs(): Long {
            val savedHistory = if (targetEpisode == preliminaryEpisode) {
                preliminaryProgressDeferred.await()
            } else {
                preliminaryProgressDeferred.cancel()
                dao.getEpisodeProgress(anime.hoshikaId, targetEpisode)
            }
            return if (savedHistory != null && !savedHistory.completed) {
                savedHistory.position
            } else {
                0L
            }
        }

        if (forceRefresh) {
            shortLivedStreamCache.remove(cacheKey)
        } else {
            val nowElapsed = SystemClock.elapsedRealtime()
            val cached = shortLivedStreamCache[cacheKey]
            if (cached != null && nowElapsed < cached.expiresAtElapsedMs) {
                `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                    "stream_cache_hit key=$cacheKey audio=${cached.streamData.language} " +
                        "primary_url=${cached.streamData.sources.firstOrNull()?.url?.substringBefore("&ref=") ?: "none"}"
                )
                val resumePositionMs = awaitResumePositionMs()
                return@withContext StreamLoadResult.Success(
                    streamData = cached.streamData,
                    skipIntervals = extractEmbeddedSkipIntervals(cached.streamData),
                    savedPositionMs = resumePositionMs,
                    provider = provider,
                    serverResolutionDurationMs = serverResolutionMs,
                    apiRequestDurationMs = 0L,
                    fromShortLivedCache = true
                )
            } else if (cached != null) {
                shortLivedStreamCache.remove(cacheKey, cached)
            }
        }

        // Step 3: Deduplicate concurrent identical requests
        val existingDeferred = inFlightRequests[cacheKey]
        if (existingDeferred != null && !forceRefresh) {
            val awaitedStart = SystemClock.elapsedRealtime()
            val sharedStream = runCatching { existingDeferred.await() }.getOrNull()
            val awaitedMs = (SystemClock.elapsedRealtime() - awaitedStart).coerceAtLeast(0L)
            if (sharedStream != null) {
                val resumePositionMs = awaitResumePositionMs()
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
            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                "stream_resolve_request hoshika_id=${anime.hoshikaId} anilist_id=$anilistId " +
                    "ep=$targetEpisode source=${provider.sourceId} requested_audio=$normalizedLang " +
                    "endpoint=api/watch/${provider.sourceId}/$anilistId/$targetEpisode/$normalizedLang"
            )
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
                preliminaryProgressDeferred.cancel()
                return@withContext StreamLoadResult.Error(
                    userMessage = "Unable to connect. Check your internet connection.",
                    provider = provider
                )
            }
            val apiDurationMs = (SystemClock.elapsedRealtime() - apiStartMs).coerceAtLeast(0L)

            if (!response.isSuccessful) {
                deferred.complete(null)
                preliminaryProgressDeferred.cancel()
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
                preliminaryProgressDeferred.cancel()
                return@withContext StreamLoadResult.Error(
                    userMessage = "No playable streaming source was found.",
                    provider = provider
                )
            }

            val rawStreamData = JsonParsers.parseAniScrollStreamResponse(
                anilistId = anilistId,
                episodeNumber = targetEpisode,
                language = normalizedLang,
                rawBody = rawBody
            )
            if (rawStreamData == null) {
                deferred.complete(null)
                preliminaryProgressDeferred.cancel()
                return@withContext StreamLoadResult.Error(
                    userMessage = if (normalizedLang == "dub") {
                        "No DUB streaming source is available for this title."
                    } else {
                        "No playable streaming source was found."
                    },
                    provider = provider
                )
            }

            val resolvedMalId = rawStreamData.malId
                ?: resolvedTarget.malId
                ?: anime.malId?.takeIf { it > 0L }
            val streamData = if (rawStreamData.malId == null && resolvedMalId != null) {
                rawStreamData.copy(malId = resolvedMalId)
            } else {
                rawStreamData
            }

            val cachedAtMs = SystemClock.elapsedRealtime()
            val expiresAtMs = computeCacheExpiresAtElapsedMs(streamData, cachedAtMs)
            shortLivedStreamCache[cacheKey] = CachedStreamEntry(
                streamData = streamData,
                provider = provider,
                cachedAtElapsedMs = cachedAtMs,
                expiresAtElapsedMs = expiresAtMs
            )
            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                "stream_resolve_response source=${provider.sourceId} anilist_id=$anilistId ep=$targetEpisode " +
                    "requested_audio=$normalizedLang returned_audio=${streamData.language} " +
                    "sources=${streamData.sources.size} subs=${streamData.subtitles.size} " +
                    "primary_url=${streamData.sources.firstOrNull()?.url?.substringBefore("&ref=") ?: "none"}"
            )
            deferred.complete(streamData)

            // Extract embedded skip intervals immediately; external AniSkip v2 is fetched non-blockingly by PlaybackController
            val initialSkipIntervals = extractEmbeddedSkipIntervals(streamData)

            // Await concurrently-fetched saved playback position from Room
            val resumePositionMs = awaitResumePositionMs()

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

    private val skipIntervalsCache = ConcurrentHashMap<String, SkipIntervals>()

    fun extractEmbeddedSkipIntervals(streamData: StreamData): SkipIntervals {
        val offset = streamData.timestampOffsetSec

        fun buildValidRange(startSec: Float?, endSec: Float?, tag: String): TimeRange? {
            if (startSec == null || endSec == null || startSec.isNaN() || endSec.isNaN()) return null
            val adjustedStart = (startSec + offset).coerceAtLeast(0f)
            val adjustedEnd = endSec + offset
            if (adjustedEnd <= adjustedStart || (adjustedEnd - adjustedStart) < 2f) {
                `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                    "skip_embedded_invalid_ignored type=$tag start=$startSec end=$endSec offset=$offset"
                )
                return null
            }
            return TimeRange(
                startTimeSec = adjustedStart,
                endTimeSec = adjustedEnd,
                skipId = tag
            )
        }

        val fallbackIntro = buildValidRange(streamData.introStartSec, streamData.introEndSec, "embedded_intro")
        val fallbackOutro = buildValidRange(streamData.outroStartSec, streamData.outroEndSec, "embedded_outro")
        val fallbackRecap = buildValidRange(streamData.recapStartSec, streamData.recapEndSec, "embedded_recap")

        `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
            "skip_embedded_extracted anilist_id=${streamData.anilistId} mal_id=${streamData.malId ?: -1} ep=${streamData.episodeNumber} " +
                "intro=${fallbackIntro?.let { "${it.startTimeSec}..${it.endTimeSec}s" } ?: "none"} " +
                "outro=${fallbackOutro?.let { "${it.startTimeSec}..${it.endTimeSec}s" } ?: "none"} " +
                "recap=${fallbackRecap?.let { "${it.startTimeSec}..${it.endTimeSec}s" } ?: "none"}"
        )

        return SkipIntervals(
            intro = fallbackIntro,
            outro = fallbackOutro,
            recap = fallbackRecap,
            anilistId = streamData.anilistId,
            malId = streamData.malId,
            episodeNumber = streamData.episodeNumber
        )
    }

    /**
     * Non-blocking AniSkip v2 interval lookup.
     * Queries AniSkip v2 (`https://api.aniskip.com/v2/skip-times/{malId}/{episodeNumber}`) using
     * the resolved MyAnimeList ID (`malId`) first (with `anilistId` fallback if needed),
     * merges with embedded stream skip intervals, and caches the result per episode.
     */
    suspend fun fetchAniSkipIntervals(
        anilistId: Long,
        episodeNumber: Int,
        streamData: StreamData,
        malId: Long? = streamData.malId,
        episodeDurationSec: Float = 0f
    ): SkipIntervals = withContext(Dispatchers.IO) {
        val normalizedEp = episodeNumber.coerceAtLeast(1)
        val effectiveMalId = (malId ?: streamData.malId)?.takeIf { it > 0L }
        val cacheKey = "$anilistId:${effectiveMalId ?: 0L}:$normalizedEp"

        val embedded = extractEmbeddedSkipIntervals(streamData)
        skipIntervalsCache[cacheKey]?.let { cached ->
            val mergedCached = SkipIntervals(
                intro = cached.intro ?: embedded.intro,
                outro = cached.outro ?: embedded.outro,
                recap = cached.recap ?: embedded.recap,
                anilistId = anilistId,
                malId = effectiveMalId,
                episodeNumber = normalizedEp
            )
            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                "aniskip_cache_hit anilist_id=$anilistId mal_id=${effectiveMalId ?: -1} ep=$normalizedEp " +
                    "intro=${mergedCached.intro != null} outro=${mergedCached.outro != null} recap=${mergedCached.recap != null}"
            )
            return@withContext mergedCached
        }

        // If embedded payload already provides intro, outro, and recap, no external request needed
        if (embedded.intro != null && embedded.outro != null && embedded.recap != null) {
            skipIntervalsCache[cacheKey] = embedded
            return@withContext embedded
        }

        // AniSkip v2 is indexed by MyAnimeList ID (malId). Try effectiveMalId first, then anilistId fallback.
        val candidateIds = listOfNotNull(
            effectiveMalId,
            anilistId.takeIf { it > 0L }
        ).distinct()

        val requestedTypes = listOf("op", "ed", "mixed-op", "mixed-ed", "recap")
        val sanitizedLength = if (episodeDurationSec > 30f && !episodeDurationSec.isNaN()) episodeDurationSec else 0f

        for (candidateId in candidateIds) {
            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                "aniskip_request id=$candidateId (mal_id=${effectiveMalId ?: -1}, anilist_id=$anilistId) " +
                    "ep=$normalizedEp types=${requestedTypes.joinToString(",")} ep_len=$sanitizedLength"
            )
            val apiResp = runCatching {
                aniSkipApi.getSkipTimes(
                    animeId = candidateId,
                    episodeNumber = normalizedEp,
                    types = requestedTypes,
                    episodeLength = sanitizedLength
                )
            }.onFailure { err ->
                `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                    "aniskip_request_error id=$candidateId ep=$normalizedEp error=${err.javaClass.simpleName}:${err.message ?: "none"}"
                )
            }.getOrNull()

            if (apiResp != null) {
                val statusCode = apiResp.code()
                if (apiResp.isSuccessful) {
                    val raw = apiResp.body()?.string()
                    if (!raw.isNullOrBlank()) {
                        val parsed = JsonParsers.parseAniSkipResponse(raw)
                        val offset = streamData.timestampOffsetSec
                        fun applyOffsetIfAny(range: TimeRange?): TimeRange? {
                            if (range == null) return null
                            if (offset == 0f) return range
                            val s = (range.startTimeSec + offset).coerceAtLeast(0f)
                            val e = range.endTimeSec + offset
                            return if (e > s + 2f) range.copy(startTimeSec = s, endTimeSec = e) else null
                        }

                        val apiIntro = applyOffsetIfAny(parsed.intro)
                        val apiOutro = applyOffsetIfAny(parsed.outro)
                        val apiRecap = applyOffsetIfAny(parsed.recap)

                        if (apiIntro != null || apiOutro != null || apiRecap != null) {
                            val merged = SkipIntervals(
                                intro = embedded.intro ?: apiIntro,
                                outro = embedded.outro ?: apiOutro,
                                recap = embedded.recap ?: apiRecap,
                                anilistId = anilistId,
                                malId = effectiveMalId,
                                episodeNumber = normalizedEp
                            )
                            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                                "aniskip_response_parsed id=$candidateId status=$statusCode ep=$normalizedEp " +
                                    "intro=${merged.intro?.let { "${it.startTimeSec}..${it.endTimeSec}s" } ?: "none"} " +
                                    "outro=${merged.outro?.let { "${it.startTimeSec}..${it.endTimeSec}s" } ?: "none"} " +
                                    "recap=${merged.recap?.let { "${it.startTimeSec}..${it.endTimeSec}s" } ?: "none"}"
                            )
                            skipIntervalsCache[cacheKey] = merged
                            return@withContext merged
                        } else {
                            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                                "aniskip_response_empty id=$candidateId status=$statusCode ep=$normalizedEp"
                            )
                        }
                    }
                } else {
                    `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                        "aniskip_response_http_status id=$candidateId status=$statusCode ep=$normalizedEp"
                    )
                }
            }
        }

        if (embedded.recap == null) {
            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                "skip_recap_unavailable anilist_id=$anilistId mal_id=${effectiveMalId ?: -1} ep=$normalizedEp " +
                    "reason=no_recap_timestamps_in_stream_or_aniskip"
            )
        }
        if (embedded.hasAnyInterval) {
            skipIntervalsCache[cacheKey] = embedded
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
