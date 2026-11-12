package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.LogoCacheEntity
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.LogoInfo

/**
 * Coordinates the Title Logo Fallback Sequence (Sections 5, 9, 10, 11):
 *
 * Hoshika anime information
 *         |
 *         v
 * TMDB TV search & validation
 *         |
 *         v
 * Retrieve TMDB logos
 *         |
 *         v
 * Valid logo available?
 *    YES       NO
 *     |         |
 *     v         v
 *  Display   Fanart.tv (hdtvlogo)
 *               |
 *               v
 *        Valid logo available?
 *           YES       NO
 *            |         |
 *            v         v
 *         Display   Text fallback
 *
 * Room Cache Rules:
 * - Never permanently caches a failed/null logo lookup in Room.
 * - Automatically invalidates expired or broken cache entries and retries TMDB -> Fanart.tv -> Text fallback.
 */
class LogoRepository(
    private val dao: AniScrollDao,
    private val tmdbLogoProvider: TmdbLogoProvider,
    private val fanartLogoProvider: FanartLogoProvider
) {

    companion object {
        private const val VALID_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
    }

    suspend fun getLogoForAnime(anime: Anime, forceRefresh: Boolean = false): String? {
        return getLogoInfoForAnime(anime = anime, forceRefresh = forceRefresh)?.logoUrl
    }

    suspend fun getLogoInfoForAnime(
        anime: Anime,
        forceRefresh: Boolean = false,
        excludedUrl: String? = null
    ): LogoInfo? {
        val hoshikaId = anime.hoshikaId
        if (hoshikaId <= 0L) return null

        LogoLogger.d("HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> Starting logo lookup (forceRefresh=$forceRefresh)")

        // 1. Check Room cache unless forceRefresh is requested
        if (!forceRefresh && excludedUrl == null) {
            val cached = dao.getLogoCache(hoshikaId)
            if (cached != null) {
                val age = System.currentTimeMillis() - cached.lastUpdated
                val isValidUrl = !cached.logoUrl.isNullOrBlank() &&
                    (cached.logoUrl.startsWith("https://") || cached.logoUrl.startsWith("http://")) &&
                    cached.validationStatus == "VALID"

                if (isValidUrl && age in 0 until VALID_CACHE_TTL_MS) {
                    LogoLogger.d(
                        "HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> Cache HIT provider=${cached.provider} host=${LogoLogger.extractHost(cached.logoUrl)}"
                    )
                    return LogoInfo(
                        hoshikaId = cached.hoshikaId,
                        tmdbId = cached.tmdbId,
                        provider = cached.provider,
                        logoUrl = cached.logoUrl,
                        language = cached.language,
                        width = cached.width,
                        height = cached.height,
                        lastUpdated = cached.lastUpdated,
                        isValid = true
                    )
                } else {
                    // Clean up stale or previously cached null/invalid entry so it never blocks future requests
                    LogoLogger.d(
                        "HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> Cache INVALID/EXPIRED (status=${cached.validationStatus}), removing entry"
                    )
                    dao.deleteLogoCache(hoshikaId)
                }
            } else {
                LogoLogger.d("HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> Cache MISS")
            }
        }

        // 2. Step 1: Search & validate TMDB TV series ID
        val tmdbTvId = runCatching {
            tmdbLogoProvider.resolveVerifiedTmdbTvId(anime)
        }.onFailure { e ->
            LogoLogger.e("HoshikaID=$hoshikaId TMDB ID resolution error: ${e.message}", e)
        }.getOrNull()

        if (tmdbTvId != null && tmdbTvId > 0L) {
            // 3. Step 2: Retrieve TMDB logos
            val tmdbLogo = runCatching {
                tmdbLogoProvider.fetchBestLogoForTvId(
                    hoshikaId = hoshikaId,
                    tvId = tmdbTvId,
                    excludedUrl = excludedUrl
                )
            }.onFailure { e ->
                LogoLogger.e("HoshikaID=$hoshikaId TMDB logo fetch error for tvId=$tmdbTvId: ${e.message}", e)
            }.getOrNull()

            if (tmdbLogo != null && !tmdbLogo.logoUrl.isNullOrBlank()) {
                saveToCache(tmdbLogo)
                LogoLogger.i(
                    "HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> Resolved via provider=TMDB host=${LogoLogger.extractHost(tmdbLogo.logoUrl)}"
                )
                return tmdbLogo
            }

            // 4. Step 3: Fallback to Fanart.tv (`hdtvlogo`) using the validated TMDB TV ID
            val fanartLogo = runCatching {
                fanartLogoProvider.fetchFallbackLogo(
                    hoshikaId = hoshikaId,
                    tmdbTvId = tmdbTvId,
                    excludedUrl = excludedUrl
                )
            }.onFailure { e ->
                LogoLogger.e("HoshikaID=$hoshikaId Fanart logo fetch error for tvId=$tmdbTvId: ${e.message}", e)
            }.getOrNull()

            if (fanartLogo != null && !fanartLogo.logoUrl.isNullOrBlank()) {
                saveToCache(fanartLogo)
                LogoLogger.i(
                    "HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> Resolved via provider=FANART host=${LogoLogger.extractHost(fanartLogo.logoUrl)}"
                )
                return fanartLogo
            }
        }

        // 5. Step 4: Both failed or returned no logos -> Do NOT cache failure in Room; return null for clean text fallback
        LogoLogger.i("HoshikaID=$hoshikaId title='${anime.preferredTitle}' -> No valid logo from TMDB or Fanart.tv; using text fallback")
        return null
    }

    /**
     * Called when a cached or supplied logo URL fails to load in Coil (Section 9):
     * 1. Invalidates the bad cache entry in Room.
     * 2. Retries TMDB (excluding the broken URL).
     * 3. Retries Fanart.tv (excluding the broken URL).
     * 4. Returns null for text fallback if both fail.
     */
    suspend fun invalidateAndRetryLogo(anime: Anime, failedUrl: String?): LogoInfo? {
        LogoLogger.w(
            "HoshikaID=${anime.hoshikaId} title='${anime.preferredTitle}' -> Invalidating failed logo host=${LogoLogger.extractHost(failedUrl)} and retrying providers"
        )
        dao.deleteLogoCache(anime.hoshikaId)
        return getLogoInfoForAnime(
            anime = anime.copy(logoUrl = null),
            forceRefresh = true,
            excludedUrl = failedUrl
        )
    }

    suspend fun invalidateLogoCache(hoshikaId: Long) {
        dao.deleteLogoCache(hoshikaId)
    }

    private suspend fun saveToCache(info: LogoInfo) {
        if (info.logoUrl.isNullOrBlank() || !info.isValid) return
        dao.insertLogoCache(
            LogoCacheEntity(
                hoshikaId = info.hoshikaId,
                tmdbId = info.tmdbId,
                provider = info.provider,
                logoUrl = info.logoUrl,
                language = info.language,
                width = info.width,
                height = info.height,
                validationStatus = "VALID",
                lastUpdated = info.lastUpdated
            )
        )
    }
}
