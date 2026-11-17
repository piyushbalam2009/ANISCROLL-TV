package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.TmdbApi
import `in`.aniscroll.tv.data.remote.TmdbMovieApi
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.LogoInfo
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * TMDB Title Logo Provider.
 * Exclusively retrieves transparent anime title logos from TMDB (TV series and Movies).
 * Never used for metadata, posters, backdrops, descriptions, or search results.
 */
class TmdbLogoProvider(
    private val tmdbApi: TmdbApi,
    private val aniScrollApi: AniScrollApi? = null,
    private val tmdbMovieApi: TmdbMovieApi? = null,
    private val hasValidCredentials: () -> Boolean = { NetworkModule.hasValidTmdbCredentials() }
) {

    data class ResolvedTmdbMedia(
        val id: Long,
        val isMovie: Boolean,
        val fromKnownId: Boolean = false
    )

    suspend fun findAndSelectLogo(
        anime: Anime,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet()
    ): LogoInfo? {
        val allExcluded = buildExcludedSet(excludedUrl, excludedUrls)
        val resolved = resolveVerifiedTmdbMedia(anime)
        if (resolved != null && resolved.id > 0L) {
            val logo = fetchBestLogoForMediaId(
                hoshikaId = anime.hoshikaId,
                mediaId = resolved.id,
                isMovie = resolved.isMovie,
                excludedUrls = allExcluded,
                anilistId = anime.anilistId
            )
            if (logo != null) return logo
        }

        // Fallback to verified AniScroll backend /api/tmdb/anime route if configured
        return fetchBackendTmdbArtworkLogo(anime, allExcluded)
    }

    suspend fun resolveVerifiedTmdbTvId(anime: Anime): Long? {
        return resolveVerifiedTmdbMedia(anime)?.id
    }

    internal suspend fun resolveVerifiedTmdbMedia(anime: Anime): ResolvedTmdbMedia? {
        val isMovieFormat = anime.format.equals("MOVIE", ignoreCase = true)
        val knownTmdbId = anime.tmdbId?.takeIf {
            it > 0L && it != anime.hoshikaId && it != anime.anilistId
        }
        if (knownTmdbId != null) {
            LogoLogger.d(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' using pre-verified TMDB ID=$knownTmdbId (isMovie=$isMovieFormat)"
            )
            return ResolvedTmdbMedia(id = knownTmdbId, isMovie = isMovieFormat, fromKnownId = true)
        }

        if (!hasValidCredentials()) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.MISSING_CREDENTIALS,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "TMDB search skipped because TMDB_API_KEY is missing or placeholder"
            )
            return null
        }

        return searchAndVerifyMedia(anime)
    }

    suspend fun searchAndVerifyTvSeries(anime: Anime): Long? {
        return searchAndVerifyMedia(anime)?.id
    }

    internal suspend fun searchAndVerifyMedia(anime: Anime): ResolvedTmdbMedia? {
        if (!hasValidCredentials()) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.MISSING_CREDENTIALS,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "TMDB search skipped: TMDB_API_KEY missing or placeholder"
            )
            return null
        }

        val candidateQueries = buildCandidateSearchQueries(anime)
        if (candidateQueries.isEmpty()) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.MISSING_ARTWORK,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "No valid titles available for TMDB search"
            )
            return null
        }

        val isMovieFormat = anime.format.equals("MOVIE", ignoreCase = true)
        if (isMovieFormat && tmdbMovieApi != null) {
            for (queryTitle in candidateQueries) {
                val searchOutcome = executeTmdbMovieSearch(anime, queryTitle)
                if (searchOutcome.abortRemainingQueries) return null
                val rawJson = searchOutcome.body ?: continue
                val matchedMovieId = verifyMatchingMedia(rawJson, anime, queryTitle, isMovie = true)
                if (matchedMovieId != null && matchedMovieId > 0L) {
                    LogoLogger.i(
                        "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Selected TMDB Movie ID=$matchedMovieId (query='$queryTitle')"
                    )
                    return ResolvedTmdbMedia(id = matchedMovieId, isMovie = true, fromKnownId = false)
                }
            }
        }

        for (queryTitle in candidateQueries) {
            val searchOutcome = executeTmdbSearch(anime, queryTitle)
            if (searchOutcome.abortRemainingQueries) return null
            val rawJson = searchOutcome.body ?: continue

            val matchedId = verifyMatchingSeries(rawJson, anime, queryTitle)
            if (matchedId != null && matchedId > 0L) {
                LogoLogger.i(
                    "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Selected TMDB TV ID=$matchedId (query='$queryTitle')"
                )
                return ResolvedTmdbMedia(id = matchedId, isMovie = false, fromKnownId = false)
            }
        }

        LogoLogger.logDiagnostic(
            category = LogoFailureCategory.MISSING_ARTWORK,
            provider = "TMDB",
            hoshikaId = anime.hoshikaId,
            anilistId = anime.anilistId,
            detail = "TMDB search returned no validated anime match across ${candidateQueries.size} title queries"
        )
        return null
    }

    private data class SearchOutcome(
        val body: String? = null,
        val abortRemainingQueries: Boolean = false
    )

    private suspend fun executeTmdbSearch(anime: Anime, query: String): SearchOutcome {
        return try {
            val response = tmdbApi.searchTv(query = query, firstAirDateYear = null)
            val code = response.code()
            LogoLogger.d(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} -> TMDB search/tv status=$code for query='$query'"
            )
            if (response.isSuccessful) {
                val body = response.body()?.string()?.trim()
                if (!body.isNullOrBlank() && body.startsWith("{")) {
                    SearchOutcome(body = body)
                } else {
                    LogoLogger.logDiagnostic(
                        category = LogoFailureCategory.INVALID_RESPONSE,
                        provider = "TMDB",
                        hoshikaId = anime.hoshikaId,
                        anilistId = anime.anilistId,
                        detail = "TMDB search/tv returned empty or non-JSON body for query='$query'"
                    )
                    SearchOutcome()
                }
            } else {
                val category = LogoLogger.classifyHttpFailure(code)
                LogoLogger.logDiagnostic(
                    category = category,
                    provider = "TMDB",
                    hoshikaId = anime.hoshikaId,
                    anilistId = anime.anilistId,
                    detail = "TMDB search/tv HTTP $code for query='$query'"
                )
                SearchOutcome(
                    abortRemainingQueries = category == LogoFailureCategory.AUTH_FAILURE ||
                        category == LogoFailureCategory.RATE_LIMITED
                )
            }
        } catch (e: IOException) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.NETWORK_ERROR,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "TMDB search/tv network error for query='$query': ${e.message}",
                throwable = e
            )
            SearchOutcome()
        } catch (e: Exception) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.INVALID_RESPONSE,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "TMDB search/tv unexpected failure for query='$query': ${e.message}",
                throwable = e
            )
            SearchOutcome()
        }
    }

    private suspend fun executeTmdbMovieSearch(anime: Anime, query: String): SearchOutcome {
        val movieApi = tmdbMovieApi ?: return SearchOutcome()
        return try {
            val response = movieApi.searchMovie(query = query, primaryReleaseYear = anime.releaseYear)
            val code = response.code()
            LogoLogger.d(
                "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} -> TMDB search/movie status=$code for query='$query'"
            )
            if (response.isSuccessful) {
                val body = response.body()?.string()?.trim()
                if (!body.isNullOrBlank() && body.startsWith("{")) {
                    SearchOutcome(body = body)
                } else {
                    SearchOutcome()
                }
            } else {
                val category = LogoLogger.classifyHttpFailure(code)
                LogoLogger.logDiagnostic(
                    category = category,
                    provider = "TMDB",
                    hoshikaId = anime.hoshikaId,
                    anilistId = anime.anilistId,
                    detail = "TMDB search/movie HTTP $code for query='$query'"
                )
                SearchOutcome(
                    abortRemainingQueries = category == LogoFailureCategory.AUTH_FAILURE ||
                        category == LogoFailureCategory.RATE_LIMITED
                )
            }
        } catch (e: IOException) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.NETWORK_ERROR,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "TMDB search/movie network error for query='$query': ${e.message}",
                throwable = e
            )
            SearchOutcome()
        } catch (_: Exception) {
            SearchOutcome()
        }
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
        return verifyMatchingMedia(rawJson = rawJson, anime = anime, searchedQuery = searchedQuery, isMovie = false)
    }

    internal fun verifyMatchingMedia(
        rawJson: String,
        anime: Anime,
        searchedQuery: String = "",
        isMovie: Boolean = false
    ): Long? {
        return try {
            val root = JSONObject(rawJson.trim())
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val rawTargetStrings = listOfNotNull(
                anime.englishTitle,
                anime.title,
                anime.japaneseTitle,
                searchedQuery.takeIf { it.isNotBlank() }
            )

            val exactTargets = rawTargetStrings
                .map { normalizeTitle(it) }
                .filter { it.isNotEmpty() }
                .distinct()

            val baseTargets = rawTargetStrings
                .map { stripSeasonOrArcSuffix(it) }
                .map { normalizeTitle(it) }
                .filter { it.isNotEmpty() }
                .distinct()

            val significantTokens = rawTargetStrings
                .flatMap { tokenizeSignificantWords(it) }
                .distinct()

            var bestId: Long? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val mediaId = item.optLong("id", 0L).takeIf { it > 0L } ?: continue

                val rawName = if (isMovie) item.optString("title") else item.optString("name")
                val rawOriginalName = if (isMovie) item.optString("original_title") else item.optString("original_name")

                val name = normalizeTitle(rawName)
                val originalName = normalizeTitle(rawOriginalName)
                val baseName = normalizeTitle(stripSeasonOrArcSuffix(rawName))
                val baseOriginalName = normalizeTitle(stripSeasonOrArcSuffix(rawOriginalName))

                val candidateNames = listOf(name, originalName, baseName, baseOriginalName)
                    .filter { it.isNotEmpty() }
                if (candidateNames.isEmpty()) continue

                val originalLanguage = item.optString("original_language").lowercase(Locale.ROOT)
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
                val isTokenAnimeMatch = !isExactMatch && !isBaseExactMatch && !isSubstringMatch &&
                    (originalLanguage == "ja" || isAnimation) &&
                    significantTokens.any { token ->
                        candidateNames.any { cand -> cand.contains(token) }
                    }

                if (!isExactMatch && !isBaseExactMatch && !isSubstringMatch && !isTokenAnimeMatch) {
                    continue
                }

                val dateField = if (isMovie) item.optString("release_date") else item.optString("first_air_date")
                val resultYear = dateField.take(4).toIntOrNull()

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
                else if (isTokenAnimeMatch) score += 35

                if (originalLanguage == "ja") score += 35
                if (isAnimation) score += 25

                if (animeYear != null && resultYear != null) {
                    when {
                        yearDiff == 0 -> score += 30
                        yearDiff == 1 -> score += 20
                        isSequelOrMultiSeason && resultYear <= animeYear -> score += 15
                    }
                }

                score += (10 - i).coerceAtLeast(0)

                if (score > bestScore) {
                    bestScore = score
                    bestId = mediaId
                }
            }

            bestId
        } catch (e: Exception) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.INVALID_RESPONSE,
                provider = "TMDB",
                hoshikaId = anime.hoshikaId,
                anilistId = anime.anilistId,
                detail = "Failed parsing TMDB search response: ${e.message}",
                throwable = e
            )
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
        return fetchBestLogoForMediaId(
            hoshikaId = hoshikaId,
            mediaId = tvId,
            isMovie = false,
            excludedUrl = excludedUrl,
            excludedUrls = excludedUrls,
            anilistId = anilistId
        )
    }

    suspend fun fetchBestLogoForMediaId(
        hoshikaId: Long,
        mediaId: Long,
        isMovie: Boolean = false,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet(),
        anilistId: Long? = null
    ): LogoInfo? {
        if (mediaId <= 0L) return null
        if (!hasValidCredentials()) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.MISSING_CREDENTIALS,
                provider = "TMDB",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "TMDB images request skipped for mediaId=$mediaId: TMDB_API_KEY missing or placeholder"
            )
            return null
        }

        val allExcluded = buildExcludedSet(excludedUrl, excludedUrls)
        val rawJson = executeTmdbImagesRequest(hoshikaId, mediaId, isMovie, anilistId) ?: return null
        return parseAndSelectBestLogo(
            rawJson = rawJson,
            hoshikaId = hoshikaId,
            tvId = mediaId,
            excludedUrls = allExcluded,
            anilistId = anilistId
        )
    }

    private suspend fun executeTmdbImagesRequest(
        hoshikaId: Long,
        mediaId: Long,
        isMovie: Boolean,
        anilistId: Long?
    ): String? {
        val routeLabel = if (isMovie) "movie/$mediaId/images" else "tv/$mediaId/images"
        try {
            val response = if (isMovie && tmdbMovieApi != null) {
                tmdbMovieApi.getMovieImages(movieId = mediaId, includeImageLanguage = "en,null,ja")
            } else {
                tmdbApi.getTvImages(tvId = mediaId, includeImageLanguage = "en,null,ja")
            }
            val code = response.code()
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId $routeLabel -> TMDB image request status=$code"
            )
            if (response.isSuccessful) {
                val body = response.body()?.string()?.trim()
                if (!body.isNullOrBlank() && body.startsWith("{")) {
                    return body
                }
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.INVALID_RESPONSE,
                    provider = "TMDB",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "TMDB $routeLabel returned empty or non-JSON body"
                )
            } else {
                LogoLogger.logDiagnostic(
                    category = LogoLogger.classifyHttpFailure(code),
                    provider = "TMDB",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "TMDB $routeLabel returned HTTP $code"
                )
            }
        } catch (e: IOException) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.NETWORK_ERROR,
                provider = "TMDB",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "TMDB $routeLabel network failure: ${e.message}",
                throwable = e
            )
        } catch (e: Exception) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.INVALID_RESPONSE,
                provider = "TMDB",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "TMDB $routeLabel exception: ${e.message}",
                throwable = e
            )
        }
        return null
    }

    internal suspend fun fetchBackendTmdbArtworkLogo(
        anime: Anime,
        excludedUrls: Set<String> = emptySet()
    ): LogoInfo? {
        val api = aniScrollApi ?: return null
        val candidateTitles = listOfNotNull(
            anime.preferredTitle.takeIf { it.isNotBlank() },
            anime.title.takeIf { it.isNotBlank() && it != anime.preferredTitle }
        )
        for (titleCandidate in candidateTitles.ifEmpty { listOf("") }) {
            try {
                val resp = api.getTmdbAnimeArtwork(
                    malId = anime.malId,
                    title = titleCandidate.takeIf { it.isNotBlank() }
                )
                val code = resp.code()
                if (!resp.isSuccessful) {
                    LogoLogger.logDiagnostic(
                        category = LogoLogger.classifyHttpFailure(code),
                        provider = "TMDB_BACKEND",
                        hoshikaId = anime.hoshikaId,
                        anilistId = anime.anilistId,
                        detail = "/api/tmdb/anime returned HTTP $code"
                    )
                    if (code == 401 || code == 403 || code == 429) break
                    continue
                }
                val body = resp.body()?.string()?.trim()
                if (body.isNullOrBlank() || !body.startsWith("{")) continue

                val root = JSONObject(body)
                val dataObj = root.optJSONObject("data") ?: root
                val rawLogoUrl = dataObj.optString("logo", "")
                val logoUrl = LogoLogger.normalizeSecureArtworkUrl(rawLogoUrl)
                    ?.takeIf { it !in excludedUrls }
                if (logoUrl != null) {
                    LogoLogger.i(
                        "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} -> Selected provider=TMDB_BACKEND host=${LogoLogger.extractHost(logoUrl)}"
                    )
                    return LogoInfo(
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
                }
            } catch (e: IOException) {
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.NETWORK_ERROR,
                    provider = "TMDB_BACKEND",
                    hoshikaId = anime.hoshikaId,
                    anilistId = anime.anilistId,
                    detail = "/api/tmdb/anime network error: ${e.message}",
                    throwable = e
                )
                break
            } catch (e: Exception) {
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.INVALID_RESPONSE,
                    provider = "TMDB_BACKEND",
                    hoshikaId = anime.hoshikaId,
                    anilistId = anime.anilistId,
                    detail = "/api/tmdb/anime parse failure: ${e.message}",
                    throwable = e
                )
            }
        }
        return null
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
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.MISSING_ARTWORK,
                    provider = "TMDB",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "TMDB returned 0 logos for mediaId=$tvId"
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
                if (!aspect.isNaN() && aspect > 0.0 && aspect < 0.95) continue

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
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.MISSING_ARTWORK,
                    provider = "TMDB",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "0 suitable TMDB logo candidates after filtering for mediaId=$tvId"
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
                "HoshikaID=$hoshikaId AniListID=$anilistId mediaId=$tvId -> Selected provider=TMDB lang='${selected.lang ?: "neutral"}' format=${selected.format} (${selected.width}x${selected.height}) host=${LogoLogger.extractHost(selected.fullUrl)}"
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
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.INVALID_RESPONSE,
                provider = "TMDB",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "Failed parsing TMDB logos for mediaId=$tvId: ${e.message}",
                throwable = e
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

    private fun tokenizeSignificantWords(raw: String): List<String> {
        val stopWords = setOf("anime", "season", "movie", "special", "chronicle", "story", "edition", "series", "beyond")
        return raw.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 5 && it !in stopWords }
    }
}

