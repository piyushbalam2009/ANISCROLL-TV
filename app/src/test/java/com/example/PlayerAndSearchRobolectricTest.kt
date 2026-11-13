package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.aniscroll.tv.artwork.FanartLogoProvider
import `in`.aniscroll.tv.artwork.LogoRepository
import `in`.aniscroll.tv.artwork.TmdbLogoProvider
import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.database.AniScrollDatabase
import `in`.aniscroll.tv.core.datastore.UserPreferencesRepository
import `in`.aniscroll.tv.data.remote.AniScrollApi
import `in`.aniscroll.tv.data.remote.AniSkipApi
import `in`.aniscroll.tv.data.remote.FanartApi
import `in`.aniscroll.tv.data.remote.HoshikaApi
import `in`.aniscroll.tv.data.remote.TmdbApi
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.PlaybackRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.features.home.HomeScreen
import `in`.aniscroll.tv.features.home.HomeViewModel
import `in`.aniscroll.tv.features.search.SearchViewModel
import `in`.aniscroll.tv.player.ExoPlayerManager
import `in`.aniscroll.tv.player.PlaybackController
import `in`.aniscroll.tv.player.PlaybackProgressState
import `in`.aniscroll.tv.player.PlayerControlsOverlay
import `in`.aniscroll.tv.player.PlayerFocusTarget
import `in`.aniscroll.tv.player.PlayerSettingsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerAndSearchRobolectricTest {

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
    fun searchAnime_handlesAllRequiredQueries_specialChars_unicode_andEmptyResults() = runTest {
        val queryCatalog = mapOf(
            "Naruto" to Triple(41391L, "Naruto", "Naruto"),
            "One Piece" to Triple(12859L, "ONE PIECE", "One Piece"),
            "Solo Leveling" to Triple(83885L, "Ore dake Level Up na Ken", "Solo Leveling"),
            "Attack on Titan" to Triple(16498L, "Shingeki no Kyojin", "Attack on Titan"),
            "Demon Slayer" to Triple(38524L, "Kimetsu no Yaiba", "Demon Slayer: Kimetsu no Yaiba"),
            "Re:Zero" to Triple(21355L, "Re:Zero kara Hajimeru Isekai Seikatsu", "Re:ZERO -Starting Life in Another World-"),
            "Shingeki no Kyojin" to Triple(16498L, "Shingeki no Kyojin", "Attack on Titan"),
            "進撃の巨人" to Triple(16498L, "Shingeki no Kyojin", "Attack on Titan")
        )

        val fakeHoshika = FakeHoshikaApi(
            dynamicSearchHandler = { query ->
                val match = queryCatalog[query]
                if (match != null) {
                    """
                        {
                          "ok": true,
                          "anime": [
                            {
                              "shortId": ${match.first},
                              "titleRomaji": "${match.second}",
                              "titleEnglish": "${match.third}",
                              "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/${match.first}.webp",
                              "format": "TV",
                              "seasonYear": 2024
                            }
                          ]
                        }
                    """.trimIndent()
                } else {
                    """{ "ok": true, "anime": [] }"""
                }
            }
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())

        for ((query, expected) in queryCatalog) {
            val result = repo.searchAnime(query)
            assertTrue("Expected success for query '$query'", result is ResourceResult.Success)
            val items = (result as ResourceResult.Success).data
            assertEquals("Expected 1 item for query '$query'", 1, items.size)
            assertEquals(expected.first, items.first().hoshikaId)
            assertEquals(expected.third, items.first().preferredTitle)
            assertTrue((fakeHoshika.lastSearchLimit ?: 99) <= 20)
        }

        // Non-matching query returns empty success
        val emptyResult = repo.searchAnime("xyznonexistentanime999")
        assertTrue(emptyResult is ResourceResult.Success)
        assertTrue((emptyResult as ResourceResult.Success).data.isEmpty())
    }

    @Test
    fun searchAnime_fallsBackToCatalogWhenSearchIsEmpty_andRejectsHtmlResponses() = runTest {
        val fakeHoshika = FakeHoshikaApi(
            searchJson = """{ "ok": true, "anime": [] }""",
            catalogJson = """
                {
                  "ok": true,
                  "items": [
                    {
                      "shortId": 30350,
                      "titleRomaji": "Naruto: Shippuuden",
                      "titleEnglish": "Naruto Shippuden",
                      "format": "TV"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())

        val result = repo.searchAnime("shippuden")
        assertTrue(result is ResourceResult.Success)
        val items = (result as ResourceResult.Success).data
        assertEquals(1, items.size)
        assertEquals(30350L, items.first().hoshikaId)
    }

    @Test
    fun searchViewModel_debouncesRapidQueries_andClearsCleanly() = runTest {
        val fakeHoshika = FakeHoshikaApi(
            searchJson = """
                {
                  "ok": true,
                  "anime": [
                    {
                      "shortId": 41391,
                      "titleRomaji": "Naruto",
                      "titleEnglish": "Naruto",
                      "format": "TV"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val viewModel = SearchViewModel(repo)

        viewModel.appendCharacter("N")
        viewModel.appendCharacter("a")
        viewModel.appendCharacter("r")
        viewModel.onQueryChanged("Naruto")
        viewModel.triggerSearchNow()

        composeTestRule.waitForIdle()
        assertEquals("Naruto", viewModel.uiState.value.query)

        viewModel.clearQuery()
        assertEquals("", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.hasSearched)
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    @Test
    fun homeScreen_rendersHeroSectionAndTitleLogoWithinBounds() = runTest {
        val fakeHoshika = FakeHoshikaApi(
            homeJson = """
                {
                  "ok": true,
                  "trending": [
                    {
                      "shortId": 83885,
                      "titleRomaji": "Ore dake Level Up na Ken",
                      "titleEnglish": "Solo Leveling",
                      "bannerImage": "https://cdn.hoshika.app/catalog/banners/anime/83885.webp",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/83885.webp",
                      "description": "Ten years ago, the Gate appeared and connected the real world with the realm of magic and monsters.",
                      "format": "TV",
                      "status": "FINISHED",
                      "seasonYear": 2024,
                      "genres": ["Action", "Adventure", "Fantasy"]
                    },
                    {
                      "shortId": 41391,
                      "titleRomaji": "Naruto",
                      "titleEnglish": "Naruto",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/41391.webp",
                      "format": "TV",
                      "seasonYear": 2002
                    }
                  ]
                }
            """.trimIndent()
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val feed = repo.getHomeFeed()
        assertNotNull(feed.featuredAnime)
        assertEquals(83885L, feed.featuredAnime?.hoshikaId)

        val homeViewModel = HomeViewModel(repo)
        composeTestRule.setContent {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenAnimeDetails = {},
                onPlayEpisode = { _, _, _ -> }
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            !homeViewModel.uiState.value.isLoading
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_hero_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_hero_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("anime_logo_container_83885").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hero_play_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hero_details_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hero_watchlist_button").assertIsDisplayed()
    }

    @Test
    fun exoPlayerManager_singleInstanceAndIdempotentRelease() {
        val manager = ExoPlayerManager(context)
        assertNull(manager.peekPlayer())

        val first = manager.getOrCreatePlayer()
        val second = manager.getOrCreatePlayer()
        assertSame(first, second)
        assertNotNull(manager.peekPlayer())

        manager.pauseIfActive()
        assertFalse(first.playWhenReady)

        manager.releasePlayer()
        assertNull(manager.peekPlayer())

        // Calling releasePlayer or pauseIfActive again does not crash or recreate a player
        manager.pauseIfActive()
        manager.releasePlayer()
        assertNull(manager.peekPlayer())
    }

    @Test
    fun playbackController_exitCancelsPendingSessionAndReleasesPlayer() = runTest {
        val fakeHoshika = FakeHoshikaApi(
            detailsDelayMs = 400L,
            detailsJson = """
                {
                  "shortId": 41391,
                  "titleRomaji": "Naruto",
                  "titleEnglish": "Naruto",
                  "mappings": { "anilist": 20 }
                }
            """.trimIndent()
        )
        val exoManager = ExoPlayerManager(context)
        val animeRepo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val playbackRepo = PlaybackRepository(
            aniScrollApi = FakeAniScrollApi(),
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )
        val prefs = UserPreferencesRepository(context)
        val controller = PlaybackController(
            exoPlayerManager = exoManager,
            animeRepository = animeRepo,
            playbackRepository = playbackRepo,
            preferencesRepository = prefs
        )

        // Start playback session (which suspends in Hoshika details fetch), then exit immediately
        exoManager.getOrCreatePlayer()
        controller.startPlaybackSession(hoshikaId = 41391L, episodeNumber = 1)
        controller.exitAndReleasePlayer()

        assertNull(exoManager.peekPlayer())
        assertFalse(controller.uiState.value.isPlaying)

        // Wait past the simulated delayed API response to ensure no ghost ExoPlayer is created
        delay(500L)
        assertNull(exoManager.peekPlayer())
    }

    @Test
    fun playbackController_backgroundedPausesPlaybackWithoutAutoResume() {
        val exoManager = ExoPlayerManager(context)
        val animeRepo = createAnimeRepository(FakeHoshikaApi(), FakeAniScrollApi())
        val playbackRepo = PlaybackRepository(
            aniScrollApi = FakeAniScrollApi(),
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )
        val controller = PlaybackController(
            exoPlayerManager = exoManager,
            animeRepository = animeRepo,
            playbackRepository = playbackRepo,
            preferencesRepository = UserPreferencesRepository(context)
        )

        val player = exoManager.getOrCreatePlayer()
        player.playWhenReady = true
        controller.onAppBackgrounded()

        assertFalse(player.playWhenReady)
        assertFalse(controller.uiState.value.isPlaying)
        exoManager.releasePlayer()
    }

    @Test
    fun playerControlsOverlay_rendersCompactControlsAndThinTimeline() {
        val progressFlow = MutableStateFlow(
            PlaybackProgressState(
                currentPositionMs = 125_000L,
                bufferedPositionMs = 300_000L,
                durationMs = 1_440_000L
            )
        )

        composeTestRule.setContent {
            PlayerControlsOverlay(
                animeTitle = "Attack on Titan",
                episodeNumber = 1,
                episodeTitle = "To You, in 2000 Years",
                audioLanguage = "sub",
                selectedQualityLabel = "1080p",
                playbackSpeed = 1.0f,
                isPlaying = true,
                hasPreviousEpisode = false,
                hasNextEpisode = true,
                progressStateFlow = progressFlow,
                activePanel = PlayerSettingsPanel.NONE,
                canInteract = true,
                lastFocusTarget = PlayerFocusTarget.PLAY_PAUSE,
                onFocusTargetChanged = {},
                onUserActivity = {},
                onBackClick = {},
                onPreviousEpisode = {},
                onSeekRelative = {},
                onTogglePlayPause = {},
                onNextEpisode = {},
                onToggleAudioLanguage = {},
                onOpenSubtitlesPanel = {},
                onOpenSpeedPanel = {},
                onOpenQualityPanel = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_back_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_timeline_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_audio_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_subtitles_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_speed_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_quality_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Attack on Titan").assertIsDisplayed()
    }

    @Test
    fun playbackRepository_routesMoviesExclusivelyToZokoAnime() = runTest {
        val fakeAniScroll = FakeAniScrollApi(
            zokoStreamBody = """
                {
                  "source": "zokoanime",
                  "anilistId": 21519,
                  "episode": 1,
                  "type": "sub",
                  "m3u8": "https://cdn.example.com/your-name-master.m3u8",
                  "qualities": [
                    { "quality": "1080p", "url": "https://cdn.example.com/your-name-1080p.m3u8" }
                  ],
                  "subtitles": [
                    { "lang": "en", "label": "English", "url": "https://cdn.example.com/your-name-en.vtt", "default": true }
                  ]
                }
            """.trimIndent()
        )
        val animeRepo = createAnimeRepository(FakeHoshikaApi(), fakeAniScroll)
        val playbackRepo = PlaybackRepository(
            aniScrollApi = fakeAniScroll,
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )

        val movieAnime = `in`.aniscroll.tv.domain.model.Anime(
            hoshikaId = 50001L,
            anilistId = 21519L,
            title = "Kimi no Na wa.",
            englishTitle = "Your Name.",
            format = "MOVIE",
            totalEpisodes = 1
        )

        val subResult = playbackRepo.resolveEpisodeStream(movieAnime, episodeNumber = 1, language = "sub")
        assertTrue(subResult is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
        val subSuccess = subResult as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success
        assertEquals(21519L, subSuccess.streamData.anilistId)
        assertEquals(1, subSuccess.streamData.episodeNumber)
        assertEquals("sub", subSuccess.streamData.language)
        assertEquals(1, fakeAniScroll.zokoCalls.size)
        assertEquals(0, fakeAniScroll.megaPlayCalls.size)
        assertEquals(Triple(21519L, 1, "sub"), fakeAniScroll.zokoCalls.first())

        // Verify DUB movie also routes exclusively to ZokoAnime
        val dubResult = playbackRepo.resolveEpisodeStream(movieAnime, episodeNumber = 1, language = "dub")
        assertTrue(dubResult is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
        assertEquals(2, fakeAniScroll.zokoCalls.size)
        assertEquals(0, fakeAniScroll.megaPlayCalls.size)
        assertEquals(Triple(21519L, 1, "dub"), fakeAniScroll.zokoCalls.last())
    }

    @Test
    fun playbackRepository_routesTvSeriesSpecialsAndEpisodicAnimeExclusivelyToMegaPlay() = runTest {
        val fakeAniScroll = FakeAniScrollApi(
            megaPlayStreamBody = """
                {
                  "source": "megaplay",
                  "anilistId": 16498,
                  "episode": 5,
                  "type": "sub",
                  "m3u8": "https://cdn.example.com/aot-ep5-master.m3u8",
                  "qualities": [
                    { "quality": "1080p", "url": "https://cdn.example.com/aot-ep5-1080p.m3u8" },
                    { "quality": "720p", "url": "https://cdn.example.com/aot-ep5-720p.m3u8" }
                  ],
                  "subtitles": [
                    { "lang": "en", "label": "English", "url": "https://cdn.example.com/aot-ep5-en.vtt", "default": true }
                  ]
                }
            """.trimIndent()
        )
        val animeRepo = createAnimeRepository(FakeHoshikaApi(), fakeAniScroll)
        val playbackRepo = PlaybackRepository(
            aniScrollApi = fakeAniScroll,
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )

        val episodicFormats = listOf("TV", "TV_SHORT", "SPECIAL", "OVA", "ONA")
        episodicFormats.forEachIndexed { idx, format ->
            val tvAnime = `in`.aniscroll.tv.domain.model.Anime(
                hoshikaId = 60000L + idx,
                anilistId = 16498L + idx,
                title = "Attack on Titan ($format)",
                englishTitle = "Attack on Titan ($format)",
                format = format,
                totalEpisodes = 25
            )
            val lang = if (idx % 2 == 0) "sub" else "dub"
            val ep = idx + 1
            val res = playbackRepo.resolveEpisodeStream(tvAnime, episodeNumber = ep, language = lang)
            assertTrue("Expected success for format=$format", res is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
            assertEquals(Triple(16498L + idx, ep, lang), fakeAniScroll.megaPlayCalls.last())
        }

        assertEquals(episodicFormats.size, fakeAniScroll.megaPlayCalls.size)
        assertEquals(0, fakeAniScroll.zokoCalls.size)
    }

    @Test
    fun playbackRepository_determinesFormatFromAniListMetadataWhenAnimeFormatIsNull() = runTest {
        val fakeAniScroll = FakeAniScrollApi(
            searchBody = """
                {
                  "results": [
                    {
                      "id": 101922,
                      "title": "Demon Slayer: Kimetsu no Yaiba - The Movie: Mugen Train",
                      "format": "MOVIE",
                      "episodes": 1
                    }
                  ]
                }
            """.trimIndent(),
            zokoStreamBody = """
                {
                  "source": "zokoanime",
                  "m3u8": "https://cdn.example.com/mugen-train.m3u8"
                }
            """.trimIndent()
        )
        val fakeHoshika = FakeHoshikaApi(
            detailsJson = """
                {
                  "shortId": 77001,
                  "titleRomaji": "Kimetsu no Yaiba: Mugen Ressha-hen",
                  "titleEnglish": "Demon Slayer: Kimetsu no Yaiba - The Movie: Mugen Train"
                }
            """.trimIndent()
        )
        val animeRepo = createAnimeRepository(fakeHoshika, fakeAniScroll)
        val playbackRepo = PlaybackRepository(
            aniScrollApi = fakeAniScroll,
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )

        val movieWithoutLocalFormat = `in`.aniscroll.tv.domain.model.Anime(
            hoshikaId = 77001L,
            title = "Kimetsu no Yaiba: Mugen Ressha-hen",
            englishTitle = "Demon Slayer: Kimetsu no Yaiba - The Movie: Mugen Train",
            format = null
        )

        val result = playbackRepo.resolveEpisodeStream(movieWithoutLocalFormat, episodeNumber = 1, language = "sub")
        assertTrue(result is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
        assertEquals(1, fakeAniScroll.zokoCalls.size)
        assertEquals(0, fakeAniScroll.megaPlayCalls.size)
        assertEquals(Triple(101922L, 1, "sub"), fakeAniScroll.zokoCalls.first())
    }

    private fun createAnimeRepository(
        hoshikaApi: HoshikaApi,
        aniScrollApi: AniScrollApi
    ): AnimeRepository {
        val logoRepo = LogoRepository(
            dao = dao,
            tmdbLogoProvider = TmdbLogoProvider(DummyTmdbApi(), hasValidCredentials = { false }),
            fanartLogoProvider = FanartLogoProvider(DummyFanartApi(), hasValidCredentials = { false })
        )
        return AnimeRepository(
            hoshikaApi = hoshikaApi,
            aniScrollApi = aniScrollApi,
            dao = dao,
            logoRepository = logoRepo
        )
    }

    private class FakeHoshikaApi(
        private val homeJson: String = """{"ok":true}""",
        private val searchJson: String = """{"ok":true,"anime":[]}""",
        private val catalogJson: String = """{"ok":true,"items":[]}""",
        private val detailsJson: String = """{"shortId":41391,"titleRomaji":"Naruto"}""",
        private val detailsDelayMs: Long = 0L,
        private val dynamicSearchHandler: ((String) -> String)? = null
    ) : HoshikaApi {
        var lastSearchLimit: Int? = null

        private fun jsonOk(body: String): Response<ResponseBody> =
            Response.success(body.toResponseBody("application/json".toMediaType()))

        override suspend fun getHome(locale: String) = jsonOk(homeJson)
        override suspend fun getBrowse(type: String, sort: String, locale: String) = jsonOk("""{"ok":true,"items":[]}""")
        override suspend fun getSeason(locale: String) = jsonOk("""{"ok":true,"items":[]}""")
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
        ) = jsonOk(catalogJson)

        override suspend fun search(
            query: String,
            limit: Int,
            format: String?,
            year: Int?,
            locale: String
        ): Response<ResponseBody> {
            lastSearchLimit = limit
            if (limit > 20) {
                return Response.error(400, """{"ok":false,"code":"invalid"}""".toResponseBody("application/json".toMediaType()))
            }
            val payload = dynamicSearchHandler?.invoke(query) ?: searchJson
            return jsonOk(payload)
        }

        override suspend fun getDiscover(locale: String) = jsonOk("""{"ok":true,"items":[]}""")
        override suspend fun getAnimeDetails(shortId: Long, locale: String): Response<ResponseBody> {
            if (detailsDelayMs > 0L) delay(detailsDelayMs)
            return jsonOk(detailsJson)
        }

        override suspend fun getAnimeEpisodes(shortId: Long, after: Int?, limit: Int, locale: String) =
            jsonOk("""{"ok":true,"items":[]}""")

        override suspend fun getSchedule(year: Int?, week: Int?, locale: String) = jsonOk("""{"ok":true,"items":[]}""")
        override suspend fun getGenres() = jsonOk("""{"ok":true,"genres":[]}""")
        override suspend fun getGenreTitles(slug: String, type: String, locale: String) = jsonOk("""{"ok":true,"items":[]}""")
    }

    private class FakeAniScrollApi(
        private val zokoStreamBody: String? = null,
        private val megaPlayStreamBody: String? = null,
        private val searchBody: String? = null
    ) : AniScrollApi {
        val zokoCalls = mutableListOf<Triple<Long, Int, String>>()
        val megaPlayCalls = mutableListOf<Triple<Long, Int, String>>()

        override suspend fun getZokoAnimeStreams(anilistId: Long, episode: Int, language: String): Response<ResponseBody> {
            zokoCalls.add(Triple(anilistId, episode, language))
            val body = zokoStreamBody ?: return Response.error(404, "".toResponseBody(null))
            return Response.success(body.toResponseBody("application/json".toMediaType()))
        }

        override suspend fun getMegaPlayStreams(anilistId: Long, episode: Int, language: String): Response<ResponseBody> {
            megaPlayCalls.add(Triple(anilistId, episode, language))
            val body = megaPlayStreamBody ?: return Response.error(404, "".toResponseBody(null))
            return Response.success(body.toResponseBody("application/json".toMediaType()))
        }

        override suspend fun searchAnimeIds(query: String, limit: Int): Response<ResponseBody> {
            val body = searchBody ?: return Response.error(404, "".toResponseBody(null))
            return Response.success(body.toResponseBody("application/json".toMediaType()))
        }

        override suspend fun getAniListIdFromMal(malId: Long) =
            Response.error<ResponseBody>(404, "".toResponseBody(null))

        override suspend fun getHoshikaProxy(
            path: String,
            query: String?,
            page: Int?,
            genre: String?,
            day: String?
        ): Response<ResponseBody> =
            Response.success("<!doctype html><html><body>SPA</body></html>".toResponseBody("text/html".toMediaType()))

        override suspend fun getTmdbAnimeArtwork(malId: Long?, title: String?) =
            Response.error<ResponseBody>(404, "".toResponseBody(null))
    }

    private class FakeAniSkipApi : AniSkipApi {
        override suspend fun getSkipTimes(animeId: Long, episodeNumber: Int, types: List<String>, episodeLength: Float) =
            Response.error<ResponseBody>(404, "".toResponseBody(null))
    }

    private class DummyTmdbApi : TmdbApi {
        override suspend fun searchTv(query: String, firstAirDateYear: Int?, includeAdult: Boolean) =
            Response.error<ResponseBody>(401, "".toResponseBody(null))

        override suspend fun getTvImages(tvId: Long, includeImageLanguage: String) =
            Response.error<ResponseBody>(401, "".toResponseBody(null))
    }

    private class DummyFanartApi : FanartApi {
        override suspend fun getTvImages(tmdbTvId: Long, source: String) =
            Response.error<ResponseBody>(401, "".toResponseBody(null))
    }
}
