package `in`.aniscroll.tv.artwork

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
    private val aniScrollApi: AniScrollApi? = null
) {

    suspend fun fetchFallbackLogo(
        hoshikaId: Long,
        tmdbTvId: Long,
        excludedUrl: String? = null
    ): LogoInfo? {
        if (tmdbTvId <= 0L) {
            LogoLogger.w("HoshikaID=$hoshikaId Fanart fallback skipped: invalid tmdbTvId=$tmdbTvId")
            return null
        }

        LogoLogger.d("HoshikaID=$hoshikaId attempting Fanart.tv fallback for tmdbTvId=$tmdbTvId (source=tmdb)")
        val rawJson = executeFanartRequest(hoshikaId, tmdbTvId) ?: return null

        return parseAndSelectBestFanartLogo(
            rawJson = rawJson,
            hoshikaId = hoshikaId,
            tmdbTvId = tmdbTvId,
            excludedUrl = excludedUrl
        )
    }

    private suspend fun executeFanartRequest(hoshikaId: Long, tmdbTvId: Long): String? {
        try {
            val response = fanartApi.getTvImages(tmdbTvId = tmdbTvId, source = "tmdb")
            val code = response.code()
            LogoLogger.d("HoshikaID=$hoshikaId tmdbTvId=$tmdbTvId -> Fanart HTTP status=$code")
            when (code) {
                200 -> {
                    val body = response.body()?.string()?.trim()
                    if (!body.isNullOrBlank() && body.startsWith("{")) {
                        return body
                    }
                }
                401 -> LogoLogger.w("HoshikaID=$hoshikaId Fanart returned 401 Unauthorized")
                404 -> LogoLogger.d("HoshikaID=$hoshikaId Fanart returned 404 Not Found for tmdbTvId=$tmdbTvId")
                429 -> LogoLogger.w("HoshikaID=$hoshikaId Fanart returned 429 Too Many Requests")
                in 500..599 -> LogoLogger.w("HoshikaID=$hoshikaId Fanart returned server error $code")
            }
        } catch (e: Exception) {
            LogoLogger.w("HoshikaID=$hoshikaId Fanart request exception for tmdbTvId=$tmdbTvId: ${e.message}", e)
        }

        if (aniScrollApi != null) {
            try {
                val proxyResp = aniScrollApi.getFanartTvImages(tvId = tmdbTvId)
                if (proxyResp.isSuccessful) {
                    val body = proxyResp.body()?.string()?.trim()
                    if (!body.isNullOrBlank() && body.startsWith("{")) {
                        return body
                    }
                }
            } catch (e: Exception) {
                LogoLogger.w("HoshikaID=$hoshikaId Fanart proxy exception: ${e.message}")
            }
        }
        return null
    }

    internal fun parseAndSelectBestFanartLogo(
        rawJson: String,
        hoshikaId: Long,
        tmdbTvId: Long,
        excludedUrl: String? = null
    ): LogoInfo? {
        return try {
            val root = JSONObject(rawJson.trim())
            val logoArray = root.optJSONArray("hdtvlogo")
            val totalLogos = logoArray?.length() ?: 0
            LogoLogger.d("HoshikaID=$hoshikaId tmdbTvId=$tmdbTvId -> Number of Fanart hdtvlogo items=$totalLogos")
            if (logoArray == null || totalLogos == 0) return null

            data class FanartCandidate(
                val url: String,
                val lang: String?,
                val likes: Int,
                val width: Int,
                val height: Int
            )

            val candidates = mutableListOf<FanartCandidate>()
            for (i in 0 until totalLogos) {
                val obj = logoArray.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").trim().takeIf {
                    it.isNotEmpty() && (it.startsWith("https://") || it.startsWith("http://"))
                } ?: continue

                if (excludedUrl != null && url == excludedUrl) continue

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
                        likes = likes,
                        width = if (width > 0) width else 800,
                        height = if (height > 0) height else 310
                    )
                )
            }

            if (candidates.isEmpty()) return null

            // Language priority: 1. English ("en"), 2. Japanese ("ja"), 3. Language-neutral (null / "" / "00" / "xx"), 4. Other
            val best = candidates.sortedWith(
                compareBy<FanartCandidate> {
                    when (it.lang?.lowercase(Locale.ROOT)) {
                        "en" -> 0
                        "ja" -> 1
                        null, "", "00", "xx" -> 2
                        else -> 3
                    }
                }.thenByDescending { it.width }
                    .thenByDescending { it.likes }
            ).first()

            LogoLogger.i(
                "HoshikaID=$hoshikaId tmdbTvId=$tmdbTvId -> Selected FANART hdtvlogo lang='${best.lang ?: "neutral"}' (${best.width}x${best.height}, likes=${best.likes}) host=${LogoLogger.extractHost(best.url)}"
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
            LogoLogger.e("Failed parsing Fanart hdtvlogo for HoshikaID=$hoshikaId tmdbTvId=$tmdbTvId: ${e.message}", e)
            null
        }
    }
}
