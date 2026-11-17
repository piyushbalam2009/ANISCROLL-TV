package `in`.aniscroll.tv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import `in`.aniscroll.tv.artwork.AnimeLogo
import `in`.aniscroll.tv.artwork.ArtworkType
import `in`.aniscroll.tv.core.designsystem.SafeFocusRequester
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvAnimeRowSkeleton
import `in`.aniscroll.tv.core.designsystem.TvArtworkImage
import `in`.aniscroll.tv.core.device.TvDeviceCapabilities
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy
import `in`.aniscroll.tv.core.designsystem.TvEmptyStateBanner
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.rememberSafeFocusRequester
import `in`.aniscroll.tv.core.designsystem.safeFocusRequester
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvHomeLoadingSkeleton
import `in`.aniscroll.tv.core.designsystem.TvSectionLoadingHeader
import `in`.aniscroll.tv.data.local.UiPreviewFixtures
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.HomeSectionRow
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val featuredAnime: Anime? = null,
    val sections: List<HomeSectionRow> = emptyList(),
    val isFromOfflineCache: Boolean = false,
    val isUsingDevFixtures: Boolean = false,
    val errorMessage: String? = null,
    val lastFocusedRowIndex: Int = -1,
    val lastFocusedItemId: Long = -1L,
    val shouldRestoreCardFocus: Boolean = false
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

    private var loadHomeJob: Job? = null

    init {
        loadHome()
    }

    fun loadHome(forceRefresh: Boolean = false) {
        if (!forceRefresh && loadHomeJob?.isActive == true) return
        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            val current = _uiState.value
            val hasExistingContent = current.featuredAnime != null || current.sections.isNotEmpty()
            _uiState.update {
                it.copy(
                    isLoading = !hasExistingContent,
                    isRefreshing = hasExistingContent,
                    errorMessage = null,
                    isUsingDevFixtures = false
                )
            }
            val result = animeRepository.getHomeFeed()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    featuredAnime = result.featuredAnime ?: it.featuredAnime,
                    sections = if (result.sections.isNotEmpty()) result.sections else it.sections,
                    isFromOfflineCache = result.isFromOfflineCache,
                    errorMessage = result.errorMessage
                )
            }
        }
    }

    fun loadDevPreviewFixtures() {
        loadHomeJob?.cancel()
        viewModelScope.launch {
            val sample = UiPreviewFixtures.sampleAnimeList
            val first = sample.firstOrNull()?.let { animeRepository.enrichAnimeWithLogo(it) }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    featuredAnime = first,
                    sections = UiPreviewFixtures.getPreviewHomeSections(),
                    isUsingDevFixtures = true,
                    errorMessage = null
                )
            }
        }
    }

    fun onHeroLogoLoadFailed(failedUrl: String) {
        val currentFeatured = _uiState.value.featuredAnime ?: return
        viewModelScope.launch {
            val retried = animeRepository.invalidateAndRetryLogo(currentFeatured, failedUrl)
            _uiState.update { state ->
                if (state.featuredAnime?.hoshikaId == currentFeatured.hoshikaId) {
                    state.copy(featuredAnime = retried)
                } else {
                    state
                }
            }
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

    fun markCardClickedForReturnFocus(rowIndex: Int, hoshikaId: Long) {
        _uiState.update {
            it.copy(
                lastFocusedRowIndex = rowIndex,
                lastFocusedItemId = hoshikaId,
                shouldRestoreCardFocus = true
            )
        }
    }

    fun consumeCardFocusRestore() {
        _uiState.update {
            it.copy(shouldRestoreCardFocus = false)
        }
    }

    fun onHeroFocused() {
        _uiState.update {
            it.copy(
                lastFocusedRowIndex = -1,
                lastFocusedItemId = -1L,
                shouldRestoreCardFocus = false
            )
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
    val coroutineScope = rememberCoroutineScope()

    val heroPlayFocusRequester = rememberSafeFocusRequester()
    val firstRowFirstCardFocusRequester = rememberSafeFocusRequester()
    var hasRequestedInitialHeroFocus by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
    ) {
        // Responsive dimensions across 720p, 1080p, and 4K displays
        val heroHeight = (maxHeight * 0.52f).coerceIn(248.dp, 336.dp)
        val heroContentMaxWidth = (maxWidth * 0.58f).coerceIn(340.dp, 580.dp)
        val logoMaxWidth = (heroContentMaxWidth * 0.72f).coerceIn(190.dp, 310.dp)
        val logoMaxHeight = (heroHeight * 0.24f).coerceIn(48.dp, 74.dp)

        val hasContinueWatchingRow = continueWatching.isNotEmpty()
        val hasRemoteHomeContent = state.featuredAnime != null || state.sections.any { it.items.isNotEmpty() }
        val hasAnyContentRow = hasContinueWatchingRow || state.sections.any { it.items.isNotEmpty() }

        when {
            state.isLoading && !hasRemoteHomeContent && !hasContinueWatchingRow -> {
                TvHomeLoadingSkeleton(
                    heroHeight = heroHeight,
                    statusMessage = "Loading Hoshika anime catalogue..."
                )
            }
            state.errorMessage != null && !hasRemoteHomeContent -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 540.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TvErrorBanner(
                        message = state.errorMessage!!,
                        onRetry = { viewModel.loadHome(forceRefresh = true) }
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
            !state.isLoading && !hasRemoteHomeContent && !hasContinueWatchingRow -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    TvEmptyStateBanner(
                        title = "No anime sections available",
                        subtitle = "Unable to retrieve trending or popular anime right now.",
                        actionLabel = "Retry",
                        onAction = { viewModel.loadHome(forceRefresh = true) }
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("home_lazy_column"),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (state.isRefreshing) {
                        item(key = "home_refresh_banner") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp, vertical = 8.dp)
                            ) {
                                TvSectionLoadingHeader(
                                    label = TvContentLoadingCopy.LOADING_ANIME,
                                    detailText = "Refreshing anime sections..."
                                )
                            }
                        }
                    }

                    // Status banner if offline cache or dev fixture mode is active
                    if (state.isFromOfflineCache || state.isUsingDevFixtures) {
                        item(key = "home_status_banner") {
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
                                    onClick = { viewModel.loadHome(forceRefresh = true) },
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
                                heroHeight = heroHeight,
                                contentMaxWidth = heroContentMaxWidth,
                                logoMaxWidth = logoMaxWidth,
                                logoMaxHeight = logoMaxHeight,
                                playFocusRequester = heroPlayFocusRequester,
                                downFocusRequester = if (hasAnyContentRow) firstRowFirstCardFocusRequester else null,
                                onHeroFocused = {
                                    viewModel.onHeroFocused()
                                    coroutineScope.launch {
                                        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                                            listState.animateScrollToItem(0, 0)
                                        }
                                    }
                                },
                                onPlay = { onPlayEpisode(featured.hoshikaId, 1, null) },
                                onDetails = { onOpenAnimeDetails(featured.hoshikaId) },
                                onToggleWatchlist = { viewModel.toggleWatchlist(featured) },
                                onLogoLoadFailed = { failedUrl -> viewModel.onHeroLogoLoadFailed(failedUrl) }
                            )
                        }
                    }

                    // 2. Continue Watching Row (when local watch progress exists)
                    if (hasContinueWatchingRow) {
                        item(key = "row_continue_watching") {
                            ContinueWatchingHomeRow(
                                items = continueWatching,
                                firstCardFocusRequester = firstRowFirstCardFocusRequester,
                                upFocusRequester = if (state.featuredAnime != null) heroPlayFocusRequester else null,
                                onResumeItem = { item ->
                                    onPlayEpisode(item.hoshikaId, item.episodeNumber, item.language)
                                }
                            )
                        }
                    }

                    // 3. Hoshika Content Rows (or section-level skeleton while remote rows load)
                    if (state.isLoading && state.sections.isEmpty()) {
                        item(key = "home_remote_rows_skeleton") {
                            TvAnimeRowSkeleton(
                                sectionLabel = TvContentLoadingCopy.LOADING_ANIME,
                                cardsCount = 6
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = state.sections,
                            key = { index, section -> "section_${index}_${section.title}" }
                        ) { rowIndex, section ->
                            val firstNonEmptySectionIndex = state.sections.indexOfFirst { it.items.isNotEmpty() }
                            val isFirstContentRowBelowHero = !hasContinueWatchingRow &&
                                firstNonEmptySectionIndex >= 0 &&
                                rowIndex == firstNonEmptySectionIndex
                            HomeAnimeContentRow(
                                title = section.title,
                                items = section.items,
                                firstCardFocusRequester = if (isFirstContentRowBelowHero) firstRowFirstCardFocusRequester else null,
                                upFocusRequester = if (isFirstContentRowBelowHero && state.featuredAnime != null) heroPlayFocusRequester else null,
                                restoredFocusId = if (state.shouldRestoreCardFocus && state.lastFocusedRowIndex == rowIndex) {
                                    state.lastFocusedItemId
                                } else {
                                    -1L
                                },
                                onConsumeRestoreFocus = { viewModel.consumeCardFocusRestore() },
                                onAnimeClick = { anime ->
                                    viewModel.markCardClickedForReturnFocus(rowIndex, anime.hoshikaId)
                                    onOpenAnimeDetails(anime.hoshikaId)
                                },
                                onAnimeFocused = { anime ->
                                    viewModel.recordFocusedCard(rowIndex, anime.hoshikaId)
                                }
                            )
                        }
                    }
                }

                LaunchedEffect(state.featuredAnime?.hoshikaId, heroPlayFocusRequester.isAttached) {
                    if (!hasRequestedInitialHeroFocus &&
                        !state.shouldRestoreCardFocus &&
                        state.featuredAnime != null &&
                        heroPlayFocusRequester.isAttached
                    ) {
                        hasRequestedInitialHeroFocus = true
                        heroPlayFocusRequester.requestFocusSafely()
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
    heroHeight: Dp,
    contentMaxWidth: Dp,
    logoMaxWidth: Dp,
    logoMaxHeight: Dp,
    playFocusRequester: SafeFocusRequester,
    downFocusRequester: SafeFocusRequester?,
    onHeroFocused: () -> Unit,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onLogoLoadFailed: (String) -> Unit
) {
    val context = LocalContext.current
    val deviceProfile = remember(context) { TvDeviceCapabilities.getDeviceProfile(context) }

    val heroButtonFocusModifier = Modifier
        .then(
            if (downFocusRequester != null) {
                Modifier.focusProperties { down = downFocusRequester.orDefault() }
            } else {
                Modifier
            }
        )
        .onFocusChanged { focusState ->
            if (focusState.isFocused) {
                onHeroFocused()
            }
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(RectangleShape)
            .background(TvColors.SecondaryBackground)
            .testTag("home_hero_section")
    ) {
        // Wide Hoshika backdrop artwork (falls back cleanly to poster if banner missing or fails)
        TvArtworkImage(
            primaryUrl = anime.bannerUrl,
            fallbackUrl = anime.posterUrl,
            artworkType = ArtworkType.BANNER,
            screenName = "HomeHero",
            hoshikaId = anime.hoshikaId,
            contentDescription = anime.preferredTitle,
            targetWidthPx = deviceProfile.recommendedBannerWidthPx,
            targetHeightPx = deviceProfile.recommendedBannerHeightPx,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
            imageModifier = Modifier
                .fillMaxSize()
                .testTag("home_hero_backdrop")
        )

        // Cinematic dark gradient overlays (left-to-right and top-to-bottom) for contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            TvColors.MainBackground,
                            TvColors.MainBackground.copy(alpha = 0.88f),
                            TvColors.MainBackground.copy(alpha = 0.42f),
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
                            TvColors.MainBackground.copy(alpha = 0.25f),
                            Color.Transparent,
                            TvColors.MainBackground.copy(alpha = 0.72f),
                            TvColors.MainBackground
                        )
                    )
                )
        )

        // Hero content column strictly bounded inside the hero container
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxHeight()
                .widthIn(max = contentMaxWidth)
                .padding(horizontal = 32.dp, vertical = 18.dp)
                .testTag("home_hero_content"),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            // 1. Transparent Title Logo from TMDB/Fanart or clean white typography fallback
            AnimeLogo(
                hoshikaId = anime.hoshikaId,
                animeTitle = anime.preferredTitle,
                logoUrl = anime.logoUrl,
                contentDescription = anime.preferredTitle,
                maxWidth = logoMaxWidth,
                maxHeight = logoMaxHeight,
                onLogoLoadFailed = onLogoLoadFailed
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Metadata row: Year • Status • Format • Genres
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
                    color = TvColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("home_hero_metadata")
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 3. Short description (2–3 lines max)
            if (!anime.description.isNullOrBlank()) {
                Text(
                    text = anime.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("home_hero_description")
                )
                Spacer(modifier = Modifier.height(14.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 4. Action Buttons: Play, Details, Add to My List
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvFocusableButton(
                    text = "Play",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    onClick = onPlay,
                    modifier = heroButtonFocusModifier
                        .safeFocusRequester(playFocusRequester)
                        .testTag("hero_play_button")
                )
                TvFocusableButton(
                    text = "Details",
                    icon = Icons.Default.Info,
                    onClick = onDetails,
                    modifier = heroButtonFocusModifier.testTag("hero_details_button")
                )
                TvFocusableButton(
                    text = if (isInWatchlist) "In My List" else "Add to My List",
                    icon = if (isInWatchlist) Icons.Default.Check else Icons.Default.Add,
                    onClick = onToggleWatchlist,
                    modifier = heroButtonFocusModifier.testTag("hero_watchlist_button")
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingHomeRow(
    items: List<WatchProgressItem>,
    firstCardFocusRequester: SafeFocusRequester?,
    upFocusRequester: SafeFocusRequester?,
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
            itemsIndexed(
                items = items,
                key = { _, item -> "cw_${item.hoshikaId}_${item.episodeNumber}" }
            ) { index, item ->
                val cardAnime = Anime(
                    hoshikaId = item.hoshikaId,
                    anilistId = item.anilistId,
                    title = item.animeTitle,
                    englishTitle = item.animeTitle,
                    posterUrl = item.posterUrl,
                    bannerUrl = item.bannerUrl,
                    logoUrl = item.logoUrl
                )
                val cardFocusMod = Modifier
                    .then(
                        if (upFocusRequester != null) {
                            Modifier.focusProperties { up = upFocusRequester.orDefault() }
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (index == 0 && firstCardFocusRequester != null) {
                            Modifier.safeFocusRequester(firstCardFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                TvAnimePosterCard(
                    anime = cardAnime,
                    subtitleBadge = "EP ${item.episodeNumber} • ${item.language.uppercase()}",
                    progressFraction = item.progressFraction,
                    onClick = { onResumeItem(item) },
                    modifier = cardFocusMod.testTag("continue_watching_card_${item.hoshikaId}")
                )
            }
        }
    }
}

@Composable
private fun HomeAnimeContentRow(
    title: String,
    items: List<Anime>,
    firstCardFocusRequester: SafeFocusRequester?,
    upFocusRequester: SafeFocusRequester?,
    restoredFocusId: Long,
    onConsumeRestoreFocus: () -> Unit,
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
            itemsIndexed(
                items = items,
                key = { _, anime -> "${title}_${anime.hoshikaId}" }
            ) { index, anime ->
                val cardFocusRequester = remember(anime.hoshikaId, index, firstCardFocusRequester) {
                    if (index == 0 && firstCardFocusRequester != null) {
                        firstCardFocusRequester
                    } else {
                        SafeFocusRequester()
                    }
                }
                LaunchedEffect(restoredFocusId, cardFocusRequester.isAttached) {
                    if (restoredFocusId == anime.hoshikaId && cardFocusRequester.isAttached) {
                        onConsumeRestoreFocus()
                        cardFocusRequester.requestFocusSafely()
                    }
                }
                val upMod = if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester.orDefault() }
                } else {
                    Modifier
                }
                TvAnimePosterCard(
                    anime = anime,
                    onClick = { onAnimeClick(anime) },
                    onFocused = { onAnimeFocused(anime) },
                    modifier = upMod
                        .safeFocusRequester(cardFocusRequester)
                        .testTag("home_card_${anime.hoshikaId}")
                )
            }
        }
    }
}
