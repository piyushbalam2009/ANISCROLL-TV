package `in`.aniscroll.tv.data.repository

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

enum class StreamingProvider(val slug: String) {
    ZOKO_ANIME("zokoanime"),
    MEGA_PLAY("megaplay")
}

sealed class StreamLoadResult {
    data class Success(
        val streamData: StreamData,
        val skipIntervals: SkipIntervals,
        val savedPositionMs: Long
    ) : StreamLoadResult()

    data class Error(val userMessage: String) : StreamLoadResult()
}

class PlaybackRepository(
    private val aniScrollApi: AniScrollApi,
    private val aniSkipApi: AniSkipApi,
    private val dao: AniScrollDao,
    private val idConverter: HoshikaIdConverter
) {

    companion object {
        private const val COMPLETION_THRESHOLD = 0.90f

        /**
         * Determines the streaming provider strictly from the anime format:
         * - MOVIE -> ZokoAnime (`GET /api/watch/zokoanime/{anilist_id}/{episode}/{sub/dub}`)
         * - All other formats (TV, TV_SHORT, SPECIAL, OVA, ONA, episodic) -> MegaPlay (`GET /api/watch/megaplay/{anilist_id}/{episode}/{sub/dub}`)
         */
        fun selectProviderForFormat(format: String?): StreamingProvider {
            return if (format?.trim()?.equals("MOVIE", ignoreCase = true) == true) {
                StreamingProvider.ZOKO_ANIME
            } else {
                StreamingProvider.MEGA_PLAY
            }
        }
    }

    /**
     * Complete playback resolution flow:
     * 1. Convert Hoshika ID -> AniList ID and resolve anime format using HoshikaIdConverter.
     * 2. Validate mapping (never pass Hoshika ID directly).
     * 3. Select the streaming provider based on anime format BEFORE making the API request:
     *    - If format is MOVIE: call GET https://anivault-scraper-five.vercel.app/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}
     *    - Otherwise (TV series, TV specials, episodic): call GET https://anivault-scraper-five.vercel.app/api/watch/megaplay/{anilist_id}/{episode}/{sub|dub}
     * 4. Fetch AniSkip v2 timestamps using the AniList ID.
     * 5. Retrieve saved playback progress from Room.
     */
    suspend fun resolveEpisodeStream(
        anime: Anime,
        episodeNumber: Int,
        language: String
    ): StreamLoadResult {
        val normalizedLang = if (language.equals("dub", ignoreCase = true)) "dub" else "sub"
        val targetEpisode = if (episodeNumber > 0) episodeNumber else 1

        // Step 1: Convert Hoshika ID -> AniList ID and resolve format metadata
        val resolvedTarget = idConverter.resolveTarget(anime)
        val anilistId = resolvedTarget?.anilistId

        if (anilistId == null || anilistId <= 0L) {
            return StreamLoadResult.Error("Unable to find a compatible streaming ID for this anime.")
        }

        // Step 2: Select provider strictly from anime format before making the network request
        val effectiveFormat = resolvedTarget.format ?: anime.format
        val provider = selectProviderForFormat(effectiveFormat)

        // Step 3: Request HLS stream from the selected provider only (never cross-calling the other provider)
        val response = runCatching {
            when (provider) {
                StreamingProvider.ZOKO_ANIME -> {
                    aniScrollApi.getZokoAnimeStreams(
                        anilistId = anilistId,
                        episode = targetEpisode,
                        language = normalizedLang
                    )
                }
                StreamingProvider.MEGA_PLAY -> {
                    aniScrollApi.getMegaPlayStreams(
                        anilistId = anilistId,
                        episode = targetEpisode,
                        language = normalizedLang
                    )
                }
            }
        }.getOrElse {
            return StreamLoadResult.Error("Unable to connect. Check your internet connection.")
        }

        if (!response.isSuccessful) {
            val msg = if (normalizedLang == "dub" && response.code() == 404) {
                "No DUB streaming source was found for this episode."
            } else {
                "No playable streaming source was found."
            }
            return StreamLoadResult.Error(msg)
        }

        val rawBody = response.body()?.string()
        if (rawBody.isNullOrBlank()) {
            return StreamLoadResult.Error("No playable streaming source was found.")
        }

        val streamData = JsonParsers.parseAniScrollStreamResponse(
            anilistId = anilistId,
            episodeNumber = targetEpisode,
            language = normalizedLang,
            rawBody = rawBody
        ) ?: return StreamLoadResult.Error("No playable streaming source was found.")

        // Step 4: Fetch AniSkip v2 intervals (gracefully fallback if unavailable)
        val skipIntervals = fetchAniSkipIntervals(
            anilistId = anilistId,
            episodeNumber = targetEpisode,
            streamData = streamData
        )

        // Step 5: Check saved playback position in Room
        val savedHistory = dao.getEpisodeProgress(anime.hoshikaId, targetEpisode)
        val resumePositionMs = if (savedHistory != null && !savedHistory.completed) {
            savedHistory.position
        } else {
            0L
        }

        return StreamLoadResult.Success(
            streamData = streamData,
            skipIntervals = skipIntervals,
            savedPositionMs = resumePositionMs
        )
    }

    private suspend fun fetchAniSkipIntervals(
        anilistId: Long,
        episodeNumber: Int,
        streamData: StreamData
    ): SkipIntervals {
        val apiResp = runCatching {
            aniSkipApi.getSkipTimes(animeId = anilistId, episodeNumber = episodeNumber)
        }.getOrNull()

        if (apiResp != null && apiResp.isSuccessful) {
            val raw = apiResp.body()?.string()
            if (!raw.isNullOrBlank()) {
                val parsed = JsonParsers.parseAniSkipResponse(raw)
                if (parsed.intro != null || parsed.outro != null) {
                    return parsed
                }
            }
        }

        // Fallback to intro/outro timestamps included in the AniScroll stream payload if present
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
    ) {
        if (durationMs <= 0L || positionMs < 0L) return

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
