package `in`.aniscroll.tv.data.repository

import `in`.aniscroll.tv.artwork.LogoRepository
import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.AnimeEntity
import `in`.aniscroll.tv.core.database.SearchHistoryEntity
import `in`.aniscroll.tv.core.database.WatchHistoryEntity
import `in`.aniscroll.tv.core.database.WatchlistEntity
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.HoshikaApi
import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.domain.converter.HoshikaIdConverter
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.ScheduleItem
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private data class TimedCacheEntry<T>(
    val value: T,
    val cachedAtMs: Long
)

data class HomeSectionRow(
    val title: String,
    val items: List<Anime>
)

data class HomeFeedResult(
    val featuredAnime: Anime?,
    val sections: List<HomeSectionRow>,
    val isFromOfflineCache: Boolean = false,
    val errorMessage: String? = null
)

sealed class ResourceResult<out T> {
    data class Success<T>(
        val data: T,
        val isCachedFallback: Boolean = false,
        val hasIncompletePagination: Boolean = false
    ) : ResourceResult<T>()
    data class Error(val userMessage: String) : ResourceResult<Nothing>()
}

class AnimeRepository(
    private val hoshikaApi: HoshikaApi,
    private val aniScrollApi: AniScrollApi,
    private val dao: AniScrollDao,
    private val logoRepository: LogoRepository,
    private val aniListGraphQlApi: `in`.aniscroll.tv.data.remote.AniListGraphQlApi =
        `in`.aniscroll.tv.core.network.NetworkModule.aniListGraphQlApi
) {

    companion object {
        private const val ANIME_CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes
        private const val EPISODES_CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes
        private const val WATCH_ORDER_CACHE_TTL_MS = 20 * 60 * 1000L // 20 minutes
        private const val MAX_EPISODE_PAGES = 250 // Supports up to 25,000 episodes while preventing runaway loops
    }

    private val animeMemoryCache = ConcurrentHashMap<Long, TimedCacheEntry<Anime>>()
    private val episodesMemoryCache = ConcurrentHashMap<Long, TimedCacheEntry<List<Episode>>>()
    private val episodePageCache = ConcurrentHashMap<String, TimedCacheEntry<JsonParsers.EpisodePageResult>>()
    private val partialEpisodesCache = ConcurrentHashMap<Long, List<Episode>>()
    private val watchOrderMemoryCache = ConcurrentHashMap<Long, TimedCacheEntry<`in`.aniscroll.tv.domain.model.FranchiseWatchOrder>>()

    val idConverter: HoshikaIdConverter = HoshikaIdConverter(
        dao = dao,
        aniScrollApi = aniScrollApi,
        fetchHoshikaAnimeDetails = { hoshikaId -> fetchRemoteAnimeDetails(hoshikaId) }
    )

    // Watch cached full schedule for fast day switching
    private var cachedWeeklySchedule: List<ScheduleItem> = emptyList()

    // --- Watchlist (My List) ---
    val watchlistFlow: Flow<List<Anime>> = dao.observeWatchlist().map { list ->
        list.map { entity ->
            Anime(
                hoshikaId = entity.hoshikaId,
                title = entity.animeTitle,
                englishTitle = entity.animeTitle,
                posterUrl = entity.posterUrl,
                bannerUrl = entity.bannerUrl,
                logoUrl = entity.logoUrl,
                releaseYear = entity.releaseYear,
                status = entity.status
            )
        }
    }

    fun observeIsInWatchlist(hoshikaId: Long): Flow<Boolean> =
        dao.observeIsInWatchlist(hoshikaId)

    suspend fun toggleWatchlist(anime: Anime): Boolean {
        val exists = dao.isInWatchlist(anime.hoshikaId)
        if (exists) {
            dao.removeFromWatchlist(anime.hoshikaId)
            return false
        } else {
            dao.addToWatchlist(
                WatchlistEntity(
                    hoshikaId = anime.hoshikaId,
                    animeTitle = anime.preferredTitle,
                    posterUrl = anime.posterUrl,
                    bannerUrl = anime.bannerUrl,
                    logoUrl = anime.logoUrl,
                    releaseYear = anime.releaseYear,
                    status = anime.status,
                    addedAt = System.currentTimeMillis()
                )
            )
            return true
        }
    }

    // --- Continue Watching & Watch History ---
    val continueWatchingFlow: Flow<List<WatchProgressItem>> = dao.observeContinueWatching().map { list ->
        list.map { it.toDomain() }
    }

    val allWatchHistoryFlow: Flow<List<WatchProgressItem>> = dao.observeAllWatchHistory().map { list ->
        list.map { it.toDomain() }
    }

    fun observeWatchHistoryForAnime(hoshikaId: Long): Flow<List<WatchProgressItem>> =
        dao.observeWatchHistoryForAnime(hoshikaId).map { list -> list.map { it.toDomain() } }

    suspend fun removeContinueWatching(hoshikaId: Long, episodeNumber: Int) {
        dao.removeContinueWatchingItem(hoshikaId, episodeNumber)
    }

    // --- Search History ---
    val searchHistoryFlow: Flow<List<String>> = dao.observeSearchHistory().map { list ->
        list.map { it.query }
    }

    suspend fun saveSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            dao.insertSearchQuery(SearchHistoryEntity(query = trimmed, timestamp = System.currentTimeMillis()))
        }
    }

    suspend fun deleteSearchQuery(query: String) {
        dao.deleteSearchQuery(query)
    }

    suspend fun clearSearchHistory() {
        dao.clearSearchHistory()
    }

    // --- Home Feed (Hoshika /api/v1/home + /browse + /genre + /catalog) ---
    suspend fun getHomeFeed(): HomeFeedResult = coroutineScope {
        val sections = mutableListOf<HomeSectionRow>()
        var featured: Anime? = null

        val homeDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getHome() },
                proxyPath = "home"
            )
        }
        val topPopularDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getBrowse(type = "anime", sort = "top") },
                proxyPath = "browse"
            )
        }
        val upcomingDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getCatalog(type = "anime", status = "NOT_YET_RELEASED", page = 1) },
                proxyPath = "catalog"
            )
        }
        val discoverDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getDiscover() },
                proxyPath = "discover"
            )
        }
        val actionDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getGenreTitles(slug = "action", type = "anime") },
                proxyPath = "genre/action"
            )
        }
        val adventureDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getGenreTitles(slug = "adventure", type = "anime") },
                proxyPath = "genre/adventure"
            )
        }
        val fantasyDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getGenreTitles(slug = "fantasy", type = "anime") },
                proxyPath = "genre/fantasy"
            )
        }
        val romanceDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getGenreTitles(slug = "romance", type = "anime") },
                proxyPath = "genre/romance"
            )
        }
        val comedyDeferred = async {
            executeHoshikaGet(
                directCall = { hoshikaApi.getGenreTitles(slug = "comedy", type = "anime") },
                proxyPath = "genre/comedy"
            )
        }

        // 1. Parse /api/v1/home ("trending", "seasonal", "recentlyAdded")
        val homeRaw = homeDeferred.await()
        if (homeRaw != null) {
            runCatching {
                val root = JSONObject(homeRaw.trim())
                val dataObj = root.optJSONObject("data") ?: root

                val homeSectionMappings = listOf(
                    "trending" to "Trending Anime",
                    "seasonal" to "Currently Airing",
                    "recentlyAdded" to "Recently Updated",
                    "recently_updated" to "Recently Updated",
                    "popular" to "Popular Anime",
                    "upcoming" to "Upcoming Anime",
                    "recommended" to "Recommended Anime"
                )
                for ((key, title) in homeSectionMappings) {
                    val arr = dataObj.optJSONArray(key) ?: continue
                    val list = buildList {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            JsonParsers.parseHoshikaAnime(obj)?.let { add(it) }
                        }
                    }
                    if (list.isNotEmpty() && sections.none { it.title == title }) {
                        cacheAnimeBatch(list)
                        sections.add(HomeSectionRow(title = title, items = list))
                    }
                }
            }
        }

        // 2. Append Popular Anime (from /api/v1/browse?type=anime&sort=top)
        topPopularDeferred.await()?.let { raw ->
            val list = JsonParsers.parseAnimeListFromResponse(raw).take(20)
            if (list.isNotEmpty()) {
                cacheAnimeBatch(list)
                sections.add(HomeSectionRow("Popular Anime", list))
            }
        }

        // 3. Append Upcoming Anime
        upcomingDeferred.await()?.let { raw ->
            val list = JsonParsers.parseAnimeListFromResponse(raw).take(20)
            if (list.isNotEmpty()) {
                cacheAnimeBatch(list)
                sections.add(HomeSectionRow("Upcoming Anime", list))
            }
        }

        // 4. Append Recommended Anime (from /api/v1/discover)
        discoverDeferred.await()?.let { raw ->
            val list = JsonParsers.parseAnimeListFromResponse(raw).take(20)
            if (list.isNotEmpty()) {
                cacheAnimeBatch(list)
                sections.add(HomeSectionRow("Recommended Anime", list))
            }
        }

        // 5. Append Genre rows
        val genreFutures = listOf(
            "Action" to actionDeferred,
            "Adventure" to adventureDeferred,
            "Fantasy" to fantasyDeferred,
            "Romance" to romanceDeferred,
            "Comedy" to comedyDeferred
        )
        for ((genreTitle, deferred) in genreFutures) {
            deferred.await()?.let { raw ->
                val list = JsonParsers.parseAnimeListFromResponse(raw).take(20)
                if (list.isNotEmpty()) {
                    cacheAnimeBatch(list)
                    sections.add(HomeSectionRow(genreTitle, list))
                }
            }
        }

        // Pick a featured hero anime with wide bannerImage if available
        featured = sections.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { !it.bannerUrl.isNullOrBlank() && !it.description.isNullOrBlank() }
            ?: sections.firstOrNull()?.items?.firstOrNull()

        if (featured != null) {
            featured = enrichAnimeWithLogo(featured!!)
            cacheAnime(featured!!)
        }

        if (sections.isNotEmpty() || featured != null) {
            return@coroutineScope HomeFeedResult(
                featuredAnime = featured,
                sections = sections,
                isFromOfflineCache = false,
                errorMessage = null
            )
        }

        val cachedEntities = dao.getRecentCachedAnime(limit = 30)
        if (cachedEntities.isNotEmpty()) {
            val cachedAnime = cachedEntities.map { it.toDomain() }
            val cachedFeatured = cachedAnime.firstOrNull()?.let { enrichAnimeWithLogo(it) }
            return@coroutineScope HomeFeedResult(
                featuredAnime = cachedFeatured,
                sections = listOf(HomeSectionRow("Cached Library", cachedAnime)),
                isFromOfflineCache = true,
                errorMessage = null
            )
        }

        return@coroutineScope HomeFeedResult(
            featuredAnime = null,
            sections = emptyList(),
            isFromOfflineCache = false,
            errorMessage = "Unable to connect. Check your internet connection."
        )
    }

    // --- Anime Details ---
    suspend fun getAnimeDetails(hoshikaId: Long): ResourceResult<Anime> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        animeMemoryCache[hoshikaId]?.takeIf { now - it.cachedAtMs < ANIME_CACHE_TTL_MS }?.value?.let { mem ->
            if (!mem.logoUrl.isNullOrBlank() && !mem.format.isNullOrBlank()) {
                return@withContext ResourceResult.Success(mem, isCachedFallback = false)
            }
        }

        val remote = fetchRemoteAnimeDetails(hoshikaId)
        if (remote != null) {
            val enriched = enrichAnimeWithLogo(remote)
            cacheAnime(enriched)
            return@withContext ResourceResult.Success(enriched, isCachedFallback = false)
        }

        val cached = dao.getCachedAnime(hoshikaId)
        if (cached != null) {
            val enrichedCached = enrichAnimeWithLogo(cached.toDomain())
            cacheAnime(enrichedCached)
            return@withContext ResourceResult.Success(enrichedCached, isCachedFallback = true)
        }

        ResourceResult.Error("Anime information is unavailable.")
    }

    /**
     * Ultra-fast metadata lookup for video playback startup and episode switching.
     * - Reuses valid in-memory or Room cached anime metadata immediately when format is known.
     * - Never blocks stream resolution on TMDB/Fanart logo enrichment requests.
     */
    suspend fun getAnimeForPlayback(hoshikaId: Long): ResourceResult<Anime> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        animeMemoryCache[hoshikaId]?.takeIf { now - it.cachedAtMs < ANIME_CACHE_TTL_MS }?.value?.let { mem ->
            if (!mem.format.isNullOrBlank()) {
                return@withContext ResourceResult.Success(mem, isCachedFallback = true)
            }
        }

        val localCached = dao.getCachedAnime(hoshikaId)?.toDomain()
        if (localCached != null && !localCached.format.isNullOrBlank()) {
            animeMemoryCache[hoshikaId] = TimedCacheEntry(localCached, now)
            return@withContext ResourceResult.Success(localCached, isCachedFallback = true)
        }

        val remote = fetchRemoteAnimeDetails(hoshikaId)
        if (remote != null) {
            cacheAnime(remote)
            return@withContext ResourceResult.Success(remote, isCachedFallback = false)
        }

        if (localCached != null) {
            animeMemoryCache[hoshikaId] = TimedCacheEntry(localCached, now)
            return@withContext ResourceResult.Success(localCached, isCachedFallback = true)
        }

        ResourceResult.Error("Anime information is unavailable.")
    }

    suspend fun enrichAnimeWithLogo(anime: Anime, forceRefresh: Boolean = false): Anime {
        val logoInfo = runCatching {
            logoRepository.getLogoInfoForAnime(anime = anime, forceRefresh = forceRefresh)
        }.getOrNull()
        return if (logoInfo != null && !logoInfo.logoUrl.isNullOrBlank()) {
            anime.copy(
                logoUrl = logoInfo.logoUrl,
                tmdbId = logoInfo.tmdbId ?: anime.tmdbId
            )
        } else {
            anime
        }
    }

    /**
     * Invalidates a broken cached logo URL in Room and retries TMDB -> Fanart.tv -> Text fallback.
     */
    suspend fun invalidateAndRetryLogo(anime: Anime, failedUrl: String?): Anime {
        val retried = runCatching {
            logoRepository.invalidateAndRetryLogo(anime = anime, failedUrl = failedUrl)
        }.getOrNull()
        val updated = anime.copy(
            logoUrl = retried?.logoUrl,
            tmdbId = retried?.tmdbId ?: anime.tmdbId
        )
        cacheAnime(updated)
        return updated
    }

    private suspend fun fetchRemoteAnimeDetails(hoshikaId: Long): Anime? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        animeMemoryCache[hoshikaId]?.takeIf { now - it.cachedAtMs < ANIME_CACHE_TTL_MS }?.value?.let { mem ->
            if (!mem.format.isNullOrBlank() && (!mem.englishTitle.isNullOrBlank() || mem.totalEpisodes != null)) {
                return@withContext mem
            }
        }

        val raw = executeHoshikaGet(
            directCall = { hoshikaApi.getAnimeDetails(hoshikaId) },
            proxyPath = "anime/$hoshikaId"
        ) ?: return@withContext null

        val parsed = JsonParsers.parseSingleAnimeFromResponse(raw) ?: return@withContext null
        idConverter.cacheMappingIfValid(parsed.hoshikaId, parsed.anilistId)
        animeMemoryCache[hoshikaId] = TimedCacheEntry(parsed, System.currentTimeMillis())
        parsed
    }

    // --- Anime Episodes ---
    suspend fun getAnimeEpisodes(
        hoshikaId: Long,
        after: Int? = null,
        forceRefresh: Boolean = false,
        onPartialProgress: ((episodesSoFar: List<Episode>, hasMorePages: Boolean) -> Unit)? = null
    ): ResourceResult<List<Episode>> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val hasPartialToResume = partialEpisodesCache.containsKey(hoshikaId)

            if (forceRefresh && !hasPartialToResume) {
                episodesMemoryCache.remove(hoshikaId)
                episodePageCache.keys.removeIf { it.startsWith("${hoshikaId}_after_") }
            }

            if (after == null && !forceRefresh) {
                episodesMemoryCache[hoshikaId]?.takeIf { now - it.cachedAtMs < EPISODES_CACHE_TTL_MS }?.value?.let {
                    return@withContext ResourceResult.Success(it, isCachedFallback = true)
                }
            }

            val episodesByNumber = LinkedHashMap<Int, Episode>()
            val visitedCursors = mutableSetOf<Int?>()
            var currentAfter = after
            var pageCount = 0
            var anyPageSucceeded = false
            var paginationFailedMidStream = false

            while (pageCount < MAX_EPISODE_PAGES) {
                currentCoroutineContext().ensureActive()
                if (!visitedCursors.add(currentAfter)) {
                    break
                }

                val pageCacheKey = "${hoshikaId}_after_${currentAfter ?: 0}"
                val cachedPage = episodePageCache[pageCacheKey]
                    ?.takeIf { System.currentTimeMillis() - it.cachedAtMs < EPISODES_CACHE_TTL_MS }
                    ?.value

                val parsedPage: JsonParsers.EpisodePageResult = if (cachedPage != null) {
                    cachedPage
                } else {
                    val raw = executeHoshikaGet(
                        directCall = {
                            hoshikaApi.getAnimeEpisodes(
                                shortId = hoshikaId,
                                after = currentAfter,
                                limit = 100
                            )
                        },
                        proxyPath = "anime/$hoshikaId/episodes",
                        page = currentAfter
                    )
                    if (raw == null) {
                        if (anyPageSucceeded && episodesByNumber.isNotEmpty()) {
                            paginationFailedMidStream = true
                        }
                        break
                    }
                    JsonParsers.parseEpisodePageFromResponse(hoshikaId, raw).also { parsed ->
                        episodePageCache[pageCacheKey] = TimedCacheEntry(parsed, System.currentTimeMillis())
                    }
                }

                anyPageSucceeded = true
                var newEpisodesInPage = 0
                for (ep in parsedPage.episodes) {
                    val existing = episodesByNumber[ep.number]
                    if (existing == null) {
                        episodesByNumber[ep.number] = ep
                        newEpisodesInPage++
                    } else {
                        episodesByNumber[ep.number] = JsonParsers.mergeDuplicateEpisode(existing, ep)
                    }
                }

                if (after != null || parsedPage.episodes.isEmpty() || newEpisodesInPage == 0) {
                    break
                }

                val meta = parsedPage.pagination
                val maxEpInPage = parsedPage.episodes.maxOfOrNull { it.number }
                val currentPageNum = meta.currentPage ?: (pageCount + 1)

                val explicitlyDone = meta.hasNextPage == false ||
                    (meta.totalPages != null && currentPageNum >= meta.totalPages) ||
                    (meta.totalEpisodes != null && episodesByNumber.size >= meta.totalEpisodes &&
                        meta.nextAfter == null && meta.hasNextPage != true &&
                        (meta.nextPage == null || meta.nextPage <= currentPageNum))

                val nextCursor: Int? = if (explicitlyDone) {
                    null
                } else {
                    when {
                        meta.nextAfter != null && meta.nextAfter !in visitedCursors -> meta.nextAfter
                        (meta.hasNextPage == true ||
                            (meta.nextPage != null && meta.nextPage > currentPageNum) ||
                            (meta.totalPages != null && currentPageNum < meta.totalPages) ||
                            (meta.totalEpisodes != null && episodesByNumber.size < meta.totalEpisodes)) &&
                            maxEpInPage != null && maxEpInPage !in visitedCursors -> maxEpInPage
                        meta.hasNextPage == null && meta.nextAfter == null && meta.totalPages == null &&
                            parsedPage.episodes.size in 50..100 &&
                            maxEpInPage != null && maxEpInPage !in visitedCursors -> maxEpInPage
                        else -> null
                    }
                }

                if (onPartialProgress != null && episodesByNumber.isNotEmpty()) {
                    onPartialProgress(episodesByNumber.values.sortedBy { it.number }, nextCursor != null)
                }

                if (nextCursor == null) {
                    break
                }
                currentAfter = nextCursor
                pageCount++
            }

            if (episodesByNumber.isNotEmpty()) {
                val sorted = episodesByNumber.values.sortedBy { it.number }
                if (paginationFailedMidStream) {
                    partialEpisodesCache[hoshikaId] = sorted
                    return@withContext ResourceResult.Success(
                        data = sorted,
                        isCachedFallback = false,
                        hasIncompletePagination = true
                    )
                } else {
                    partialEpisodesCache.remove(hoshikaId)
                    if (after == null) {
                        episodesMemoryCache[hoshikaId] = TimedCacheEntry(sorted, System.currentTimeMillis())
                    }
                    return@withContext ResourceResult.Success(
                        data = sorted,
                        isCachedFallback = false,
                        hasIncompletePagination = false
                    )
                }
            }

            // Preserve any previously fetched episodes if a subsequent retry failed completely
            partialEpisodesCache[hoshikaId]?.takeIf { it.isNotEmpty() }?.let { preserved ->
                return@withContext ResourceResult.Success(
                    data = preserved,
                    isCachedFallback = true,
                    hasIncompletePagination = true
                )
            }
            episodesMemoryCache[hoshikaId]?.value?.takeIf { it.isNotEmpty() }?.let { cachedFull ->
                return@withContext ResourceResult.Success(
                    data = cachedFull,
                    isCachedFallback = true,
                    hasIncompletePagination = false
                )
            }

            // If Hoshika returned 0 episode objects for an anime whose metadata explicitly declares totalEpisodes > 0,
            // generate playable episode entries for all known episodes (without any 200-episode cap).
            val cachedAnime = dao.getCachedAnime(hoshikaId)?.toDomain() ?: fetchRemoteAnimeDetails(hoshikaId)
            val knownTotalEpisodes = cachedAnime?.totalEpisodes?.takeIf { it > 0 }
            if (cachedAnime != null && knownTotalEpisodes != null) {
                val fallbackList = (1..knownTotalEpisodes).map { epNum ->
                    Episode(
                        id = "${hoshikaId}_ep_$epNum",
                        hoshikaId = hoshikaId,
                        number = epNum,
                        title = "Episode $epNum",
                        thumbnailUrl = cachedAnime.bannerUrl ?: cachedAnime.posterUrl
                    )
                }
                if (after == null) {
                    episodesMemoryCache[hoshikaId] = TimedCacheEntry(fallbackList, System.currentTimeMillis())
                }
                return@withContext ResourceResult.Success(fallbackList)
            }

            if (anyPageSucceeded) {
                return@withContext ResourceResult.Success(emptyList())
            }
            ResourceResult.Error("Unable to connect. Check your internet connection.")
        }

    // --- Search ---
    suspend fun searchAnime(query: String, page: Int = 1): ResourceResult<List<Anime>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ResourceResult.Success(emptyList())

        `in`.aniscroll.tv.features.search.SearchLogger.d(
            "Starting search query='$trimmed' page=$page endpoint='https://hoshika.app/api/v1/search?q=$trimmed&limit=20&locale=en'"
        )

        var searchHttpCode: Int? = null
        var searchRaw: String? = null
        try {
            val resp = hoshikaApi.search(query = trimmed, limit = 20)
            searchHttpCode = resp.code()
            `in`.aniscroll.tv.features.search.SearchLogger.d(
                "Endpoint='/api/v1/search' query='$trimmed' HTTP status=$searchHttpCode"
            )
            if (resp.isSuccessful) {
                val body = resp.body()?.string()?.trim()
                if (!body.isNullOrBlank() && (body.startsWith("{") || body.startsWith("["))) {
                    searchRaw = body
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            `in`.aniscroll.tv.features.search.SearchLogger.d(
                "Search request cancelled for query='$trimmed'"
            )
            throw ce
        } catch (e: Exception) {
            `in`.aniscroll.tv.features.search.SearchLogger.w(
                "Network failure on '/api/v1/search' for query='$trimmed': ${e.message}",
                e
            )
        }

        if (searchRaw != null) {
            val list = JsonParsers.parseAnimeListFromResponse(searchRaw).distinctBy { it.hoshikaId }
            `in`.aniscroll.tv.features.search.SearchLogger.i(
                "Endpoint='/api/v1/search' query='$trimmed' returned ${list.size} results"
            )
            if (list.isNotEmpty()) {
                cacheAnimeBatch(list)
                return ResourceResult.Success(list)
            }
        }

        // Fallback to Hoshika /catalog?type=anime&q=... if /search returned empty or error
        `in`.aniscroll.tv.features.search.SearchLogger.d(
            "Attempting fallback endpoint='https://hoshika.app/api/v1/catalog?type=anime&q=$trimmed&page=$page' for query='$trimmed'"
        )
        var catalogRaw: String? = null
        try {
            val catResp = hoshikaApi.getCatalog(type = "anime", query = trimmed, page = page)
            `in`.aniscroll.tv.features.search.SearchLogger.d(
                "Endpoint='/api/v1/catalog' query='$trimmed' HTTP status=${catResp.code()}"
            )
            if (catResp.isSuccessful) {
                val body = catResp.body()?.string()?.trim()
                if (!body.isNullOrBlank() && (body.startsWith("{") || body.startsWith("["))) {
                    catalogRaw = body
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            `in`.aniscroll.tv.features.search.SearchLogger.d(
                "Catalog search fallback cancelled for query='$trimmed'"
            )
            throw ce
        } catch (e: Exception) {
            `in`.aniscroll.tv.features.search.SearchLogger.w(
                "Network failure on '/api/v1/catalog' for query='$trimmed': ${e.message}",
                e
            )
        }

        if (catalogRaw != null) {
            val list = JsonParsers.parseAnimeListFromResponse(catalogRaw).distinctBy { it.hoshikaId }
            `in`.aniscroll.tv.features.search.SearchLogger.i(
                "Endpoint='/api/v1/catalog' query='$trimmed' returned ${list.size} results"
            )
            if (list.isNotEmpty()) {
                cacheAnimeBatch(list)
            } else {
                `in`.aniscroll.tv.features.search.SearchLogger.i(
                    "Empty search response for query='$trimmed' across /search and /catalog"
                )
            }
            return ResourceResult.Success(list)
        }

        if (searchRaw != null) {
            `in`.aniscroll.tv.features.search.SearchLogger.i(
                "Empty search response for query='$trimmed'"
            )
            return ResourceResult.Success(emptyList())
        }

        `in`.aniscroll.tv.features.search.SearchLogger.e(
            "Search failed for query='$trimmed' (last HTTP status=${searchHttpCode ?: "none"})"
        )
        return ResourceResult.Error("Unable to connect. Check your internet connection.")
    }

    // --- Discover ---
    suspend fun getDiscoverAnime(
        category: String,
        genre: String?,
        page: Int = 1
    ): ResourceResult<List<Anime>> {
        val raw = when {
            !genre.isNullOrBlank() -> {
                if (page == 1) {
                    val slug = genre.lowercase().replace(" ", "-")
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getGenreTitles(slug = slug, type = "anime") },
                        proxyPath = "genre/$slug"
                    ) ?: executeHoshikaGet(
                        directCall = { hoshikaApi.getCatalog(type = "anime", genre = genre, page = page) },
                        proxyPath = "catalog",
                        genre = genre,
                        page = page
                    )
                } else {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getCatalog(type = "anime", genre = genre, page = page) },
                        proxyPath = "catalog",
                        genre = genre,
                        page = page
                    )
                }
            }
            category.equals("Trending", ignoreCase = true) -> {
                if (page == 1) {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getBrowse(type = "anime", sort = "trending") },
                        proxyPath = "browse"
                    )
                } else {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getCatalog(type = "anime", sort = "trending", page = page) },
                        proxyPath = "catalog",
                        page = page
                    )
                }
            }
            category.equals("Popular", ignoreCase = true) -> {
                if (page == 1) {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getBrowse(type = "anime", sort = "top") },
                        proxyPath = "browse"
                    )
                } else {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getCatalog(type = "anime", sort = "top", page = page) },
                        proxyPath = "catalog",
                        page = page
                    )
                }
            }
            category.equals("Latest", ignoreCase = true) -> {
                if (page == 1) {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getBrowse(type = "anime", sort = "recent") },
                        proxyPath = "browse"
                    )
                } else {
                    executeHoshikaGet(
                        directCall = { hoshikaApi.getCatalog(type = "anime", sort = "recent", page = page) },
                        proxyPath = "catalog",
                        page = page
                    )
                }
            }
            category.equals("Seasonal", ignoreCase = true) -> {
                executeHoshikaGet(
                    directCall = { hoshikaApi.getSeason() },
                    proxyPath = "season"
                )
            }
            category.equals("Upcoming", ignoreCase = true) -> {
                executeHoshikaGet(
                    directCall = { hoshikaApi.getCatalog(type = "anime", status = "NOT_YET_RELEASED", page = page) },
                    proxyPath = "catalog",
                    page = page
                )
            }
            else -> {
                executeHoshikaGet(
                    directCall = { hoshikaApi.getCatalog(type = "anime", page = page) },
                    proxyPath = "catalog",
                    page = page
                )
            }
        }

        if (raw != null) {
            val list = JsonParsers.parseAnimeListFromResponse(raw)
            cacheAnimeBatch(list)
            return ResourceResult.Success(list)
        }

        val cached = dao.getRecentCachedAnime(limit = 24)
        if (cached.isNotEmpty()) {
            val filtered = cached.map { it.toDomain() }.filter { anime ->
                genre.isNullOrBlank() || anime.genres.any { it.equals(genre, ignoreCase = true) }
            }
            if (filtered.isNotEmpty()) {
                return ResourceResult.Success(filtered, isCachedFallback = true)
            }
        }

        return ResourceResult.Error("Unable to connect. Check your internet connection.")
    }

    // --- Schedule ---
    suspend fun getSchedule(day: String): ResourceResult<List<ScheduleItem>> {
        if (cachedWeeklySchedule.isNotEmpty()) {
            val filtered = cachedWeeklySchedule.filter { it.dayOfWeek.equals(day, ignoreCase = true) }
            return ResourceResult.Success(filtered)
        }

        val raw = executeHoshikaGet(
            directCall = { hoshikaApi.getSchedule() },
            proxyPath = "schedule",
            day = day.lowercase()
        )
        if (raw != null) {
            val items = JsonParsers.parseScheduleFromResponse(raw)
            cachedWeeklySchedule = items
            cacheAnimeBatch(items.map { it.anime })
            val filtered = items.filter { it.dayOfWeek.equals(day, ignoreCase = true) }
            return ResourceResult.Success(filtered)
        }
        return ResourceResult.Error("Unable to connect. Check your internet connection.")
    }

    private suspend fun executeHoshikaGet(
        directCall: suspend () -> retrofit2.Response<okhttp3.ResponseBody>,
        proxyPath: String,
        query: String? = null,
        page: Int? = null,
        genre: String? = null,
        day: String? = null
    ): String? {
        val directResp = runCatching { directCall() }.getOrNull()
        if (directResp != null && directResp.isSuccessful) {
            val body = directResp.body()?.string()?.trim()
            if (!body.isNullOrBlank() && (body.startsWith("{") || body.startsWith("["))) {
                return body
            }
        }

        val proxyResp = runCatching {
            aniScrollApi.getHoshikaProxy(
                path = proxyPath,
                query = query,
                page = page,
                genre = genre,
                day = day
            )
        }.getOrNull()
        if (proxyResp != null && proxyResp.isSuccessful) {
            val body = proxyResp.body()?.string()?.trim()
            if (!body.isNullOrBlank() && (body.startsWith("{") || body.startsWith("["))) {
                return body
            }
        }
        return null
    }

    private suspend fun cacheAnime(anime: Anime) {
        idConverter.cacheMappingIfValid(anime.hoshikaId, anime.anilistId)
        val existingAnime = dao.getCachedAnime(anime.hoshikaId)
        val existingLogo = dao.getLogoCache(anime.hoshikaId)?.takeIf { it.validationStatus == "VALID" }
        val merged = anime.copy(
            logoUrl = anime.logoUrl ?: existingLogo?.logoUrl ?: existingAnime?.logoUrl,
            tmdbId = anime.tmdbId ?: existingLogo?.tmdbId ?: existingAnime?.tmdbId
        )
        animeMemoryCache[merged.hoshikaId] = TimedCacheEntry(merged, System.currentTimeMillis())
        dao.insertAnime(merged.toEntity())
    }

    private suspend fun cacheAnimeBatch(list: List<Anime>) {
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = list.map { anime ->
            idConverter.cacheMappingIfValid(anime.hoshikaId, anime.anilistId)
            val existingAnime = dao.getCachedAnime(anime.hoshikaId)
            val existingLogo = dao.getLogoCache(anime.hoshikaId)?.takeIf { it.validationStatus == "VALID" }
            val merged = anime.copy(
                logoUrl = anime.logoUrl ?: existingLogo?.logoUrl ?: existingAnime?.logoUrl,
                tmdbId = anime.tmdbId ?: existingLogo?.tmdbId ?: existingAnime?.tmdbId
            )
            animeMemoryCache[merged.hoshikaId] = TimedCacheEntry(merged, now)
            merged.toEntity()
        }
        dao.insertAnimeList(entities)
    }

    private fun Anime.toEntity(): AnimeEntity = AnimeEntity(
        hoshikaId = hoshikaId,
        anilistId = anilistId,
        title = title,
        englishTitle = englishTitle,
        japaneseTitle = japaneseTitle,
        description = description,
        posterUrl = posterUrl,
        bannerUrl = bannerUrl,
        logoUrl = logoUrl,
        releaseYear = releaseYear,
        season = season,
        status = status,
        format = format,
        totalEpisodes = totalEpisodes,
        genresCsv = genres.joinToString(","),
        tmdbId = tmdbId,
        lastUpdated = System.currentTimeMillis()
    )

    private fun AnimeEntity.toDomain(): Anime = Anime(
        hoshikaId = hoshikaId,
        anilistId = anilistId,
        tmdbId = tmdbId,
        title = title,
        englishTitle = englishTitle,
        japaneseTitle = japaneseTitle,
        description = description,
        posterUrl = posterUrl,
        bannerUrl = bannerUrl,
        logoUrl = logoUrl,
        releaseYear = releaseYear,
        season = season,
        status = status,
        format = format,
        totalEpisodes = totalEpisodes,
        genres = genresCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    )

    private fun WatchHistoryEntity.toDomain(): WatchProgressItem = WatchProgressItem(
        hoshikaId = hoshikaId,
        anilistId = anilistId,
        animeTitle = animeTitle.ifBlank { "Anime #$hoshikaId" },
        posterUrl = posterUrl,
        bannerUrl = bannerUrl,
        logoUrl = logoUrl,
        episodeNumber = episodeNumber,
        language = language,
        positionMs = position,
        durationMs = duration,
        completed = completed,
        lastWatched = lastWatched
    )

    // --- Franchise Watch Order (AniList GraphQL Relations + Hoshika Relations) ---
    suspend fun getFranchiseWatchOrder(
        hoshikaId: Long,
        forceRefresh: Boolean = false
    ): ResourceResult<`in`.aniscroll.tv.domain.model.FranchiseWatchOrder> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            watchOrderMemoryCache[hoshikaId]
                ?.takeIf { now - it.cachedAtMs < WATCH_ORDER_CACHE_TTL_MS }
                ?.value
                ?.let { cachedOrder ->
                    val refreshedOrder = refreshWatchOrderStatuses(cachedOrder)
                    watchOrderMemoryCache[hoshikaId] = TimedCacheEntry(refreshedOrder, now)
                    return@withContext ResourceResult.Success(refreshedOrder, isCachedFallback = true)
                }
        }

        val rootAnimeResult = getAnimeForPlayback(hoshikaId)
        val rootAnime = when (rootAnimeResult) {
            is ResourceResult.Success -> rootAnimeResult.data
            is ResourceResult.Error -> {
                `in`.aniscroll.tv.data.local.UiPreviewFixtures.sampleAnimeList.find { it.hoshikaId == hoshikaId }
                    ?: return@withContext ResourceResult.Error(rootAnimeResult.userMessage)
            }
        }

        val resolvedTarget = idConverter.resolveTarget(rootAnime)
        val rootAnilistId = resolvedTarget?.anilistId
            ?: rootAnime.anilistId?.takeIf { it > 0L }
            ?: rootAnime.hoshikaId

        val nodesById = LinkedHashMap<Long, `in`.aniscroll.tv.features.watchorder.RawFranchiseMediaNode>()
        val edges = mutableListOf<`in`.aniscroll.tv.features.watchorder.RawRelationEdge>()
        val resolvedHoshikaIdsByAnilistId = mutableMapOf<Long, Long>()
        resolvedHoshikaIdsByAnilistId[rootAnilistId] = rootAnime.hoshikaId

        // 1. Collect Hoshika relations for direct shortId mappings and fallback
        val hoshikaDetailsRaw = executeHoshikaGet(
            directCall = { hoshikaApi.getAnimeDetails(hoshikaId) },
            proxyPath = "anime/$hoshikaId"
        )
        val hoshikaRelationStubs = hoshikaDetailsRaw?.let {
            `in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.parseHoshikaRelationsFromDetails(it)
        }.orEmpty()

        // 2. Query AniList GraphQL with bounded BFS (up to 3 batched requests, max 36 anime nodes)
        val expandedAnilistIds = mutableSetOf<Long>()
        var frontierIds = listOf(rootAnilistId)
        var hop = 0

        while (frontierIds.isNotEmpty() && hop < 3 && nodesById.size < 36) {
            val batchIds = frontierIds.filter { it > 0L && it !in expandedAnilistIds }.distinct().take(25)
            if (batchIds.isEmpty()) break
            expandedAnilistIds.addAll(batchIds)

            val fetchedNodes = fetchAniListMediaBatch(batchIds)
            if (fetchedNodes.isEmpty()) break

            val nextFrontier = mutableListOf<Long>()
            for (mediaNode in fetchedNodes) {
                val existing = nodesById[mediaNode.anilistId]
                nodesById[mediaNode.anilistId] = if (existing != null && mediaNode.relations.isEmpty()) {
                    existing
                } else {
                    mediaNode
                }

                for ((relType, relatedNode) in mediaNode.relations) {
                    if (`in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.shouldIncludeAnimeNode(relatedNode, relType)) {
                        if (nodesById.size < 36 || relatedNode.anilistId in nodesById) {
                            nodesById.putIfAbsent(relatedNode.anilistId, relatedNode)
                            edges.add(
                                `in`.aniscroll.tv.features.watchorder.RawRelationEdge(
                                    fromAnilistId = mediaNode.anilistId,
                                    toAnilistId = relatedNode.anilistId,
                                    relationType = relType
                                )
                            )
                            if (relType in `in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.CORE_EXPANSION_RELATIONS &&
                                relatedNode.anilistId !in expandedAnilistIds &&
                                nodesById.size < 36
                            ) {
                                nextFrontier.add(relatedNode.anilistId)
                            }
                        }
                    }
                }
            }

            frontierIds = nextFrontier.distinct()
            hop++
        }

        // 3. Fallback to Hoshika relations if AniList GraphQL returned no franchise neighbors
        if (nodesById.size <= 1 && hoshikaRelationStubs.isNotEmpty()) {
            val rootNode = `in`.aniscroll.tv.features.watchorder.RawFranchiseMediaNode(
                anilistId = rootAnilistId,
                hoshikaId = rootAnime.hoshikaId,
                malId = rootAnime.malId,
                mediaType = "ANIME",
                format = rootAnime.format ?: "TV",
                status = rootAnime.status,
                episodes = rootAnime.totalEpisodes,
                releaseYear = rootAnime.releaseYear,
                startDateNumeric = (rootAnime.releaseYear ?: 9999) * 10000 + 1332,
                titleRomaji = rootAnime.title,
                titleEnglish = rootAnime.englishTitle,
                titleNative = rootAnime.japaneseTitle,
                description = rootAnime.description,
                posterUrl = rootAnime.posterUrl,
                bannerUrl = rootAnime.bannerUrl
            )
            nodesById[rootAnilistId] = rootNode

            for (stub in hoshikaRelationStubs) {
                val stubDetails = fetchRemoteAnimeDetails(stub.shortId)
                val stubAnilistId = stubDetails?.anilistId?.takeIf { it > 0L }
                    ?: dao.getMapping(stub.shortId)?.anilistId?.takeIf { it > 0L }
                    ?: stub.shortId
                resolvedHoshikaIdsByAnilistId[stubAnilistId] = stub.shortId

                val stubNode = `in`.aniscroll.tv.features.watchorder.RawFranchiseMediaNode(
                    anilistId = stubAnilistId,
                    hoshikaId = stub.shortId,
                    malId = stubDetails?.malId,
                    mediaType = "ANIME",
                    format = stubDetails?.format ?: stub.format ?: "TV",
                    status = stubDetails?.status,
                    episodes = stubDetails?.totalEpisodes,
                    releaseYear = stubDetails?.releaseYear,
                    startDateNumeric = (stubDetails?.releaseYear ?: 9999) * 10000 + 1332,
                    titleRomaji = stubDetails?.title ?: stub.title,
                    titleEnglish = stubDetails?.englishTitle ?: stub.title,
                    titleNative = stubDetails?.japaneseTitle,
                    description = stubDetails?.description,
                    posterUrl = stubDetails?.posterUrl ?: stub.coverImage,
                    bannerUrl = stubDetails?.bannerUrl
                )
                nodesById[stubAnilistId] = stubNode
                edges.add(
                    `in`.aniscroll.tv.features.watchorder.RawRelationEdge(
                        fromAnilistId = rootAnilistId,
                        toAnilistId = stubAnilistId,
                        relationType = stub.relationType
                    )
                )
            }
        }

        // 4. Map AniList nodes to existing Hoshika shortIds (from Room cache or Hoshika relation stubs)
        for ((anilistId, node) in nodesById) {
            if (resolvedHoshikaIdsByAnilistId.containsKey(anilistId)) continue

            val mappedFromRoom = dao.getMappingByAnilistId(anilistId)?.hoshikaId
                ?: dao.getCachedAnimeByAnilistId(anilistId)?.hoshikaId
            if (mappedFromRoom != null && mappedFromRoom > 0L) {
                resolvedHoshikaIdsByAnilistId[anilistId] = mappedFromRoom
                continue
            }

            // Check Hoshika relation stubs by title similarity
            val nodeTitles = listOfNotNull(node.titleEnglish, node.titleRomaji, node.titleNative)
                .map { it.lowercase().trim() }
            val matchedStub = hoshikaRelationStubs.firstOrNull { stub ->
                val stubTitle = stub.title.lowercase().trim()
                nodeTitles.any { it == stubTitle }
            }
            if (matchedStub != null) {
                resolvedHoshikaIdsByAnilistId[anilistId] = matchedStub.shortId
            }
        }

        // 5. Gather watch status labels for all franchise entries
        val watchStatusByHoshikaId = mutableMapOf<Long, String>()
        for ( entryHoshikaId in resolvedHoshikaIdsByAnilistId.values.distinct()) {
            computeWatchStatusLabel(entryHoshikaId)?.let { label ->
                watchStatusByHoshikaId[entryHoshikaId] = label
            }
        }

        val generated = `in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.generateWatchOrder(
            rootAnime = rootAnime,
            rootAnilistId = rootAnilistId,
            nodesById = nodesById,
            edges = edges.distinct(),
            resolvedHoshikaIdsByAnilistId = resolvedHoshikaIdsByAnilistId,
            watchStatusByHoshikaId = watchStatusByHoshikaId
        )

        // 6. Pre-cache all franchise entries in Room so selecting any item in Watch Order
        // opens DetailsScreen and streams immediately
        for (entry in generated.recommendedOrder) {
            cacheWatchOrderEntryIfAbsent(entry)
        }

        watchOrderMemoryCache[hoshikaId] = TimedCacheEntry(generated, System.currentTimeMillis())
        ResourceResult.Success(generated)
    }

    /**
     * Ensures that when a user selects an anime entry in the Watch Order screen,
     * its real Hoshika shortId is resolved (if available in Hoshika search) and cached in Room
     * so [DetailsScreen] opens seamlessly without duplicate screens or missing metadata.
     */
    suspend fun resolveAndCacheWatchOrderEntry(
        entry: `in`.aniscroll.tv.domain.model.WatchOrderEntry
    ): Long = withContext(Dispatchers.IO) {
        if (!`in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.isSyntheticHoshikaId(entry.hoshikaId)) {
            cacheWatchOrderEntryIfAbsent(entry)
            return@withContext entry.hoshikaId
        }

        // Check if Room already mapped this anilistId to a real Hoshika ID
        val existingMapping = dao.getMappingByAnilistId(entry.anilistId)?.hoshikaId
            ?.takeIf { !`in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.isSyntheticHoshikaId(it) }
        if (existingMapping != null) {
            return@withContext existingMapping
        }

        // Try a quick search on Hoshika for the exact title & format
        val searchQuery = entry.preferredTitle
        val searchResp = runCatching { hoshikaApi.search(query = searchQuery, limit = 10) }.getOrNull()
        val body = if (searchResp?.isSuccessful == true) searchResp.body()?.string()?.trim() else null
        if (!body.isNullOrBlank() && (body.startsWith("{") || body.startsWith("["))) {
            val candidates = JsonParsers.parseAnimeListFromResponse(body)
            val normTargets = listOfNotNull(entry.englishTitle, entry.title, entry.japaneseTitle)
                .map { it.lowercase().trim() }
            val matched = candidates.firstOrNull { cand ->
                val candTitles = listOfNotNull(cand.englishTitle, cand.title, cand.japaneseTitle)
                    .map { it.lowercase().trim() }
                val titleExact = candTitles.any { it in normTargets }
                val yearMatch = entry.releaseYear == null || cand.releaseYear == null || cand.releaseYear == entry.releaseYear
                titleExact && yearMatch
            }
            if (matched != null) {
                val enriched = matched.copy(
                    anilistId = entry.anilistId,
                    malId = matched.malId ?: entry.malId,
                    posterUrl = matched.posterUrl ?: entry.posterUrl,
                    bannerUrl = matched.bannerUrl ?: entry.bannerUrl,
                    description = matched.description ?: entry.description,
                    releaseYear = matched.releaseYear ?: entry.releaseYear,
                    format = matched.format ?: entry.format,
                    totalEpisodes = matched.totalEpisodes ?: entry.episodes
                )
                cacheAnime(enriched)
                return@withContext enriched.hoshikaId
            }
        }

        cacheWatchOrderEntryIfAbsent(entry)
        entry.hoshikaId
    }

    private suspend fun cacheWatchOrderEntryIfAbsent(
        entry: `in`.aniscroll.tv.domain.model.WatchOrderEntry
    ) {
        val existing = dao.getCachedAnime(entry.hoshikaId)
        val domainAnime = Anime(
            hoshikaId = entry.hoshikaId,
            anilistId = entry.anilistId,
            malId = entry.malId,
            title = entry.title,
            englishTitle = entry.englishTitle ?: existing?.englishTitle,
            japaneseTitle = entry.japaneseTitle ?: existing?.japaneseTitle,
            description = entry.description ?: existing?.description ?: entry.relationshipExplanation,
            posterUrl = entry.posterUrl ?: existing?.posterUrl,
            bannerUrl = entry.bannerUrl ?: existing?.bannerUrl,
            logoUrl = existing?.logoUrl,
            releaseYear = entry.releaseYear ?: existing?.releaseYear,
            status = entry.status ?: existing?.status,
            format = entry.format,
            totalEpisodes = entry.episodes ?: existing?.totalEpisodes
        )
        cacheAnime(domainAnime)
    }

    private suspend fun refreshWatchOrderStatuses(
        order: `in`.aniscroll.tv.domain.model.FranchiseWatchOrder
    ): `in`.aniscroll.tv.domain.model.FranchiseWatchOrder {
        val allHoshikaIds = (order.recommendedOrder.map { it.hoshikaId } +
            order.releaseOrder.map { it.hoshikaId } +
            order.chronologicalOrder.map { it.hoshikaId }).distinct()

        val statusMap = mutableMapOf<Long, String?>()
        for (id in allHoshikaIds) {
            statusMap[id] = computeWatchStatusLabel(id)
        }

        return order.copy(
            recommendedOrder = order.recommendedOrder.map { it.copy(watchStatusLabel = statusMap[it.hoshikaId]) },
            releaseOrder = order.releaseOrder.map { it.copy(watchStatusLabel = statusMap[it.hoshikaId]) },
            chronologicalOrder = order.chronologicalOrder.map { it.copy(watchStatusLabel = statusMap[it.hoshikaId]) }
        )
    }

    private suspend fun computeWatchStatusLabel(hoshikaId: Long): String? {
        val lastWatched = dao.getLastWatchedForAnime(hoshikaId)
        if (lastWatched != null) {
            return if (lastWatched.completed) {
                "Watched • Ep ${lastWatched.episodeNumber}"
            } else {
                "Watching • Ep ${lastWatched.episodeNumber}"
            }
        }
        if (dao.isInWatchlist(hoshikaId)) {
            return "In My List"
        }
        return null
    }

    private suspend fun fetchAniListMediaBatch(
        ids: List<Long>
    ): List<`in`.aniscroll.tv.features.watchorder.RawFranchiseMediaNode> {
        if (ids.isEmpty()) return emptyList()
        val payload = `in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.buildBatchMediaRelationsQueryJson(ids)
        val mediaType = with(okhttp3.MediaType) { "application/json; charset=utf-8".toMediaType() }
        val requestBody = with(okhttp3.RequestBody) { payload.toRequestBody(mediaType) }
        val resp = runCatching { aniListGraphQlApi.query(requestBody) }.getOrNull() ?: return emptyList()
        if (!resp.isSuccessful) return emptyList()
        val rawBody = resp.body()?.string()?.trim() ?: return emptyList()
        return `in`.aniscroll.tv.features.watchorder.WatchOrderGenerator.parseAniListBatchMediaResponse(rawBody)
    }
}
