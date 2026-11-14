package `in`.aniscroll.tv.data.remote

import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.ScheduleItem
import `in`.aniscroll.tv.domain.model.SkipIntervals
import `in`.aniscroll.tv.domain.model.StreamData
import `in`.aniscroll.tv.domain.model.StreamQualitySource
import `in`.aniscroll.tv.domain.model.SubtitleTrack
import `in`.aniscroll.tv.domain.model.TimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object JsonParsers {

    fun parseHoshikaAnime(json: JSONObject): Anime? {
        val hoshikaId = json.optLong("shortId", 0L).takeIf { it > 0L }
            ?: json.optLong("hoshika_id", 0L).takeIf { it > 0L }
            ?: json.optLong("hoshikaId", 0L).takeIf { it > 0L }
            ?: json.optLong("id", 0L).takeIf { it > 0L }
            ?: return null

        // Extract AniList ID strictly from Hoshika's anilist mapping field (never MAL or Hoshika ID)
        val mappingsObj = json.optJSONObject("mappings")
            ?: json.optJSONObject("external_ids")
            ?: json.optJSONObject("externalIds")
            ?: json.optJSONObject("crossReferences")

        val anilistId = json.optLong("anilist_id", 0L).takeIf { it > 0L }
            ?: json.optLong("anilistId", 0L).takeIf { it > 0L }
            ?: mappingsObj?.optLong("anilist_id", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("anilist", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("anilistId", 0L)?.takeIf { it > 0L }

        val malId = json.optLong("mal_id", 0L).takeIf { it > 0L }
            ?: json.optLong("malId", 0L).takeIf { it > 0L }
            ?: mappingsObj?.optLong("mal_id", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("mal", 0L)?.takeIf { it > 0L }

        val tmdbId = json.optLong("tmdb_id", 0L).takeIf { it > 0L }
            ?: json.optLong("tmdbId", 0L).takeIf { it > 0L }
            ?: mappingsObj?.optLong("tmdb_id", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("tmdb", 0L)?.takeIf { it > 0L }

        val titleObj = json.optJSONObject("title")
        val romajiTitle = json.optString("titleRomaji").takeNonNull()
            ?: titleObj?.optString("romaji").takeNonNull()
            ?: titleObj?.optString("default").takeNonNull()
            ?: json.optString("title").takeNonNull()
            ?: json.optString("name").takeNonNull()

        val englishTitle = json.optString("titleEnglish").takeNonNull()
            ?: json.optString("titleLocalized").takeNonNull()
            ?: titleObj?.optString("english").takeNonNull()
            ?: json.optString("english_title").takeNonNull()
            ?: json.optString("englishTitle").takeNonNull()

        val japaneseTitle = json.optString("titleJapanese").takeNonNull()
            ?: titleObj?.optString("native").takeNonNull()
            ?: titleObj?.optString("japanese").takeNonNull()
            ?: json.optString("japanese_title").takeNonNull()
            ?: json.optString("japaneseTitle").takeNonNull()

        val primaryTitle = romajiTitle ?: englishTitle ?: japaneseTitle ?: return null

        val artworkObj = json.optJSONObject("artwork")
            ?: json.optJSONObject("images")

        val posterUrl = json.optString("coverImage").takeNonNull()
            ?: json.optString("poster").takeNonNull()
            ?: json.optString("poster_url").takeNonNull()
            ?: json.optString("posterUrl").takeNonNull()
            ?: json.optString("cover").takeNonNull()
            ?: json.optString("image").takeNonNull()
            ?: artworkObj?.optString("poster").takeNonNull()
            ?: artworkObj?.optString("large").takeNonNull()

        val bannerUrl = json.optString("bannerImage").takeNonNull()
            ?: json.optString("banner").takeNonNull()
            ?: json.optString("banner_url").takeNonNull()
            ?: json.optString("bannerUrl").takeNonNull()
            ?: json.optString("background").takeNonNull()
            ?: artworkObj?.optString("banner").takeNonNull()
            ?: artworkObj?.optString("background").takeNonNull()

        val logoUrl = json.optString("logo_url").takeNonNull()
            ?: json.optString("logoUrl").takeNonNull()
            ?: artworkObj?.optString("logo").takeNonNull()

        val description = json.optString("synopsis").takeNonNull()
            ?: json.optString("description").takeNonNull()

        val cleanDescription = description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&quot;", "\"")
            ?.replace("&#039;", "'")
            ?.replace("&amp;", "&")
            ?.trim()

        val releaseYear = json.optInt("seasonYear", 0).takeIf { it > 1900 }
            ?: json.optInt("year", 0).takeIf { it > 1900 }
            ?: json.optInt("release_year", 0).takeIf { it > 1900 }
            ?: json.optInt("releaseYear", 0).takeIf { it > 1900 }
            ?: json.optString("startedAiring").takeNonNull()?.take(4)?.toIntOrNull()

        val season = json.optString("season").takeNonNull()
        val status = json.optString("status").takeNonNull()
        val format = json.optString("format").takeNonNull()
            ?: json.optString("type").takeNonNull()

        val totalEpisodes = json.optInt("episodes", 0).takeIf { it > 0 }
            ?: json.optInt("total_episodes", 0).takeIf { it > 0 }
            ?: json.optInt("totalEpisodes", 0).takeIf { it > 0 }
            ?: json.optInt("episodes_count", 0).takeIf { it > 0 }

        val rating = json.optDouble("averageScore", Double.NaN).takeIf { !it.isNaN() && it > 0 }

        val genres = mutableListOf<String>()
        val genresArr = json.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val item = genresArr.opt(i)
                when (item) {
                    is String -> if (item.isNotBlank() && item != "null") genres.add(item)
                    is JSONObject -> {
                        val name = item.optString("name").takeNonNull()
                            ?: item.optString("label").takeNonNull()
                            ?: item.optString("title").takeNonNull()
                        if (name != null) genres.add(name)
                    }
                }
            }
        }

        return Anime(
            hoshikaId = hoshikaId,
            anilistId = anilistId,
            malId = malId,
            tmdbId = tmdbId,
            title = primaryTitle,
            englishTitle = englishTitle,
            japaneseTitle = japaneseTitle,
            description = cleanDescription,
            posterUrl = posterUrl,
            bannerUrl = bannerUrl,
            logoUrl = logoUrl,
            releaseYear = releaseYear,
            season = season,
            status = status,
            format = format,
            totalEpisodes = totalEpisodes,
            genres = genres,
            rating = rating
        )
    }

    fun parseAnimeListFromResponse(rawBody: String): List<Anime> {
        return runCatching {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
                return emptyList()
            }
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                if (root.has("ok") && !root.optBoolean("ok", true)) {
                    return emptyList()
                }
                root.optJSONArray("items")
                    ?: root.optJSONArray("anime")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("seasonal")
                    ?: root.optJSONArray("trending")
                    ?: root.optJSONObject("data")?.let { dataObj ->
                        dataObj.optJSONArray("items")
                            ?: dataObj.optJSONArray("anime")
                            ?: dataObj.optJSONArray("results")
                    }
            } ?: return emptyList()

            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseHoshikaAnime(obj)?.let { add(it) }
                }
            }.distinctBy { it.hoshikaId }
        }.onFailure { e ->
            `in`.aniscroll.tv.features.search.SearchLogger.e(
                "Response parsing error in parseAnimeListFromResponse: ${e.message}",
                e
            )
        }.getOrDefault(emptyList())
    }

    fun parseSingleAnimeFromResponse(rawBody: String): Anime? {
        return runCatching {
            val root = JSONObject(rawBody.trim())
            val target = root.optJSONObject("anime")
                ?: root.optJSONObject("data")
                ?: root.optJSONObject("result")
                ?: root
            parseHoshikaAnime(target)
        }.getOrNull()
    }

    data class EpisodePaginationMeta(
        val nextAfter: Int? = null,
        val hasNextPage: Boolean? = null,
        val nextPage: Int? = null,
        val currentPage: Int? = null,
        val totalPages: Int? = null,
        val totalEpisodes: Int? = null
    )

    data class EpisodePageResult(
        val episodes: List<Episode>,
        val pagination: EpisodePaginationMeta = EpisodePaginationMeta()
    )

    fun mergeDuplicateEpisode(primary: Episode, secondary: Episode): Episode {
        val preferredId = if (!primary.id.startsWith("${primary.hoshikaId}_ep_")) {
            primary.id
        } else {
            secondary.id
        }
        return primary.copy(
            id = preferredId,
            title = primary.title ?: secondary.title,
            description = primary.description ?: secondary.description,
            thumbnailUrl = primary.thumbnailUrl ?: secondary.thumbnailUrl,
            durationSeconds = primary.durationSeconds ?: secondary.durationSeconds,
            airDate = primary.airDate ?: secondary.airDate,
            isFiller = primary.isFiller || secondary.isFiller
        )
    }

    fun parseEpisodePageFromResponse(hoshikaId: Long, rawBody: String): EpisodePageResult {
        return runCatching {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return EpisodePageResult(emptyList())

            var paginationMeta = EpisodePaginationMeta()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                if (root.has("ok") && !root.optBoolean("ok", true)) {
                    return EpisodePageResult(emptyList())
                }
                val dataObj = root.optJSONObject("data")
                val animeObj = root.optJSONObject("anime") ?: dataObj?.optJSONObject("anime")
                val paginationObj = root.optJSONObject("pagination")
                    ?: dataObj?.optJSONObject("pagination")
                    ?: root.optJSONObject("pageInfo")
                    ?: dataObj?.optJSONObject("pageInfo")
                    ?: root.optJSONObject("meta")
                    ?: dataObj?.optJSONObject("meta")

                val metaSources = listOfNotNull(root, dataObj, paginationObj, animeObj)

                fun extractPositiveInt(vararg keys: String): Int? {
                    for (source in metaSources) {
                        for (key in keys) {
                            if (!source.has(key) || source.isNull(key)) continue
                            val intVal = source.optInt(key, -1)
                            if (intVal > 0) return intVal
                            val strVal = source.optString(key).takeNonNull()?.toIntOrNull()
                            if (strVal != null && strVal > 0) return strVal
                        }
                    }
                    return null
                }

                fun extractExplicitBoolean(vararg keys: String): Boolean? {
                    for (source in metaSources) {
                        for (key in keys) {
                            if (!source.has(key) || source.isNull(key)) continue
                            val rawVal = source.opt(key)
                            when (rawVal) {
                                is Boolean -> return rawVal
                                is String -> {
                                    if (rawVal.equals("true", ignoreCase = true)) return true
                                    if (rawVal.equals("false", ignoreCase = true)) return false
                                }
                                is Number -> return rawVal.toInt() != 0
                            }
                        }
                    }
                    return null
                }

                paginationMeta = EpisodePaginationMeta(
                    nextAfter = extractPositiveInt(
                        "nextAfter",
                        "next_after",
                        "nextCursor",
                        "next_cursor",
                        "after",
                        "endCursor",
                        "cursor"
                    ),
                    hasNextPage = extractExplicitBoolean(
                        "hasNextPage",
                        "has_next_page",
                        "hasMore",
                        "has_more"
                    ),
                    nextPage = extractPositiveInt("nextPage", "next_page"),
                    currentPage = extractPositiveInt("currentPage", "current_page", "page"),
                    totalPages = extractPositiveInt(
                        "totalPages",
                        "total_pages",
                        "lastPage",
                        "last_page",
                        "last_visible_page"
                    ),
                    totalEpisodes = extractPositiveInt(
                        "totalEpisodes",
                        "total_episodes",
                        "total",
                        "totalCount",
                        "total_count",
                        "episodesCount",
                        "episodes_count"
                    )
                )

                root.optJSONArray("items")
                    ?: root.optJSONArray("episodes")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: dataObj?.optJSONArray("items")
                    ?: dataObj?.optJSONArray("episodes")
                    ?: dataObj?.optJSONArray("results")
                    ?: animeObj?.optJSONArray("episodes")
                    ?: animeObj?.optJSONArray("items")
            } else {
                null
            } ?: return EpisodePageResult(emptyList(), paginationMeta)

            val byNumber = LinkedHashMap<Int, Episode>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val number = extractEpisodeNumber(obj) ?: continue
                val epId = obj.optString("id").takeNonNull()
                    ?: obj.optString("episodeId").takeNonNull()
                    ?: obj.optString("episode_id").takeNonNull()
                    ?: obj.optString("sourceId").takeNonNull()
                    ?: "${hoshikaId}_ep_$number"
                val title = obj.optString("title").takeNonNull()
                    ?: obj.optString("name").takeNonNull()
                val thumb = obj.optString("thumbnail").takeNonNull()
                    ?: obj.optString("image").takeNonNull()
                    ?: obj.optString("thumbnailUrl").takeNonNull()
                val desc = obj.optString("synopsis").takeNonNull()
                    ?: obj.optString("description").takeNonNull()
                val duration = obj.optInt("duration", 0).takeIf { it > 0 }
                    ?: obj.optInt("durationSeconds", 0).takeIf { it > 0 }
                val airDate = obj.optString("aired").takeNonNull()
                    ?: obj.optString("air_date").takeNonNull()
                    ?: obj.optString("airedAt").takeNonNull()

                val parsedEpisode = Episode(
                    id = epId,
                    hoshikaId = hoshikaId,
                    number = number,
                    title = title,
                    description = desc,
                    thumbnailUrl = thumb,
                    durationSeconds = duration,
                    airDate = airDate,
                    isFiller = obj.optBoolean("filler", obj.optBoolean("is_filler", false))
                )
                val existing = byNumber[number]
                byNumber[number] = if (existing == null) {
                    parsedEpisode
                } else {
                    mergeDuplicateEpisode(existing, parsedEpisode)
                }
            }

            EpisodePageResult(
                episodes = byNumber.values.sortedBy { it.number },
                pagination = paginationMeta
            )
        }.getOrDefault(EpisodePageResult(emptyList()))
    }

    private fun extractEpisodeNumber(obj: JSONObject): Int? {
        val numberKeys = arrayOf("number", "episode_number", "episodeNumber", "episode", "ep", "num")
        for (key in numberKeys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val intVal = obj.optInt(key, -1)
            if (intVal >= 0) return intVal
            val strVal = obj.optString(key).takeNonNull()?.toDoubleOrNull()?.toInt()
            if (strVal != null && strVal >= 0) return strVal
        }
        return null
    }

    fun parseEpisodesFromResponse(hoshikaId: Long, rawBody: String): List<Episode> {
        return parseEpisodePageFromResponse(hoshikaId, rawBody).episodes
    }

    fun parseScheduleFromResponse(rawBody: String): List<ScheduleItem> {
        return runCatching {
            val trimmed = rawBody.trim()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("items")
                    ?: root.optJSONArray("schedule")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
            } ?: return emptyList()

            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val animeObj = obj.optJSONObject("anime") ?: obj
                    val anime = parseHoshikaAnime(animeObj) ?: continue

                    val epNum = obj.optInt("episode", 0).takeIf { it > 0 }
                        ?: obj.optInt("episode_number", 0).takeIf { it > 0 }
                        ?: obj.optInt("nextEpisodeNumber", 0).takeIf { it > 0 }

                    val airingIso = obj.optString("airingAt").takeNonNull()
                        ?: obj.optString("airing_at").takeNonNull()
                        ?: obj.optString("nextEpisodeAiringAt").takeNonNull()

                    val parsedIsoMs = airingIso?.let { parseIso8601ToMillis(it) }
                    val numericTs = obj.optLong("airing_at", 0L).takeIf { it > 0L }
                        ?: obj.optLong("timestamp", 0L).takeIf { it > 0L }
                    val timestampMs = parsedIsoMs
                        ?: numericTs?.let { if (it < 10_000_000_000L) it * 1000L else it }

                    val dayOfWeek = if (timestampMs != null) {
                        formatTimestampToLocalDayOfWeek(timestampMs)
                    } else {
                        obj.optString("day").takeNonNull()
                            ?: obj.optString("day_of_week").takeNonNull()
                            ?: "Monday"
                    }

                    val formattedLocal = timestampMs?.let { formatTimestampToLocalTime(it) }
                        ?: obj.optString("time").takeNonNull()

                    add(
                        ScheduleItem(
                            id = "${anime.hoshikaId}_${dayOfWeek}_${epNum ?: i}",
                            anime = anime,
                            dayOfWeek = dayOfWeek,
                            episodeNumber = epNum,
                            airingTimestampMs = timestampMs,
                            formattedLocalTime = formattedLocal,
                            releaseStatus = obj.optString("status").takeNonNull() ?: anime.status
                        )
                    )
                }
            }.sortedBy { it.airingTimestampMs ?: Long.MAX_VALUE }
        }.getOrDefault(emptyList())
    }

    private fun parseIso8601ToMillis(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(iso)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun formatTimestampToLocalDayOfWeek(timestampMs: Long): String {
        val sdf = SimpleDateFormat("EEEE", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(timestampMs))
    }

    fun formatTimestampToLocalTime(timestampMs: Long): String {
        val sdf = SimpleDateFormat("HH:mm z", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(timestampMs))
    }

    /**
     * Parses AniList ID from GET https://anivault-scraper-five.vercel.app/api/anilist/id?malId={malId}
     */
    fun parseAniListIdFromMalResponse(rawBody: String): Long? {
        return runCatching {
            val root = JSONObject(rawBody.trim())
            root.optLong("anilistId", 0L).takeIf { it > 0L }
                ?: root.optLong("id", 0L).takeIf { it > 0L }
                ?: root.optJSONObject("data")?.optLong("anilistId", 0L)?.takeIf { it > 0L }
        }.getOrNull()
    }

    data class AniScrollSearchMatch(
        val anilistId: Long,
        val format: String?
    )

    /**
     * Resolves the best matching AniList ID from GET https://anivault-scraper-five.vercel.app/api/search?q={query}
     * by comparing candidate titles, format (TV/MOVIE/OVA/ONA), and episode counts against [targetAnime].
     */
    fun parseAniScrollIdSearch(rawBody: String, targetAnime: Anime): Long? {
        return parseAniScrollSearchMatch(rawBody, targetAnime)?.anilistId
    }

    fun parseAniScrollSearchMatch(rawBody: String, targetAnime: Anime): AniScrollSearchMatch? {
        return runCatching {
            val root = JSONObject(rawBody.trim())
            val results = root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: return null
            if (results.length() == 0) return null

            val targetTitles = listOfNotNull(
                targetAnime.englishTitle,
                targetAnime.title,
                targetAnime.japaneseTitle
            ).map { normalizeTitleForMatch(it) }.filter { it.isNotEmpty() }.distinct()

            val targetFormat = targetAnime.format?.uppercase()?.trim()
            val targetEpisodes = targetAnime.totalEpisodes

            var bestId: Long? = null
            var bestFormat: String? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val candidateId = item.optLong("id", 0L).takeIf { it > 0L }
                    ?: item.optLong("anilistId", 0L).takeIf { it > 0L }
                    ?: continue

                if (candidateId == targetAnime.hoshikaId && targetAnime.hoshikaId > 300_000L) {
                    continue
                }

                val candidateTitle = item.optString("title").takeNonNull() ?: ""
                val normCandidate = normalizeTitleForMatch(candidateTitle)

                var score = 0
                // Position bias (search engine relevance)
                score += (10 - i).coerceAtLeast(0) * 3

                // Title match scoring
                if (normCandidate.isNotEmpty()) {
                    when {
                        targetTitles.any { it == normCandidate } -> score += 100
                        targetTitles.any { it.contains(normCandidate) || normCandidate.contains(it) } -> score += 55
                    }
                }

                // Format match
                val candidateFormat = item.optString("format").takeNonNull()?.uppercase()
                if (targetFormat != null && candidateFormat != null) {
                    if (targetFormat == candidateFormat) {
                        score += 25
                    } else if (targetFormat == "TV" && candidateFormat == "MOVIE") {
                        score -= 20
                    }
                }

                // Episode count match
                val candidateEps = item.optInt("episodes", 0).takeIf { it > 0 }
                if (targetEpisodes != null && targetEpisodes > 0 && candidateEps != null) {
                    if (targetEpisodes == candidateEps) {
                        score += 30
                    } else if (kotlin.math.abs(targetEpisodes - candidateEps) <= 2) {
                        score += 10
                    }
                }

                if (score > bestScore) {
                    bestScore = score
                    bestId = candidateId
                    bestFormat = candidateFormat
                }
            }

            bestId?.let { AniScrollSearchMatch(anilistId = it, format = bestFormat) }
        }.getOrNull()
    }

    private fun normalizeTitleForMatch(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u3040-\\u30ff\\u4e00-\\u9faf]+"), " ")
            .trim()
    }

    /**
     * Parses AniScroll streaming response from both:
     * - MegaPlay (series): GET https://anivault-scraper-five.vercel.app/api/watch/megaplay/{anilist_id}/{episode}/{sub|dub}
     * - ZokoAnime (movies): GET https://anivault-scraper-five.vercel.app/api/watch/zokoanime/{anilist_id}/{episode}/{sub|dub}
     *
     * Rules enforced:
     * 1. Uses `hlsProxyUrl` as the primary "Auto" HLS source when valid and available.
     * 2. Falls back to direct `m3u8` / `mp4ProxyUrl` / `sources` when `hlsProxyUrl` is absent or as fallback sources.
     * 3. Never attempts to play `embedUrl`, `downloadUrl`, or an `iframeOnly` webpage as an HLS/MP4 stream.
     * 4. Filters out preview/thumbnail sprite VTT tracks (`thumbnails.vtt` / `sprites.vtt`).
     */
    fun parseAniScrollStreamResponse(
        anilistId: Long,
        episodeNumber: Int,
        language: String,
        rawBody: String
    ): StreamData? {
        return runCatching {
            val trimmed = rawBody.trim()
            if (!trimmed.startsWith("{")) return null

            val root = JSONObject(trimmed)
            if (root.has("ok") && !root.optBoolean("ok", true)) return null
            if (root.optString("error").takeNonNull() != null &&
                root.optString("m3u8").takeNonNull() == null &&
                root.optString("hlsProxyUrl").takeNonNull() == null
            ) {
                return null
            }

            val dataObj = root.optJSONObject("data") ?: root
            val iframeOnly = dataObj.optBoolean("iframeOnly", root.optBoolean("iframeOnly", false))
            val playbackMode = (dataObj.optString("playbackMode").takeNonNull()
                ?: root.optString("playbackMode").takeNonNull()
                ?: "hls").lowercase(Locale.ROOT)

            val embedUrl = dataObj.optString("embedUrl").takeNonNull()
                ?: root.optString("embedUrl").takeNonNull()
            val downloadUrl = dataObj.optString("downloadUrl").takeNonNull()
                ?: root.optString("downloadUrl").takeNonNull()

            fun String?.takeValidStreamUrl(): String? {
                val candidate = this.takeNonNull() ?: return null
                if (!candidate.startsWith("https://", ignoreCase = true) &&
                    !candidate.startsWith("http://", ignoreCase = true)
                ) {
                    return null
                }
                val normalizedCandidate = candidate.trimEnd('/')
                if (embedUrl != null && normalizedCandidate.equals(embedUrl.trimEnd('/'), ignoreCase = true)) {
                    return null
                }
                if (downloadUrl != null && normalizedCandidate.equals(downloadUrl.trimEnd('/'), ignoreCase = true)) {
                    return null
                }
                if (candidate.contains("/stream/mal/", ignoreCase = true) &&
                    !candidate.contains("/api/proxy/hls", ignoreCase = true) &&
                    !candidate.contains(".m3u8", ignoreCase = true)
                ) {
                    return null
                }
                return candidate
            }

            val sources = mutableListOf<StreamQualitySource>()

            val hlsProxyUrl = (dataObj.optString("hlsProxyUrl").takeValidStreamUrl()
                ?: root.optString("hlsProxyUrl").takeValidStreamUrl())
            val masterM3u8 = (dataObj.optString("m3u8").takeValidStreamUrl()
                ?: dataObj.optString("hls").takeValidStreamUrl()
                ?: dataObj.optString("streamUrl").takeValidStreamUrl()
                ?: dataObj.optString("url").takeValidStreamUrl()
                ?: root.optString("m3u8").takeValidStreamUrl())
            val mp4ProxyUrl = (dataObj.optString("mp4ProxyUrl").takeValidStreamUrl()
                ?: dataObj.optString("mp4").takeValidStreamUrl()
                ?: root.optString("mp4ProxyUrl").takeValidStreamUrl())

            // 1. Prefer hlsProxyUrl when valid and available
            if (hlsProxyUrl != null) {
                sources.add(
                    StreamQualitySource(
                        url = hlsProxyUrl,
                        quality = "Auto",
                        isM3u8 = true
                    )
                )
            }

            // 2. Include direct masterM3u8 as primary "Auto" (if no proxy) or secondary fallback ("Direct HLS")
            if (masterM3u8 != null && sources.none { it.url == masterM3u8 }) {
                sources.add(
                    StreamQualitySource(
                        url = masterM3u8,
                        quality = if (sources.isEmpty()) "Auto" else "Direct HLS",
                        isM3u8 = true
                    )
                )
            }

            // 3. Parse `qualities` array (preferring each quality's hlsProxyUrl when available)
            val qualitiesArr = dataObj.optJSONArray("qualities") ?: root.optJSONArray("qualities")
            if (qualitiesArr != null) {
                for (i in 0 until qualitiesArr.length()) {
                    val qObj = qualitiesArr.optJSONObject(i) ?: continue
                    val qUrl = qObj.optString("hlsProxyUrl").takeValidStreamUrl()
                        ?: qObj.optString("url").takeValidStreamUrl()
                        ?: qObj.optString("src").takeValidStreamUrl()
                        ?: continue
                    val rawQuality = qObj.optString("quality").takeNonNull()
                        ?: qObj.optInt("height", 0).takeIf { it > 0 }?.let { "${it}p" }
                        ?: qObj.optString("resolution").takeNonNull()
                        ?: "1080p"
                    val normalizedQuality = normalizeQualityLabel(rawQuality)
                    val isM3u8 = qUrl.contains(".m3u8", ignoreCase = true) ||
                        qUrl.contains("/proxy/hls", ignoreCase = true) ||
                        playbackMode == "hls"
                    if (sources.none { it.quality == normalizedQuality && it.url == qUrl }) {
                        sources.add(
                            StreamQualitySource(
                                url = qUrl,
                                quality = normalizedQuality,
                                isM3u8 = isM3u8
                            )
                        )
                    }
                }
            }

            // 4. Parse `sources` or `streams` array if present
            val sourcesArr = dataObj.optJSONArray("sources")
                ?: dataObj.optJSONArray("streams")
                ?: root.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val sObj = sourcesArr.optJSONObject(i) ?: continue
                    val url = sObj.optString("hlsProxyUrl").takeValidStreamUrl()
                        ?: sObj.optString("url").takeValidStreamUrl()
                        ?: sObj.optString("file").takeValidStreamUrl()
                        ?: sObj.optString("src").takeValidStreamUrl()
                        ?: continue
                    val rawQuality = sObj.optString("quality").takeNonNull()
                        ?: sObj.optString("label").takeNonNull()
                        ?: "Auto"
                    val normalizedQuality = normalizeQualityLabel(rawQuality)
                    val isM3u8 = sObj.optBoolean(
                        "isM3U8",
                        url.contains(".m3u8", ignoreCase = true) || url.contains("/proxy/hls", ignoreCase = true)
                    )
                    if (sources.none { it.url == url }) {
                        sources.add(StreamQualitySource(url = url, quality = normalizedQuality, isM3u8 = isM3u8))
                    }
                }
            }

            // 5. Support direct MP4 when provided
            if (mp4ProxyUrl != null && sources.none { it.url == mp4ProxyUrl }) {
                sources.add(
                    StreamQualitySource(
                        url = mp4ProxyUrl,
                        quality = if (sources.isEmpty()) "Auto" else "MP4",
                        isM3u8 = false
                    )
                )
            }

            if (sources.isEmpty() || (iframeOnly && hlsProxyUrl == null && masterM3u8 == null && mp4ProxyUrl == null)) {
                return null
            }

            val subtitles = mutableListOf<SubtitleTrack>()
            val subsArr = dataObj.optJSONArray("subtitles")
                ?: dataObj.optJSONArray("tracks")
                ?: root.optJSONArray("subtitles")
                ?: root.optJSONArray("tracks")

            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val subObj = subsArr.optJSONObject(i) ?: continue
                    val kind = subObj.optString("kind", "captions").lowercase(Locale.ROOT)
                    if (kind == "thumbnails" || kind == "sprites") continue

                    val subUrl = subObj.optString("proxyUrl").takeNonNull()
                        ?: subObj.optString("url").takeNonNull()
                        ?: subObj.optString("src").takeNonNull()
                        ?: subObj.optString("file").takeNonNull()
                        ?: continue

                    if (subUrl.contains("thumbnails.vtt", ignoreCase = true) ||
                        subUrl.contains("sprites.vtt", ignoreCase = true) ||
                        subUrl.contains("/preview/thumbnails", ignoreCase = true) ||
                        subUrl.contains("%2Fpreview%2Fthumbnails", ignoreCase = true)
                    ) {
                        continue
                    }

                    val lang = subObj.optString("lang").takeNonNull()
                        ?: subObj.optString("language").takeNonNull()
                        ?: subObj.optString("label").takeNonNull()
                        ?: "English"
                    if (lang.equals("thumbnails", ignoreCase = true)) continue

                    val label = subObj.optString("label").takeNonNull() ?: lang
                    val isDefault = subObj.optBoolean("default", false)
                    subtitles.add(
                        SubtitleTrack(
                            url = subUrl,
                            language = lang,
                            label = label,
                            isDefault = isDefault
                        )
                    )
                }
            }

            val headers = mutableMapOf<String, String>()
            if (embedUrl != null) {
                headers["Referer"] = embedUrl
                runCatching {
                    val uri = java.net.URI(embedUrl)
                    if (uri.scheme != null && uri.host != null) {
                        headers["Origin"] = "${uri.scheme}://${uri.host}"
                    }
                }
            }

            val headersObj = dataObj.optJSONObject("headers") ?: root.optJSONObject("headers")
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headersObj.optString(key)
                    if (value.isNotBlank()) {
                        headers[key] = value
                    }
                }
            }

            val skipObj = dataObj.optJSONObject("skip") ?: root.optJSONObject("skip")
            val introObj = dataObj.optJSONObject("intro") ?: skipObj?.optJSONObject("intro")
            val outroObj = dataObj.optJSONObject("outro") ?: skipObj?.optJSONObject("outro")

            StreamData(
                anilistId = anilistId,
                episodeNumber = episodeNumber,
                language = language,
                sources = sources,
                subtitles = subtitles,
                headers = headers,
                introStartSec = introObj?.optDouble("start")?.takeIf { !it.isNaN() }?.toFloat(),
                introEndSec = introObj?.optDouble("end")?.takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                outroStartSec = outroObj?.optDouble("start")?.takeIf { !it.isNaN() }?.toFloat(),
                outroEndSec = outroObj?.optDouble("end")?.takeIf { !it.isNaN() && it > 0 }?.toFloat()
            )
        }.getOrNull()
    }

    private fun normalizeQualityLabel(raw: String): String {
        val lower = raw.lowercase().trim()
        return when {
            lower == "auto" || lower == "default" || lower == "master" -> "Auto"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            else -> raw
        }
    }

    fun parseAniSkipResponse(rawBody: String): SkipIntervals {
        return runCatching {
            val root = JSONObject(rawBody.trim())
            if (!root.optBoolean("found", false)) {
                return SkipIntervals()
            }
            val results = root.optJSONArray("results") ?: return SkipIntervals()
            var introRange: TimeRange? = null
            var outroRange: TimeRange? = null

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val skipType = item.optString("skipType").lowercase()
                val interval = item.optJSONObject("interval") ?: continue
                val start = interval.optDouble("startTime", -1.0).toFloat()
                val end = interval.optDouble("endTime", -1.0).toFloat()
                val skipId = item.optString("skipId", "")

                if (start >= 0f && end > start) {
                    val range = TimeRange(startTimeSec = start, endTimeSec = end, skipId = skipId)
                    when (skipType) {
                        "op", "mixed-op" -> if (introRange == null) introRange = range
                        "ed", "mixed-ed" -> if (outroRange == null) outroRange = range
                    }
                }
            }
            SkipIntervals(intro = introRange, outro = outroRange)
        }.getOrDefault(SkipIntervals())
    }

    private fun String?.takeNonNull(): String? {
        if (this == null) return null
        val trimmed = this.trim()
        return if (trimmed.isNotEmpty() && !trimmed.equals("null", ignoreCase = true)) trimmed else null
    }
}
