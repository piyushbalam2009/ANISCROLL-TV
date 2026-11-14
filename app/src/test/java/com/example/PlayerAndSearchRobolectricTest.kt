package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
@Config(sdk = [36], qualifiers = "w1280dp-h720dp-land")
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
    fun searchViewModel_debouncesRapidQueries_preventsDuplicateSubmit_andClearsCleanly() = runTest {
        var searchCallCount = 0
        val fakeHoshika = FakeHoshikaApi(
            dynamicSearchHandler = {
                searchCallCount++
                """
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
            }
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val viewModel = SearchViewModel(repo)

        composeTestRule.setContent {
            `in`.aniscroll.tv.features.search.SearchScreen(
                viewModel = viewModel,
                onOpenAnimeDetails = {}
            )
        }

        // Verify custom keyboard keys are completely removed and native search input is displayed
        composeTestRule.onNodeWithTag("search_input_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_submit_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_key_A").assertDoesNotExist()
        composeTestRule.onNodeWithTag("search_key_space").assertDoesNotExist()
        composeTestRule.onNodeWithTag("search_key_backspace").assertDoesNotExist()

        viewModel.onQueryChanged("N")
        viewModel.onQueryChanged("Na")
        viewModel.onQueryChanged("Nar")
        viewModel.onQueryChanged("Naruto")
        viewModel.triggerSearchNow()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            viewModel.uiState.value.hasSearched && !viewModel.uiState.value.isLoading
        }
        composeTestRule.waitForIdle()
        assertEquals("Naruto", viewModel.uiState.value.query)
        assertEquals(1, searchCallCount)

        // Pressing Search again with the same already-succeeded query must not submit a duplicate request
        viewModel.triggerSearchNow()
        composeTestRule.waitForIdle()
        assertEquals(1, searchCallCount)
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
            Box {
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
                    onOpenSubtitlesPanel = {}
                )
                `in`.aniscroll.tv.player.SkipIntroOutroButton(
                    prompt = `in`.aniscroll.tv.player.ActiveSkipPrompt(
                        type = `in`.aniscroll.tv.player.ActiveSkipType.INTRO,
                        label = "Skip Intro  >>",
                        targetTimeMs = 90_000L
                    ),
                    onClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_back_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_seek_back").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_seek_forward").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_timeline_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_next_episode").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_audio_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_subtitles_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_aniskip_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Attack on Titan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip Intro").assertIsDisplayed()
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

    @Test
    fun mandatoryFourScenarios_tvSubDubMegaPlay_movieSubDubZokoAnime_prioritizesHlsProxyAndPreparesExoPlayer() = runTest {
        val liveMegaPlaySubJson = """
            {
              "source": "megaplay",
              "anilistId": 16498,
              "malId": 16498,
              "title": "Attack on Titan",
              "episode": 1,
              "epId": "2225",
              "type": "sub",
              "server": "MegaPlay",
              "embedUrl": "https://megaplay.buzz/",
              "m3u8": "https://s9.Stormshade84.live/stream/v2/hls/master.m3u8",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fs9.Stormshade84.live%2Fstream%2Fv2%2Fhls%2Fmaster.m3u8&ref=https%3A%2F%2Fmegaplay.buzz%2F",
              "qualities": [],
              "subtitles": [
                {
                  "lang": "English",
                  "label": "English",
                  "default": true,
                  "src": "https://mgstatics.xyz/subtitle/aot-en.vtt",
                  "url": "https://anivault-scraper-five.vercel.app/api/proxy/subtitle?url=https%3A%2F%2Fmgstatics.xyz%2Fsubtitle%2Faot-en.vtt&ref=https%3A%2F%2Fmegaplay.buzz%2F"
                },
                {
                  "lang": "Unknown",
                  "label": "Unknown",
                  "default": false,
                  "src": "https://mgstatics.xyz/thumbnails/preview/thumbnails.vtt",
                  "url": "https://anivault-scraper-five.vercel.app/api/proxy/subtitle?url=https%3A%2F%2Fmgstatics.xyz%2Fthumbnails%2Fpreview%2Fthumbnails.vtt&ref=https%3A%2F%2Fmegaplay.buzz%2F"
                }
              ],
              "skip": {
                "intro": { "start": 95, "end": 185 },
                "outro": { "start": 1351, "end": 1440 }
              },
              "iframeOnly": false
            }
        """.trimIndent()

        val liveMegaPlayDubJson = liveMegaPlaySubJson
            .replace("\"type\": \"sub\"", "\"type\": \"dub\"")
            .replace("master.m3u8", "dub-master.m3u8")

        val liveZokoMovieSubJson = """
            {
              "source": "zokoanime",
              "anilistId": 21519,
              "malId": 32281,
              "title": "Your Name.",
              "episode": 1,
              "type": "sub",
              "videoId": 168705,
              "embedUrl": "https://zokoanime.video/stream/mal/32281/1/sub",
              "m3u8": "https://hls.dramahot.top/p/ff/29/master.m3u8",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fhls.dramahot.top%2Fp%2Fff%2F29%2Fmaster.m3u8&ref=https%3A%2F%2Fzokoanime.video%2Fstream%2Fmal%2F32281%2F1%2Fsub",
              "qualities": [
                {
                  "quality": "1080p",
                  "resolution": "1920x1080",
                  "width": 1920,
                  "height": 1080,
                  "url": "https://hls.dramahot.top/p/ff/29/index-f1-v1-a1.m3u8",
                  "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fhls.dramahot.top%2Fp%2Fff%2F29%2Findex-f1-v1-a1.m3u8&ref=https%3A%2F%2Fzokoanime.video%2Fstream%2Fmal%2F32281%2F1%2Fsub"
                }
              ],
              "subtitles": [
                {
                  "lang": "en",
                  "label": "English",
                  "default": true,
                  "src": "https://hls.dramahot.top/subs/mal/32281/1.en.vtt",
                  "url": "https://anivault-scraper-five.vercel.app/api/proxy/subtitle?url=https%3A%2F%2Fhls.dramahot.top%2Fsubs%2Fmal%2F32281%2F1.en.vtt&ref=https%3A%2F%2Fzokoanime.video%2Fstream%2Fmal%2F32281%2F1%2Fsub"
                }
              ],
              "skip": { "intro": null, "outro": null },
              "iframeOnly": false
            }
        """.trimIndent()

        val liveZokoMovieDubJson = liveZokoMovieSubJson
            .replace("\"type\": \"sub\"", "\"type\": \"dub\"")
            .replace("168705", "168710")
            .replace("/1/sub", "/1/dub")
            .replace("%2F1%2Fsub", "%2F1%2Fdub")

        val fakeAniScroll = object : AniScrollApi {
            val watchCalls = mutableListOf<String>()

            override suspend fun getWatchStreams(
                source: String,
                anilistId: Long,
                episode: Int,
                language: String
            ): Response<ResponseBody> {
                val route = "/api/watch/$source/$anilistId/$episode/$language"
                watchCalls.add(route)
                val body = when (route) {
                    "/api/watch/megaplay/16498/1/sub" -> liveMegaPlaySubJson
                    "/api/watch/megaplay/16498/1/dub" -> liveMegaPlayDubJson
                    "/api/watch/zokoanime/21519/1/sub" -> liveZokoMovieSubJson
                    "/api/watch/zokoanime/21519/1/dub" -> liveZokoMovieDubJson
                    else -> return Response.error(404, "".toResponseBody(null))
                }
                return Response.success(body.toResponseBody("application/json".toMediaType()))
            }

            override suspend fun getZokoAnimeStreams(anilistId: Long, episode: Int, language: String) =
                getWatchStreams("zokoanime", anilistId, episode, language)

            override suspend fun getMegaPlayStreams(anilistId: Long, episode: Int, language: String) =
                getWatchStreams("megaplay", anilistId, episode, language)

            override suspend fun searchAnimeIds(query: String, limit: Int) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))

            override suspend fun getAniListIdFromMal(malId: Long) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))

            override suspend fun getHoshikaProxy(
                path: String,
                query: String?,
                page: Int?,
                genre: String?,
                day: String?
            ) = Response.error<ResponseBody>(404, "".toResponseBody(null))

            override suspend fun getTmdbAnimeArtwork(malId: Long?, title: String?) =
                Response.error<ResponseBody>(404, "".toResponseBody(null))
        }

        val animeRepo = createAnimeRepository(FakeHoshikaApi(), fakeAniScroll)
        val playbackRepo = PlaybackRepository(
            aniScrollApi = fakeAniScroll,
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )

        val tvSeries = `in`.aniscroll.tv.domain.model.Anime(
            hoshikaId = 33374L,
            anilistId = 16498L,
            title = "Shingeki no Kyojin",
            englishTitle = "Attack on Titan",
            format = "TV",
            totalEpisodes = 25
        )
        val movieAnime = `in`.aniscroll.tv.domain.model.Anime(
            hoshikaId = 50001L,
            anilistId = 21519L,
            title = "Kimi no Na wa.",
            englishTitle = "Your Name.",
            format = "MOVIE",
            totalEpisodes = 1
        )

        val exoManager = ExoPlayerManager(context)
        try {
            // 1. TV series, episode 1, sub -> MegaPlay
            val tvSub = playbackRepo.resolveEpisodeStream(tvSeries, episodeNumber = 1, language = "sub")
            assertTrue(tvSub is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
            val tvSubData = (tvSub as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success).streamData
            assertEquals("Auto", tvSubData.sources.first().quality)
            assertTrue(tvSubData.sources.first().url.startsWith("https://anivault-scraper-five.vercel.app/api/proxy/hls"))
            assertFalse(tvSubData.sources.any { it.url == "https://megaplay.buzz/" })
            // Verify thumbnails.vtt was filtered out of subtitles
            assertEquals(1, tvSubData.subtitles.size)
            assertEquals("English", tvSubData.subtitles.first().language)
            exoManager.loadStream(
                streamData = tvSubData,
                selectedSourceUrl = tvSubData.sources.first().url,
                startPositionMs = 0L,
                playWhenReady = true,
                playbackSpeed = 1.0f
            )
            assertNotNull(exoManager.peekPlayer()?.currentMediaItem)

            // 2. TV series, episode 1, dub -> MegaPlay
            val tvDub = playbackRepo.resolveEpisodeStream(tvSeries, episodeNumber = 1, language = "dub")
            assertTrue(tvDub is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
            val tvDubData = (tvDub as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success).streamData
            assertTrue(tvDubData.sources.first().url.contains("dub-master.m3u8"))

            // 3. Anime movie, sub -> ZokoAnime (even if caller passed episode 5, movie normalizes to episode 1)
            val movieSub = playbackRepo.resolveEpisodeStream(movieAnime, episodeNumber = 5, language = "sub")
            assertTrue(movieSub is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
            val movieSubData = (movieSub as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success).streamData
            assertEquals(1, movieSubData.episodeNumber)
            assertEquals("Auto", movieSubData.sources.first().quality)
            assertTrue(movieSubData.sources.first().url.startsWith("https://anivault-scraper-five.vercel.app/api/proxy/hls"))
            assertTrue(movieSubData.sources.any { it.quality == "1080p" })
            exoManager.loadStream(
                streamData = movieSubData,
                selectedSourceUrl = movieSubData.sources.first().url,
                startPositionMs = 0L,
                playWhenReady = true,
                playbackSpeed = 1.0f
            )
            assertNotNull(exoManager.peekPlayer()?.currentMediaItem)

            // 4. Anime movie, dub -> ZokoAnime
            val movieDub = playbackRepo.resolveEpisodeStream(movieAnime, episodeNumber = 1, language = "dub")
            assertTrue(movieDub is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
            val movieDubData = (movieDub as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success).streamData
            assertTrue(movieDubData.sources.first().url.contains("%2F1%2Fdub"))

            assertEquals(
                listOf(
                    "/api/watch/megaplay/16498/1/sub",
                    "/api/watch/megaplay/16498/1/dub",
                    "/api/watch/zokoanime/21519/1/sub",
                    "/api/watch/zokoanime/21519/1/dub"
                ),
                fakeAniScroll.watchCalls
            )
        } finally {
            exoManager.releasePlayer()
        }
    }

    @Test
    fun playbackRepository_neverCrossCallsOtherProviderWhenPrimaryProviderErrors() = runTest {
        val fakeAniScroll = FakeAniScrollApi(
            zokoStreamBody = null, // Returns 404
            megaPlayStreamBody = null // Returns 404
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
            title = "Your Name.",
            format = "MOVIE"
        )
        val movieErr = playbackRepo.resolveEpisodeStream(movieAnime, episodeNumber = 1, language = "dub")
        assertTrue(movieErr is `in`.aniscroll.tv.data.repository.StreamLoadResult.Error)
        assertEquals(1, fakeAniScroll.zokoCalls.size)
        assertEquals(0, fakeAniScroll.megaPlayCalls.size)

        val tvAnime = `in`.aniscroll.tv.domain.model.Anime(
            hoshikaId = 33374L,
            anilistId = 16498L,
            title = "Attack on Titan",
            format = "TV"
        )
        val tvErr = playbackRepo.resolveEpisodeStream(tvAnime, episodeNumber = 1, language = "sub")
        assertTrue(tvErr is `in`.aniscroll.tv.data.repository.StreamLoadResult.Error)
        assertEquals(1, fakeAniScroll.zokoCalls.size)
        assertEquals(1, fakeAniScroll.megaPlayCalls.size)
    }

    @Test
    fun streamingPerformanceOptimizations_shortLivedCache_bufferConfig_andDiagnostics() = runTest {
        assertEquals(15_000, ExoPlayerManager.MIN_BUFFER_MS)
        assertEquals(50_000, ExoPlayerManager.MAX_BUFFER_MS)
        assertEquals(1_000, ExoPlayerManager.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(2_000, ExoPlayerManager.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        assertEquals("ANISCROLL_PERFORMANCE", `in`.aniscroll.tv.player.StreamingDiagnostics.TAG)

        val streamPayload = """
            {
              "source": "megaplay",
              "anilistId": 16498,
              "malId": 16498,
              "title": "Attack on Titan",
              "episode": 2,
              "type": "sub",
              "m3u8": "https://s4.Owocdn.top/stream/v2/hls/aot-ep2/master.m3u8",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fs4.Owocdn.top%2Fstream%2Fv2%2Fhls%2Faot-ep2%2Fmaster.m3u8&ref=https%3A%2F%2Fmegaplay.buzz%2F",
              "qualities": [],
              "subtitles": []
            }
        """.trimIndent()

        val fakeAniScroll = FakeAniScrollApi(megaPlayStreamBody = streamPayload)
        val animeRepo = createAnimeRepository(FakeHoshikaApi(), fakeAniScroll)
        val playbackRepo = PlaybackRepository(
            aniScrollApi = fakeAniScroll,
            aniSkipApi = FakeAniSkipApi(),
            dao = dao,
            idConverter = animeRepo.idConverter
        )

        val tvAnime = `in`.aniscroll.tv.domain.model.Anime(
            hoshikaId = 33374L,
            anilistId = 16498L,
            title = "Attack on Titan",
            format = "TV"
        )

        val firstLoad = playbackRepo.resolveEpisodeStream(tvAnime, episodeNumber = 2, language = "sub")
        assertTrue(firstLoad is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
        val firstSuccess = firstLoad as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success
        assertFalse(firstSuccess.fromShortLivedCache)
        assertEquals(1, fakeAniScroll.megaPlayCalls.size)

        // Immediate repeat call hits short-lived cache without making a duplicate API call
        val secondLoad = playbackRepo.resolveEpisodeStream(tvAnime, episodeNumber = 2, language = "sub")
        assertTrue(secondLoad is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
        val secondSuccess = secondLoad as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success
        assertTrue(secondSuccess.fromShortLivedCache)
        assertEquals(0L, secondSuccess.apiRequestDurationMs)
        assertEquals(1, fakeAniScroll.megaPlayCalls.size)

        // Invalidating expired stream cache forces a fresh API fetch
        playbackRepo.invalidateCachedStream(16498L, 2, "sub")
        val thirdLoad = playbackRepo.resolveEpisodeStream(tvAnime, episodeNumber = 2, language = "sub")
        assertTrue(thirdLoad is `in`.aniscroll.tv.data.repository.StreamLoadResult.Success)
        assertFalse((thirdLoad as `in`.aniscroll.tv.data.repository.StreamLoadResult.Success).fromShortLivedCache)
        assertEquals(2, fakeAniScroll.megaPlayCalls.size)
    }

    @Test
    fun franchiseWatchOrder_generatesRecommendedReleaseAndChronologicalOrders_andRendersTimelineAndDetailButton() = runTest {
        val graphQlPayload = """
            {
              "data": {
                "Page": {
                  "media": [
                    {
                      "id": 113415,
                      "idMal": 40748,
                      "type": "ANIME",
                      "format": "TV",
                      "status": "FINISHED",
                      "episodes": 24,
                      "seasonYear": 2020,
                      "startDate": { "year": 2020, "month": 10, "day": 3 },
                      "description": "Yuji Itadori swallows a cursed talisman.",
                      "title": {
                        "romaji": "Jujutsu Kaisen",
                        "english": "JUJUTSU KAISEN",
                        "native": "呪術廻戦"
                      },
                      "coverImage": { "extraLarge": "https://s4.anilist.co/jk1.jpg", "large": "https://s4.anilist.co/jk1.jpg" },
                      "bannerImage": "https://s4.anilist.co/jk1-banner.jpg",
                      "relations": {
                        "edges": [
                          {
                            "relationType": "SOURCE",
                            "node": {
                              "id": 101517,
                              "type": "MANGA",
                              "format": "MANGA",
                              "title": { "romaji": "Jujutsu Kaisen", "english": "Jujutsu Kaisen" }
                            }
                          },
                          {
                            "relationType": "PREQUEL",
                            "node": {
                              "id": 131573,
                              "idMal": 48561,
                              "type": "ANIME",
                              "format": "MOVIE",
                              "status": "FINISHED",
                              "episodes": 1,
                              "seasonYear": 2021,
                              "startDate": { "year": 2021, "month": 12, "day": 24 },
                              "title": {
                                "romaji": "Jujutsu Kaisen 0 Movie",
                                "english": "JUJUTSU KAISEN 0",
                                "native": "劇場版 呪術廻戦 0"
                              },
                              "coverImage": { "large": "https://s4.anilist.co/jk0.jpg" }
                            }
                          },
                          {
                            "relationType": "SEQUEL",
                            "node": {
                              "id": 145064,
                              "idMal": 51009,
                              "type": "ANIME",
                              "format": "TV",
                              "status": "FINISHED",
                              "episodes": 23,
                              "seasonYear": 2023,
                              "startDate": { "year": 2023, "month": 7, "day": 6 },
                              "title": {
                                "romaji": "Jujutsu Kaisen 2nd Season",
                                "english": "JUJUTSU KAISEN Season 2",
                                "native": "呪術廻戦 第2期"
                              },
                              "coverImage": { "large": "https://s4.anilist.co/jk2.jpg" }
                            }
                          },
                          {
                            "relationType": "SUMMARY",
                            "node": {
                              "id": 180001,
                              "type": "ANIME",
                              "format": "SPECIAL",
                              "status": "FINISHED",
                              "episodes": 1,
                              "seasonYear": 2021,
                              "startDate": { "year": 2021, "month": 1, "day": 9 },
                              "title": {
                                "romaji": "Jujutsu Kaisen Recap",
                                "english": "JUJUTSU KAISEN Recap Special"
                              },
                              "coverImage": { "large": "https://s4.anilist.co/jk-recap.jpg" }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val fakeGraphQl = object : `in`.aniscroll.tv.data.remote.AniListGraphQlApi {
            override suspend fun query(body: okhttp3.RequestBody): Response<ResponseBody> {
                return Response.success(graphQlPayload.toResponseBody("application/json".toMediaType()))
            }
        }

        val fakeHoshika = FakeHoshikaApi(
            detailsJson = """
                {
                  "ok": true,
                  "anime": {
                    "shortId": 77001,
                    "anilistId": 113415,
                    "titleRomaji": "Jujutsu Kaisen",
                    "titleEnglish": "JUJUTSU KAISEN",
                    "format": "TV",
                    "seasonYear": 2020,
                    "episodes": 24,
                    "synopsis": "Yuji Itadori joins Tokyo Jujutsu High."
                  }
                }
            """.trimIndent()
        )

        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi(), fakeGraphQl)
        val woResult = repo.getFranchiseWatchOrder(77001L)
        assertTrue(woResult is ResourceResult.Success)
        val watchOrder = (woResult as ResourceResult.Success).data

        // 4 anime entries (MANGA source is excluded from watchable timeline but noted in sourceMaterialNote)
        assertEquals(4, watchOrder.totalEntries)
        assertEquals("Adapted from Manga: Jujutsu Kaisen", watchOrder.sourceMaterialNote)

        // Recommended Order:
        // 1. JUJUTSU KAISEN (2020 Main Series) — NOT spoiled by 2021 Prequel Movie!
        // 2. JUJUTSU KAISEN 0 (2021 Prequel Movie placed after Season 1)
        // 3. JUJUTSU KAISEN Season 2 (2023 Sequel)
        // 4. JUJUTSU KAISEN Recap Special (Optional Recap placed at end)
        assertEquals(
            listOf(113415L, 131573L, 145064L, 180001L),
            watchOrder.recommendedOrder.map { it.anilistId }
        )
        assertFalse(watchOrder.recommendedOrder[0].isOptional)
        assertTrue(watchOrder.recommendedOrder[1].relationshipExplanation.contains("preserve key story reveals"))
        assertTrue(watchOrder.recommendedOrder[3].isOptional)

        // Chronological Order places the Prequel (131573) at #1 before Season 1 (113415)
        assertEquals(131573L, watchOrder.chronologicalOrder.first().anilistId)

        // Release Order sorts strictly by startDate (2020-10 -> 2021-01 Recap -> 2021-12 Movie -> 2023-07 Season 2)
        assertEquals(
            listOf(113415L, 180001L, 131573L, 145064L),
            watchOrder.releaseOrder.map { it.anilistId }
        )

        // Verify DetailsScreen Watch Order button & WatchOrderScreen UI
        val detailsViewModel = `in`.aniscroll.tv.features.details.DetailsViewModel(repo)
        val watchOrderViewModel = `in`.aniscroll.tv.features.watchorder.WatchOrderViewModel(repo)
        val showWatchOrder = androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            if (!showWatchOrder.value) {
                `in`.aniscroll.tv.features.details.DetailsScreen(
                    hoshikaId = 77001L,
                    viewModel = detailsViewModel,
                    onPlayEpisode = { _, _, _ -> },
                    onOpenEpisodesScreen = {},
                    onOpenWatchOrder = { showWatchOrder.value = true },
                    onBack = {}
                )
            } else {
                `in`.aniscroll.tv.features.watchorder.WatchOrderScreen(
                    hoshikaId = 77001L,
                    viewModel = watchOrderViewModel,
                    onOpenAnimeDetails = {},
                    onBack = { showWatchOrder.value = false }
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            !detailsViewModel.uiState.value.isLoading
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("details_watch_order_button").assertIsDisplayed()

        // Switch to WatchOrderScreen
        showWatchOrder.value = true
        watchOrderViewModel.loadWatchOrder(77001L)
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            !watchOrderViewModel.uiState.value.isLoading
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("watch_order_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_order_franchise_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_order_total_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_order_mode_RECOMMENDED").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_order_item_1").assertIsDisplayed()
    }

    @Test
    fun fireTvUniversalCompatibility_detectsRuntimeCapabilities_tiersBuffers_andCapsVideoQualityToHardwareMax() {
        val profile = `in`.aniscroll.tv.core.device.TvDeviceCapabilities.getDeviceProfile(context)
        assertNotNull(profile)
        assertTrue(profile.effectiveMaxPlaybackHeight >= 720)
        assertTrue(profile.maxHardwareVideoWidth >= 1920)

        // 1. Fire TV Stick HD (COMPACT_HD, ~1GB RAM): conservative memory-safe buffers & 1080p cap
        val compactHdLowRamBuffer = `in`.aniscroll.tv.core.device.TvDeviceCapabilities.buildBufferProfileForTier(
            tier = `in`.aniscroll.tv.core.device.TvPerformanceTier.COMPACT_HD,
            isLowRam = true
        )
        assertEquals(12_000, compactHdLowRamBuffer.minBufferMs)
        assertEquals(28_000, compactHdLowRamBuffer.maxBufferMs)
        assertEquals(8_000, compactHdLowRamBuffer.backBufferDurationMs)
        assertEquals(20 * 1024 * 1024, compactHdLowRamBuffer.targetBufferBytes)

        // 2. Fire TV Stick 4K (UHD_4K, ~1.5-2GB RAM): 4K playback with balanced 45s max buffer
        val uhd4kBuffer = `in`.aniscroll.tv.core.device.TvDeviceCapabilities.buildBufferProfileForTier(
            tier = `in`.aniscroll.tv.core.device.TvPerformanceTier.UHD_4K,
            isLowRam = false
        )
        assertEquals(15_000, uhd4kBuffer.minBufferMs)
        assertEquals(45_000, uhd4kBuffer.maxBufferMs)
        assertEquals(15_000, uhd4kBuffer.backBufferDurationMs)
        assertEquals(44 * 1024 * 1024, uhd4kBuffer.targetBufferBytes)

        // 3. Fire TV Stick 4K Max (HIGH_PERF_4K_MAX, 2GB+ RAM & AV1): full 50s max buffer
        val max4kBuffer = `in`.aniscroll.tv.core.device.TvDeviceCapabilities.buildBufferProfileForTier(
            tier = `in`.aniscroll.tv.core.device.TvPerformanceTier.HIGH_PERF_4K_MAX,
            isLowRam = false
        )
        assertEquals(15_000, max4kBuffer.minBufferMs)
        assertEquals(50_000, max4kBuffer.maxBufferMs)
        assertEquals(15_000, max4kBuffer.backBufferDurationMs)
        assertEquals(60 * 1024 * 1024, max4kBuffer.targetBufferBytes)

        // 4. Verify QualityManager filters out 4K (2160p/1440p) streams on 1080p Fire TV Stick HD,
        // while keeping 2160p available on 4K / 4K Max devices.
        val qualityManager = `in`.aniscroll.tv.player.QualityManager()
        val sampleSources = listOf(
            `in`.aniscroll.tv.domain.model.StreamQualitySource(quality = "Auto", url = "https://cdn.example.com/master.m3u8"),
            `in`.aniscroll.tv.domain.model.StreamQualitySource(quality = "2160p", url = "https://cdn.example.com/2160p.m3u8"),
            `in`.aniscroll.tv.domain.model.StreamQualitySource(quality = "1080p", url = "https://cdn.example.com/1080p.m3u8"),
            `in`.aniscroll.tv.domain.model.StreamQualitySource(quality = "720p", url = "https://cdn.example.com/720p.m3u8")
        )

        val hdStickQualities = qualityManager.extractAvailableQualities(
            sources = sampleSources,
            tracks = null,
            maxSupportedHeight = 1080
        )
        assertFalse("Fire TV Stick HD must not expose 2160p above hardware limit", hdStickQualities.any { it.label == "2160p" })
        assertTrue(hdStickQualities.any { it.label == "1080p" })
        assertTrue(hdStickQualities.any { it.label == "720p" })

        val uhd4kQualities = qualityManager.extractAvailableQualities(
            sources = sampleSources,
            tracks = null,
            maxSupportedHeight = 2160
        )
        assertTrue("Fire TV Stick 4K / 4K Max must expose 2160p", uhd4kQualities.any { it.label == "2160p" })
        assertTrue(uhd4kQualities.any { it.label == "1080p" })
    }

    @Test
    fun launchScreenAndContentLoading_rendersOfficialLogo_initializesAsync_andSupportsConsistentLoadingStates() = runTest {
        val repo = createAnimeRepository(FakeHoshikaApi(), FakeAniScrollApi())
        val prefs = UserPreferencesRepository(context)
        val homeVm = HomeViewModel(repo)
        val launchVm = `in`.aniscroll.tv.features.launch.LaunchViewModel(
            preferencesRepository = prefs,
            animeRepository = repo,
            homeViewModel = homeVm,
            minCinematicDisplayMs = 0L,
            maxStartupWaitMs = 100L
        )

        val showContentLoading = androidx.compose.runtime.mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        try {
            composeTestRule.setContent {
                if (!showContentLoading.value) {
                    `in`.aniscroll.tv.features.launch.AniScrollLaunchScreen()
                } else {
                    `in`.aniscroll.tv.core.designsystem.TvContentLoadingScreen(
                        title = `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy.FETCHING_DETAILS,
                        subtitle = "Loading anime details..."
                    )
                }
            }

            // Advance past the 60ms entrance delay and 680ms fade/scale animation
            composeTestRule.mainClock.advanceTimeBy(1000L)
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            composeTestRule.onNodeWithTag("aniscroll_launch_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("launch_monochrome_artwork").assertIsDisplayed()
            composeTestRule.onNodeWithTag("launch_logo_image").assertIsDisplayed()
            composeTestRule.onNodeWithTag("launch_loading_indicator").assertIsDisplayed()
            composeTestRule.onNodeWithTag("launch_status_text").assertIsDisplayed()
            composeTestRule.onNodeWithText("INITIALIZING ANISCROLL").assertIsDisplayed()

            // Verify LaunchViewModel completes startup initialization asynchronously without hanging
            val startWait = System.currentTimeMillis()
            while (!launchVm.uiState.value.isReadyToNavigate && System.currentTimeMillis() - startWait < 5000L) {
                org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                Thread.sleep(20L)
            }
            assertFalse(launchVm.uiState.value.isInitializing)
            assertTrue(launchVm.uiState.value.isReadyToNavigate)

            // Switch to reusable Content Loading Screen and verify official logo, spinner, and uppercase label
            showContentLoading.value = true
            composeTestRule.mainClock.advanceTimeBy(300L)
            composeTestRule.onNodeWithTag("tv_content_loading_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("content_loading_logo").assertIsDisplayed()
            composeTestRule.onNodeWithTag("content_loading_spinner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("content_loading_title").assertIsDisplayed()
            composeTestRule.onNodeWithText("FETCHING DETAILS").assertIsDisplayed()
        } finally {
            composeTestRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun detailsScreen_keepsBannerAndPosterVisibleAtTopWhenPlayButtonReceivesInitialFocus_andRestoresScrollOnReturn() = runTest {
        val longDescription = buildString {
            repeat(8) { idx ->
                append("Sentence ${idx + 1}: Yuji Itadori joins a secret organization of Jujutsu Sorcerers in order to kill a powerful Curse named Ryomen Sukuna, of whom Yuji becomes the host. ")
            }
        }
        val fakeHoshika = object : HoshikaApi by FakeHoshikaApi(
            detailsJson = """
                {
                  "ok": true,
                  "anime": {
                    "shortId": 77001,
                    "anilistId": 113415,
                    "titleRomaji": "Jujutsu Kaisen",
                    "titleEnglish": "JUJUTSU KAISEN",
                    "titleNative": "呪術廻戦",
                    "format": "TV",
                    "status": "FINISHED",
                    "seasonYear": 2020,
                    "episodes": 24,
                    "posterUrl": "https://s4.anilist.co/jk1-poster.jpg",
                    "bannerUrl": "https://s4.anilist.co/jk1-banner.jpg",
                    "genres": ["Action", "Drama", "Supernatural"],
                    "synopsis": "$longDescription"
                  }
                }
            """.trimIndent()
        ) {
            override suspend fun getAnimeEpisodes(shortId: Long, after: Int?, limit: Int, locale: String): Response<ResponseBody> {
                val items = (1..12).joinToString(",") { ep ->
                    """{"number":$ep,"title":"Episode $ep","duration":1440}"""
                }
                return Response.success("""{"ok":true,"items":[$items]}""".toResponseBody("application/json".toMediaType()))
            }
        }

        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val detailsViewModel = `in`.aniscroll.tv.features.details.DetailsViewModel(repo)

        // 1. Fresh open always starts at top (0, 0)
        assertEquals(0 to 0, detailsViewModel.consumeInitialScrollPosition(77001L))

        composeTestRule.setContent {
            `in`.aniscroll.tv.features.details.DetailsScreen(
                hoshikaId = 77001L,
                viewModel = detailsViewModel,
                onPlayEpisode = { _, _, _ -> },
                onOpenEpisodesScreen = {},
                onOpenWatchOrder = {},
                onBack = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            !detailsViewModel.uiState.value.isLoading && !detailsViewModel.uiState.value.isEpisodesLoading
        }
        composeTestRule.waitForIdle()

        // 2. Verify banner, poster, synopsis, and action buttons are all visible in the viewport at the top
        val bannerNode = composeTestRule.onNodeWithTag("details_backdrop_banner")
        val posterNode = composeTestRule.onNodeWithTag("details_poster_image")
        val heroNode = composeTestRule.onNodeWithTag("details_hero_section")
        val synopsisNode = composeTestRule.onNodeWithTag("details_synopsis_box")
        val playButtonNode = composeTestRule.onNodeWithTag("details_play_button")
        val watchOrderNode = composeTestRule.onNodeWithTag("details_watch_order_button")
        val episodesNode = composeTestRule.onNodeWithTag("details_episodes_button")
        val watchlistNode = composeTestRule.onNodeWithTag("details_watchlist_button")

        bannerNode.assertIsDisplayed()
        posterNode.assertIsDisplayed()
        synopsisNode.assertIsDisplayed()
        playButtonNode.assertIsDisplayed()
        watchOrderNode.assertIsDisplayed()
        episodesNode.assertIsDisplayed()
        watchlistNode.assertIsDisplayed()

        // 3. Verify upper hero section / banner has NOT been scrolled upward out of the viewport
        val bannerBounds = bannerNode.fetchSemanticsNode().boundsInRoot
        val posterBounds = posterNode.fetchSemanticsNode().boundsInRoot
        val heroBounds = heroNode.fetchSemanticsNode().boundsInRoot
        val synopsisBounds = synopsisNode.fetchSemanticsNode().boundsInRoot
        val playBounds = playButtonNode.fetchSemanticsNode().boundsInRoot

        assertEquals("Backdrop banner must stay anchored at the very top (y = 0) on initial Play focus", 0f, bannerBounds.top, 1f)
        assertTrue("Poster must be displayed inside the banner area", posterBounds.top >= bannerBounds.top && posterBounds.bottom <= bannerBounds.bottom + 1f)
        assertTrue("Synopsis must be positioned below the hero section", synopsisBounds.top >= heroBounds.bottom - 1f)
        assertTrue("Play Now button must be positioned below the synopsis", playBounds.top >= synopsisBounds.bottom - 1f)

        // 4. Verify scroll position restoration when returning from a child screen vs opening a new anime
        detailsViewModel.saveScrollPositionForReturn(hoshikaId = 77001L, index = 3, offset = 48)
        assertEquals(3 to 48, detailsViewModel.consumeInitialScrollPosition(77001L))
        // Subsequent fresh open of the same anime starts at top (0, 0)
        assertEquals(0 to 0, detailsViewModel.consumeInitialScrollPosition(77001L))
        // Saving for one anime and opening a different anime starts at top (0, 0)
        detailsViewModel.saveScrollPositionForReturn(hoshikaId = 77001L, index = 4, offset = 20)
        assertEquals(0 to 0, detailsViewModel.consumeInitialScrollPosition(99999L))
    }

    @Test
    fun episodePaginationAndUi_removes200EpisodeLimit_supports200_201_350_1000Plus_handlesGapsDuplicatesAndPartialRetry() = runTest {
        val requestedCursorsByAnime = mutableMapOf<Long, MutableList<Int?>>()
        var failAnime350AtCursor200 = true

        val multiAnimeHoshika = object : HoshikaApi by FakeHoshikaApi() {
            override suspend fun getAnimeDetails(shortId: Long, locale: String): Response<ResponseBody> {
                val totalEps = when (shortId) {
                    10200L -> 200
                    10201L -> 201
                    10350L -> 350
                    11050L -> 1050
                    10999L -> 350 // Empty /episodes array fallback test
                    else -> 250
                }
                val body = """
                    {
                      "ok": true,
                      "anime": {
                        "shortId": $shortId,
                        "anilistId": $shortId,
                        "titleRomaji": "Long Anime $shortId",
                        "titleEnglish": "Long Anime $shortId",
                        "format": "TV",
                        "episodes": $totalEps
                      }
                    }
                """.trimIndent()
                return Response.success(body.toResponseBody("application/json".toMediaType()))
            }

            override suspend fun getAnimeEpisodes(shortId: Long, after: Int?, limit: Int, locale: String): Response<ResponseBody> {
                requestedCursorsByAnime.getOrPut(shortId) { mutableListOf() }.add(after)

                return when (shortId) {
                    // 1. Anime with 201 episodes paginated in 50-item pages using nextAfter (5 pages total)
                    10201L -> {
                        val start = (after ?: 0) + 1
                        val end = (start + 49).coerceAtMost(201)
                        val items = if (start <= 201) {
                            (start..end).joinToString(",") { ep ->
                                """{"id":"src_201_$ep","number":$ep,"title":"Ep $ep"}"""
                            }
                        } else ""
                        val nextAfterField = if (end < 201) "$end" else "null"
                        Response.success(
                            """{"ok":true,"items":[$items],"nextAfter":$nextAfterField}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                    }
                    // 2. Anime with 350 episodes using hasNextPage/totalPages/totalEpisodes & simulated mid-stream failure at after=200
                    10350L -> {
                        if (after == 200 && failAnime350AtCursor200) {
                            return Response.error(503, "".toResponseBody(null))
                        }
                        val start = (after ?: 0) + 1
                        val end = (start + 99).coerceAtMost(350)
                        val hasNext = end < 350
                        val pageNum = ((after ?: 0) / 100) + 1
                        val items = (start..end).joinToString(",") { ep ->
                            """{"id":"src_350_$ep","number":$ep,"title":"Episode $ep"}"""
                        }
                        Response.success(
                            """{"ok":true,"items":[$items],"pagination":{"hasNextPage":$hasNext,"currentPage":$pageNum,"totalPages":4,"totalEpisodes":350}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                    }
                    // 3. Anime with 1050 episodes (11 pages of 100 items)
                    11050L -> {
                        val start = (after ?: 0) + 1
                        val end = (start + 99).coerceAtMost(1050)
                        val nextAfterField = if (end < 1050) "$end" else "null"
                        val items = (start..end).joinToString(",") { ep ->
                            """{"id":"onepiece_$ep","number":$ep,"title":"Romance Dawn $ep"}"""
                        }
                        Response.success(
                            """{"ok":true,"items":[$items],"nextAfter":$nextAfterField}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                    }
                    // 4. Anime returning 250 episodes in a single response with missing episode #11 and duplicate episode #200
                    10250L -> {
                        val epNumbers = (1..250).filter { it != 11 }
                        val itemsList = mutableListOf<String>()
                        for (ep in epNumbers) {
                            if (ep == 200) {
                                // Duplicate entry 1 (missing title, generic id)
                                itemsList.add("""{"number":200}""")
                                // Duplicate entry 2 (rich metadata & custom source ID)
                                itemsList.add("""{"id":"custom_id_200","number":200,"title":"Milestone Episode 200"}""")
                            } else {
                                itemsList.add("""{"id":"src_250_$ep","number":$ep,"title":"Title $ep"}""")
                            }
                        }
                        Response.success(
                            """[${itemsList.joinToString(",")}]"""
                                .toResponseBody("application/json".toMediaType())
                        )
                    }
                    // 5. Anime where /episodes returns empty array and totalEpisodes metadata is 350
                    10999L -> {
                        Response.success("""{"ok":true,"items":[]}""".toResponseBody("application/json".toMediaType()))
                    }
                    else -> Response.success("""{"ok":true,"items":[]}""".toResponseBody("application/json".toMediaType()))
                }
            }
        }

        val repo = createAnimeRepository(multiAnimeHoshika, FakeAniScrollApi())

        // A. Verify 201 episodes across 5 pages of 50 items (previously stopped at 4 pages = 200 episodes)
        val res201 = repo.getAnimeEpisodes(10201L)
        assertTrue(res201 is ResourceResult.Success)
        val eps201 = (res201 as ResourceResult.Success).data
        assertEquals(201, eps201.size)
        assertEquals(1, eps201.first().number)
        assertEquals(201, eps201.last().number)
        assertEquals("src_201_201", eps201.last().id)

        // B. Verify 350 episodes with mid-stream failure at after=200 preserves first 200 episodes,
        // and retry reuses cached pages 1 & 2 (null, 100) without re-downloading them!
        val res350Partial = repo.getAnimeEpisodes(10350L)
        assertTrue(res350Partial is ResourceResult.Success)
        val partialSuccess = res350Partial as ResourceResult.Success
        assertTrue(partialSuccess.hasIncompletePagination)
        assertEquals(200, partialSuccess.data.size)
        assertEquals(listOf(null, 100, 200), requestedCursorsByAnime[10350L])

        // Now allow after=200 to succeed and retry
        failAnime350AtCursor200 = false
        requestedCursorsByAnime[10350L]?.clear()
        val res350Full = repo.getAnimeEpisodes(10350L, forceRefresh = true)
        assertTrue(res350Full is ResourceResult.Success)
        val fullSuccess = res350Full as ResourceResult.Success
        assertFalse(fullSuccess.hasIncompletePagination)
        assertEquals(350, fullSuccess.data.size)
        assertEquals(350, fullSuccess.data.last().number)
        // Pages for after=null and after=100 were served from episodePageCache, so only after=200 and after=300 hit network!
        assertEquals(listOf<Int?>(200, 300), requestedCursorsByAnime[10350L])

        // C. Verify 1050 episodes (1000+ episode anime like One Piece)
        val res1050 = repo.getAnimeEpisodes(11050L)
        assertTrue(res1050 is ResourceResult.Success)
        val eps1050 = (res1050 as ResourceResult.Success).data
        assertEquals(1050, eps1050.size)
        assertEquals(1050, eps1050.last().number)
        assertEquals("onepiece_1050", eps1050.last().id)

        // D. Verify totalEpisodes=350 fallback when /episodes returns empty array (no 200 cap)
        val resFallback350 = repo.getAnimeEpisodes(10999L)
        assertTrue(resFallback350 is ResourceResult.Success)
        assertEquals(350, (resFallback350 as ResourceResult.Success).data.size)

        // E. Verify single-response 250-episode list with missing Episode 11 and duplicate Episode 200,
        // and verify UI navigation + selecting Episode 250 plays Episode 250 (not list index 248!)
        val res250 = repo.getAnimeEpisodes(10250L)
        assertTrue(res250 is ResourceResult.Success)
        val eps250 = (res250 as ResourceResult.Success).data
        assertEquals(249, eps250.size) // 250 minus missing #11; never fabricates #11
        assertNull(eps250.find { it.number == 11 })
        val ep200 = eps250.find { it.number == 200 }
        assertNotNull(ep200)
        assertEquals("custom_id_200", ep200!!.id)
        assertEquals("Milestone Episode 200", ep200.title)

        val detailsVm = `in`.aniscroll.tv.features.details.DetailsViewModel(repo)
        var playedEpisodeNumber = -1

        composeTestRule.setContent {
            `in`.aniscroll.tv.features.episodes.EpisodesScreen(
                hoshikaId = 10250L,
                viewModel = detailsVm,
                onPlayEpisode = { _, epNum, _ -> playedEpisodeNumber = epNum },
                onBack = {}
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            detailsVm.uiState.value.episodes.size == 249
        }
        composeTestRule.waitForIdle()

        // Jump to the last range chip (EP 202 - 250) and scroll to Episode 250 (the final episode at index 248)
        composeTestRule.onNodeWithTag("episodes_range_chip_202_250").assertIsDisplayed()
        composeTestRule.onNodeWithTag("episodes_range_chip_202_250").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("episodes_lazy_column").performScrollToIndex(248)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("episode_item_250").assertIsDisplayed()
        composeTestRule.onNodeWithTag("episode_item_250").performClick()
        assertEquals("Selecting Episode 250 (at list index 248) must play episode 250", 250, playedEpisodeNumber)
    }

    private fun createAnimeRepository(
        hoshikaApi: HoshikaApi,
        aniScrollApi: AniScrollApi,
        aniListGraphQlApi: `in`.aniscroll.tv.data.remote.AniListGraphQlApi = object : `in`.aniscroll.tv.data.remote.AniListGraphQlApi {
            override suspend fun query(body: okhttp3.RequestBody): Response<ResponseBody> =
                Response.error(404, "".toResponseBody(null))
        }
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
            logoRepository = logoRepo,
            aniListGraphQlApi = aniListGraphQlApi
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
