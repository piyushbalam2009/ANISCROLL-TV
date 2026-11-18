package `in`.aniscroll.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import `in`.aniscroll.tv.core.designsystem.AniScrollTvTheme
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.di.AppContainer
import `in`.aniscroll.tv.features.details.DetailsScreen
import `in`.aniscroll.tv.features.details.DetailsViewModel
import `in`.aniscroll.tv.features.discover.DiscoverScreen
import `in`.aniscroll.tv.features.discover.DiscoverViewModel
import `in`.aniscroll.tv.features.episodes.EpisodesScreen
import `in`.aniscroll.tv.features.history.HistoryScreen
import `in`.aniscroll.tv.features.history.HistoryViewModel
import `in`.aniscroll.tv.features.home.HomeScreen
import `in`.aniscroll.tv.features.home.HomeViewModel
import `in`.aniscroll.tv.features.launch.AniScrollLaunchScreen
import `in`.aniscroll.tv.features.launch.LaunchViewModel
import `in`.aniscroll.tv.features.schedule.ScheduleScreen
import `in`.aniscroll.tv.features.schedule.ScheduleViewModel
import `in`.aniscroll.tv.features.search.SearchScreen
import `in`.aniscroll.tv.features.search.SearchViewModel
import `in`.aniscroll.tv.features.settings.SettingsScreen
import `in`.aniscroll.tv.features.settings.SettingsViewModel
import `in`.aniscroll.tv.features.watchlist.WatchlistScreen
import `in`.aniscroll.tv.features.watchlist.WatchlistViewModel
import `in`.aniscroll.tv.features.watchorder.WatchOrderScreen
import `in`.aniscroll.tv.features.watchorder.WatchOrderViewModel
import `in`.aniscroll.tv.navigation.TvDestination
import `in`.aniscroll.tv.navigation.TvNavigationSidebar
import `in`.aniscroll.tv.player.PlaybackController
import `in`.aniscroll.tv.player.PlayerScreen
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.lifecycle.compose.collectAsStateWithLifecycle

