package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.network.NetworkModule
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.FanartApi
import `in`.aniscroll.tv.domain.model.LogoInfo
import org.json.JSONObject
import java.util.Locale

/**
 * Fanart.tv Title Logo Fallback Provider (Section 4).
 * Only called when TMDB cannot provide a valid title logo.
 * Endpoint: GET https://webservice.fanart.tv/v3.2/tv/{tmdb_tv_id}?api_key={FANART_API_KEY}&source=tmdb
 * Strictly parses the `hdtvlogo` array with language priority: 1. English, 2. Japanese, 3. Language-neutral, 4. Other.
 */
class FanartLogoProvider(
    private val fanartApi: FanartApi,
    private val aniScrollApi: AniScrollApi? = null,
    private val hasValidCredentials: () -> Boolean = { NetworkModule.hasValidFanartCredentials() }
) {

    suspend fun fetchFallbackLogo(
        hoshikaId: Long,
        tmdbTvId: Long,
        excludedUrl: String? = null,
        excludedUrls: Set<String> = emptySet(),
        anilistId: Long? = null
    ): LogoInfo? {
        if (tmdbTvId <= 0L) {
            LogoLogger.w(
                "HoshikaID=$hoshikaId AniListID=$anilistId -> Fanart fallback skipped: invalid tmdbTvId=$tmdbTvId"
            )
            return null
        }

        if (!hasValidCredentials()) {
            LogoLogger.w(
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Fanart fallback skipped: FANART_API_KEY is missing or placeholder"
            )
            return null
        }

        val allExcluded = if (excludedUrl.isNullOrBlank()) excludedUrls else excludedUrls + excludedUrl
        LogoLogger.d(
            "HoshikaID=$hoshikaId AniListID=$anilistId attempting Fanart.tv fallback for tmdbTvId=$tmdbTvId (source=tmdb)"
        )
        val rawJson = executeFanartRequest(hoshikaId, tmdbTvId, anilistId) ?: return null

        return parseAndSelectBestFanartLogo(
            rawJson = rawJson,
            hoshikaId = hoshikaId,
            tmdbTvId = tmdbTvId,
            excludedUrls = allExcluded,
            anilistId = anilistId
        )
    }

    private suspend fun executeFanartRequest(
        hoshikaId: Long,
        tmdbTvId: Long,
        anilistId: Long?
    ): String? {
        try {
            val response = fanartApi.getTvImages(tmdbTvId = tmdbTvId, source = "tmdb")
            val code = response.code()
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Fanart HTTP status=$code"
            )
            when (code) {
                200 -> {
                    val body = response.body()?.string()?.trim()
                    if (!body.isNullOrBlank() && body.startsWith("{")) {
                        return body
                    }
                    LogoLogger.w(
                        "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Fallback reason: Fanart returned empty/non-JSON body"
                    )
                }
                401 -> LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart returned 401 Unauthorized"
                )
                403 -> LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart returned 403 Forbidden"
                )
                404 -> LogoLogger.d(
                    "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart returned 404 Not Found for tmdbTvId=$tmdbTvId"
                )
                429 -> LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart returned 429 Too Many Requests"
                )
                in 500..599 -> LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart returned server error $code"
                )
                else -> LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart returned HTTP $code"
                )
            }
        } catch (e: Exception) {
            LogoLogger.w(
                "HoshikaID=$hoshikaId AniListID=$anilistId -> Fallback reason: Fanart request exception for tmdbTvId=$tmdbTvId (${e.message})",
                e
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
        anilistId: Long? = null
    ): LogoInfo? {
        val allExcluded = if (excludedUrl.isNullOrBlank()) excludedUrls else excludedUrls + excludedUrl
        return try {
            val root = JSONObject(rawJson.trim())
            val logoArray = root.optJSONArray("hdtvlogo")
            val totalLogos = logoArray?.length() ?: 0
            LogoLogger.d(
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Number of Fanart hdtvlogo items=$totalLogos"
            )
            if (logoArray == null || totalLogos == 0) {
                LogoLogger.i(
                    "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Fallback reason: Fanart hdtvlogo array is empty"
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
                val url = obj.optString("url", "").trim().takeIf {
                    it.isNotEmpty() &&
                        !it.equals("null", ignoreCase = true) &&
                        (it.startsWith("https://") || it.startsWith("http://"))
                } ?: continue

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
                LogoLogger.w(
                    "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Fallback reason: 0 valid Fanart hdtvlogo candidates after filtering"
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
                "HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId -> Selected FANART hdtvlogo lang='${best.lang ?: "neutral"}' format=${best.format} (${best.width}x${best.height}, likes=${best.likes}) host=${LogoLogger.extractHost(best.url)}"
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
            LogoLogger.e(
                "Failed parsing Fanart hdtvlogo for HoshikaID=$hoshikaId AniListID=$anilistId tmdbTvId=$tmdbTvId: ${e.message}",
                e
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
