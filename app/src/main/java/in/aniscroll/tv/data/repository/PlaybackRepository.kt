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
    }

    /**
     * Complete playback resolution flow (Sections 8, 18, 19):
     * 1. Convert Hoshika ID -> AniList ID using HoshikaIdConverter.
     * 2. Validate mapping (never pass Hoshika ID directly).
     * 3. Call GET https://api.aniscroll.in/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}.
     * 4. Fetch AniSkip v2 timestamps using the AniList ID.
     * 5. Retrieve saved playback progress from Room.
     */
    suspend fun resolveEpisodeStream(
        anime: Anime,
        episodeNumber: Int,
        language: String
    ): StreamLoadResult {
        val normalizedLang = if (language.equals("dub", ignoreCase = true)) "dub" else "sub"

        // Step 1: Convert Hoshika ID -> AniList ID
        val anilistId = idConverter.convert(anime)

        if (anilistId == null || anilistId <= 0L) {
            return StreamLoadResult.Error("Unable to find a compatible streaming ID for this anime.")
        }

        // Step 2: Request HLS stream from AniScroll backend
        val response = runCatching {
            aniScrollApi.getEpisodeStreams(
                anilistId = anilistId,
                episode = episodeNumber,
                language = normalizedLang
            )
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
            episodeNumber = episodeNumber,
            language = normalizedLang,
            rawBody = rawBody
        ) ?: return StreamLoadResult.Error("No playable streaming source was found.")

        // Step 3: Fetch AniSkip v2 intervals (gracefully fallback if unavailable)
        val skipIntervals = fetchAniSkipIntervals(
            anilistId = anilistId,
            episodeNumber = episodeNumber,
            streamData = streamData
        )

        // Step 4: Check saved playback position in Room
        val savedHistory = dao.getEpisodeProgress(anime.hoshikaId, episodeNumber)
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
