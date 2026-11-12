package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.domain.model.LogoInfo
import org.json.JSONObject

/**
 * Fanart.tv Title Logo Fallback Provider (Section 10).
 * Only called when TMDB has no suitable transparent logo.
 * Prioritizes `hdtvlogo` -> `clearlogo` with language priority: en > ja > neutral > other.
 */
class FanartLogoProvider(
    private val aniScrollApi: AniScrollApi
) {

    suspend fun fetchFallbackLogo(hoshikaId: Long, tmdbTvId: Long): LogoInfo? {
        if (tmdbTvId <= 0L) return null

        val response = runCatching {
            aniScrollApi.getFanartTvImages(tvId = tmdbTvId)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null
        val raw = response.body()?.string() ?: return null

        return runCatching {
            val root = JSONObject(raw.trim())
            val logoArray = root.optJSONArray("hdtvlogo")
                ?: root.optJSONArray("clearlogo")
                ?: return null

            if (logoArray.length() == 0) return null

            data class FanartCandidate(
                val url: String,
                val lang: String?,
                val likes: Int
            )

            val candidates = mutableListOf<FanartCandidate>()
            for (i in 0 until logoArray.length()) {
                val obj = logoArray.optJSONObject(i) ?: continue
                val url = obj.optString("url").takeIf { it.isNotBlank() && it.startsWith("http") } ?: continue
                if (!url.endsWith(".png", ignoreCase = true)) continue
                val lang = obj.optString("lang").takeIf { it.isNotBlank() && it != "null" }
                val likes = obj.optString("likes", "0").toIntOrNull() ?: 0
                candidates.add(FanartCandidate(url, lang, likes))
            }

            if (candidates.isEmpty()) return null

            val best = candidates.sortedWith(
                compareBy<FanartCandidate> {
                    when (it.lang?.lowercase()) {
                        "en" -> 0
                        "ja" -> 1
                        null, "", "00" -> 2
                        else -> 3
                    }
                }.thenByDescending { it.likes }
            ).first()

            LogoInfo(
                hoshikaId = hoshikaId,
                tmdbId = tmdbTvId,
                provider = "FANART",
                logoUrl = best.url,
                language = best.lang,
                width = 800,
                height = 310,
                lastUpdated = System.currentTimeMillis(),
                isValid = true
            )
        }.getOrNull()
    }
}
