package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.FanartApi
import `in`.aniscroll.tv.data.remote.FanartMovieApi
import `in`.aniscroll.tv.domain.model.LogoInfo
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Fanart.tv Title Logo Fallback Provider.
 * Only called when TMDB cannot provide a valid title logo.
 * Endpoint (TV): GET https://webservice.fanart.tv/v3.2/tv/{tmdb_tv_id}?api_key={FANART_API_KEY}&source=tmdb
 * Endpoint (Movie): GET https://webservice.fanart.tv/v3.2/movies/{tmdb_movie_id}?api_key={FANART_API_KEY}
 * Parses `hdtvlogo` (or `hdmovielogo` for movies) with language priority: 1. English, 2. Japanese, 3. Neutral, 4. Other.
 */
class FanartLogoProvider(
    private val fanartApi: FanartApi,
    private val aniScrollApi: AniScrollApi? = null,
    private val fanartMovieApi: FanartMovieApi? = null,
    private val hasValidCredentials: () -> Boolean = { NetworkModule.hasValidFanartCredentials() }
) {

    suspend fun fetchFallbackLogo(
        hoshikaId: Long,
        tmdbTvId: Long,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet(),
        anilistId: Long? = null,
        isMovie: Boolean = false
    ): LogoInfo? {
        if (tmdbTvId <= 0L) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.MISSING_ARTWORK,
                provider = "FANART",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "Fanart fallback skipped: invalid tmdbId=$tmdbTvId"
            )
            return null
        }

        if (!hasValidCredentials()) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.MISSING_CREDENTIALS,
                provider = "FANART",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "Fanart fallback skipped for tmdbId=$tmdbTvId: FANART_API_KEY missing or placeholder"
            )
            return null
        }

        val allExcluded = if (excludedUrl.isNullOrBlank()) excludedUrls else excludedUrls + excludedUrl
        LogoLogger.d(
            "HoshikaID=$hoshikaId AniListID=$anilistId attempting Fanart.tv fallback for tmdbId=$tmdbTvId (isMovie=$isMovie)"
        )
        val rawJson = executeFanartRequest(hoshikaId, tmdbTvId, anilistId, isMovie) ?: return null

        return parseAndSelectBestFanartLogo(
            rawJson = rawJson,
            hoshikaId = hoshikaId,
            tmdbTvId = tmdbTvId,
            excludedUrls = allExcluded,
            anilistId = anilistId,
            isMovie = isMovie
        )
    }

    private suspend fun executeFanartRequest(
        hoshikaId: Long,
        tmdbId: Long,
        anilistId: Long?,
        isMovie: Boolean
    ): String? {
        try {
            val response = if (isMovie && fanartMovieApi != null) {
                fanartMovieApi.getMovieImages(tmdbMovieId = tmdbId)
            } else {
                fanartApi.getTvImages(tmdbTvId = tmdbId, source = "tmdb")
            }
            val code = response.code()
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbId=$tmdbId -> Fanart HTTP status=$code"
            )
            if (code == 200) {
                val body = response.body()?.string()?.trim()
                if (!body.isNullOrBlank() && body.startsWith("{")) {
                    return body
                }
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.INVALID_RESPONSE,
                    provider = "FANART",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "Fanart returned empty/non-JSON body for tmdbId=$tmdbId"
                )
            } else {
                LogoLogger.logDiagnostic(
                    category = LogoLogger.classifyHttpFailure(code),
                    provider = "FANART",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "Fanart returned HTTP $code for tmdbId=$tmdbId"
                )
            }
        } catch (e: IOException) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.NETWORK_ERROR,
                provider = "FANART",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "Fanart network failure for tmdbId=$tmdbId: ${e.message}",
                throwable = e
            )
        } catch (e: Exception) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.INVALID_RESPONSE,
                provider = "FANART",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "Fanart unexpected failure for tmdbId=$tmdbId: ${e.message}",
                throwable = e
            )
        }
        return null
    }

    internal fun parseAndSelectBestFanartLogo(
        rawJson: String,
        hoshikaId: Long,
        tmdbTvId: Long,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet(),
        anilistId: Long? = null,
        isMovie: Boolean = false
    ): LogoInfo? {
        val allExcluded = if (excludedUrl.isNullOrBlank()) excludedUrls else excludedUrls + excludedUrl
        return try {
            val root = JSONObject(rawJson.trim())
            val logoArray = if (isMovie) {
                root.optJSONArray("hdmovielogo")
                    ?: root.optJSONArray("movielogo")
                    ?: root.optJSONArray("hdtvlogo")
            } else {
                root.optJSONArray("hdtvlogo")
                    ?: root.optJSONArray("clearlogo")
            }
            val totalLogos = logoArray?.length() ?: 0
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbId=$tmdbTvId -> Number of Fanart logo items=$totalLogos"
            )
            if (logoArray == null || totalLogos == 0) {
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.MISSING_ARTWORK,
                    provider = "FANART",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "Fanart returned 0 logos for tmdbId=$tmdbTvId"
                )
                return null
            }

            data class FanartCandidate(
                val url: String,
                val lang: String?,
                val format: String,
                val formatPriority: Int,
                val likes: Int,
                val width: Int,
                val height: Int
            )

            val candidates = mutableListOf<FanartCandidate>()
            for (i in 0 until totalLogos) {
                val obj = logoArray.optJSONObject(i) ?: continue
                // Always normalize http://assets.fanart.tv/... -> https://assets.fanart.tv/...
                // so Android 9+ release APKs never reject Fanart images due to cleartext HTTP.
                val url = LogoLogger.normalizeSecureArtworkUrl(obj.optString("url", ""))
                    ?: continue

                if (url in allExcluded) continue

                val formatInfo = detectSupportedFormat(url) ?: continue

                val rawLang = if (obj.isNull("lang")) {
                    null
                } else {
                    obj.optString("lang", "").trim().takeIf {
                        it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                    }
                }

                val likes = obj.optString("likes", "0").toIntOrNull() ?: obj.optInt("likes", 0)
                val width = obj.optString("width", "800").toIntOrNull() ?: obj.optInt("width", 800)
                val height = obj.optString("height", "310").toIntOrNull() ?: obj.optInt("height", 310)

                candidates.add(
                    FanartCandidate(
                        url = url,
                        lang = rawLang,
                        format = formatInfo.first,
                        formatPriority = formatInfo.second,
                        likes = likes,
                        width = if (width > 0) width else 800,
                        height = if (height > 0) height else 310
                    )
                )
            }

            if (candidates.isEmpty()) {
                LogoLogger.logDiagnostic(
                    category = LogoFailureCategory.MISSING_ARTWORK,
                    provider = "FANART",
                    hoshikaId = hoshikaId,
                    anilistId = anilistId,
                    detail = "0 valid Fanart logo candidates after filtering for tmdbId=$tmdbTvId"
                )
                return null
            }

            // Language priority: 1. English ("en"), 2. Japanese ("ja"), 3. Language-neutral (null / "" / "00" / "xx"), 4. Other
            val best = candidates.sortedWith(
                compareBy<FanartCandidate> {
                    when (it.lang?.lowercase(Locale.ROOT)) {
                        "en" -> 0
                        "ja" -> 1
                        null, "", "00", "xx" -> 2
                        else -> 3
                    }
                }.thenBy { it.formatPriority }
                    .thenByDescending { it.width }
                    .thenByDescending { it.likes }
            ).first()

            LogoLogger.i(
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbId=$tmdbTvId -> Selected provider=FANART lang='${best.lang ?: "neutral"}' format=${best.format} (${best.width}x${best.height}, likes=${best.likes}) host=${LogoLogger.extractHost(best.url)}"
            )

            LogoInfo(
                hoshikaId = hoshikaId,
                tmdbId = tmdbTvId,
                provider = "FANART",
                logoUrl = best.url,
                language = best.lang,
                width = best.width,
                height = best.height,
                lastUpdated = System.currentTimeMillis(),
                isValid = true
            )
        } catch (e: Exception) {
            LogoLogger.logDiagnostic(
                category = LogoFailureCategory.INVALID_RESPONSE,
                provider = "FANART",
                hoshikaId = hoshikaId,
                anilistId = anilistId,
                detail = "Failed parsing Fanart response for tmdbId=$tmdbTvId: ${e.message}",
                throwable = e
            )
            null
        }
    }

    private fun detectSupportedFormat(url: String): Pair<String, Int>? {
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".png") -> "PNG" to 0
            clean.endsWith(".svg") -> "SVG" to 1
            clean.endsWith(".webp") -> "WEBP" to 2
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "JPG" to 3
            else -> "PNG" to 0 // Fanart URLs without query suffix default to PNG
        }
    }
}

