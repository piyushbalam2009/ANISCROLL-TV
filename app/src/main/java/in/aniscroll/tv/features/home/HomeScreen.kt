package `in`.aniscroll.tv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvLoadingIndicator
import `in`.aniscroll.tv.core.designsystem.TvTitleLogoOrText
import `in`.aniscroll.tv.data.local.UiPreviewFixtures
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.HomeSectionRow
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val featuredAnime: Anime? = null,
    val sections: List<HomeSectionRow> = emptyList(),
    val isFromOfflineCache: Boolean = false,
    val isUsingDevFixtures: Boolean = false,
    val errorMessage: String? = null,
    val lastFocusedRowIndex: Int = -1,
    val lastFocusedItemId: Long = -1L
)

class HomeViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val continueWatching: StateFlow<List<WatchProgressItem>> = animeRepository.continueWatchingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchlist: StateFlow<List<Anime>> = animeRepository.watchlistFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadHome()
    }

    fun loadHome() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isUsingDevFixtures = false) }
            val result = animeRepository.getHomeFeed()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    featuredAnime = result.featuredAnime,
                    sections = result.sections,
                    isFromOfflineCache = result.isFromOfflineCache,
                    errorMessage = result.errorMessage
                )
            }
        }
    }

    fun loadDevPreviewFixtures() {
        val sample = UiPreviewFixtures.sampleAnimeList
        _uiState.update {
            it.copy(
                isLoading = false,
                featuredAnime = sample.firstOrNull(),
                sections = UiPreviewFixtures.getPreviewHomeSections(),
                isUsingDevFixtures = true,
                errorMessage = null
            )
        }
    }

    fun toggleWatchlist(anime: Anime) {
        viewModelScope.launch {
            animeRepository.toggleWatchlist(anime)
        }
    }

    fun recordFocusedCard(rowIndex: Int, hoshikaId: Long) {
        _uiState.update {
            it.copy(lastFocusedRowIndex = rowIndex, lastFocusedItemId = hoshikaId)
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    onPlayEpisode: (Long, Int, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val heroPlayFocusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
    ) {
        when {
            state.isLoading -> {
                TvLoadingIndicator(
                    message = "Loading Hoshika anime catalogue...",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            state.errorMessage != null && state.featuredAnime == null && state.sections.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 540.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TvErrorBanner(
                        message = state.errorMessage!!,
                        onRetry = { viewModel.loadHome() }
                    )
                    if (continueWatching.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your local Continue Watching list is still available in the sidebar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvColors.SecondaryText
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TvFocusableButton(
                        text = "Load UI Preview Fixtures (Dev Mode)",
                        onClick = { viewModel.loadDevPreviewFixtures() },
                        modifier = Modifier.testTag("home_load_fixtures_button")
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // Status banner if offline cache or dev fixture mode is active
                    if (state.isFromOfflineCache || state.isUsingDevFixtures) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(TvColors.CardBackground)
                                    .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (state.isUsingDevFixtures) {
                                        "UI Preview Fixture Mode Active — Displaying separated local UI fixtures."
                                    } else {
                                        "Offline Cache Active — Showing locally cached Hoshika metadata."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TvColors.SecondaryText
                                )
                                TvFocusableButton(
                                    text = "Reconnect Live API",
                                    onClick = { viewModel.loadHome() },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // 1. Hero Section
                    state.featuredAnime?.let { featured ->
                        val isInMyList = watchlist.any { it.hoshikaId == featured.hoshikaId }
                        item(key = "hero_${featured.hoshikaId}") {
                            HomeHeroSection(
                                anime = featured,
                                isInWatchlist = isInMyList,
                                playFocusRequester = heroPlayFocusRequester,
                                onPlay = { onPlayEpisode(featured.hoshikaId, 1, null) },
                                onDetails = { onOpenAnimeDetails(featured.hoshikaId) },
                                onToggleWatchlist = { viewModel.toggleWatchlist(featured) }
                            )
                        }
                    }

                    // 2. Continue Watching Row (when local watch progress exists)
                    if (continueWatching.isNotEmpty()) {
                        item(key = "row_continue_watching") {
                            ContinueWatchingHomeRow(
                                items = continueWatching,
                                onResumeItem = { item ->
                                    onPlayEpisode(item.hoshikaId, item.episodeNumber, item.language)
                                }
                            )
                        }
                    }

                    // 3. Hoshika Content Rows
                    itemsIndexed(
                        items = state.sections,
                        key = { index, section -> "section_${index}_${section.title}" }
                    ) { rowIndex, section ->
                        HomeAnimeContentRow(
                            title = section.title,
                            items = section.items,
                            restoredFocusId = if (state.lastFocusedRowIndex == rowIndex) state.lastFocusedItemId else -1L,
                            onAnimeClick = { anime ->
                                viewModel.recordFocusedCard(rowIndex, anime.hoshikaId)
                                onOpenAnimeDetails(anime.hoshikaId)
                            },
                            onAnimeFocused = { anime ->
                                viewModel.recordFocusedCard(rowIndex, anime.hoshikaId)
                            }
                        )
                    }
                }

                LaunchedEffect(state.featuredAnime?.hoshikaId) {
                    if (state.lastFocusedItemId <= 0L && state.featuredAnime != null) {
                        runCatching { heroPlayFocusRequester.requestFocus() }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroSection(
    anime: Anime,
    isInWatchlist: Boolean,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onToggleWatchlist: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(TvColors.SecondaryBackground)
    ) {
        // Wide Hoshika background artwork
        val artworkUrl = anime.bannerUrl ?: anime.posterUrl
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    .crossfade(true)
                    .size(1280, 720)
                    .build(),
                contentDescription = anime.preferredTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Cinematic dark gradient overlays (left-to-right and bottom-to-top)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            TvColors.MainBackground,
                            TvColors.MainBackground.copy(alpha = 0.88f),
                            TvColors.MainBackground.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            TvColors.MainBackground.copy(alpha = 0.65f),
                            TvColors.MainBackground
                        )
                    )
                )
        )

        // Hero metadata & actions
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 620.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            // Transparent Title Logo from TMDB/Fanart or clean white typography fallback
            TvTitleLogoOrText(
                logoUrl = anime.logoUrl,
                fallbackTitle = anime.preferredTitle,
                maxHeight = 84.dp,
                maxWidth = 360.dp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata row: Year • Status • Genres
            val metaParts = buildList {
                anime.releaseYear?.let { add(it.toString()) }
                anime.status?.takeIf { it.isNotBlank() }?.let { add(it) }
                anime.format?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (anime.genres.isNotEmpty()) {
                    add(anime.genres.take(3).joinToString(" / "))
                }
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString("  •  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = TvColors.SecondaryText
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!anime.description.isNullOrBlank()) {
                Text(
                    text = anime.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvColors.SecondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(18.dp))
            }

            // Action Buttons: Play, Details, Add to My List
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvFocusableButton(
                    text = "Play",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    focusRequester = playFocusRequester,
                    onClick = onPlay,
                    modifier = Modifier.testTag("hero_play_button")
                )
                TvFocusableButton(
                    text = "Details",
                    icon = Icons.Default.Info,
                    onClick = onDetails,
                    modifier = Modifier.testTag("hero_details_button")
                )
                TvFocusableButton(
                    text = if (isInWatchlist) "In My List" else "Add to My List",
                    icon = if (isInWatchlist) Icons.Default.Check else Icons.Default.Add,
                    onClick = onToggleWatchlist,
                    modifier = Modifier.testTag("hero_watchlist_button")
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingHomeRow(
    items: List<WatchProgressItem>,
    onResumeItem: (WatchProgressItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.titleLarge,
            color = TvColors.PrimaryText,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = items,
                key = { "cw_${it.hoshikaId}_${it.episodeNumber}" }
            ) { item ->
                val cardAnime = Anime(
                    hoshikaId = item.hoshikaId,
                    anilistId = item.anilistId,
                    title = item.animeTitle,
                    englishTitle = item.animeTitle,
                    posterUrl = item.posterUrl,
                    bannerUrl = item.bannerUrl,
                    logoUrl = item.logoUrl
                )
                TvAnimePosterCard(
                    anime = cardAnime,
                    subtitleBadge = "EP ${item.episodeNumber} • ${item.language.uppercase()}",
                    progressFraction = item.progressFraction,
                    onClick = { onResumeItem(item) }
                )
            }
        }
    }
}

@Composable
private fun HomeAnimeContentRow(
    title: String,
    items: List<Anime>,
    restoredFocusId: Long,
    onAnimeClick: (Anime) -> Unit,
    onAnimeFocused: (Anime) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TvColors.PrimaryText,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = items,
                key = { "${title}_${it.hoshikaId}" }
            ) { anime ->
                val focusRequester = remember(anime.hoshikaId) { FocusRequester() }
                LaunchedEffect(restoredFocusId) {
                    if (restoredFocusId == anime.hoshikaId) {
                        runCatching { focusRequester.requestFocus() }
                    }
                }
                TvAnimePosterCard(
                    anime = anime,
                    focusRequester = focusRequester,
                    onClick = { onAnimeClick(anime) },
                    onFocused = { onAnimeFocused(anime) }
                )
            }
        }
    }
}
