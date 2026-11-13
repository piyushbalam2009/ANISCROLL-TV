package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.TmdbApi
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.LogoInfo
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * TMDB Title Logo Provider (Section 3).
 * Exclusively retrieves transparent anime title logos from TMDB.
 * Never used for metadata, posters, backdrops, descriptions, or search results.
 */
class TmdbLogoProvider(
    private val tmdbApi: TmdbApi,
    private val aniScrollApi: AniScrollApi? = null,
    private val hasValidCredentials: () -> Boolean = { NetworkModule.hasValidTmdbCredentials() }
) {

    suspend fun findAndSelectLogo(
        anime: Anime,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet()
    ): LogoInfo? {
        val allExcluded = buildExcludedSet(excludedUrl, excludedUrls)
        val verifiedTvId = resolveVerifiedTmdbTvId(anime)
        if (verifiedTvId != null && verifiedTvId > 0L) {
            val logo = fetchBestLogoForTvId(
                hoshikaId = anime.hoshikaId,
                tvId = verifiedTvId,
                excludedUrls = allExcluded,
                anilistId = anime.anilistId
            )
            if (logo != null) return logo
        }

        // Optional fallback to verified AniScroll backend /api/tmdb/anime route if configured
        return fetchBackendTmdbArtworkLogo(anime, allExcluded)
    }

    suspend fun resolveVerifiedTmdbTvId(anime: Anime): Long? {
        val knownTmdbId = anime.tmdbId?.takeIf {
            it > 0L && it != anime.hoshikaId && it != anime.anilistId
        }
        if (knownTmdbId != null) {
            LogoLogger.d(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' using pre-verified TMDB TV ID=$knownTmdbId"
            )
            return knownTmdbId
        }

        if (!hasValidCredentials()) {
            LogoLogger.w(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> TMDB search skipped: TMDB_API_KEY is missing or placeholder"
            )
            return null
        }

        return searchAndVerifyTvSeries(anime)
    }

    suspend fun searchAndVerifyTvSeries(anime: Anime): Long? {
        if (!hasValidCredentials()) {
            LogoLogger.w(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} -> Cannot search TMDB: credentials missing"
            )
            return null
        }

        val candidateQueries = buildCandidateSearchQueries(anime)
        if (candidateQueries.isEmpty()) {
            LogoLogger.w(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} has no valid titles for TMDB search"
            )
            return null
        }

        for (queryTitle in candidateQueries) {
            val rawJson = executeTmdbSearch(anime, queryTitle)
            if (rawJson.isNullOrBlank()) continue

            val matchedId = verifyMatchingSeries(rawJson, anime, queryTitle)
            if (matchedId != null && matchedId > 0L) {
                LogoLogger.i(
                    "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Selected TMDB TV ID=$matchedId (query='$queryTitle')"
                )
                return matchedId
            }
        }

        LogoLogger.w(
            "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Fallback reason: TMDB search returned no validated anime TV series match"
        )
        return null
    }

    private suspend fun executeTmdbSearch(anime: Anime, query: String): String? {
        try {
            val response = tmdbApi.searchTv(query = query, firstAirDateYear = null)
            LogoLogger.d(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} -> TMDB search status=${response.code()} for query='$query'"
            )
            if (response.isSuccessful) {
                val body = response.body()?.string()?.trim()
                if (!body.isNullOrBlank() && body.startsWith("{")) {
                    return body
                }
            } else {
                LogoLogger.w(
                    "HoshikaID=${anime.hoshikaId} -> TMDB search HTTP ${response.code()} for query='$query'"
                )
            }
        } catch (e: Exception) {
            LogoLogger.w(
                "HoshikaID=${anime.hoshikaId} -> TMDB search exception for query='$query': ${e.message}",
                e
            )
        }
        return null
    }

    internal fun buildCandidateSearchQueries(anime: Anime): List<String> {
        val rawTitles = listOfNotNull(
            anime.englishTitle?.trim()?.takeIf { it.isNotEmpty() },
            anime.title.trim().takeIf { it.isNotEmpty() },
            anime.japaneseTitle?.trim()?.takeIf { it.isNotEmpty() }
        )
        val queries = mutableListOf<String>()
        for (t in rawTitles) {
            if (t !in queries) queries.add(t)
            val stripped = stripSeasonOrArcSuffix(t)
            if (stripped.length >= 2 && stripped !in queries) {
                queries.add(stripped)
            }
        }
        return queries
    }

    internal fun verifyMatchingSeries(
        rawJson: String,
        anime: Anime,
        searchedQuery: String = ""
    ): Long? {
        return try {
            val root = JSONObject(rawJson.trim())
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val exactTargets = listOfNotNull(
                anime.englishTitle,
                anime.title,
                anime.japaneseTitle,
                searchedQuery.takeIf { it.isNotBlank() }
            ).map { normalizeTitle(it) }.filter { it.isNotEmpty() }.distinct()

            val baseTargets = listOfNotNull(
                anime.englishTitle?.let { stripSeasonOrArcSuffix(it) },
                stripSeasonOrArcSuffix(anime.title),
                anime.japaneseTitle?.let { stripSeasonOrArcSuffix(it) },
                searchedQuery.takeIf { it.isNotBlank() }?.let { stripSeasonOrArcSuffix(it) }
            ).map { normalizeTitle(it) }.filter { it.isNotEmpty() }.distinct()

            var bestTvId: Long? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val tvId = item.optLong("id", 0L).takeIf { it > 0L } ?: continue

                val name = normalizeTitle(item.optString("name"))
                val originalName = normalizeTitle(item.optString("original_name"))
                val baseName = normalizeTitle(stripSeasonOrArcSuffix(item.optString("name")))
                val baseOriginalName = normalizeTitle(stripSeasonOrArcSuffix(item.optString("original_name")))

                val candidateNames = listOf(name, originalName, baseName, baseOriginalName)
                    .filter { it.isNotEmpty() }
                if (candidateNames.isEmpty()) continue

                val isExactMatch = exactTargets.any { target ->
                    name == target || originalName == target
                }
                val isBaseExactMatch = baseTargets.any { target ->
                    baseName == target || baseOriginalName == target || name == target || originalName == target
                }
                val isSubstringMatch = (exactTargets + baseTargets).any { target ->
                    target.length >= 4 && candidateNames.any { cand ->
                        cand.length >= 4 && (cand.contains(target) || target.contains(cand))
                    }
                }

                if (!isExactMatch && !isBaseExactMatch && !isSubstringMatch) {
                    continue
                }

                val firstAirDate = item.optString("first_air_date")
                val resultYear = firstAirDate.take(4).toIntOrNull()
                val originalLanguage = item.optString("original_language").lowercase(Locale.ROOT)

                // Check if animation genre (16) is present
                val genreIds = item.optJSONArray("genre_ids")
                var isAnimation = false
                if (genreIds != null) {
                    for (g in 0 until genreIds.length()) {
                        if (genreIds.optInt(g) == 16) {
                            isAnimation = true
                            break
                        }
                    }
                }

                // Validate release year:
                // For Season 1, first_air_date year is within +/- 2 years.
                // For multi-season anime (e.g., Season 2/3), TMDB's first_air_date is the Season 1 premiere year (<= anime.releaseYear).
                val animeYear = anime.releaseYear
                val yearDiff = if (animeYear != null && resultYear != null) abs(animeYear - resultYear) else 0
                val isSequelOrMultiSeason = hasSeasonIndicator(anime.englishTitle) || hasSeasonIndicator(anime.title)

                val yearAcceptable = when {
                    animeYear == null || resultYear == null -> true
                    yearDiff <= 2 -> true
                    isSequelOrMultiSeason && resultYear <= animeYear + 1 && resultYear >= 1980 -> true
                    isExactMatch && (originalLanguage == "ja" || isAnimation) -> true
                    else -> false
                }

                if (!yearAcceptable) continue

                var score = 0
                if (isExactMatch) score += 120
                else if (isBaseExactMatch) score += 90
                else if (isSubstringMatch) score += 45

                if (originalLanguage == "ja") score += 35
                if (isAnimation) score += 25

                if (animeYear != null && resultYear != null) {
                    when {
                        yearDiff == 0 -> score += 30
                        yearDiff == 1 -> score += 20
                        isSequelOrMultiSeason && resultYear <= animeYear -> score += 15
                    }
                }

                // Slight preference for higher search relevance rank when tied
                score += (10 - i).coerceAtLeast(0)

                if (score > bestScore) {
                    bestScore = score
                    bestTvId = tvId
                }
            }

            bestTvId
        } catch (e: Exception) {
            LogoLogger.e("Failed parsing TMDB search response for HoshikaID=${anime.hoshikaId}: ${e.message}", e)
            null
        }
    }

    suspend fun fetchBestLogoForTvId(
        hoshikaId: Long,
        tvId: Long,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet(),
        anilistId: Long? = null
    ): LogoInfo? {
        if (tvId <= 0L) return null
        if (!hasValidCredentials()) {
            LogoLogger.w(
                "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> TMDB images request skipped: TMDB_API_KEY is missing or placeholder"
            )
            return null
        }

        val allExcluded = buildExcludedSet(excludedUrl, excludedUrls)
        val rawJson = executeTmdbImagesRequest(hoshikaId, tvId, anilistId) ?: return null
        return parseAndSelectBestLogo(
            rawJson = rawJson,
            hoshikaId = hoshikaId,
            tvId = tvId,
            excludedUrls = allExcluded,
            anilistId = anilistId
        )
    }

    private suspend fun executeTmdbImagesRequest(
        hoshikaId: Long,
        tvId: Long,
        anilistId: Long?
    ): String? {
        try {
            val response = tmdbApi.getTvImages(tvId = tvId, includeImageLanguage = "en,null,ja")
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> TMDB image request status=${response.code()}"
            )
            if (response.isSuccessful) {
                val body = response.body()?.string()?.trim()
                if (!body.isNullOrBlank() && body.startsWith("{")) {
                    return body
                }
            } else {
                LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> Fallback reason: TMDB images returned HTTP ${response.code()}"
                )
            }
        } catch (e: Exception) {
            LogoLogger.w(
                "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> Fallback reason: TMDB images exception (${e.message})",
                e
            )
        }
        return null
    }

    internal suspend fun fetchBackendTmdbArtworkLogo(
        anime: Anime,
        excludedUrls: Set<String> = emptySet()
    ): LogoInfo? {
        val api = aniScrollApi ?: return null
        return try {
            val resp = api.getTmdbAnimeArtwork(
                malId = anime.malId,
                title = anime.preferredTitle
            )
            if (!resp.isSuccessful) return null
            val body = resp.body()?.string()?.trim() ?: return null
            if (!body.startsWith("{")) return null

            val root = JSONObject(body)
            val dataObj = root.optJSONObject("data") ?: root
            val logoUrl = dataObj.optString("logo", "").trim().takeIf {
                it.isNotEmpty() &&
                    !it.equals("null", ignoreCase = true) &&
                    (it.startsWith("https://") || it.startsWith("http://")) &&
                    it !in excludedUrls
            } ?: return null

            LogoLogger.i(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} -> Resolved logo via AniScroll /api/tmdb/anime host=${LogoLogger.extractHost(logoUrl)}"
            )
            LogoInfo(
                hoshikaId = anime.hoshikaId,
                tmdbId = anime.tmdbId,
                provider = "TMDB",
                logoUrl = logoUrl,
                language = "en",
                width = 800,
                height = 310,
                lastUpdated = System.currentTimeMillis(),
                isValid = true
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseAndSelectBestLogo(
        rawJson: String,
        hoshikaId: Long,
        tvId: Long,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet(),
        anilistId: Long? = null
    ): LogoInfo? {
        val allExcluded = buildExcludedSet(excludedUrl, excludedUrls)
        return try {
            val root = JSONObject(rawJson.trim())
            val logos = root.optJSONArray("logos")
            val totalLogos = logos?.length() ?: 0
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> Number of TMDB logos=$totalLogos"
            )
            if (logos == null || totalLogos == 0) {
                LogoLogger.i(
                    "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> Fallback reason: TMDB returned 0 logos"
                )
                return null
            }

            data class CandidateLogo(
                val fullUrl: String,
                val lang: String?,
                val format: String,
                val formatPriority: Int,
                val width: Int,
                val height: Int,
                val aspectRatio: Double,
                val voteAverage: Double
            )

            val candidates = mutableListOf<CandidateLogo>()
            for (i in 0 until totalLogos) {
                val obj = logos.optJSONObject(i) ?: continue
                if (obj.isNull("file_path")) continue
                val rawPath = obj.optString("file_path", "").trim()
                if (rawPath.isEmpty() || rawPath.equals("null", ignoreCase = true)) continue

                val cleanPath = rawPath.trimStart('/')
                if (cleanPath.isEmpty()) continue

                val formatInfo = detectSupportedLogoFormat(cleanPath) ?: continue
                val fullUrl = "https://image.tmdb.org/t/p/original/$cleanPath"
                if (fullUrl in allExcluded) continue

                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)
                val aspect = obj.optDouble(
                    "aspect_ratio",
                    if (height > 0) width.toDouble() / height.toDouble() else 2.5
                )

                // Reject vertical poster-like images; logos are horizontal/wide or square badges
                if (aspect > 0.0 && aspect < 0.95) continue

                val rawLang = if (obj.isNull("iso_639_1")) {
                    null
                } else {
                    obj.optString("iso_639_1", "").trim().takeIf {
                        it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                    }
                }

                val vote = obj.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it }
                candidates.add(
                    CandidateLogo(
                        fullUrl = fullUrl,
                        lang = rawLang,
                        format = formatInfo.first,
                        formatPriority = formatInfo.second,
                        width = width,
                        height = height,
                        aspectRatio = aspect,
                        voteAverage = vote
                    )
                )
            }

            if (candidates.isEmpty()) {
                LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> Fallback reason: 0 suitable TMDB logo candidates after format/aspect/exclusion filtering"
                )
                return null
            }

            // Priority:
            // 1. Language: English ("en") -> Japanese ("ja") -> Language-neutral (null / "" / "xx") -> Other
            // 2. Format: PNG (0) -> SVG (1) -> WEBP (2) -> JPG (3)
            // 3. Vote average & width
            val selected = candidates.sortedWith(
                compareBy<CandidateLogo> {
                    when (it.lang?.lowercase(Locale.ROOT)) {
                        "en" -> 0
                        "ja" -> 1
                        null, "", "xx" -> 2
                        else -> 3
                    }
                }.thenBy { it.formatPriority }
                    .thenByDescending { it.voteAverage }
                    .thenByDescending { it.width }
            ).first()

            LogoLogger.i(
                "HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId -> Selected TMDB logo lang='${selected.lang ?: "neutral"}' format=${selected.format} (${selected.width}x${selected.height}) host=${LogoLogger.extractHost(selected.fullUrl)}"
            )

            LogoInfo(
                hoshikaId = hoshikaId,
                tmdbId = tvId,
                provider = "TMDB",
                logoUrl = selected.fullUrl,
                language = selected.lang,
                width = selected.width,
                height = selected.height,
                lastUpdated = System.currentTimeMillis(),
                isValid = true
            )
        } catch (e: Exception) {
            LogoLogger.e(
                "Failed parsing TMDB logos for HoshikaID=$hoshikaId AniListID=$anilistId tvId=$tvId: ${e.message}",
                e
            )
            null
        }
    }

    internal fun detectSupportedLogoFormat(pathOrUrl: String): Pair<String, Int>? {
        val clean = pathOrUrl.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".png") -> "PNG" to 0
            clean.endsWith(".svg") -> "SVG" to 1
            clean.endsWith(".webp") -> "WEBP" to 2
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "JPG" to 3
            else -> null
        }
    }

    private fun buildExcludedSet(excludedUrl: String?, excludedUrls: Set<String>): Set<String> {
        if (excludedUrl.isNullOrBlank()) return excludedUrls
        return excludedUrls + excludedUrl
    }

    internal fun stripSeasonOrArcSuffix(title: String): String {
        return title
            .replace(
                Regex(
                    "\\s*(?::\\s*[^:]+|\\b(?:season|part|cour)\\s*\\d+|\\b\\d+(?:st|nd|rd|th)\\s+season|\\b(?:final\\s+season|the\\s+final\\s+chapters))\\s*$",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }

    private fun hasSeasonIndicator(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return Regex(
            "\\b(?:season\\s*\\d+|\\d+(?:st|nd|rd|th)\\s+season|part\\s*\\d+|arc|final\\s+season|2nd|3rd|4th|ii|iii|iv)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(title) || title.contains(":")
    }

    private fun normalizeTitle(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u3040-\\u30ff\\u4e00-\\u9faf]"), "")
            .trim()
    }
}
