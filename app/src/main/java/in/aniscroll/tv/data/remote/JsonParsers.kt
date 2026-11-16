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

    data class ExtractedTitles(
        val english: String?,
        val romaji: String?,
        val native: String?
    ) {
        val primary: String?
            get() = romaji ?: english ?: native
    }

    fun extractTitles(json: JSONObject): ExtractedTitles {
        var english: String? = json.optCleanString("titleEnglish")
            ?: json.optCleanString("title_english")
            ?: json.optCleanString("englishTitle")
            ?: json.optCleanString("english_title")
            ?: json.optCleanString("titleLocalized")
            ?: json.optCleanString("title_localized")
            ?: json.optCleanString("localizedTitle")
            ?: json.optCleanString("titleEn")
            ?: json.optCleanString("title_en")
            ?: json.optCleanString("enTitle")
            ?: json.optCleanString("en_title")
            ?: json.optCleanString("english")

        var romaji: String? = json.optCleanString("titleRomaji")
            ?: json.optCleanString("title_romaji")
            ?: json.optCleanString("romajiTitle")
            ?: json.optCleanString("romaji_title")
            ?: json.optCleanString("romaji")
            ?: json.optCleanString("canonicalTitle")
            ?: json.optCleanString("canonical_title")
            ?: json.optCleanString("userPreferred")
            ?: json.optCleanString("user_preferred")
            ?: json.optCleanString("displayTitle")
            ?: json.optCleanString("display_title")
            ?: json.optCleanString("mainTitle")
            ?: json.optCleanString("main_title")

        var nativeTitle: String? = json.optCleanString("titleNative")
            ?: json.optCleanString("title_native")
            ?: json.optCleanString("nativeTitle")
            ?: json.optCleanString("native_title")
            ?: json.optCleanString("titleJapanese")
            ?: json.optCleanString("title_japanese")
            ?: json.optCleanString("japaneseTitle")
            ?: json.optCleanString("japanese_title")
            ?: json.optCleanString("native")
            ?: json.optCleanString("japanese")

        val titleObjects = listOfNotNull(
            json.optJSONObject("title"),
            json.optJSONObject("titles"),
            json.optJSONObject("name")
        )
        for (obj in titleObjects) {
            if (english == null) {
                english = obj.optCleanString("english")
                    ?: obj.optCleanString("en")
                    ?: obj.optCleanString("en_us")
                    ?: obj.optCleanString("enUs")
                    ?: obj.optCleanString("en_jp")
                    ?: obj.optCleanString("enJp")
                    ?: obj.optCleanString("localized")
                    ?: obj.optCleanString("us")
            }
            if (romaji == null) {
                romaji = obj.optCleanString("romaji")
                    ?: obj.optCleanString("ro")
                    ?: obj.optCleanString("userPreferred")
                    ?: obj.optCleanString("user_preferred")
                    ?: obj.optCleanString("default")
                    ?: obj.optCleanString("main")
                    ?: obj.optCleanString("canonical")
                    ?: obj.optCleanString("x-jat")
            }
            if (nativeTitle == null) {
                nativeTitle = obj.optCleanString("native")
                    ?: obj.optCleanString("japanese")
                    ?: obj.optCleanString("ja")
                    ?: obj.optCleanString("ja_jp")
                    ?: obj.optCleanString("jp")
                    ?: obj.optCleanString("original")
            }
        }

        val titleArrays = listOfNotNull(
            json.optJSONArray("titles"),
            json.optJSONArray("alternativeTitles"),
            json.optJSONArray("alternative_titles")
        )
        for (arr in titleArrays) {
            for (i in 0 until arr.length()) {
                when (val entry = arr.opt(i)) {
                    is JSONObject -> {
                        val value = entry.optCleanString("title")
                            ?: entry.optCleanString("value")
                            ?: entry.optCleanString("name")
                            ?: entry.optCleanString("text")
                            ?: continue
                        val type = (entry.optCleanString("type")
                            ?: entry.optCleanString("lang")
                            ?: entry.optCleanString("language")
                            ?: "").lowercase(Locale.ROOT)
                        when {
                            type == "english" || type == "en" || type == "en-us" || type == "en_us" -> {
                                if (english == null) english = value
                            }
                            type == "japanese" || type == "native" || type == "ja" || type == "jp" -> {
                                if (nativeTitle == null) nativeTitle = value
                            }
                            type == "default" || type == "romaji" || type == "main" || type == "canonical" || type == "x-jat" -> {
                                if (romaji == null) romaji = value
                            }
                            else -> {
                                if (romaji == null) romaji = value
                            }
                        }
                    }
                    is String -> {
                        val clean = entry.takeCleanString()
                        if (clean != null && romaji == null) {
                            romaji = clean
                        }
                    }
                }
            }
        }

        if (romaji == null) {
            romaji = json.optCleanString("title") ?: json.optCleanString("name")
        }

        return ExtractedTitles(
            english = english,
            romaji = romaji,
            native = nativeTitle
        )
    }

    fun parseHoshikaAnime(json: JSONObject): Anime? {
        val nestedAnime = json.optJSONObject("anime")
            ?: json.optJSONObject("media")
            ?: json.optJSONObject("show")

        val hoshikaId = json.optLong("shortId", 0L).takeIf { it > 0L }
            ?: json.optLong("hoshika_id", 0L).takeIf { it > 0L }
            ?: json.optLong("hoshikaId", 0L).takeIf { it > 0L }
            ?: json.optLong("id", 0L).takeIf { it > 0L }
            ?: nestedAnime?.optLong("shortId", 0L)?.takeIf { it > 0L }
            ?: nestedAnime?.optLong("hoshika_id", 0L)?.takeIf { it > 0L }
            ?: nestedAnime?.optLong("hoshikaId", 0L)?.takeIf { it > 0L }
            ?: nestedAnime?.optLong("id", 0L)?.takeIf { it > 0L }
            ?: return null

        val primaryJson = if (nestedAnime != null &&
            json.opt("title") == null &&
            json.opt("titles") == null &&
            json.opt("titleRomaji") == null &&
            json.opt("titleEnglish") == null &&
            json.opt("name") == null
        ) {
            nestedAnime
        } else {
            json
        }

        // Extract AniList ID strictly from Hoshika's anilist mapping field (never MAL or Hoshika ID)
        val mappingsObj = primaryJson.optJSONObject("mappings")
            ?: primaryJson.optJSONObject("external_ids")
            ?: primaryJson.optJSONObject("externalIds")
            ?: primaryJson.optJSONObject("crossReferences")
            ?: nestedAnime?.optJSONObject("mappings")

        val anilistId = primaryJson.optLong("anilist_id", 0L).takeIf { it > 0L }
            ?: primaryJson.optLong("anilistId", 0L).takeIf { it > 0L }
            ?: mappingsObj?.optLong("anilist_id", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("anilist", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("anilistId", 0L)?.takeIf { it > 0L }

        val malId = primaryJson.optLong("mal_id", 0L).takeIf { it > 0L }
            ?: primaryJson.optLong("malId", 0L).takeIf { it > 0L }
            ?: mappingsObj?.optLong("mal_id", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("mal", 0L)?.takeIf { it > 0L }

        val tmdbId = primaryJson.optLong("tmdb_id", 0L).takeIf { it > 0L }
            ?: primaryJson.optLong("tmdbId", 0L).takeIf { it > 0L }
            ?: mappingsObj?.optLong("tmdb_id", 0L)?.takeIf { it > 0L }
            ?: mappingsObj?.optLong("tmdb", 0L)?.takeIf { it > 0L }

        val primaryTitles = extractTitles(primaryJson)
        val fallbackTitles = if (nestedAnime != null && nestedAnime !== primaryJson) {
            extractTitles(nestedAnime)
        } else {
            null
        }

        val englishTitle = primaryTitles.english ?: fallbackTitles?.english
        val romajiTitle = primaryTitles.romaji ?: fallbackTitles?.romaji
        val japaneseTitle = primaryTitles.native ?: fallbackTitles?.native
        val primaryTitle = romajiTitle ?: englishTitle ?: japaneseTitle

        if (primaryTitle == null) {
            val keysPreview = buildList {
                val it = primaryJson.keys()
                while (it.hasNext() && size < 15) {
                    add(it.next())
                }
            }.joinToString(",")
            `in`.aniscroll.tv.features.search.SearchLogger.w(
                "Metadata mapping failure: Missing anime title for hoshikaId=$hoshikaId keys=[$keysPreview]"
            )
            return null
        }

        val artworkObj = primaryJson.optJSONObject("artwork")
            ?: primaryJson.optJSONObject("images")
        val coverObj = primaryJson.optJSONObject("coverImage")
            ?: primaryJson.optJSONObject("poster")
            ?: primaryJson.optJSONObject("image")
        val bannerObj = primaryJson.optJSONObject("bannerImage")
            ?: primaryJson.optJSONObject("banner")

        val posterUrl = primaryJson.optCleanString("coverImage")
            ?: primaryJson.optCleanString("poster")
            ?: primaryJson.optCleanString("poster_url")
            ?: primaryJson.optCleanString("posterUrl")
            ?: primaryJson.optCleanString("cover")
            ?: primaryJson.optCleanString("image")
            ?: coverObj?.optCleanString("extraLarge")
            ?: coverObj?.optCleanString("large")
            ?: coverObj?.optCleanString("medium")
            ?: coverObj?.optCleanString("url")
            ?: artworkObj?.optCleanString("poster")
            ?: artworkObj?.optCleanString("large")

        val bannerUrl = primaryJson.optCleanString("bannerImage")
            ?: primaryJson.optCleanString("banner")
            ?: primaryJson.optCleanString("banner_url")
            ?: primaryJson.optCleanString("bannerUrl")
            ?: primaryJson.optCleanString("background")
            ?: bannerObj?.optCleanString("extraLarge")
            ?: bannerObj?.optCleanString("large")
            ?: bannerObj?.optCleanString("url")
            ?: artworkObj?.optCleanString("banner")
            ?: artworkObj?.optCleanString("background")

        val logoUrl = primaryJson.optCleanString("logo_url")
            ?: primaryJson.optCleanString("logoUrl")
            ?: artworkObj?.optCleanString("logo")

        val description = primaryJson.optCleanString("synopsis")
            ?: primaryJson.optCleanString("description")

        val cleanDescription = description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&quot;", "\"")
            ?.replace("&#039;", "'")
            ?.replace("&amp;", "&")
            ?.trim()

        val releaseYear = primaryJson.optInt("seasonYear", 0).takeIf { it > 1900 }
            ?: primaryJson.optInt("year", 0).takeIf { it > 1900 }
            ?: primaryJson.optInt("release_year", 0).takeIf { it > 1900 }
            ?: primaryJson.optInt("releaseYear", 0).takeIf { it > 1900 }
            ?: primaryJson.optCleanString("startedAiring")?.take(4)?.toIntOrNull()

        val season = primaryJson.optCleanString("season")
        val status = primaryJson.optCleanString("status")
        val format = primaryJson.optCleanString("format")
            ?: primaryJson.optCleanString("type")

        val totalEpisodes = primaryJson.optInt("episodes", 0).takeIf { it > 0 }
            ?: primaryJson.optInt("total_episodes", 0).takeIf { it > 0 }
            ?: primaryJson.optInt("totalEpisodes", 0).takeIf { it > 0 }
            ?: primaryJson.optInt("episodes_count", 0).takeIf { it > 0 }

        val rating = primaryJson.optDouble("averageScore", Double.NaN).takeIf { !it.isNaN() && it > 0 }

        val genres = mutableListOf<String>()
        val genresArr = primaryJson.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val item = genresArr.opt(i)
                when (item) {
                    is String -> item.takeCleanString()?.let { genres.add(it) }
                    is JSONObject -> {
                        val name = item.optCleanString("name")
                            ?: item.optCleanString("label")
                            ?: item.optCleanString("title")
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
                    ?: root.optJSONArray("media")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("seasonal")
                    ?: root.optJSONArray("trending")
                    ?: root.optJSONObject("data")?.let { dataObj ->
                        dataObj.optJSONArray("items")
                            ?: dataObj.optJSONArray("anime")
                            ?: dataObj.optJSONArray("media")
                            ?: dataObj.optJSONArray("results")
                            ?: dataObj.optJSONArray("trending")
                            ?: dataObj.optJSONArray("popular")
                            ?: dataObj.optJSONArray("seasonal")
                            ?: dataObj.optJSONArray("upcoming")
                            ?: dataObj.optJSONArray("recommended")
                            ?: dataObj.optJSONArray("list")
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
                ?: root.optJSONObject("data")?.let { dataObj ->
                    dataObj.optJSONObject("anime") ?: dataObj.optJSONObject("media") ?: dataObj
                }
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
                val title = obj.optCleanString("title")
                    ?: obj.optCleanString("name")
                    ?: extractTitles(obj).let { it.english ?: it.romaji ?: it.native }
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
        val format: String?,
        val malId: Long? = null
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
            var bestMalId: Long? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val candidateId = item.optLong("id", 0L).takeIf { it > 0L }
                    ?: item.optLong("anilistId", 0L).takeIf { it > 0L }
                    ?: continue

                if (candidateId == targetAnime.hoshikaId && targetAnime.hoshikaId > 300_000L) {
                    continue
                }

                val candidateMalId = item.optLong("malId", 0L).takeIf { it > 0L }
                    ?: item.optLong("idMal", 0L).takeIf { it > 0L }
                    ?: item.optLong("mal_id", 0L).takeIf { it > 0L }

                val extracted = extractTitles(item)
                val candidateTitles = listOfNotNull(extracted.english, extracted.romaji, extracted.native)
                    .map { normalizeTitleForMatch(it) }
                    .filter { it.isNotEmpty() }

                var score = 0
                // Position bias (search engine relevance)
                score += (10 - i).coerceAtLeast(0) * 3

                // Title match scoring
                if (candidateTitles.isNotEmpty()) {
                    when {
                        candidateTitles.any { cand -> targetTitles.any { it == cand } } -> score += 100
                        candidateTitles.any { cand -> targetTitles.any { it.contains(cand) || cand.contains(it) } } -> score += 55
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
                    bestMalId = candidateMalId
                }
            }

            bestId?.let { AniScrollSearchMatch(anilistId = it, format = bestFormat, malId = bestMalId) }
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

            val malIdFromPayload = dataObj.optLong("malId", 0L).takeIf { it > 0L }
                ?: dataObj.optLong("mal_id", 0L).takeIf { it > 0L }
                ?: dataObj.optLong("idMal", 0L).takeIf { it > 0L }
                ?: root.optLong("malId", 0L).takeIf { it > 0L }
                ?: root.optLong("mal_id", 0L).takeIf { it > 0L }
                ?: root.optLong("idMal", 0L).takeIf { it > 0L }
                ?: embedUrl?.let { url ->
                    Regex("/stream/mal/(\\d+)/").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
                }

            val parsedEmbeddedSkips = extractEmbeddedSkipsFromJson(dataObj, root)
            val offsetSec = extractExplicitTimestampOffsetSec(dataObj, root)

            val streamTitle = extractTitles(dataObj).let { it.english ?: it.romaji ?: it.native }
                ?: extractTitles(root).let { it.english ?: it.romaji ?: it.native }

            StreamData(
                anilistId = anilistId,
                episodeNumber = episodeNumber,
                language = language,
                sources = sources,
                subtitles = subtitles,
                headers = headers,
                introStartSec = parsedEmbeddedSkips.intro?.startTimeSec,
                introEndSec = parsedEmbeddedSkips.intro?.endTimeSec,
                outroStartSec = parsedEmbeddedSkips.outro?.startTimeSec,
                outroEndSec = parsedEmbeddedSkips.outro?.endTimeSec,
                recapStartSec = parsedEmbeddedSkips.recap?.startTimeSec,
                recapEndSec = parsedEmbeddedSkips.recap?.endTimeSec,
                timestampOffsetSec = offsetSec,
                malId = malIdFromPayload,
                title = streamTitle
            )
        }.getOrNull()
    }

    private fun extractExplicitTimestampOffsetSec(dataObj: JSONObject, root: JSONObject): Float {
        val keys = arrayOf("timestampOffset", "timestamp_offset", "skipOffset", "skip_offset")
        for (source in listOf(dataObj, root)) {
            for (key in keys) {
                if (source.has(key) && !source.isNull(key)) {
                    val v = source.optDouble(key, Double.NaN)
                    if (!v.isNaN() && kotlin.math.abs(v) <= 120.0) {
                        return v.toFloat()
                    }
                }
            }
        }
        return 0f
    }

    private fun parseRangeCandidate(raw: Any?, skipId: String = ""): TimeRange? {
        when (raw) {
            is JSONObject -> {
                val intervalObj = raw.optJSONObject("interval") ?: raw
                val startMs = intervalObj.optDouble("startTimeMs", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("start_ms", Double.NaN).takeIf { !it.isNaN() }
                val endMs = intervalObj.optDouble("endTimeMs", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("end_ms", Double.NaN).takeIf { !it.isNaN() }
                if (startMs != null && endMs != null) {
                    val s = (startMs / 1000.0).toFloat()
                    val e = (endMs / 1000.0).toFloat()
                    if (s >= 0f && e > s && (e - s) >= 2f) {
                        return TimeRange(startTimeSec = s, endTimeSec = e, skipId = skipId)
                    }
                }

                val startSec = intervalObj.optDouble("start", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("startTime", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("start_time", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("from", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("begin", Double.NaN).takeIf { !it.isNaN() }
                val endSec = intervalObj.optDouble("end", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("endTime", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("end_time", Double.NaN).takeIf { !it.isNaN() }
                    ?: intervalObj.optDouble("to", Double.NaN).takeIf { !it.isNaN() }
                if (startSec != null && endSec != null) {
                    val s = startSec.toFloat()
                    val e = endSec.toFloat()
                    if (s >= 0f && e > s && (e - s) >= 2f) {
                        return TimeRange(startTimeSec = s, endTimeSec = e, skipId = skipId)
                    }
                }
            }
            is JSONArray -> {
                if (raw.length() >= 2) {
                    val s = raw.optDouble(0, Double.NaN).toFloat()
                    val e = raw.optDouble(1, Double.NaN).toFloat()
                    if (!s.isNaN() && !e.isNaN() && s >= 0f && e > s && (e - s) >= 2f) {
                        return TimeRange(startTimeSec = s, endTimeSec = e, skipId = skipId)
                    }
                }
            }
        }
        return null
    }

    private fun extractEmbeddedSkipsFromJson(dataObj: JSONObject, root: JSONObject): SkipIntervals {
        var introRange: TimeRange? = null
        var outroRange: TimeRange? = null
        var recapRange: TimeRange? = null

        val skipContainers = listOfNotNull(
            dataObj.optJSONObject("skip"),
            root.optJSONObject("skip"),
            dataObj.optJSONObject("skips"),
            root.optJSONObject("skips"),
            dataObj.optJSONObject("skipTimes"),
            root.optJSONObject("skipTimes"),
            dataObj,
            root
        )

        val introKeys = listOf("intro", "op", "opening", "mixed-op", "mixed_op")
        val outroKeys = listOf("outro", "ed", "ending", "mixed-ed", "mixed_ed")
        val recapKeys = listOf("recap", "summary")

        for (container in skipContainers) {
            if (introRange == null) {
                for (key in introKeys) {
                    if (container.has(key) && !container.isNull(key)) {
                        introRange = parseRangeCandidate(container.opt(key), "embedded_intro")
                        if (introRange != null) break
                    }
                }
            }
            if (outroRange == null) {
                for (key in outroKeys) {
                    if (container.has(key) && !container.isNull(key)) {
                        outroRange = parseRangeCandidate(container.opt(key), "embedded_outro")
                        if (outroRange != null) break
                    }
                }
            }
            if (recapRange == null) {
                for (key in recapKeys) {
                    if (container.has(key) && !container.isNull(key)) {
                        recapRange = parseRangeCandidate(container.opt(key), "embedded_recap")
                        if (recapRange != null) break
                    }
                }
            }
        }

        // Also check flat numeric fields if present
        for (source in listOf(dataObj, root)) {
            if (introRange == null) {
                val s = source.optDouble("introStart", Double.NaN).takeIf { !it.isNaN() }
                    ?: source.optDouble("intro_start", Double.NaN).takeIf { !it.isNaN() }
                val e = source.optDouble("introEnd", Double.NaN).takeIf { !it.isNaN() }
                    ?: source.optDouble("intro_end", Double.NaN).takeIf { !it.isNaN() }
                if (s != null && e != null && s >= 0.0 && e > s && (e - s) >= 2.0) {
                    introRange = TimeRange(s.toFloat(), e.toFloat(), "embedded_intro_flat")
                }
            }
            if (outroRange == null) {
                val s = source.optDouble("outroStart", Double.NaN).takeIf { !it.isNaN() }
                    ?: source.optDouble("outro_start", Double.NaN).takeIf { !it.isNaN() }
                val e = source.optDouble("outroEnd", Double.NaN).takeIf { !it.isNaN() }
                    ?: source.optDouble("outro_end", Double.NaN).takeIf { !it.isNaN() }
                if (s != null && e != null && s >= 0.0 && e > s && (e - s) >= 2.0) {
                    outroRange = TimeRange(s.toFloat(), e.toFloat(), "embedded_outro_flat")
                }
            }
            if (recapRange == null) {
                val s = source.optDouble("recapStart", Double.NaN).takeIf { !it.isNaN() }
                    ?: source.optDouble("recap_start", Double.NaN).takeIf { !it.isNaN() }
                val e = source.optDouble("recapEnd", Double.NaN).takeIf { !it.isNaN() }
                    ?: source.optDouble("recap_end", Double.NaN).takeIf { !it.isNaN() }
                if (s != null && e != null && s >= 0.0 && e > s && (e - s) >= 2.0) {
                    recapRange = TimeRange(s.toFloat(), e.toFloat(), "embedded_recap_flat")
                }
            }
        }

        // Also check array-based skip/chapters lists if present
        val arrayCandidates = listOfNotNull(
            dataObj.optJSONArray("skip"),
            root.optJSONArray("skip"),
            dataObj.optJSONArray("skips"),
            root.optJSONArray("skips"),
            dataObj.optJSONArray("skipTimes"),
            root.optJSONArray("skipTimes"),
            dataObj.optJSONArray("chapters"),
            root.optJSONArray("chapters"),
            dataObj.optJSONArray("segments"),
            root.optJSONArray("segments")
        )
        for (arr in arrayCandidates) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rawType = (item.optString("type").takeNonNull()
                    ?: item.optString("skipType").takeNonNull()
                    ?: item.optString("kind").takeNonNull()
                    ?: item.optString("name").takeNonNull()
                    ?: item.optString("title").takeNonNull()
                    ?: "").lowercase(Locale.ROOT).trim()
                val range = parseRangeCandidate(item, "embedded_$rawType") ?: continue
                when {
                    rawType in introKeys || rawType.contains("intro") || rawType.contains("opening") -> {
                        if (introRange == null) introRange = range
                    }
                    rawType in outroKeys || rawType.contains("outro") || rawType.contains("ending") -> {
                        if (outroRange == null) outroRange = range
                    }
                    rawType in recapKeys || rawType.contains("recap") -> {
                        if (recapRange == null) recapRange = range
                    }
                    rawType.isNotEmpty() -> {
                        `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                            "skip_parser_unsupported_embedded_segment type=$rawType start=${range.startTimeSec} end=${range.endTimeSec}"
                        )
                    }
                }
            }
        }

        return SkipIntervals(
            intro = introRange,
            outro = outroRange,
            recap = recapRange
        )
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
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return SkipIntervals()

            val results: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    if (root.has("found") && !root.optBoolean("found", false)) {
                        return SkipIntervals()
                    }
                    root.optJSONArray("results")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONObject("data")?.optJSONArray("results")
                        ?: return extractEmbeddedSkipsFromJson(root, root)
                }
                else -> return SkipIntervals()
            }

            var introRange: TimeRange? = null
            var outroRange: TimeRange? = null
            var recapRange: TimeRange? = null

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val skipType = (item.optString("skipType").takeNonNull()
                    ?: item.optString("type").takeNonNull()
                    ?: "").lowercase(Locale.ROOT).trim()
                val skipId = item.optString("skipId", "")
                val range = parseRangeCandidate(item, skipId) ?: continue

                when (skipType) {
                    "op", "mixed-op", "mixed_op", "intro", "opening" -> {
                        if (introRange == null) introRange = range
                    }
                    "ed", "mixed-ed", "mixed_ed", "outro", "ending" -> {
                        if (outroRange == null) outroRange = range
                    }
                    "recap", "summary" -> {
                        if (recapRange == null) recapRange = range
                    }
                    else -> {
                        if (skipType.isNotEmpty()) {
                            `in`.aniscroll.tv.player.StreamingDiagnostics.logEvent(
                                "aniskip_unsupported_segment_type type=$skipType start=${range.startTimeSec} end=${range.endTimeSec}"
                            )
                        }
                    }
                }
            }
            SkipIntervals(
                intro = introRange,
                outro = outroRange,
                recap = recapRange
            )
        }.getOrDefault(SkipIntervals())
    }

    private fun JSONObject.optCleanString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) ?: return null
        if (raw is JSONObject || raw is JSONArray) return null
        return raw.toString().takeCleanString()
    }

    private fun String?.takeCleanString(): String? {
        val s = this.takeNonNull() ?: return null
        if (s.equals("undefined", ignoreCase = true)) return null
        if ((s.startsWith("{") && s.endsWith("}")) ||
            (s.startsWith("[") && s.endsWith("]"))
        ) {
            return null
        }
        return s
    }

    private fun String?.takeNonNull(): String? {
        if (this == null) return null
        val trimmed = this.trim()
        return if (trimmed.isNotEmpty() && !trimmed.equals("null", ignoreCase = true)) trimmed else null
    }
}
