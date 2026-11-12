package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.LogoCacheEntity
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.LogoInfo

/**
 * Coordinates the Title Logo Fallback Sequence (Sections 9 & 10):
 * 1. Check Room `logo_cache` (with 7-day TTL for valid logos, 24h for text fallback).
 * 2. Search & verify TMDB TV series and inspect `/tv/{tv_id}/images` `logos`.
 * 3. If TMDB returns no valid logo, fallback to Fanart.tv `hdtvlogo`.
 * 4. Cache result in Room so we do not repeat logo searches every time details open.
 */
class LogoRepository(
    private val dao: AniScrollDao,
    private val tmdbLogoProvider: TmdbLogoProvider,
    private val fanartLogoProvider: FanartLogoProvider
) {

    companion object {
        private const val VALID_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
        private const val EMPTY_CACHE_TTL_MS = 24L * 60 * 60 * 1000L // 24 hours
    }

    suspend fun getLogoForAnime(anime: Anime, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            val cached = dao.getLogoCache(anime.hoshikaId)
            if (cached != null) {
                val age = System.currentTimeMillis() - cached.lastUpdated
                val ttl = if (!cached.logoUrl.isNullOrBlank()) VALID_CACHE_TTL_MS else EMPTY_CACHE_TTL_MS
                if (age < ttl) {
                    return cached.logoUrl?.takeIf { it.isNotBlank() }
                }
            }
        }

        // If Hoshika itself already supplied a transparent logo URL, cache and return it
        if (!anime.logoUrl.isNullOrBlank()) {
            saveToCache(
                LogoInfo(
                    hoshikaId = anime.hoshikaId,
                    tmdbId = anime.tmdbId,
                    provider = "HOSHIKA",
                    logoUrl = anime.logoUrl,
                    language = "en",
                    width = 800,
                    height = 310,
                    lastUpdated = System.currentTimeMillis(),
                    isValid = true
                )
            )
            return anime.logoUrl
        }

        // Step 1: Try TMDB
        val tmdbTvId = anime.tmdbId?.takeIf { it > 0L }
            ?: tmdbLogoProvider.searchAndVerifyTvSeries(anime)

        if (tmdbTvId != null && tmdbTvId > 0L) {
            val tmdbLogo = tmdbLogoProvider.fetchBestLogoForTvId(anime.hoshikaId, tmdbTvId)
            if (tmdbLogo != null && !tmdbLogo.logoUrl.isNullOrBlank()) {
                saveToCache(tmdbLogo)
                return tmdbLogo.logoUrl
            }

            // Step 2: Fallback to Fanart.tv using the verified TMDB TV ID
            val fanartLogo = fanartLogoProvider.fetchFallbackLogo(anime.hoshikaId, tmdbTvId)
            if (fanartLogo != null && !fanartLogo.logoUrl.isNullOrBlank()) {
                saveToCache(fanartLogo)
                return fanartLogo.logoUrl
            }
        }

        // Step 3: Cache empty result so UI immediately falls back to clean white typography
        saveToCache(
            LogoInfo(
                hoshikaId = anime.hoshikaId,
                tmdbId = tmdbTvId,
                provider = "NONE",
                logoUrl = null,
                language = null,
                width = 0,
                height = 0,
                lastUpdated = System.currentTimeMillis(),
                isValid = false
            )
        )
        return null
    }

    private suspend fun saveToCache(info: LogoInfo) {
        dao.insertLogoCache(
            LogoCacheEntity(
                hoshikaId = info.hoshikaId,
                tmdbId = info.tmdbId,
                provider = info.provider,
                logoUrl = info.logoUrl,
                language = info.language,
                width = info.width,
                height = info.height,
                validationStatus = if (info.isValid) "VALID" else "TEXT_FALLBACK",
                lastUpdated = info.lastUpdated
            )
        )
    }
}
