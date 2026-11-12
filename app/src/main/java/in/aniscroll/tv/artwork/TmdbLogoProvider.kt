package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.LogoInfo
import org.json.JSONObject
import kotlin.math.abs

/**
 * TMDB Title Logo Provider (Section 9).
 * Exclusively retrieves transparent anime title logos via the AniScroll backend proxy.
 * Never used for metadata, posters, banners, descriptions, or search results.
 */
class TmdbLogoProvider(
    private val aniScrollApi: AniScrollApi
) {

    suspend fun findAndSelectLogo(anime: Anime): LogoInfo? {
        val knownTmdbId = anime.tmdbId?.takeIf { it > 0L }
        val verifiedTvId = knownTmdbId ?: searchAndVerifyTvSeries(anime) ?: return null

        return fetchBestLogoForTvId(
            hoshikaId = anime.hoshikaId,
            tvId = verifiedTvId
        )
    }

    suspend fun searchAndVerifyTvSeries(anime: Anime): Long? {
        val candidateTitles = listOfNotNull(
            anime.englishTitle?.takeIf { it.isNotBlank() },
            anime.title.takeIf { it.isNotBlank() },
            anime.japaneseTitle?.takeIf { it.isNotBlank() }
        ).distinct()

        for (queryTitle in candidateTitles) {
            val response = runCatching {
                aniScrollApi.searchTmdbTv(query = queryTitle, year = anime.releaseYear)
            }.getOrNull() ?: continue

            if (!response.isSuccessful) continue
            val raw = response.body()?.string() ?: continue
            val matchedId = verifyMatchingSeries(raw, anime)
            if (matchedId != null) {
                return matchedId
            }
        }
        return null
    }

    private fun verifyMatchingSeries(rawJson: String, anime: Anime): Long? {
        return runCatching {
            val root = JSONObject(rawJson.trim())
            val results = root.optJSONArray("results") ?: return null
            val targetTitles = listOfNotNull(
                anime.title,
                anime.englishTitle,
                anime.japaneseTitle
            ).map { normalizeTitle(it) }.filter { it.isNotEmpty() }

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val tvId = item.optLong("id", 0L).takeIf { it > 0L } ?: continue
                val name = normalizeTitle(item.optString("name"))
                val originalName = normalizeTitle(item.optString("original_name"))
                val firstAirDate = item.optString("first_air_date")
                val resultYear = firstAirDate.take(4).toIntOrNull()

                val titleMatches = targetTitles.any { target ->
                    name == target || originalName == target ||
                        (target.length > 5 && (name.contains(target) || target.contains(name)))
                }

                val yearMatches = anime.releaseYear == null ||
                    resultYear == null ||
                    abs(anime.releaseYear - resultYear) <= 1

                if (titleMatches && yearMatches) {
                    return tvId
                }
            }
            null
        }.getOrNull()
    }

    suspend fun fetchBestLogoForTvId(hoshikaId: Long, tvId: Long): LogoInfo? {
        val response = runCatching {
            aniScrollApi.getTmdbTvImages(tvId = tvId)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null
        val raw = response.body()?.string() ?: return null

        return runCatching {
            val root = JSONObject(raw.trim())
            val logos = root.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            data class CandidateLogo(
                val filePath: String,
                val lang: String?,
                val width: Int,
                val height: Int,
                val aspectRatio: Double,
                val voteAverage: Double
            )

            val candidates = mutableListOf<CandidateLogo>()
            for (i in 0 until logos.length()) {
                val obj = logos.optJSONObject(i) ?: continue
                val filePath = obj.optString("file_path").takeIf { it.isNotBlank() } ?: continue
                // Prefer PNG with transparency; reject posters (aspect_ratio < 1.1)
                if (!filePath.endsWith(".png", ignoreCase = true) && !filePath.endsWith(".svg", ignoreCase = true)) {
                    continue
                }
                val width = obj.optInt("width", 0)
                val height = obj.optInt("height", 0)
                val aspect = obj.optDouble("aspect_ratio", if (height > 0) width.toDouble() / height else 0.0)
                if (aspect < 1.15) continue // Never display a vertical poster as a title logo

                val rawLang = obj.optString("iso_639_1").takeIf { it.isNotBlank() && it != "null" }
                val vote = obj.optDouble("vote_average", 0.0)
                candidates.add(CandidateLogo(filePath, rawLang, width, height, aspect, vote))
            }

            if (candidates.isEmpty()) return null

            // Language priority: 1. English ("en"), 2. Japanese ("ja"), 3. Language-neutral (null/"xx"), 4. Other
            val selected = candidates.sortedWith(
                compareBy<CandidateLogo> {
                    when (it.lang?.lowercase()) {
                        "en" -> 0
                        "ja" -> 1
                        null, "", "xx" -> 2
                        else -> 3
                    }
                }.thenByDescending { it.width }
                    .thenByDescending { it.voteAverage }
            ).first()

            LogoInfo(
                hoshikaId = hoshikaId,
                tmdbId = tvId,
                provider = "TMDB",
                logoUrl = "https://image.tmdb.org/t/p/original${selected.filePath}",
                language = selected.lang,
                width = selected.width,
                height = selected.height,
                lastUpdated = System.currentTimeMillis(),
                isValid = true
            )
        }.getOrNull()
    }

    private fun normalizeTitle(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9\\u3040-\\u30ff\\u4e00-\\u9faf]"), "")
            .trim()
    }
}
