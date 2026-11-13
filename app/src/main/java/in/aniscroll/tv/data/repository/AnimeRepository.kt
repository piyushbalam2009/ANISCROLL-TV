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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

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
    data class Success<T>(val data: T, val isCachedFallback: Boolean = false) : ResourceResult<T>()
    data class Error(val userMessage: String) : ResourceResult<Nothing>()
}

class AnimeRepository(
    private val hoshikaApi: HoshikaApi,
    private val aniScrollApi: AniScrollApi,
    private val dao: AniScrollDao,
    private val logoRepository: LogoRepository
) {

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
    suspend fun getAnimeDetails(hoshikaId: Long): ResourceResult<Anime> {
        val remote = fetchRemoteAnimeDetails(hoshikaId)
        if (remote != null) {
            val enriched = enrichAnimeWithLogo(remote)
            cacheAnime(enriched)
            return ResourceResult.Success(enriched, isCachedFallback = false)
        }

        val cached = dao.getCachedAnime(hoshikaId)
        if (cached != null) {
            val enrichedCached = enrichAnimeWithLogo(cached.toDomain())
            cacheAnime(enrichedCached)
            return ResourceResult.Success(enrichedCached, isCachedFallback = true)
        }

        return ResourceResult.Error("Anime information is unavailable.")
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

    private suspend fun fetchRemoteAnimeDetails(hoshikaId: Long): Anime? {
        val raw = executeHoshikaGet(
            directCall = { hoshikaApi.getAnimeDetails(hoshikaId) },
            proxyPath = "anime/$hoshikaId"
        ) ?: return null

        val parsed = JsonParsers.parseSingleAnimeFromResponse(raw) ?: return null
        idConverter.cacheMappingIfValid(parsed.hoshikaId, parsed.anilistId)
        return parsed
    }

    // --- Anime Episodes ---
    suspend fun getAnimeEpisodes(hoshikaId: Long, after: Int? = null): ResourceResult<List<Episode>> {
        val allEpisodes = mutableListOf<Episode>()
        var currentAfter = after
        var pageCount = 0
        var anyPageSucceeded = false

        while (pageCount < 4) {
            val raw = executeHoshikaGet(
                directCall = { hoshikaApi.getAnimeEpisodes(shortId = hoshikaId, after = currentAfter, limit = 100) },
                proxyPath = "anime/$hoshikaId/episodes"
            ) ?: break

            anyPageSucceeded = true
            val pageEpisodes = JsonParsers.parseEpisodesFromResponse(hoshikaId, raw)
            allEpisodes.addAll(pageEpisodes)

            val nextAfter = runCatching {
                val root = JSONObject(raw.trim())
                if (root.isNull("nextAfter")) null else root.optInt("nextAfter", -1).takeIf { it > 0 }
            }.getOrNull()

            if (nextAfter == null || pageEpisodes.isEmpty() || after != null) {
                break
            }
            currentAfter = nextAfter
            pageCount++
        }

        if (allEpisodes.isNotEmpty()) {
            return ResourceResult.Success(allEpisodes.distinctBy { it.number }.sortedBy { it.number })
        }

        // If Hoshika returned 0 episode objects for an anime that has episodes, synthesize playable episode entries
        val cachedAnime = dao.getCachedAnime(hoshikaId)?.toDomain() ?: fetchRemoteAnimeDetails(hoshikaId)
        if (cachedAnime != null) {
            val count = (cachedAnime.totalEpisodes ?: 12).coerceIn(1, 200)
            val fallbackList = (1..count).map { epNum ->
                Episode(
                    id = "${hoshikaId}_ep_$epNum",
                    hoshikaId = hoshikaId,
                    number = epNum,
                    title = "Episode $epNum",
                    thumbnailUrl = cachedAnime.bannerUrl ?: cachedAnime.posterUrl
                )
            }
            return ResourceResult.Success(fallbackList)
        }

        if (anyPageSucceeded) {
            return ResourceResult.Success(emptyList())
        }
        return ResourceResult.Error("Unable to connect. Check your internet connection.")
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
        dao.insertAnime(merged.toEntity())
    }

    private suspend fun cacheAnimeBatch(list: List<Anime>) {
        if (list.isEmpty()) return
        val entities = list.map { anime ->
            idConverter.cacheMappingIfValid(anime.hoshikaId, anime.anilistId)
            val existingAnime = dao.getCachedAnime(anime.hoshikaId)
            val existingLogo = dao.getLogoCache(anime.hoshikaId)?.takeIf { it.validationStatus == "VALID" }
            anime.copy(
                logoUrl = anime.logoUrl ?: existingLogo?.logoUrl ?: existingAnime?.logoUrl,
                tmdbId = anime.tmdbId ?: existingLogo?.tmdbId ?: existingAnime?.tmdbId
            ).toEntity()
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
}
