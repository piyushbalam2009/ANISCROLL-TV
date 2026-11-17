package `in`.aniscroll.tv.artwork

import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.LogoCacheEntity
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.LogoInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the Title Logo Fallback Sequence (Sections 5, 9, 10, 11):
 *
 * Hoshika anime information
 *         |
 *         v
 * TMDB TV search & validation
 *         |
 *         v
 * Retrieve TMDB logos (English -> Japanese -> Neutral -> Other)
 *         |
 *         v
 * Valid logo available?
 *    YES       NO
 *     |         |
 *     v         v
 *  Display   Fanart.tv (hdtvlogo: English -> Japanese -> Neutral -> Other)
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
        const val VALID_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
    }

    private val failedUrlsByAnime = ConcurrentHashMap<Long, MutableSet<String>>()

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

        if (!excludedUrl.isNullOrBlank()) {
            failedUrlsByAnime.getOrPut(hoshikaId) { ConcurrentHashMap.newKeySet() }.add(excludedUrl)
        }
        val excludedUrls: Set<String> = failedUrlsByAnime[hoshikaId]?.toSet() ?: emptySet()

        LogoLogger.d(
            "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Starting logo lookup (forceRefresh=$forceRefresh, excludedCount=${excludedUrls.size})"
        )

        // 1. Check Room cache unless forceRefresh is requested
        if (!forceRefresh && excludedUrls.isEmpty()) {
            val cached = dao.getLogoCache(hoshikaId)
            if (cached != null) {
                val age = System.currentTimeMillis() - cached.lastUpdated
                val normalizedCachedUrl = LogoLogger.normalizeSecureArtworkUrl(cached.logoUrl)
                val isValidUrl = !normalizedCachedUrl.isNullOrBlank() &&
                    cached.validationStatus == "VALID"

                if (isValidUrl && age in 0 until VALID_CACHE_TTL_MS) {
                    if (normalizedCachedUrl != cached.logoUrl) {
                        dao.insertLogoCache(cached.copy(logoUrl = normalizedCachedUrl))
                    }
                    LogoLogger.d(
                        "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Cache HIT provider=${cached.provider} lang=${cached.language ?: "neutral"} host=${LogoLogger.extractHost(normalizedCachedUrl)}"
                    )
                    return LogoInfo(
                        hoshikaId = cached.hoshikaId,
                        tmdbId = cached.tmdbId,
                        provider = cached.provider,
                        logoUrl = normalizedCachedUrl,
                        language = cached.language,
                        width = cached.width,
                        height = cached.height,
                        lastUpdated = cached.lastUpdated,
                        isValid = true
                    )
                } else {
                    // Clean up stale or previously cached null/invalid entry so it never blocks future requests
                    LogoLogger.d(
                        "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Cache INVALID/EXPIRED (status=${cached.validationStatus}, ageMs=$age), removing entry"
                    )
                    dao.deleteLogoCache(hoshikaId)
                }
            } else {
                LogoLogger.d(
                    "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Cache MISS"
                )
            }
        }

        // 2. Step 1: Resolve TMDB media ID (pre-known or search)
        val initialResolved = runCatching {
            tmdbLogoProvider.resolveVerifiedTmdbMedia(anime)
        }.onFailure { e ->
            LogoLogger.e(
                "HoshikaID=$hoshikaId AniListID=${anime.anilistId} TMDB ID resolution error: ${e.message}",
                e
            )
        }.getOrNull()

        if (initialResolved != null && initialResolved.id > 0L) {
            val directLogo = tryResolveDirectLogo(
                anime = anime,
                mediaId = initialResolved.id,
                isMovie = initialResolved.isMovie,
                excludedUrls = excludedUrls
            )
            if (directLogo != null) {
                saveToCache(directLogo)
                return directLogo
            }

            // If a pre-verified tmdbId failed on both TMDB and Fanart, try title-based search once
            if (initialResolved.fromKnownId) {
                val searchedMedia = runCatching { tmdbLogoProvider.searchAndVerifyMedia(anime) }.getOrNull()
                if (searchedMedia != null &&
                    (searchedMedia.id != initialResolved.id || searchedMedia.isMovie != initialResolved.isMovie)
                ) {
                    val searchedLogo = tryResolveDirectLogo(
                        anime = anime,
                        mediaId = searchedMedia.id,
                        isMovie = searchedMedia.isMovie,
                        excludedUrls = excludedUrls
                    )
                    if (searchedLogo != null) {
                        saveToCache(searchedLogo)
                        return searchedLogo
                    }
                }
            }
        }

        // 3. Always attempt AniScroll backend /api/tmdb/anime proxy fallback if direct TMDB and Fanart failed or lacked credentials
        val backendLogo = runCatching {
            tmdbLogoProvider.fetchBackendTmdbArtworkLogo(anime, excludedUrls)
        }.getOrNull()
        if (backendLogo != null && !backendLogo.logoUrl.isNullOrBlank()) {
            saveToCache(backendLogo)
            LogoLogger.i(
                "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Final selected provider=TMDB_BACKEND host=${LogoLogger.extractHost(backendLogo.logoUrl)}"
            )
            return backendLogo
        }

        // 4. Both failed or returned no logos -> Do NOT cache failure in Room; return null for clean text fallback
        LogoLogger.i(
            "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Final selected provider=TEXT_FALLBACK (TMDB and Fanart.tv unavailable or returned no logos)"
        )
        return null
    }

    private suspend fun tryResolveDirectLogo(
        anime: Anime,
        mediaId: Long,
        isMovie: Boolean,
        excludedUrls: Set<String>
    ): LogoInfo? {
        val hoshikaId = anime.hoshikaId
        val tmdbLogo = runCatching {
            tmdbLogoProvider.fetchBestLogoForMediaId(
                hoshikaId = hoshikaId,
                mediaId = mediaId,
                isMovie = isMovie,
                excludedUrls = excludedUrls,
                anilistId = anime.anilistId
            )
        }.onFailure { e ->
            LogoLogger.e(
                "HoshikaID=$hoshikaId AniListID=${anime.anilistId} TMDB logo fetch error for mediaId=$mediaId: ${e.message}",
                e
            )
        }.getOrNull()

        if (tmdbLogo != null && !tmdbLogo.logoUrl.isNullOrBlank()) {
            LogoLogger.i(
                "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Final selected provider=TMDB lang=${tmdbLogo.language ?: "neutral"} host=${LogoLogger.extractHost(tmdbLogo.logoUrl)}"
            )
            return tmdbLogo
        }

        LogoLogger.i(
            "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Falling back from TMDB to Fanart.tv for mediaId=$mediaId"
        )
        val fanartLogo = runCatching {
            fanartLogoProvider.fetchFallbackLogo(
                hoshikaId = hoshikaId,
                tmdbTvId = mediaId,
                excludedUrls = excludedUrls,
                anilistId = anime.anilistId,
                isMovie = isMovie
            )
        }.onFailure { e ->
            LogoLogger.e(
                "HoshikaID=$hoshikaId AniListID=${anime.anilistId} Fanart logo fetch error for mediaId=$mediaId: ${e.message}",
                e
            )
        }.getOrNull()

        if (fanartLogo != null && !fanartLogo.logoUrl.isNullOrBlank()) {
            LogoLogger.i(
                "HoshikaID=$hoshikaId AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Final selected provider=FANART lang=${fanartLogo.language ?: "neutral"} host=${LogoLogger.extractHost(fanartLogo.logoUrl)}"
            )
            return fanartLogo
        }
        return null
    }

    /**
     * Called when a cached or supplied logo URL fails to load in Coil:
     * 1. Invalidates the bad cache entry in Room.
     * 2. Records the failed URL in the exclusion set for this anime.
     * 3. Retries TMDB (excluding all failed URLs).
     * 4. Retries Fanart.tv (excluding all failed URLs).
     * 5. Returns null for text fallback if both fail.
     */
    suspend fun invalidateAndRetryLogo(anime: Anime, failedUrl: String?): LogoInfo? {
        LogoLogger.w(
            "HoshikaID=${anime.hoshikaId} AniListID=${anime.anilistId} title='${anime.preferredTitle}' -> Invalidating failed logo host=${LogoLogger.extractHost(failedUrl)} and retrying providers"
        )
        dao.deleteLogoCache(anime.hoshikaId)
        val normalizedFailed = LogoLogger.normalizeSecureArtworkUrl(failedUrl)
        if (!normalizedFailed.isNullOrBlank() && normalizedFailed != failedUrl) {
            failedUrlsByAnime.getOrPut(anime.hoshikaId) { ConcurrentHashMap.newKeySet() }.add(normalizedFailed)
        }
        return getLogoInfoForAnime(
            anime = anime.copy(logoUrl = null),
            forceRefresh = true,
            excludedUrl = failedUrl
        )
    }

    suspend fun invalidateLogoCache(hoshikaId: Long) {
        failedUrlsByAnime.remove(hoshikaId)
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

