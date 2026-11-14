package `in`.aniscroll.tv.features.details

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy
import `in`.aniscroll.tv.core.designsystem.TvContentLoadingScreen
import `in`.aniscroll.tv.core.designsystem.TvEpisodeListSkeleton
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvSectionLoadingHeader
import `in`.aniscroll.tv.data.local.UiPreviewFixtures
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.data.repository.StreamingSourceResolver
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import `in`.aniscroll.tv.features.episodes.EpisodeListRowItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailsUiState(
    val hoshikaId: Long = -1L,
    val isLoading: Boolean = true,
    val isEpisodesLoading: Boolean = false,
    val isLoadingMoreEpisodes: Boolean = false,
    val episodesPaginationError: String? = null,
    val anime: Anime? = null,
    val episodes: List<Episode> = emptyList(),
    val isInWatchlist: Boolean = false,
    val watchProgress: List<WatchProgressItem> = emptyList(),
    val lastWatchedEpisode: WatchProgressItem? = null,
    val errorMessage: String? = null
)

class DetailsViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var observersJob: Job? = null

    private var savedScrollAnimeId: Long = -1L
    private var savedScrollIndex: Int = 0
    private var savedScrollOffset: Int = 0
    private var shouldRestoreScrollOnReturn: Boolean = false

    /**
     * Records the current scroll position before navigating from the Detail Page to a child
     * destination (Player, Watch Order, or Episodes Browser) so returning via Back restores it.
     */
    fun saveScrollPositionForReturn(hoshikaId: Long, index: Int, offset: Int) {
        savedScrollAnimeId = hoshikaId
        savedScrollIndex = index.coerceAtLeast(0)
        savedScrollOffset = offset.coerceAtLeast(0)
        shouldRestoreScrollOnReturn = true
    }

    /**
     * Consumes any saved scroll state when returning to the same anime detail page,
     * or returns `(0, 0)` when opening an anime detail page fresh so it always starts at the top.
     */
    fun consumeInitialScrollPosition(hoshikaId: Long): Pair<Int, Int> {
        return if (shouldRestoreScrollOnReturn && savedScrollAnimeId == hoshikaId) {
            shouldRestoreScrollOnReturn = false
            savedScrollIndex to savedScrollOffset
        } else {
            shouldRestoreScrollOnReturn = false
            savedScrollAnimeId = hoshikaId
            savedScrollIndex = 0
            savedScrollOffset = 0
            0 to 0
        }
    }

    fun loadAnime(hoshikaId: Long, forceRefresh: Boolean = false) {
        val current = _uiState.value
        if (current.hoshikaId != hoshikaId) {
            // Switching to a different anime always resets saved scroll state to top
            savedScrollAnimeId = hoshikaId
            savedScrollIndex = 0
            savedScrollOffset = 0
            shouldRestoreScrollOnReturn = false
        }

        if (!forceRefresh &&
            current.hoshikaId == hoshikaId &&
            current.anime != null &&
            current.errorMessage == null &&
            current.episodesPaginationError == null &&
            !current.isLoading
        ) {
            return
        }

        loadJob?.cancel()
        observersJob?.cancel()

        val isSameAnimeRefresh = current.hoshikaId == hoshikaId && current.anime != null

        observersJob = viewModelScope.launch {
            launch {
                animeRepository.observeIsInWatchlist(hoshikaId).collect { inList ->
                    _uiState.update { it.copy(isInWatchlist = inList) }
                }
            }
            launch {
                animeRepository.observeWatchHistoryForAnime(hoshikaId).collect { history ->
                    val latest = history.maxByOrNull { it.lastWatched }
                    _uiState.update {
                        it.copy(
                            watchProgress = history,
                            lastWatchedEpisode = latest
                        )
                    }
                }
            }
        }

        loadJob = viewModelScope.launch {
            _uiState.update {
                if (isSameAnimeRefresh) {
                    it.copy(
                        isLoading = false,
                        isEpisodesLoading = it.episodes.isEmpty(),
                        isLoadingMoreEpisodes = it.episodes.isNotEmpty(),
                        episodesPaginationError = null,
                        errorMessage = null
                    )
                } else {
                    DetailsUiState(
                        hoshikaId = hoshikaId,
                        isLoading = true,
                        isEpisodesLoading = true,
                        isLoadingMoreEpisodes = false,
                        episodesPaginationError = null,
                        errorMessage = null
                    )
                }
            }

            // Check if this is one of the isolated UI preview fixture IDs
            val fixtureAnime = UiPreviewFixtures.sampleAnimeList.find { it.hoshikaId == hoshikaId }
            val animeResult = animeRepository.getAnimeDetails(hoshikaId)
            val resolvedAnime = when (animeResult) {
                is ResourceResult.Success -> animeResult.data
                is ResourceResult.Error -> fixtureAnime?.let { animeRepository.enrichAnimeWithLogo(it) }
            }

            if (resolvedAnime == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEpisodesLoading = false,
                        isLoadingMoreEpisodes = false,
                        errorMessage = (animeResult as? ResourceResult.Error)?.userMessage
                            ?: "Anime information is unavailable."
                    )
                }
                return@launch
            }

            // Immediately show the resolved anime details while episodes fetch in section-level partial loading
            _uiState.update {
                it.copy(
                    hoshikaId = hoshikaId,
                    isLoading = false,
                    isEpisodesLoading = it.episodes.isEmpty(),
                    anime = resolvedAnime,
                    errorMessage = null
                )
            }

            fetchAllEpisodesForAnime(
                hoshikaId = hoshikaId,
                resolvedAnime = resolvedAnime,
                fixtureAnime = fixtureAnime,
                forceRefresh = forceRefresh
            )
        }
    }

    fun retryLoadMoreEpisodes(hoshikaId: Long) {
        val current = _uiState.value
        val anime = current.anime ?: return
        if (current.hoshikaId != hoshikaId || current.isLoadingMoreEpisodes) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isEpisodesLoading = it.episodes.isEmpty(),
                    isLoadingMoreEpisodes = it.episodes.isNotEmpty(),
                    episodesPaginationError = null
                )
            }
            val fixtureAnime = UiPreviewFixtures.sampleAnimeList.find { it.hoshikaId == hoshikaId }
            fetchAllEpisodesForAnime(
                hoshikaId = hoshikaId,
                resolvedAnime = anime,
                fixtureAnime = fixtureAnime,
                forceRefresh = true
            )
        }
    }

    fun cancelActiveEpisodeRequests() {
        loadJob?.cancel()
        loadJob = null
        _uiState.update {
            it.copy(
                isEpisodesLoading = false,
                isLoadingMoreEpisodes = false
            )
        }
    }

    private suspend fun fetchAllEpisodesForAnime(
        hoshikaId: Long,
        resolvedAnime: Anime,
        fixtureAnime: Anime?,
        forceRefresh: Boolean
    ) {
        val episodesResult = animeRepository.getAnimeEpisodes(
            hoshikaId = hoshikaId,
            forceRefresh = forceRefresh,
            onPartialProgress = { partialList, hasMorePages ->
                _uiState.update { state ->
                    if (state.hoshikaId == hoshikaId) {
                        state.copy(
                            isLoading = false,
                            isEpisodesLoading = false,
                            isLoadingMoreEpisodes = hasMorePages,
                            episodes = partialList,
                            episodesPaginationError = null
                        )
                    } else {
                        state
                    }
                }
            }
        )

        when (episodesResult) {
            is ResourceResult.Success -> {
                val resolvedEpisodes = episodesResult.data
                val paginationErr = if (episodesResult.hasIncompletePagination) {
                    "Loaded ${resolvedEpisodes.size} episodes. Additional episodes could not be loaded."
                } else {
                    null
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEpisodesLoading = false,
                        isLoadingMoreEpisodes = false,
                        anime = resolvedAnime,
                        episodes = resolvedEpisodes,
                        episodesPaginationError = paginationErr,
                        errorMessage = null
                    )
                }
            }
            is ResourceResult.Error -> {
                val existing = _uiState.value.episodes
                val fallbackEpisodes = when {
                    existing.isNotEmpty() -> existing
                    fixtureAnime != null -> UiPreviewFixtures.getPreviewEpisodes(hoshikaId)
                    else -> emptyList()
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isEpisodesLoading = false,
                        isLoadingMoreEpisodes = false,
                        anime = resolvedAnime,
                        episodes = fallbackEpisodes,
                        episodesPaginationError = if (existing.isNotEmpty()) episodesResult.userMessage else null,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun onLogoLoadFailed(failedUrl: String) {
        val currentAnime = _uiState.value.anime ?: return
        viewModelScope.launch {
            val retried = animeRepository.invalidateAndRetryLogo(currentAnime, failedUrl)
            _uiState.update { state ->
                if (state.anime?.hoshikaId == currentAnime.hoshikaId) {
                    state.copy(anime = retried)
                } else {
                    state
                }
            }
        }
    }

    fun toggleWatchlist() {
        val anime = _uiState.value.anime ?: return
        viewModelScope.launch {
            animeRepository.toggleWatchlist(anime)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(
    hoshikaId: Long,
    viewModel: DetailsViewModel,
    onPlayEpisode: (Long, Int, String?) -> Unit,
    onOpenEpisodesScreen: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenWatchOrder: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val primaryActionFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    val initialScroll = remember(hoshikaId) {
        viewModel.consumeInitialScrollPosition(hoshikaId)
    }
    val isFreshTopOpen = remember(hoshikaId) {
        initialScroll.first == 0 && initialScroll.second == 0
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScroll.first,
        initialFirstVisibleItemScrollOffset = initialScroll.second
    )

    var isInitialFocusSettled by remember(hoshikaId) { mutableStateOf(false) }
    var isSynopsisExpanded by remember(hoshikaId) { mutableStateOf(false) }

    val density = LocalDensity.current
    val edgePaddingPx = remember(density) { with(density) { 24.dp.toPx() } }

    // Custom BringIntoViewSpec scoped to DetailsScreen so Android TV's PivotBringIntoViewSpec
    // does NOT scroll the hero banner and poster off-screen when Play Now receives focus,
    // while still scrolling naturally when the user navigates to off-screen controls/episodes.
    val detailsBringIntoViewSpec = remember(edgePaddingPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                if (!isInitialFocusSettled || containerSize <= 0f) {
                    return 0f
                }

                val leadingEdge = offset
                val trailingEdge = offset + size

                // If the focused element is already completely visible within the TV viewport,
                // keep the current scroll position (prevents unwanted pivot jumping).
                if (leadingEdge >= 0f && trailingEdge <= containerSize) {
                    return 0f
                }
                if (leadingEdge <= 0f && trailingEdge >= containerSize) {
                    return 0f
                }

                val margin = edgePaddingPx.coerceAtMost(containerSize * 0.1f)
                return when {
                    leadingEdge < margin -> leadingEdge - margin
                    trailingEdge > containerSize - margin -> {
                        val delta = trailingEdge - (containerSize - margin)
                        delta.coerceAtMost((leadingEdge - margin).coerceAtLeast(0f))
                    }
                    else -> 0f
                }
            }
        }
    }

    LaunchedEffect(hoshikaId) {
        viewModel.loadAnime(hoshikaId)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
    ) {
        when {
            state.isLoading && state.anime == null -> {
                TvContentLoadingScreen(
                    title = TvContentLoadingCopy.FETCHING_DETAILS,
                    subtitle = "Loading anime details...",
                    modifier = Modifier.fillMaxSize()
                )
            }
            state.errorMessage != null || state.anime == null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 520.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TvErrorBanner(
                        message = state.errorMessage ?: "Anime information is unavailable.",
                        onRetry = { viewModel.loadAnime(hoshikaId, forceRefresh = true) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TvFocusableButton(
                        text = "Back",
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onBack
                    )
                }
            }
            else -> {
                val anime = state.anime!!
                val bannerUrl = anime.bannerUrl ?: anime.posterUrl

                CompositionLocalProvider(LocalBringIntoViewSpec provides detailsBringIntoViewSpec) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("details_lazy_column"),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Item 0: Scrollable Hero Section (Banner + Poster + Title/Metadata) + Synopsis + Action Buttons
                        item(key = "details_hero_header") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Upper Hero Section: Backdrop Banner + Prominent Poster + Title & Metadata
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("details_hero_section")
                                ) {
                                    // Backdrop Banner visible at the top of the detail page
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .testTag("details_backdrop_banner")
                                    ) {
                                        if (!bannerUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(bannerUrl)
                                                    .crossfade(true)
                                                    .size(1280, 720)
                                                    .build(),
                                                contentDescription = "${anime.preferredTitle} banner",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        // Horizontal vignette for left-side text contrast while keeping right-side banner clear
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            TvColors.MainBackground.copy(alpha = 0.82f),
                                                            TvColors.MainBackground.copy(alpha = 0.48f),
                                                            TvColors.MainBackground.copy(alpha = 0.22f)
                                                        )
                                                    )
                                                )
                                        )

                                        // Vertical vignette that keeps the top banner bright and blends the bottom into pure black
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colorStops = arrayOf(
                                                            0.0f to TvColors.MainBackground.copy(alpha = 0.18f),
                                                            0.62f to TvColors.MainBackground.copy(alpha = 0.52f),
                                                            1.0f to TvColors.MainBackground
                                                        )
                                                    )
                                                )
                                        )
                                    }

                                    // Foreground Hero Content: Back Button + Poster + Title/Metadata within the Banner Area
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 36.dp, vertical = 14.dp)
                                    ) {
                                        TvFocusableButton(
                                            text = "Back",
                                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                                            onClick = onBack,
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused && isInitialFocusSettled &&
                                                        (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                                                    ) {
                                                        coroutineScope.launch {
                                                            listState.animateScrollToItem(0, 0)
                                                        }
                                                    }
                                                }
                                                .testTag("details_back_button")
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            // Anime Poster prominently displayed within the banner area
                                            Box(
                                                modifier = Modifier
                                                    .width(132.dp)
                                                    .aspectRatio(2f / 3f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(TvColors.CardBackground)
                                                    .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
                                                    .testTag("details_poster_image")
                                            ) {
                                                if (!anime.posterUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(anime.posterUrl)
                                                            .crossfade(true)
                                                            .build(),
                                                        contentDescription = anime.preferredTitle,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(10.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = anime.preferredTitle,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            color = TvColors.SecondaryText,
                                                            maxLines = 3,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }

                                            // Anime Title / Logo & Essential Metadata
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(bottom = 2.dp)
                                            ) {
                                                `in`.aniscroll.tv.artwork.AnimeLogo(
                                                    hoshikaId = anime.hoshikaId,
                                                    animeTitle = anime.preferredTitle,
                                                    logoUrl = anime.logoUrl,
                                                    contentDescription = anime.preferredTitle,
                                                    maxWidth = 340.dp,
                                                    maxHeight = 68.dp,
                                                    onLogoLoadFailed = { failedUrl ->
                                                        viewModel.onLogoLoadFailed(failedUrl)
                                                    }
                                                )

                                                Spacer(modifier = Modifier.height(6.dp))

                                                if (!anime.englishTitle.isNullOrBlank() && anime.englishTitle != anime.title) {
                                                    Text(
                                                        text = anime.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = TvColors.SecondaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (!anime.japaneseTitle.isNullOrBlank()) {
                                                    Text(
                                                        text = anime.japaneseTitle,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = TvColors.MutedText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                // Metadata badges (Release year, Format, Status, Total episodes)
                                                val metadataItems = buildList {
                                                    anime.releaseYear?.let { add("$it") }
                                                    anime.format?.takeIf { it.isNotBlank() }?.let { add(it) }
                                                    anime.status?.takeIf { it.isNotBlank() }?.let { add(it) }
                                                    anime.totalEpisodes?.takeIf { it > 0 }?.let { add("$it Episodes") }
                                                }
                                                if (metadataItems.isNotEmpty()) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        metadataItems.forEach { badge ->
                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(4.dp))
                                                                    .background(TvColors.ElevatedSurface.copy(alpha = 0.9f))
                                                                    .border(1.dp, TvColors.Divider, RoundedCornerShape(4.dp))
                                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                                            ) {
                                                                Text(
                                                                    text = badge,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = TvColors.PrimaryText
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                }

                                                if (anime.genres.isNotEmpty()) {
                                                    Text(
                                                        text = "Genres: ${anime.genres.joinToString(" • ")}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = TvColors.SecondaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Below Hero Section: Synopsis & Action Buttons
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 36.dp)
                                ) {
                                    if (!anime.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val synopsisInteraction = remember { MutableInteractionSource() }
                                        val isSynopsisFocused by synopsisInteraction.collectIsFocusedAsState()

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(TvColors.SecondaryBackground.copy(alpha = 0.78f))
                                                .border(
                                                    width = if (isSynopsisFocused) 1.5.dp else 1.dp,
                                                    color = if (isSynopsisFocused) TvColors.FocusOutline else TvColors.Divider,
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused && isInitialFocusSettled && !isSynopsisExpanded &&
                                                        (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                                                    ) {
                                                        coroutineScope.launch {
                                                            listState.animateScrollToItem(0, 0)
                                                        }
                                                    }
                                                }
                                                .onKeyEvent { event ->
                                                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                        (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                                                    ) {
                                                        isSynopsisExpanded = !isSynopsisExpanded
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                .focusable(interactionSource = synopsisInteraction)
                                                .clickable(
                                                    interactionSource = synopsisInteraction,
                                                    indication = null,
                                                    onClick = { isSynopsisExpanded = !isSynopsisExpanded }
                                                )
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                .testTag("details_synopsis_box")
                                        ) {
                                            Text(
                                                text = anime.description,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TvColors.SecondaryText,
                                                maxLines = if (isSynopsisExpanded) 12 else 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Action Buttons: 1. Play, 2. Watch Order, 3. Resume, 4. Episodes, 5. Add to My List
                                    val isMovie = StreamingSourceResolver.isMovie(anime.format)
                                    val scrollToTopOnActionFocus = Modifier.onFocusChanged { focusState ->
                                        if (focusState.isFocused && isInitialFocusSettled && !isSynopsisExpanded &&
                                            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                                        ) {
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(0, 0)
                                            }
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val firstEpNum = if (isMovie) 1 else (state.episodes.firstOrNull()?.number ?: 1)
                                        TvFocusableButton(
                                            text = if (isMovie) "Play Movie" else "Play Ep $firstEpNum",
                                            icon = Icons.Default.PlayArrow,
                                            isPrimary = true,
                                            focusRequester = primaryActionFocusRequester,
                                            onClick = {
                                                viewModel.saveScrollPositionForReturn(
                                                    hoshikaId = anime.hoshikaId,
                                                    index = listState.firstVisibleItemIndex,
                                                    offset = listState.firstVisibleItemScrollOffset
                                                )
                                                onPlayEpisode(anime.hoshikaId, firstEpNum, null)
                                            },
                                            modifier = scrollToTopOnActionFocus.testTag("details_play_button")
                                        )

                                        TvFocusableButton(
                                            text = "Watch Order",
                                            icon = Icons.AutoMirrored.Filled.List,
                                            onClick = {
                                                viewModel.saveScrollPositionForReturn(
                                                    hoshikaId = anime.hoshikaId,
                                                    index = listState.firstVisibleItemIndex,
                                                    offset = listState.firstVisibleItemScrollOffset
                                                )
                                                onOpenWatchOrder(anime.hoshikaId)
                                            },
                                            modifier = scrollToTopOnActionFocus.testTag("details_watch_order_button")
                                        )

                                        state.lastWatchedEpisode?.let { lastEp ->
                                            TvFocusableButton(
                                                text = if (isMovie) "Resume Movie" else "Resume Ep ${lastEp.episodeNumber}",
                                                icon = Icons.Default.Replay,
                                                onClick = {
                                                    viewModel.saveScrollPositionForReturn(
                                                        hoshikaId = anime.hoshikaId,
                                                        index = listState.firstVisibleItemIndex,
                                                        offset = listState.firstVisibleItemScrollOffset
                                                    )
                                                    onPlayEpisode(
                                                        anime.hoshikaId,
                                                        if (isMovie) 1 else lastEp.episodeNumber,
                                                        lastEp.language
                                                    )
                                                },
                                                modifier = scrollToTopOnActionFocus.testTag("details_resume_button")
                                            )
                                        }

                                        if (!isMovie) {
                                            TvFocusableButton(
                                                text = "Episodes",
                                                icon = Icons.AutoMirrored.Filled.List,
                                                onClick = {
                                                    viewModel.saveScrollPositionForReturn(
                                                        hoshikaId = anime.hoshikaId,
                                                        index = listState.firstVisibleItemIndex,
                                                        offset = listState.firstVisibleItemScrollOffset
                                                    )
                                                    onOpenEpisodesScreen(anime.hoshikaId)
                                                },
                                                modifier = scrollToTopOnActionFocus.testTag("details_episodes_button")
                                            )
                                        }

                                        TvFocusableButton(
                                            text = if (state.isInWatchlist) "In My List" else "Add to My List",
                                            icon = if (state.isInWatchlist) Icons.Default.Check else Icons.Default.Add,
                                            onClick = { viewModel.toggleWatchlist() },
                                            modifier = scrollToTopOnActionFocus.testTag("details_watchlist_button")
                                        )
                                    }
                                }
                            }
                        }

                        // Inline Episode List Preview (with section-level partial loading skeleton)
                        if (state.isEpisodesLoading && state.episodes.isEmpty()) {
                            item(key = "details_episodes_skeleton") {
                                Box(modifier = Modifier.padding(horizontal = 36.dp)) {
                                    TvEpisodeListSkeleton(
                                        title = TvContentLoadingCopy.LOADING_EPISODES,
                                        subtitle = "Loading episodes...",
                                        itemCount = 3
                                    )
                                }
                            }
                        } else if (state.episodes.isNotEmpty()) {
                            item(key = "details_episodes_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 36.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Episodes (${state.episodes.size})",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = TvColors.PrimaryText
                                    )
                                    TvFocusableButton(
                                        text = "Full Episode Browser",
                                        onClick = {
                                            viewModel.saveScrollPositionForReturn(
                                                hoshikaId = anime.hoshikaId,
                                                index = listState.firstVisibleItemIndex,
                                                offset = listState.firstVisibleItemScrollOffset
                                            )
                                            onOpenEpisodesScreen(anime.hoshikaId)
                                        }
                                    )
                                }
                            }

                            items(
                                items = state.episodes,
                                key = { "detail_ep_${it.number}_${it.id}" }
                            ) { episode ->
                                val epProgress = state.watchProgress.find { it.episodeNumber == episode.number }
                                Box(modifier = Modifier.padding(horizontal = 36.dp)) {
                                    EpisodeListRowItem(
                                        episode = episode,
                                        progressItem = epProgress,
                                        isCurrentlyPlaying = state.lastWatchedEpisode?.episodeNumber == episode.number &&
                                            state.lastWatchedEpisode?.completed == false,
                                        onClick = {
                                            viewModel.saveScrollPositionForReturn(
                                                hoshikaId = anime.hoshikaId,
                                                index = listState.firstVisibleItemIndex,
                                                offset = listState.firstVisibleItemScrollOffset
                                            )
                                            onPlayEpisode(anime.hoshikaId, episode.number, epProgress?.language)
                                        }
                                    )
                                }
                            }

                            if (state.isLoadingMoreEpisodes) {
                                item(key = "details_episodes_loading_more") {
                                    Box(modifier = Modifier.padding(horizontal = 36.dp)) {
                                        TvSectionLoadingHeader(
                                            label = TvContentLoadingCopy.LOADING_EPISODES,
                                            detailText = "Fetching more episodes (${state.episodes.size} loaded)...",
                                            modifier = Modifier.testTag("details_loading_more_episodes")
                                        )
                                    }
                                }
                            }

                            if (state.episodesPaginationError != null) {
                                item(key = "details_episodes_pagination_error") {
                                    Box(modifier = Modifier.padding(horizontal = 36.dp)) {
                                        TvErrorBanner(
                                            message = state.episodesPaginationError!!,
                                            onRetry = { viewModel.retryLoadMoreEpisodes(anime.hoshikaId) },
                                            modifier = Modifier.testTag("details_episodes_pagination_error_banner")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(anime.hoshikaId) {
                    if (isFreshTopOpen) {
                        listState.scrollToItem(0, 0)
                    }
                    withFrameNanos { }
                    runCatching { primaryActionFocusRequester.requestFocus() }
                    withFrameNanos { }
                    if (isFreshTopOpen &&
                        (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
                    ) {
                        listState.scrollToItem(0, 0)
                    }
                    isInitialFocusSettled = true
                }
            }
        }
    }
}
