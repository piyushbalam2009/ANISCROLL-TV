package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.aniscroll.tv.core.designsystem.rememberSafeFocusRequester
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun searchScreen_compactHeaderAndRecentSearches_maximizesResultsGridHeight() = runTest {
        val sampleResultsJson = (1..10).joinToString(",") { idx ->
            """
                {
                  "shortId": ${9000 + idx},
                  "titleRomaji": "Anime Title $idx",
                  "titleEnglish": "Anime Title $idx",
                  "format": "TV",
                  "seasonYear": 2024
                }
            """.trimIndent()
        }
        val fakeHoshika = FakeHoshikaApi(
            dynamicSearchHandler = {
                """{ "ok": true, "anime": [$sampleResultsJson] }"""
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
        composeTestRule.waitForIdle()

        // 1. No recent searches -> Recent Searches section does not exist and reserves zero space
        composeTestRule.onNodeWithTag("search_recent_section").assertDoesNotExist()
        composeTestRule.onNodeWithTag("search_screen_heading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_bar_row").assertIsDisplayed()

        // 2. Populate 8 recent search queries -> compact row caps initial chips to 6 + "+2 more" chip
        listOf("Bleach", "Jujutsu Kaisen", "Frieren", "Attack on Titan", "One Piece", "Naruto", "Solo Leveling", "Demon Slayer")
            .forEach { repo.saveSearchQuery(it) }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            viewModel.searchHistory.value.size == 8
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("search_recent_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_clear_history_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_recent_more_chip").assertIsDisplayed()
        composeTestRule.onNodeWithText("+2 more").assertIsDisplayed()

        // Verify compact vertical footprint of recent searches strip (single compact row, not stacked)
        val recentBounds = composeTestRule.onNodeWithTag("search_recent_section").getBoundsInRoot()
        val recentHeightDp = recentBounds.bottom - recentBounds.top
        assertTrue("Recent searches row should stay compact (was $recentHeightDp)", recentHeightDp <= 60.dp)

        // Clicking "+2 more" expands remaining chips and hides the overflow chip
        composeTestRule.onNodeWithTag("search_recent_more_chip").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("search_recent_more_chip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Bleach").assertIsDisplayed()

        // 3. Trigger a search via query/recent selection and verify Search Results start high on screen
        viewModel.selectHistoryQuery("Solo Leveling")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            viewModel.uiState.value.results.size == 10 && !viewModel.uiState.value.isLoading
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("search_clear_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_results_grid").assertIsDisplayed()

        val gridBounds = composeTestRule.onNodeWithTag("search_results_grid").getBoundsInRoot()
        assertTrue("Results grid should start in upper ~30% of 720dp screen (top=${gridBounds.top})", gridBounds.top <= 220.dp)
        assertTrue("Results grid should have ample height (height=${gridBounds.bottom - gridBounds.top})", (gridBounds.bottom - gridBounds.top) >= 450.dp)

        // Multiple poster cards fit side-by-side on row 1
        composeTestRule.onNodeWithTag("search_result_card_9001").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_result_card_9006").assertIsDisplayed()

        // D-pad navigation down from search bar works cleanly
        composeTestRule.onNodeWithTag("search_input_field").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeTestRule.waitForIdle()

        // 4. Clear History removes all saved queries and collapses the Recent Searches area completely
        composeTestRule.onNodeWithTag("search_clear_history_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            viewModel.searchHistory.value.isEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("search_recent_section").assertDoesNotExist()
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun homeScreenDpadNavigation_neverThrowsUninitializedFocusRequester() = runTest {
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
                      "format": "TV",
                      "seasonYear": 2024
                    },
                    {
                      "shortId": 41391,
                      "titleRomaji": "Naruto",
                      "titleEnglish": "Naruto",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/41391.webp",
                      "format": "TV",
                      "seasonYear": 2002
                    }
                  ],
                  "popular": [
                    {
                      "shortId": 21,
                      "titleRomaji": "One Piece",
                      "titleEnglish": "One Piece",
                      "coverImage": "https://cdn.hoshika.app/catalog/covers/anime/21.webp",
                      "format": "TV",
                      "seasonYear": 1999
                    }
                  ]
                }
            """.trimIndent()
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
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

        // Navigate across Hero buttons and down/up into rows without crashing
        composeTestRule.onNodeWithTag("hero_play_button").assertIsFocused()
        composeTestRule.onNodeWithTag("hero_play_button").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hero_details_button").assertIsFocused()
        composeTestRule.onNodeWithTag("hero_details_button").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.waitForIdle()

        // Scroll Home LazyColumn far down so the Hero section is detached, then press DirectionUp
        composeTestRule.onNodeWithTag("home_lazy_column").performScrollToIndex(2)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_card_21").performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        composeTestRule.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun playerControlsDpadNavigation_neverThrowsUninitializedFocusRequester() {
        val progressFlow = MutableStateFlow(
            PlaybackProgressState(
                currentPositionMs = 40_000L,
                bufferedPositionMs = 120_000L,
                durationMs = 1_440_000L
            )
        )
        val showSkipButton = androidx.compose.runtime.mutableStateOf(false)
        val showControls = androidx.compose.runtime.mutableStateOf(true)

        composeTestRule.setContent {
            val playPauseSafe = rememberSafeFocusRequester()
            val skipSafe = rememberSafeFocusRequester()
            Box {
                if (showControls.value) {
                    PlayerControlsOverlay(
                        animeTitle = "Solo Leveling",
                        episodeNumber = 1,
                        episodeTitle = "I'm Used to It",
                        audioLanguage = "sub",
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
                        playPauseSafeFocusRequester = playPauseSafe,
                        // Intentionally pass an unattached skipSafe requester while SkipIntroOutroButton is absent
                        skipButtonSafeFocusRequester = skipSafe
                    )
                }
                if (showSkipButton.value) {
                    `in`.aniscroll.tv.player.SkipIntroOutroButton(
                        prompt = `in`.aniscroll.tv.player.ActiveSkipPrompt(
                            type = `in`.aniscroll.tv.player.ActiveSkipType.INTRO,
                            label = "Skip Intro",
                            targetTimeMs = 90_000L
                        ),
                        safeFocusRequester = skipSafe,
                        // Intentionally pass playPauseSafe even if controls flip off
                        controlsSafeFocusRequester = playPauseSafe,
                        onClick = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Dispatch D-pad keys while skipSafe is unattached -> must fall back safely without IllegalStateException
        composeTestRule.onNodeWithTag("player_seek_forward").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithTag("player_timeline_bar").performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionDown)
        }
        composeTestRule.waitForIdle()

        // Flip: Skip button visible, controls hidden -> pressing DirectionLeft/Up/Down must not crash
        showControls.value = false
        showSkipButton.value = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_aniskip_button").performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionDown)
        }
        composeTestRule.waitForIdle()
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

    @Test
    fun animeTitleExtraction_handlesNestedTitleObjects_arrays_nullStrings_andPriorityOrder() = runTest {
        val rawHomePayload = """
            {
              "ok": true,
              "trending": [
                {
                  "shortId": 1001,
                  "title": {
                    "english": "Attack on Titan",
                    "romaji": "Shingeki no Kyojin",
                    "native": "進撃の巨人"
                  },
                  "format": "TV"
                },
                {
                  "shortId": 1002,
                  "title": {
                    "english": "Solo Leveling"
                  },
                  "format": "TV"
                },
                {
                  "shortId": 1003,
                  "title": {
                    "english": "null",
                    "romaji": "Kimetsu no Yaiba",
                    "native": "鬼滅の刃"
                  },
                  "format": "TV"
                },
                {
                  "shortId": 1004,
                  "title": {
                    "english": "",
                    "romaji": "undefined",
                    "native": "葬送のフリーレン"
                  },
                  "format": "TV"
                },
                {
                  "shortId": 1005,
                  "titles": [
                    { "type": "Default", "title": "ONE PIECE" },
                    { "type": "English", "title": "One Piece" }
                  ],
                  "format": "TV"
                },
                {
                  "shortId": 1006,
                  "format": "TV"
                }
              ]
            }
        """.trimIndent()

        val parsed = `in`.aniscroll.tv.data.remote.JsonParsers.parseAnimeListFromResponse(rawHomePayload)
        // Item 1006 lacks any title and should be safely skipped instead of showing "null" or raw JSON
        assertEquals(5, parsed.size)

        val aot = parsed.first { it.hoshikaId == 1001L }
        assertEquals("Attack on Titan", aot.preferredTitle)
        assertEquals("Shingeki no Kyojin", aot.title)

        val solo = parsed.first { it.hoshikaId == 1002L }
        assertEquals("Solo Leveling", solo.preferredTitle)
        assertFalse(solo.preferredTitle.startsWith("{"))

        val demon = parsed.first { it.hoshikaId == 1003L }
        assertEquals("Kimetsu no Yaiba", demon.preferredTitle)

        val frieren = parsed.first { it.hoshikaId == 1004L }
        assertEquals("葬送のフリーレン", frieren.preferredTitle)

        val onePiece = parsed.first { it.hoshikaId == 1005L }
        assertEquals("One Piece", onePiece.preferredTitle)

        // Streaming response should also extract optional anime title when present
        val streamJson = """
            {
              "ok": true,
              "title": "Frieren: Beyond Journey's End",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=master.m3u8"
            }
        """.trimIndent()
        val streamData = `in`.aniscroll.tv.data.remote.JsonParsers.parseAniScrollStreamResponse(
            anilistId = 154587L,
            episodeNumber = 1,
            language = "sub",
            rawBody = streamJson
        )
        assertNotNull(streamData)
        assertEquals("Frieren: Beyond Journey's End", streamData!!.title)
    }

    @Test
    fun animeTitleUiDisplay_rendersTitleInHomeScreenEvenWhileLogoLoads() = runTest {
        val fakeHoshika = FakeHoshikaApi(
            homeJson = """
                {
                  "ok": true,
                  "data": {
                    "trending": [
                      {
                        "shortId": 83885,
                        "title": {
                          "english": "Solo Leveling",
                          "romaji": "Ore dake Level Up na Ken"
                        },
                        "logoUrl": "https://example.com/slow-logo.png",
                        "coverImage": "https://cdn.hoshika.app/covers/83885.webp",
                        "format": "TV"
                      },
                      {
                        "shortId": 99001,
                        "title": {
                          "romaji": "Sousou no Frieren",
                          "english": null
                        },
                        "format": "TV"
                      }
                    ]
                  }
                }
            """.trimIndent()
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val homeVm = HomeViewModel(repo)

        composeTestRule.setContent {
            HomeScreen(
                viewModel = homeVm,
                onOpenAnimeDetails = {},
                onPlayEpisode = { _, _, _ -> }
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            !homeVm.uiState.value.isLoading && homeVm.uiState.value.sections.isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("anime_logo_text_fallback_83885").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sousou no Frieren").assertIsDisplayed()
    }

    @Test
    fun animeTitleUiDisplay_rendersTitleInDetailsScreenEvenWhileLogoLoads() = runTest {
        val fakeHoshika = FakeHoshikaApi(
            detailsJson = """
                {
                  "ok": true,
                  "anime": {
                    "shortId": 83885,
                    "anilistId": 151807,
                    "title": {
                      "english": "Solo Leveling",
                      "romaji": "Ore dake Level Up na Ken",
                      "native": "俺だけレベルアップな件"
                    },
                    "logoUrl": "https://example.com/slow-logo.png",
                    "description": "A weak hunter gains a leveling system.",
                    "format": "TV",
                    "seasonYear": 2024
                  }
                }
            """.trimIndent()
        )
        val repo = createAnimeRepository(fakeHoshika, FakeAniScrollApi())
        val detailsVm = `in`.aniscroll.tv.features.details.DetailsViewModel(repo)

        composeTestRule.setContent {
            `in`.aniscroll.tv.features.details.DetailsScreen(
                hoshikaId = 83885L,
                viewModel = detailsVm,
                onPlayEpisode = { _, _, _ -> },
                onOpenEpisodesScreen = {},
                onBack = {}
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            detailsVm.uiState.value.anime != null
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("anime_logo_text_fallback_83885").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ore dake Level Up na Ken").assertIsDisplayed()
    }

    @Test
    fun animeTitleUiDisplay_resolvesCachedTitleInHistoryAndRendersInPlayer() = runTest {
        val repo = createAnimeRepository(FakeHoshikaApi(), FakeAniScrollApi())
        dao.insertAnime(
            `in`.aniscroll.tv.core.database.AnimeEntity(
                hoshikaId = 83885L,
                anilistId = 151807L,
                title = "Ore dake Level Up na Ken",
                englishTitle = "Solo Leveling",
                japaneseTitle = "俺だけレベルアップな件",
                description = "A weak hunter gains a leveling system.",
                posterUrl = null,
                bannerUrl = null,
                logoUrl = "https://example.com/slow-logo.png",
                releaseYear = 2024,
                season = null,
                status = "FINISHED",
                format = "TV",
                totalEpisodes = 12,
                genresCsv = "Action",
                tmdbId = null,
                lastUpdated = System.currentTimeMillis()
            )
        )
        dao.upsertWatchHistory(
            `in`.aniscroll.tv.core.database.WatchHistoryEntity(
                hoshikaId = 83885L,
                anilistId = 151807L,
                animeTitle = "Anime #83885",
                posterUrl = null,
                bannerUrl = null,
                logoUrl = null,
                episodeNumber = 1,
                language = "sub",
                position = 120_000L,
                duration = 1_440_000L,
                completed = false,
                lastWatched = System.currentTimeMillis()
            )
        )

        val historyVm = `in`.aniscroll.tv.features.history.HistoryViewModel(repo)
        composeTestRule.setContent {
            androidx.compose.foundation.layout.Column {
                Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                    `in`.aniscroll.tv.features.history.HistoryScreen(
                        viewModel = historyVm,
                        onResumePlayback = { _, _, _ -> },
                        onOpenAnimeDetails = {}
                    )
                }
                Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                    PlayerControlsOverlay(
                        animeTitle = "Frieren: Beyond Journey's End",
                        episodeNumber = 1,
                        episodeTitle = "The Journey's End",
                        audioLanguage = "sub",
                        selectedQualityLabel = "1080p",
                        playbackSpeed = 1.0f,
                        isPlaying = true,
                        hasPreviousEpisode = false,
                        hasNextEpisode = true,
                        progressStateFlow = MutableStateFlow(PlaybackProgressState(60_000L, 120_000L, 1_440_000L)),
                        activePanel = PlayerSettingsPanel.NONE,
                        canInteract = true,
                        lastFocusTarget = PlayerFocusTarget.PLAY_PAUSE,
                        onSeekingChanged = {},
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
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            historyVm.continueWatching.value.isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Solo Leveling").assertIsDisplayed()
        composeTestRule.onNodeWithText("Frieren: Beyond Journey's End").assertIsDisplayed()
    }

    @Test
    fun sidebar_doesNotAutomaticallyExpandOnFocusChange_andTogglesViaBrandHeader() = runTest {
        var selectedDest = `in`.aniscroll.tv.navigation.TvDestination.HOME
        composeTestRule.setContent {
            `in`.aniscroll.tv.navigation.TvNavigationSidebar(
                selectedDestination = selectedDest,
                onSelectDestination = { selectedDest = it }
            )
        }
        composeTestRule.waitForIdle()
        // Initially collapsed (ANISCROLL TV label not displayed)
        composeTestRule.onNodeWithText("ANISCROLL TV").assertDoesNotExist()

        // Focus or click navigation item (e.g. Search) - must NOT expand sidebar automatically
        composeTestRule.onNodeWithTag("nav_search").performClick()
        composeTestRule.waitForIdle()
        assertEquals(`in`.aniscroll.tv.navigation.TvDestination.SEARCH, selectedDest)
        composeTestRule.onNodeWithText("ANISCROLL TV").assertDoesNotExist()

        // Click / activate brand header to toggle expansion
        composeTestRule.onNodeWithTag("sidebar_brand_header").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ANISCROLL TV").assertIsDisplayed()

        // Click brand header again to collapse
        composeTestRule.onNodeWithTag("sidebar_brand_header").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ANISCROLL TV").assertDoesNotExist()
    }

    @Test
    fun videoPlayback_preventsBlackScreenWithAudio_keepsTextureViewAttached_andRecoversBoundedly() = runTest {
        val exoManager = ExoPlayerManager(context)
        try {
            val player = exoManager.getOrCreatePlayer()
            val trackSelector = player.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector
            assertNotNull("Expected DefaultTrackSelector on ExoPlayer", trackSelector)
            val params = trackSelector!!.parameters
            assertTrue("exceedVideoConstraintsIfNecessary must be true so 1080p/Hi10P streams never drop video after first segment", params.exceedVideoConstraintsIfNecessary)
            assertTrue("exceedRendererCapabilitiesIfNecessary must be true so video renderer is never disabled", params.exceedRendererCapabilitiesIfNecessary)
            assertFalse("Video track type must never be disabled", player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO))

            // Verify QualityManager Auto mode on CONSTRAINED or STANDARD tier never disables video track
            exoManager.let { mgr ->
                val qm = `in`.aniscroll.tv.player.QualityManager()
                qm.applyQualitySelection(
                    player = player,
                    option = `in`.aniscroll.tv.player.AvailableQualityOption("Auto", null),
                    networkTier = `in`.aniscroll.tv.core.network.NetworkTier.CONSTRAINED,
                    maxHardwareHeight = mgr.deviceProfile.effectiveMaxPlaybackHeight
                )
                assertFalse(player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO))
                // Auto mode must not cap initial video bitrate to 2.8 Mbps before playback stabilizes
                assertEquals(Int.MAX_VALUE, player.trackSelectionParameters.maxVideoBitrate)
            }

            // Inflate R.layout.exo_player_texture_view and verify TextureView videoSurfaceView is created
            val inflatedView = android.view.LayoutInflater.from(context)
                .inflate(com.example.R.layout.exo_player_texture_view, null, false) as androidx.media3.ui.PlayerView
            assertTrue(
                "Expected TextureView videoSurfaceView for reliable Compose layering and controls auto-hide",
                inflatedView.videoSurfaceView is android.view.TextureView
            )

            // Verify PlaybackController detects video track deselection / frozen frames while audio advances and performs bounded recovery
            val streamPayload = """
                {
                  "source": "megaplay",
                  "anilistId": 16498,
                  "malId": 16498,
                  "title": "Attack on Titan",
                  "episode": 1,
                  "type": "sub",
                  "m3u8": "https://cdn.example.com/aot-ep1-direct.m3u8",
                  "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fcdn.example.com%2Faot-ep1-direct.m3u8",
                  "qualities": [
                    { "quality": "1080p", "url": "https://cdn.example.com/aot-ep1-1080p.m3u8" }
                  ],
                  "subtitles": []
                }
            """.trimIndent()
            val fakeAniScroll = FakeAniScrollApi(megaPlayStreamBody = streamPayload)
            val fakeHoshika = FakeHoshikaApi(
                detailsJson = """
                    {
                      "shortId": 33374,
                      "titleRomaji": "Shingeki no Kyojin",
                      "titleEnglish": "Attack on Titan",
                      "format": "TV",
                      "mappings": { "anilist": 16498 }
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
            val prefs = UserPreferencesRepository(context)
            val controller = PlaybackController(
                exoPlayerManager = exoManager,
                animeRepository = animeRepo,
                playbackRepository = playbackRepo,
                preferencesRepository = prefs
            )

            controller.startPlaybackSession(hoshikaId = 33374L, episodeNumber = 1, requestedLanguage = "sub")
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
                !controller.uiState.value.isLoading
            }

            assertEquals(0, controller.uiState.value.surfaceRecoveryEpoch)
            assertFalse(exoManager.preferSoftwareDecoderFallback)

            // Simulate Attempt 1: video track deselected while audio is playing at 5,000ms
            val deselectedSnapshot = `in`.aniscroll.tv.player.VideoRenderingHealthSnapshot(
                hasVideoTrackGroup = true,
                isVideoTrackSelected = false,
                isVideoTrackSupported = true,
                renderedOutputBufferCount = 120,
                skippedOutputBufferCount = 0,
                droppedBufferCount = 0,
                videoWidth = 1920,
                videoHeight = 1080,
                surfaceWidth = 1920,
                surfaceHeight = 1080,
                activeDecoderName = "c2.mtk.avc.decoder",
                lastVideoCodecError = null,
                isUsingSoftwareDecoderFallback = false
            )
            val recovered1 = controller.evaluateVideoRenderingHealth(
                currentAudioPositionMs = 5_000L,
                isPlaying = true,
                playbackState = androidx.media3.common.Player.STATE_READY,
                snapshot = deselectedSnapshot
            )
            assertTrue("Expected Attempt 1 soft surface + track recovery to trigger", recovered1)
            assertEquals(1, controller.uiState.value.surfaceRecoveryEpoch)
            assertEquals(1, controller.uiState.value.videoRecoveryCount)

            // Simulate Attempt 2: hardware codec error while audio continues -> must switch to software decoder fallback
            val codecErrorSnapshot = deselectedSnapshot.copy(
                isVideoTrackSelected = true,
                lastVideoCodecError = "MediaCodecVideoDecoderException: Decoder failed"
            )
            val recovered2 = controller.evaluateVideoRenderingHealth(
                currentAudioPositionMs = 9_500L,
                isPlaying = true,
                playbackState = androidx.media3.common.Player.STATE_READY,
                snapshot = codecErrorSnapshot
            )
            assertTrue("Expected Attempt 2 software decoder fallback recovery to trigger", recovered2)
            assertTrue("Expected software decoder fallback mode to be enabled", exoManager.preferSoftwareDecoderFallback)
            assertEquals(2, controller.uiState.value.surfaceRecoveryEpoch)
            assertEquals(2, controller.uiState.value.videoRecoveryCount)

            // Attempt 3: must be bounded (MAX_VIDEO_RECOVERY_ATTEMPTS = 2) and never loop infinitely
            val recovered3 = controller.evaluateVideoRenderingHealth(
                currentAudioPositionMs = 15_000L,
                isPlaying = true,
                playbackState = androidx.media3.common.Player.STATE_READY,
                snapshot = codecErrorSnapshot
            )
            assertFalse("Expected recovery to stop after MAX_VIDEO_RECOVERY_ATTEMPTS (no infinite loop)", recovered3)
            assertEquals(2, controller.uiState.value.videoRecoveryCount)

            controller.exitAndReleasePlayer()
        } finally {
            exoManager.releasePlayer()
        }
    }

    @Test
    fun skipButtons_parsesIntroOutroRecap_mapsMalIdForAniSkip_resetsAcrossEpisodes_andRendersAndSeeksInPlayer() = runTest {
        // 1. Verify AniSkip v2 response parsing for op, ed, and recap with decimal seconds + invalid range filtering
        val aniSkipJson = """
            {
              "found": true,
              "results": [
                {
                  "interval": { "startTime": 10.0, "endTime": 45.5 },
                  "skipType": "recap",
                  "skipId": "recap-1",
                  "episodeLength": 1440.0
                },
                {
                  "interval": { "startTime": 85.25, "endTime": 175.25 },
                  "skipType": "op",
                  "skipId": "op-1",
                  "episodeLength": 1440.0
                },
                {
                  "interval": { "startTime": 1320.0, "endTime": 1410.0 },
                  "skipType": "ed",
                  "skipId": "ed-1",
                  "episodeLength": 1440.0
                },
                {
                  "interval": { "startTime": 0.0, "endTime": 0.0 },
                  "skipType": "op",
                  "skipId": "invalid-zero"
                }
              ]
            }
        """.trimIndent()

        val parsedAniSkip = `in`.aniscroll.tv.data.remote.JsonParsers.parseAniSkipResponse(aniSkipJson)
        assertNotNull(parsedAniSkip.recap)
        assertEquals(10_000L, parsedAniSkip.recap!!.startTimeMs)
        assertEquals(45_500L, parsedAniSkip.recap!!.endTimeMs)
        assertNotNull(parsedAniSkip.intro)
        assertEquals(85_250L, parsedAniSkip.intro!!.startTimeMs)
        assertEquals(175_250L, parsedAniSkip.intro!!.endTimeMs)
        assertNotNull(parsedAniSkip.outro)
        assertEquals(1_320_000L, parsedAniSkip.outro!!.startTimeMs)
        assertEquals(1_410_000L, parsedAniSkip.outro!!.endTimeMs)

        // 2. Verify embedded stream response parsing (malId extraction, 0..0 placeholder rejection, recap/intro/outro extraction)
        val embeddedStreamJson = """
            {
              "source": "megaplay",
              "anilistId": 154587,
              "malId": 52991,
              "title": "Frieren: Beyond Journey's End",
              "episode": 1,
              "type": "sub",
              "m3u8": "https://cdn.example.com/frieren-ep1.m3u8",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fcdn.example.com%2Ffrieren-ep1.m3u8",
              "intro": { "start": 0, "end": 0 },
              "outro": { "start": 1320.5, "end": 1410.5 },
              "recap": { "start": 12.0, "end": 42.0 },
              "qualities": [],
              "subtitles": []
            }
        """.trimIndent()

        val parsedStream = `in`.aniscroll.tv.data.remote.JsonParsers.parseAniScrollStreamResponse(
            anilistId = 154587L,
            episodeNumber = 1,
            language = "sub",
            rawBody = embeddedStreamJson
        )
        assertNotNull(parsedStream)
        assertEquals(52991L, parsedStream!!.malId)
        assertNull("0..0 placeholder intro must be ignored", parsedStream.introStartSec)
        assertEquals(1320.5f, parsedStream.outroStartSec!!, 0.01f)
        assertEquals(1410.5f, parsedStream.outroEndSec!!, 0.01f)
        assertEquals(12.0f, parsedStream.recapStartSec!!, 0.01f)
        assertEquals(42.0f, parsedStream.recapEndSec!!, 0.01f)

        // 3. Verify PlaybackRepository queries AniSkip using MAL ID (52991) first instead of only AniList ID (154587)
        val requestedAniSkipIds = mutableListOf<Pair<Long, Int>>()
        val requestedAniSkipTypes = mutableListOf<List<String>>()
        val fakeAniSkip = object : AniSkipApi {
            override suspend fun getSkipTimes(
                animeId: Long,
                episodeNumber: Int,
                types: List<String>,
                episodeLength: Float
            ): Response<ResponseBody> {
                requestedAniSkipIds.add(animeId to episodeNumber)
                requestedAniSkipTypes.add(types)
                return if (animeId == 52991L && episodeNumber == 1) {
                    Response.success(aniSkipJson.toResponseBody("application/json".toMediaType()))
                } else {
                    Response.error(404, """{"found":false,"results":[]}""".toResponseBody("application/json".toMediaType()))
                }
            }
        }

        val ep1OnlyEmbeddedRecapNoIntroJson = """
            {
              "source": "megaplay",
              "anilistId": 154587,
              "malId": 52991,
              "title": "Frieren: Beyond Journey's End",
              "episode": 1,
              "type": "sub",
              "m3u8": "https://cdn.example.com/frieren-ep1.m3u8",
              "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fcdn.example.com%2Ffrieren-ep1.m3u8",
              "qualities": [],
              "subtitles": []
            }
        """.trimIndent()

        val fakeAniScroll = object : AniScrollApi by FakeAniScrollApi() {
            override suspend fun getWatchStreams(
                source: String,
                anilistId: Long,
                episode: Int,
                language: String
            ): Response<ResponseBody> = getMegaPlayStreams(anilistId, episode, language)

            override suspend fun getMegaPlayStreams(anilistId: Long, episode: Int, language: String): Response<ResponseBody> {
                val payload = if (episode == 1) {
                    ep1OnlyEmbeddedRecapNoIntroJson
                } else {
                    """
                    {
                      "source": "megaplay",
                      "anilistId": 154587,
                      "malId": 52991,
                      "title": "Frieren: Beyond Journey's End",
                      "episode": $episode,
                      "type": "sub",
                      "m3u8": "https://cdn.example.com/frieren-ep$episode.m3u8",
                      "hlsProxyUrl": "https://anivault-scraper-five.vercel.app/api/proxy/hls?url=https%3A%2F%2Fcdn.example.com%2Ffrieren-ep$episode.m3u8",
                      "qualities": [],
                      "subtitles": []
                    }
                    """.trimIndent()
                }
                return Response.success(payload.toResponseBody("application/json".toMediaType()))
            }
        }

        val fakeHoshika = FakeHoshikaApi(
            detailsJson = """
                {
                  "shortId": 91001,
                  "anilistId": 154587,
                  "malId": 52991,
                  "titleRomaji": "Sousou no Frieren",
                  "titleEnglish": "Frieren: Beyond Journey's End",
                  "format": "TV",
                  "episodes": 28
                }
            """.trimIndent()
        )

        val animeRepo = createAnimeRepository(fakeHoshika, fakeAniScroll)
        val playbackRepo = PlaybackRepository(
            aniScrollApi = fakeAniScroll,
            aniSkipApi = fakeAniSkip,
            dao = dao,
            idConverter = animeRepo.idConverter
        )
        val exoManager = ExoPlayerManager(context)
        val prefs = UserPreferencesRepository(context)
        val controller = PlaybackController(
            exoPlayerManager = exoManager,
            animeRepository = animeRepo,
            playbackRepository = playbackRepo,
            preferencesRepository = prefs
        )

        try {
            controller.startPlaybackSession(hoshikaId = 91001L, episodeNumber = 1, requestedLanguage = "sub")
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !controller.uiState.value.isLoading && controller.aniSkipManager.getIntervals().hasAnyInterval
            }
            assertFalse(controller.uiState.value.isLoading)
            assertTrue(controller.aniSkipManager.getIntervals().hasAnyInterval)

            // Confirm AniSkip was queried with MAL ID 52991 first and included "recap" in types
            assertEquals(52991L to 1, requestedAniSkipIds.first())
            assertTrue(requestedAniSkipTypes.first().containsAll(listOf("op", "ed", "mixed-op", "mixed-ed", "recap")))

            // 4. Verify position-based visibility transitions:
            // Before recap (5s) -> no button
            assertNull(controller.aniSkipManager.evaluateCurrentPosition(5_000L, 1_440_000L))
            // Inside recap (20s) -> Skip Recap
            val recapPrompt = controller.aniSkipManager.evaluateCurrentPosition(20_000L, 1_440_000L)
            assertNotNull(recapPrompt)
            assertEquals(`in`.aniscroll.tv.player.ActiveSkipType.RECAP, recapPrompt!!.type)
            assertEquals("Skip Recap", recapPrompt.label)
            assertEquals(45_500L, recapPrompt.targetTimeMs)

            // Between recap and intro (60s) -> no button
            assertNull(controller.aniSkipManager.evaluateCurrentPosition(60_000L, 1_440_000L))

            // Inside intro (90s) -> Skip Intro
            val introPrompt = controller.aniSkipManager.evaluateCurrentPosition(90_000L, 1_440_000L)
            assertNotNull(introPrompt)
            assertEquals(`in`.aniscroll.tv.player.ActiveSkipType.INTRO, introPrompt!!.type)
            assertEquals("Skip Intro", introPrompt.label)
            assertEquals(175_250L, introPrompt.targetTimeMs)

            // Perform Skip Intro -> seeks player to 175,250ms, hides button immediately, prevents re-triggering
            controller.performSkip(introPrompt)
            assertNull(controller.uiState.value.activeSkipPrompt)
            assertNull(
                "Skip Intro must not re-appear while still inside intro after being activated",
                controller.aniSkipManager.evaluateCurrentPosition(100_000L, 1_440_000L)
            )
            assertEquals(175_250L, controller.progressState.value.currentPositionMs)

            // Manual backward user seek before intro start re-enables Skip Intro
            controller.aniSkipManager.onManualUserSeek(80_000L)
            assertNotNull(controller.aniSkipManager.evaluateCurrentPosition(95_000L, 1_440_000L))

            // Inside outro (1350s) -> Skip Outro
            val outroPrompt = controller.aniSkipManager.evaluateCurrentPosition(1_350_000L, 1_440_000L)
            assertNotNull(outroPrompt)
            assertEquals(`in`.aniscroll.tv.player.ActiveSkipType.OUTRO, outroPrompt!!.type)
            assertEquals("Skip Outro", outroPrompt.label)
            assertEquals(1_410_000L, outroPrompt.targetTimeMs)

            // 5. Switching to Episode 2 (which has no skip segments) immediately resets stale Episode 1 segments
            controller.startPlaybackSession(hoshikaId = 91001L, episodeNumber = 2, requestedLanguage = "sub")
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !controller.uiState.value.isLoading && controller.uiState.value.currentEpisodeNumber == 2
            }
            assertFalse(controller.aniSkipManager.getIntervals().hasAnyInterval)
            assertNull(controller.uiState.value.activeSkipPrompt)
            assertNull(controller.aniSkipManager.evaluateCurrentPosition(90_000L, 1_440_000L))

            // 6. Verify Compose UI rendering & click activation for Skip Recap, Skip Intro, and Skip Outro
            val activePromptState = androidx.compose.runtime.mutableStateOf(recapPrompt)
            var clickedPromptType: `in`.aniscroll.tv.player.ActiveSkipType? = null
            composeTestRule.setContent {
                `in`.aniscroll.tv.player.SkipIntroOutroButton(
                    prompt = activePromptState.value,
                    onClick = { clickedPromptType = activePromptState.value.type }
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("player_aniskip_button").assertIsDisplayed()
            composeTestRule.onNodeWithText("Skip Recap").assertIsDisplayed()
            composeTestRule.onNodeWithTag("player_aniskip_button").performClick()
            assertEquals(`in`.aniscroll.tv.player.ActiveSkipType.RECAP, clickedPromptType)

            activePromptState.value = introPrompt
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Skip Intro").assertIsDisplayed()

            activePromptState.value = outroPrompt
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Skip Outro").assertIsDisplayed()

            controller.exitAndReleasePlayer()
        } finally {
            exoManager.releasePlayer()
        }
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