sealed class TvScreenRoute {
    data class MainTab(val destination: TvDestination) : TvScreenRoute()
    data class AnimeDetails(val hoshikaId: Long) : TvScreenRoute()
    data class WatchOrder(val hoshikaId: Long) : TvScreenRoute()
    data class EpisodesBrowser(val hoshikaId: Long) : TvScreenRoute()
    data class VideoPlayer(
        val hoshikaId: Long,
        val episodeNumber: Int,
        val preferredLanguage: String? = null
    ) : TvScreenRoute()
}

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureTelevisionImmersiveWindow()
        appContainer = AppContainer(applicationContext)

        setContent {
            AniScrollTvTheme {
                AniScrollTvApp(appContainer = appContainer)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::appContainer.isInitialized) {
            appContainer.exoPlayerManager.pauseIfActive()
        }
    }

    override fun onDestroy() {
        if (::appContainer.isInitialized) {
            appContainer.exoPlayerManager.releasePlayer()
            appContainer.networkMonitor.unregister()
        }
        super.onDestroy()
    }

    private fun configureTelevisionImmersiveWindow() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun AniScrollTvApp(
    appContainer: AppContainer,
    showLaunchScreenOnStart: Boolean = true
) {
    val homeViewModel = remember { HomeViewModel(appContainer.animeRepository) }
    val launchViewModel = remember {
        LaunchViewModel(
            preferencesRepository = appContainer.preferencesRepository,
            animeRepository = appContainer.animeRepository,
            homeViewModel = homeViewModel
        ).also { vm ->
            if (!showLaunchScreenOnStart) {
                vm.markStartupComplete()
            }
        }
    }
    val launchState by launchViewModel.uiState.collectAsStateWithLifecycle()

    val searchViewModel = remember { SearchViewModel(appContainer.animeRepository) }
    val discoverViewModel = remember { DiscoverViewModel(appContainer.animeRepository) }
    val scheduleViewModel = remember { ScheduleViewModel(appContainer.animeRepository) }
    val watchlistViewModel = remember { WatchlistViewModel(appContainer.animeRepository) }
    val historyViewModel = remember { HistoryViewModel(appContainer.animeRepository) }
    val detailsViewModel = remember { DetailsViewModel(appContainer.animeRepository) }
    val watchOrderViewModel = remember { WatchOrderViewModel(appContainer.animeRepository) }
    val settingsViewModel = remember {
        SettingsViewModel(appContainer.preferencesRepository, appContainer.dao)
    }
    val playbackController = remember {
        PlaybackController(
            exoPlayerManager = appContainer.exoPlayerManager,
            animeRepository = appContainer.animeRepository,
            playbackRepository = appContainer.playbackRepository,
            preferencesRepository = appContainer.preferencesRepository,
            networkMonitor = appContainer.networkMonitor
        )
    }

    var currentTab by remember { mutableStateOf(TvDestination.HOME) }
    val navigationBackStack = remember {
        mutableStateListOf<TvScreenRoute>(TvScreenRoute.MainTab(TvDestination.HOME))
    }

    val activeRoute = navigationBackStack.lastOrNull() ?: TvScreenRoute.MainTab(TvDestination.HOME)

    fun pushRoute(route: TvScreenRoute) {
        navigationBackStack.add(route)
    }

    fun popRoute() {
        if (navigationBackStack.size > 1) {
            navigationBackStack.removeAt(navigationBackStack.lastIndex)
            val top = navigationBackStack.lastOrNull()
            if (top is TvScreenRoute.MainTab) {
                currentTab = top.destination
                detailsViewModel.cancelActiveEpisodeRequests()
            }
        } else if (currentTab != TvDestination.HOME) {
            currentTab = TvDestination.HOME
            navigationBackStack[0] = TvScreenRoute.MainTab(TvDestination.HOME)
            detailsViewModel.cancelActiveEpisodeRequests()
        }
    }

    BackHandler(enabled = launchState.isReadyToNavigate && (navigationBackStack.size > 1 || currentTab != TvDestination.HOME)) {
        popRoute()
    }

    if (launchState.isReadyToNavigate && activeRoute is TvScreenRoute.VideoPlayer) {
        PlayerScreen(
            hoshikaId = activeRoute.hoshikaId,
            episodeNumber = activeRoute.episodeNumber,
            initialLanguage = activeRoute.preferredLanguage,
            controller = playbackController,
            onExitPlayer = { popRoute() }
        )
    } else {
        Crossfade(
            targetState = launchState.isReadyToNavigate,
            animationSpec = tween(durationMillis = 360),
            label = "launch_to_main_crossfade"
        ) { isReady ->
            if (!isReady) {
                AniScrollLaunchScreen(statusText = launchState.statusText)
            } else {
                when (activeRoute) {
                    is TvScreenRoute.VideoPlayer -> Unit
                    else -> {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(TvColors.MainBackground)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                        ) {
                TvNavigationSidebar(
                    selectedDestination = currentTab,
                    onSelectDestination = { destination ->
                        currentTab = destination
                        navigationBackStack.clear()
                        navigationBackStack.add(TvScreenRoute.MainTab(destination))
                    }
                )

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (activeRoute) {
                        is TvScreenRoute.MainTab -> {
                            when (activeRoute.destination) {
                                TvDestination.HOME -> HomeScreen(
                                    viewModel = homeViewModel,
                                    onOpenAnimeDetails = { id ->
                                        pushRoute(TvScreenRoute.AnimeDetails(id))
                                    },
                                    onPlayEpisode = { id, ep, lang ->
                                        pushRoute(TvScreenRoute.VideoPlayer(id, ep, lang))
                                    }
                                )
                                TvDestination.SEARCH -> SearchScreen(
                                    viewModel = searchViewModel,
                                    onOpenAnimeDetails = { id ->
                                        pushRoute(TvScreenRoute.AnimeDetails(id))
                                    }
                                )
                                TvDestination.DISCOVER -> DiscoverScreen(
                                    viewModel = discoverViewModel,
                                    onOpenAnimeDetails = { id ->
                                        pushRoute(TvScreenRoute.AnimeDetails(id))
                                    },
                                    onPlayEpisode = { id, ep, lang ->
                                        pushRoute(TvScreenRoute.VideoPlayer(id, ep, lang))
                                    },
                                    onOpenSearch = {
                                        currentTab = TvDestination.SEARCH
                                        pushRoute(TvScreenRoute.MainTab(TvDestination.SEARCH))
                                    }
                                )
                                TvDestination.SCHEDULE -> ScheduleScreen(
                                    viewModel = scheduleViewModel,
                                    onOpenAnimeDetails = { id ->
                                        pushRoute(TvScreenRoute.AnimeDetails(id))
                                    }
                                )
                                TvDestination.MY_LIST -> WatchlistScreen(
                                    viewModel = watchlistViewModel,
                                    onOpenAnimeDetails = { id ->
                                        pushRoute(TvScreenRoute.AnimeDetails(id))
                                    }
                                )
                                TvDestination.CONTINUE_WATCHING -> HistoryScreen(
                                    viewModel = historyViewModel,
                                    onResumePlayback = { id, ep, lang ->
                                        pushRoute(TvScreenRoute.VideoPlayer(id, ep, lang))
                                    },
                                    onOpenAnimeDetails = { id ->
                                        pushRoute(TvScreenRoute.AnimeDetails(id))
                                    }
                                )
                                TvDestination.SETTINGS -> SettingsScreen(
                                    viewModel = settingsViewModel
                                )
                            }
                        }
                        is TvScreenRoute.AnimeDetails -> {
                            DetailsScreen(
                                hoshikaId = activeRoute.hoshikaId,
                                viewModel = detailsViewModel,
                                onPlayEpisode = { id, ep, lang ->
                                    pushRoute(TvScreenRoute.VideoPlayer(id, ep, lang))
                                },
                                onOpenEpisodesScreen = { id ->
                                    pushRoute(TvScreenRoute.EpisodesBrowser(id))
                                },
                                onOpenWatchOrder = { id ->
                                    pushRoute(TvScreenRoute.WatchOrder(id))
                                },
                                onBack = { popRoute() }
                            )
                        }
                        is TvScreenRoute.WatchOrder -> {
                            WatchOrderScreen(
                                hoshikaId = activeRoute.hoshikaId,
                                viewModel = watchOrderViewModel,
                                onOpenAnimeDetails = { id ->
                                    pushRoute(TvScreenRoute.AnimeDetails(id))
                                },
                                onBack = { popRoute() }
                            )
                        }
                        is TvScreenRoute.EpisodesBrowser -> {
                            EpisodesScreen(
                                hoshikaId = activeRoute.hoshikaId,
                                viewModel = detailsViewModel,
                                onPlayEpisode = { id, ep, lang ->
                                    pushRoute(TvScreenRoute.VideoPlayer(id, ep, lang))
                                },
                                onBack = { popRoute() }
                            )
                        }
                        is TvScreenRoute.VideoPlayer -> Unit
                    }
                }
            }
        }
                }
            }
        }
    }
}
