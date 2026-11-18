package com.example

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.aniscroll.tv.artwork.FanartLogoProvider
import `in`.aniscroll.tv.artwork.LogoRepository
import `in`.aniscroll.tv.artwork.TmdbLogoProvider
import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.AniScrollDatabase
import `in`.aniscroll.tv.data.remote.AniListGraphQlApi
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.FanartApi
import `in`.aniscroll.tv.data.remote.HoshikaApi
import `in`.aniscroll.tv.data.remote.JsonParsers
import `in`.aniscroll.tv.data.remote.TmdbApi
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.DiscoverQueryFilter
import `in`.aniscroll.tv.data.repository.DiscoverSortKey
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.features.discover.DiscoverScreen
import `in`.aniscroll.tv.features.discover.DiscoverViewModel
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h720dp-land")
class DiscoverEnhancementRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AniScrollDatabase
    private lateinit var dao: AniScrollDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AniScrollDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun jsonParsers_parseCatalogDiscoverGenresAndSeasonMetadata() {
        val catalogRaw = """
            {
              "ok": true,
              "page": 2,
              "hasMore": true,
              "total": 480,
              "items": [
                {
                  "shortId": 1001,
                  "titleRomaji": "Sousou no Frieren",
                  "titleEnglish": "Frieren: Beyond Journey's End",
                  "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/1001/hash.webp",
                  "format": "TV",
                  "status": "FINISHED",
                  "episodes": 28,
                  "averageScore": 91
                }
              ]
            }
        """.trimIndent()
        val catalog = JsonParsers.parseCatalogPageFromResponse(catalogRaw)
        assertEquals(2, catalog.page)
        assertTrue(catalog.hasMore)
        assertEquals(480, catalog.total)
        assertEquals(1, catalog.items.size)
        assertEquals(91.0, catalog.items.first().rating!!, 0.01)

        val discoverRaw = """
            {
              "ok": true,
              "nextOffset": 15,
              "hasMore": true,
              "items": [
                {
                  "shortId": 2002,
                  "titleRomaji": "Dandadan",
                  "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/2002/hash.webp",
                  "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/2002/hash.webp"
                }
              ]
            }
        """.trimIndent()
        val discover = JsonParsers.parseDiscoverPageFromResponse(discoverRaw)
        assertEquals(15, discover.nextOffset)
        assertTrue(discover.hasMore)
        assertEquals(1, discover.items.size)

        val seasonRaw = """
            {
              "ok": true,
              "season": { "season": "FALL", "year": 2026 },
              "items": []
            }
        """.trimIndent()
        assertEquals("Fall 2026", JsonParsers.parseSeasonLabelFromResponse(seasonRaw))

        val genresRaw = """{"ok":true,"genres":["Action","Comedy","Slice of Life"]}"""
        assertEquals(listOf("Action", "Comedy", "Slice of Life"), JsonParsers.parseGenresFromResponse(genresRaw))
    }

    @Test
    fun repository_getDiscoverLandingFeed_populatesCuratedSections_hidesEmpty_andFetchesMissingFullDetails() = runTest {
        var detailsRequestCount = 0
        val fakeApi = FakeDiscoverHoshikaApi(
            onDetails = { shortId ->
                detailsRequestCount++
                """
                    {
                      "shortId": $shortId,
                      "titleRomaji": "Action Summary Anime",
                      "titleEnglish": "Action Summary Anime",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/$shortId/cover.webp",
                      "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/$shortId/full.webp",
                      "synopsis": "Full synopsis fetched from details endpoint.",
                      "format": "TV",
                      "episodes": 12
                    }
                """.trimIndent()
            }
        )
        val repo = createRepository(fakeApi)

        val feed = repo.getDiscoverLandingFeed()
        assertTrue(feed.heroItems.isNotEmpty())
        assertEquals("Fall 2026", feed.seasonLabel)
        assertTrue(feed.genres.contains("Isekai"))
        assertTrue(feed.genres.contains("Action"))
        assertTrue(feed.sections.any { it.id == "trending" && it.title == "Trending Now" })
        assertTrue(feed.sections.any { it.id == "seasonal" && it.subtitle == "Fall 2026" })
        assertTrue(feed.sections.any { it.id == "action_adventure" })
        // Romance was configured empty in FakeDiscoverHoshikaApi -> must be hidden automatically
        assertFalse(feed.sections.any { it.id == "romance" })
        assertEquals(15, feed.discoverNextOffset)
        assertTrue(feed.hasMoreDiscover)

        // Item 9101 came from /genre/action WITHOUT banner/description; getAnimeDetails must still fetch full details
        val partialId = 9101L
        val detailsResult = repo.getAnimeDetails(partialId)
        assertTrue(detailsResult is ResourceResult.Success)
        val fullAnime = (detailsResult as ResourceResult.Success).data
        assertEquals(1, detailsRequestCount)
        assertEquals("Full synopsis fetched from details endpoint.", fullAnime.description)
        assertNotNull(fullAnime.bannerUrl)
    }

    @Test
    fun repository_queryDiscoverCatalog_sendsValidApiParams_andAppliesRatingAndTitleFilters() = runTest {
        val fakeApi = FakeDiscoverHoshikaApi()
        val repo = createRepository(fakeApi)

        val filter = DiscoverQueryFilter(
            genre = "Action",
            format = "MOVIE",
            year = 2024,
            status = "FINISHED",
            minRatingOutOf10 = 8.0,
            sortBy = DiscoverSortKey.TITLE
        )
        val res = repo.queryDiscoverCatalog(filter, page = 1)
        assertTrue(res is ResourceResult.Success)
        val data = (res as ResourceResult.Success).data

        // Verify valid API params sent
        val lastCall = fakeApi.catalogCalls.last()
        assertEquals("Action", lastCall.genre)
        assertEquals("MOVIE", lastCall.format)
        assertEquals(2024, lastCall.year)
        assertEquals("FINISHED", lastCall.status)
        assertEquals("popular", lastCall.sort) // TITLE uses server 'popular' + local A-Z sort

        // Item with score 72 (7.2) excluded by 8.0+ threshold; remaining sorted A-Z
        assertEquals(listOf("Alpha Knight", "Zero Requiem"), data.items.map { it.preferredTitle })
    }

    @Test
    fun discoverViewModel_paginationAndErrorRecovery_preservesLoadedItemsAndSupportsReset() = runTest {
        val fakeApi = FakeDiscoverHoshikaApi()
        val repo = createRepository(fakeApi)
        val viewModel = DiscoverViewModel(repo)

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !viewModel.uiState.value.isInitialLoading
        }

        // Select a genre -> enters filtered mode
        viewModel.selectGenre("Action")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !viewModel.uiState.value.isFilteredLoading && viewModel.uiState.value.filteredItems.isNotEmpty()
        }
        val initialCount = viewModel.uiState.value.filteredItems.size
        assertTrue(initialCount > 0)
        assertTrue(viewModel.uiState.value.hasMoreFiltered)

        // Inject error on page 2 -> existing items must NOT disappear
        fakeApi.failCatalogPage = 2
        viewModel.loadMoreFiltered()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !viewModel.uiState.value.isFilteredLoadingMore && viewModel.uiState.value.filteredErrorMessage != null
        }
        assertEquals(initialCount, viewModel.uiState.value.filteredItems.size)
        assertNotNull(viewModel.uiState.value.filteredErrorMessage)

        // Retry page 2 successfully -> appends page 2 items cleanly without duplicates
        fakeApi.failCatalogPage = null
        viewModel.retryFilteredQuery()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !viewModel.uiState.value.isFilteredLoadingMore && viewModel.uiState.value.filteredPage == 2
        }
        assertTrue(viewModel.uiState.value.filteredItems.size > initialCount)
        assertEquals(
            viewModel.uiState.value.filteredItems.size,
            viewModel.uiState.value.filteredItems.distinctBy { it.hoshikaId }.size
        )

        // Reset returns cleanly to curated landing mode
        viewModel.resetFilters()
        assertFalse(viewModel.uiState.value.isFilteredMode)
        assertTrue(viewModel.uiState.value.sections.isNotEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun discoverScreen_uiInteractions_hero_filters_andDpadNavigation() = runTest {
        val fakeApi = FakeDiscoverHoshikaApi()
        val repo = createRepository(fakeApi)
        val viewModel = DiscoverViewModel(repo)

        var openedSearch = false
        var playedAnimeId: Long? = null
        var openedDetailsId: Long? = null

        composeTestRule.setContent {
            DiscoverScreen(
                viewModel = viewModel,
                onOpenAnimeDetails = { openedDetailsId = it },
                onPlayEpisode = { id, _, _ -> playedAnimeId = id },
                onOpenSearch = { openedSearch = true }
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !viewModel.uiState.value.isInitialLoading
        }
        composeTestRule.waitForIdle()

        // Hero & first curated section displayed immediately
        composeTestRule.onNodeWithTag("discover_hero_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("discover_hero_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trending Now").assertIsDisplayed()

        // Scroll curated rows into viewport on 720dp display to verify seasonal shelf & badge
        composeTestRule.onNodeWithTag("discover_landing_column").performScrollToIndex(2)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Popular This Season").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fall 2026").assertIsDisplayed()
        composeTestRule.onNodeWithTag("discover_landing_column").performScrollToIndex(0)
        composeTestRule.waitForIdle()

        // Search button navigates to Search
        composeTestRule.onNodeWithTag("discover_search_button").performClick()
        assertTrue(openedSearch)

        // Hero Watch Now + Details work cleanly
        composeTestRule.onNodeWithTag("discover_hero_watch_button").performClick()
        assertEquals(1001L, playedAnimeId)
        composeTestRule.onNodeWithTag("discover_hero_details_button").performClick()
        assertEquals(1001L, openedDetailsId)

        // D-pad navigation around hero & down into curated row never throws focus errors
        composeTestRule.onNodeWithTag("discover_hero_watch_button").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionUp)
        }
        composeTestRule.waitForIdle()

        // Open Filters panel -> pick format Movie -> Apply -> renders filtered grid -> Reset restores shelves
        composeTestRule.onNodeWithTag("discover_filters_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("discover_filter_panel").assertIsDisplayed()

        composeTestRule.onNodeWithTag("filter_format_Movie").performClick()
        composeTestRule.onNodeWithTag("discover_apply_filters_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            viewModel.uiState.value.isFilteredMode && !viewModel.uiState.value.isFilteredLoading
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("discover_filtered_grid").assertIsDisplayed()

        composeTestRule.onNodeWithTag("discover_quick_reset_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("discover_hero_section").assertIsDisplayed()
    }

    private fun createRepository(hoshikaApi: HoshikaApi): AnimeRepository {
        val dummyAniScroll = object : AniScrollApi {
            override suspend fun getZokoAnimeStreams(anilistId: Long, episode: Int, language: String) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
            override suspend fun getMegaPlayStreams(anilistId: Long, episode: Int, language: String) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
            override suspend fun searchAnimeIds(query: String, limit: Int) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
            override suspend fun getAniListIdFromMal(malId: Long) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
            override suspend fun getHoshikaProxy(path: String, query: String?, page: Int?, genre: String?, day: String?) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
            override suspend fun getTmdbAnimeArtwork(malId: Long?, title: String?) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
        }
        val logoRepo = LogoRepository(
            dao = dao,
            tmdbLogoProvider = TmdbLogoProvider(
                object : TmdbApi {
                    override suspend fun searchTv(query: String, firstAirDateYear: Int?, includeAdult: Boolean) =
                        Response.error<ResponseBody>(401, "".toResponseBody(null))
                    override suspend fun getTvImages(tvId: Long, includeImageLanguage: String) =
                        Response.error<ResponseBody>(401, "".toResponseBody(null))
                },
                hasValidCredentials = { false }
            ),
            fanartLogoProvider = FanartLogoProvider(
                object : FanartApi {
                    override suspend fun getTvImages(tmdbTvId: Long, source: String) =
                        Response.error<ResponseBody>(401, "".toResponseBody(null))
                },
                hasValidCredentials = { false }
            )
        )
        return AnimeRepository(
            hoshikaApi = hoshikaApi,
            aniScrollApi = dummyAniScroll,
            dao = dao,
            logoRepository = logoRepo,
            aniListGraphQlApi = object : AniListGraphQlApi {
                override suspend fun query(body: RequestBody) =
                    Response.error<ResponseBody>(404, "".toResponseBody(null))
            }
        )
    }

    private data class RecordedCatalogCall(
        val genre: String?,
        val format: String?,
        val status: String?,
        val year: Int?,
        val sort: String?,
        val page: Int
    )

    private class FakeDiscoverHoshikaApi(
        private val onDetails: ((Long) -> String)? = null
    ) : HoshikaApi {
        val catalogCalls = mutableListOf<RecordedCatalogCall>()
        var failCatalogPage: Int? = null

        private fun ok(body: String) =
            Response.success(body.toResponseBody("application/json".toMediaType()))

        override suspend fun getHome(locale: String) = ok(
            """
            {
              "ok": true,
              "trending": [
                {
                  "shortId": 1001,
                  "titleRomaji": "Sousou no Frieren",
                  "titleEnglish": "Frieren: Beyond Journey's End",
                  "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/1001/c.webp",
                  "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/1001/b.webp",
                  "synopsis": "An elf mage reflects on mortality and companionship after the Demon King falls.",
                  "format": "TV",
                  "status": "FINISHED",
                  "seasonYear": 2023,
                  "episodes": 28,
                  "averageScore": 91,
                  "genres": ["Adventure", "Drama", "Fantasy"]
                }
              ],
              "seasonal": [],
              "recentlyAdded": []
            }
            """.trimIndent()
        )

        override suspend fun getBrowse(type: String, sort: String, locale: String) = ok(
            """
            {
              "ok": true,
              "items": [
                {
                  "shortId": 1002,
                  "titleRomaji": "Solo Leveling",
                  "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/1002/c.webp",
                  "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/1002/b.webp",
                  "synopsis": "A weak hunter gains a mysterious leveling system.",
                  "format": "TV",
                  "status": "FINISHED",
                  "seasonYear": 2024,
                  "episodes": 12,
                  "averageScore": 83
                }
              ]
            }
            """.trimIndent()
        )

        override suspend fun getSeason(locale: String) = ok(
            """
            {
              "ok": true,
              "season": { "season": "FALL", "year": 2026 },
              "items": [
                {
                  "shortId": 1003,
                  "titleRomaji": "Seasonal Smash Hit",
                  "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/1003/c.webp",
                  "format": "TV",
                  "status": "RELEASING",
                  "seasonYear": 2026,
                  "averageScore": 84
                }
              ]
            }
            """.trimIndent()
        )

        override suspend fun getCatalog(
            type: String,
            query: String?,
            genre: String?,
            format: String?,
            status: String?,
            year: Int?,
            sort: String?,
            page: Int,
            locale: String
        ): Response<ResponseBody> {
            catalogCalls.add(RecordedCatalogCall(genre, format, status, year, sort, page))
            if (failCatalogPage == page) {
                return Response.error(500, """{"ok":false}""".toResponseBody("application/json".toMediaType()))
            }
            val body = if (page == 1) {
                """
                {
                  "ok": true,
                  "page": 1,
                  "hasMore": true,
                  "total": 48,
                  "items": [
                    {
                      "shortId": 5001,
                      "titleRomaji": "Zero Requiem",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/5001/c.webp",
                      "format": "MOVIE",
                      "status": "FINISHED",
                      "averageScore": 85
                    },
                    {
                      "shortId": 5002,
                      "titleRomaji": "Mid Score Show",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/5002/c.webp",
                      "format": "MOVIE",
                      "status": "FINISHED",
                      "averageScore": 72
                    },
                    {
                      "shortId": 5003,
                      "titleRomaji": "Alpha Knight",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/5003/c.webp",
                      "format": "MOVIE",
                      "status": "FINISHED",
                      "averageScore": 88
                    }
                  ]
                }
                """.trimIndent()
            } else {
                """
                {
                  "ok": true,
                  "page": $page,
                  "hasMore": false,
                  "total": 48,
                  "items": [
                    {
                      "shortId": ${5100 + page},
                      "titleRomaji": "Page $page Discovery",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/${5100 + page}/c.webp",
                      "format": "TV",
                      "status": "FINISHED",
                      "averageScore": 81
                    }
                  ]
                }
                """.trimIndent()
            }
            return ok(body)
        }

        override suspend fun search(query: String, limit: Int, format: String?, year: Int?, locale: String) =
            ok("""{"ok":true,"anime":[]}""")

        override suspend fun getDiscover(offset: Int?, locale: String) = ok(
            """
            {
              "ok": true,
              "nextOffset": ${(offset ?: 0) + 15},
              "hasMore": true,
              "items": [
                {
                  "shortId": ${8000 + (offset ?: 0)},
                  "titleRomaji": "Discover Queue Title",
                  "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/8000/c.webp",
                  "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/8000/b.webp",
                  "synopsis": "Queue spotlight"
                }
              ]
            }
            """.trimIndent()
        )

        override suspend fun getAnimeDetails(shortId: Long, locale: String) =
            ok(onDetails?.invoke(shortId) ?: """{"shortId":$shortId,"titleRomaji":"Detail $shortId","format":"TV","episodes":12}""")

        override suspend fun getAnimeEpisodes(shortId: Long, after: Int?, limit: Int, locale: String) =
            ok("""{"ok":true,"items":[]}""")

        override suspend fun getSchedule(year: Int?, week: Int?, locale: String) =
            ok("""{"ok":true,"items":[]}""")

        override suspend fun getGenres() =
            ok("""{"ok":true,"genres":["Action","Adventure","Comedy","Drama","Fantasy","Romance","Sci-Fi","Slice of Life","Thriller"]}""")

        override suspend fun getGenreTitles(slug: String, type: String, locale: String): Response<ResponseBody> {
            return when (slug) {
                "action" -> ok(
                    """
                    {
                      "ok": true,
                      "items": [
                        {
                          "shortId": 9101,
                          "titleRomaji": "Action Summary Anime",
                          "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/9101/c.webp",
                          "format": "TV",
                          "episodes": 12
                        }
                      ]
                    }
                    """.trimIndent()
                )
                // Intentionally empty romance to test empty-section hiding
                "romance" -> ok("""{"ok":true,"items":[]}""")
                else -> ok(
                    """
                    {
                      "ok": true,
                      "items": [
                        {
                          "shortId": 9200,
                          "titleRomaji": "Genre $slug Show",
                          "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/9200/c.webp"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            }
        }
    }
}
