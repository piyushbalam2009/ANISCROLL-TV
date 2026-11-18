package `in`.aniscroll.tv.features.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.aniscroll.tv.core.designsystem.SafeFocusRequester
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvAnimeRowSkeleton
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy
import `in`.aniscroll.tv.core.designsystem.TvEmptyStateBanner
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvPosterGridSkeleton
import `in`.aniscroll.tv.core.designsystem.TvSectionLoadingHeader
import `in`.aniscroll.tv.core.designsystem.rememberSafeFocusRequester
import `in`.aniscroll.tv.core.designsystem.safeFocusRequester
import `in`.aniscroll.tv.data.repository.DiscoverQueryFilter
import `in`.aniscroll.tv.data.repository.DiscoverSectionData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    onPlayEpisode: (Long, Int, String?) -> Unit = { hoshikaId, _, _ -> onOpenAnimeDetails(hoshikaId) },
    onOpenSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val searchFocusRequester = rememberSafeFocusRequester()
    val allGenreChipFocusRequester = rememberSafeFocusRequester()
    val filterApplyFocusRequester = rememberSafeFocusRequester()
    val heroWatchFocusRequester = rememberSafeFocusRequester()
    val firstSectionFirstCardFocusRequester = rememberSafeFocusRequester()
    var hasRequestedInitialFocus by rememberSaveable { mutableStateOf(false) }

    val landingListState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.savedLandingScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedLandingScrollOffset
    )
    val filteredGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = viewModel.savedGridScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedGridScrollOffset
    )

    // Persist scroll offsets cleanly when navigating to Details or Search
    LaunchedEffect(landingListState) {
        snapshotFlow {
            landingListState.firstVisibleItemIndex to landingListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (idx, off) ->
                viewModel.updateLandingScrollPosition(idx, off)
            }
    }

    LaunchedEffect(filteredGridState) {
        snapshotFlow {
            filteredGridState.firstVisibleItemIndex to filteredGridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (idx, off) ->
                viewModel.updateGridScrollPosition(idx, off)
            }
    }

    // Back closes filter panel first, then exits filtered mode back to curated Discover rows
    BackHandler(enabled = state.isFilterPanelExpanded) {
        viewModel.closeFilterPanel()
    }
    BackHandler(enabled = !state.isFilterPanelExpanded && state.isFilteredMode) {
        viewModel.resetFilters()
    }

    // Auto-rotate featured hero spotlight gently when unfocused and panel closed
    LaunchedEffect(
        state.heroItems.size,
        state.selectedHeroIndex,
        state.isHeroFocused,
        state.isFilterPanelExpanded,
        state.isFilteredMode
    ) {
        if (state.heroItems.size > 1 &&
            !state.isHeroFocused &&
            !state.isFilterPanelExpanded &&
            !state.isFilteredMode
        ) {
            delay(8500L)
            viewModel.nextHero()
        }
    }

    // Move D-pad focus into Apply button when Filter Panel opens
    LaunchedEffect(state.isFilterPanelExpanded, filterApplyFocusRequester.isAttached) {
        if (state.isFilterPanelExpanded && filterApplyFocusRequester.isAttached) {
            filterApplyFocusRequester.requestFocusSafely()
        }
    }

    // Set initial landing focus once hero finishes loading (without overriding return-from-details restoration)
    LaunchedEffect(
        state.isInitialLoading,
        state.heroItems.size,
        heroWatchFocusRequester.isAttached,
        state.shouldRestoreCardFocus,
        state.isFilteredMode
    ) {
        if (!hasRequestedInitialFocus &&
            !state.shouldRestoreCardFocus &&
            !state.isInitialLoading &&
            !state.isFilteredMode &&
            state.heroItems.isNotEmpty() &&
            heroWatchFocusRequester.isAttached
        ) {
            hasRequestedInitialFocus = true
            heroWatchFocusRequester.requestFocusSafely()
        }
    }

    val headerSubtitle = remember(state.appliedFilter, state.filteredTotalCount, state.isFromOfflineCache) {
        when {
            state.appliedFilter.hasActiveFilters -> buildFilterSummaryLabel(
                filter = state.appliedFilter,
                totalCount = state.filteredTotalCount
            )
            state.isFromOfflineCache -> "Offline Cache Active • Showing locally saved anime"
            else -> "Curated collections, seasonal picks, and genre explorer"
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .testTag("discover_screen_root")
    ) {
        val heroHeight = (maxHeight * 0.44f).coerceIn(232.dp, 316.dp)
        val heroContentMaxWidth = (maxWidth * 0.62f).coerceIn(360.dp, 640.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 18.dp)
        ) {
            DiscoverHeaderBar(
                subtitle = headerSubtitle,
                activeFilterCount = state.appliedFilter.activeFilterCount,
                isFilterPanelExpanded = state.isFilterPanelExpanded,
                searchFocusRequester = searchFocusRequester,
                onOpenSearch = onOpenSearch,
                onToggleFilters = { viewModel.toggleFilterPanel() },
                onResetFilters = { viewModel.resetFilters() },
                hasActiveFilters = state.isFilteredMode
            )

            Spacer(modifier = Modifier.height(10.dp))

            DiscoverGenreExplorerRow(
                genres = state.genres,
                selectedGenre = state.appliedFilter.genre,
                allChipFocusRequester = allGenreChipFocusRequester,
                upFocusRequester = searchFocusRequester,
                downFocusRequester = when {
                    state.isFilterPanelExpanded -> filterApplyFocusRequester
                    !state.isFilteredMode && state.heroItems.isNotEmpty() -> heroWatchFocusRequester
                    !state.isFilteredMode && state.sections.isNotEmpty() -> firstSectionFirstCardFocusRequester
                    else -> null
                },
                onSelectGenre = { genre -> viewModel.selectGenre(genre) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            DiscoverFilterPanel(
                visible = state.isFilterPanelExpanded,
                draftFilter = state.draftFilter,
                genres = state.genres,
                applyFocusRequester = filterApplyFocusRequester,
                onUpdateGenre = { viewModel.updateDraftGenre(it) },
                onUpdateFormat = { viewModel.updateDraftFormat(it) },
                onUpdateStatus = { viewModel.updateDraftStatus(it) },
                onUpdateYear = { viewModel.updateDraftYear(it) },
                onUpdateMinRating = { viewModel.updateDraftMinRating(it) },
                onUpdateSort = { viewModel.updateDraftSort(it) },
                onApply = { viewModel.applyDraftFilters() },
                onReset = { viewModel.resetFilters() },
                onClose = { viewModel.closeFilterPanel() }
            )

            // Main content area: switches smoothly between Filtered Catalog Grid and Curated Discovery Shelves
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.isFilteredMode) {
                    DiscoverFilteredContent(
                        state = state,
                        gridState = filteredGridState,
                        onOpenAnimeDetails = { anime ->
                            viewModel.markCardClickedForReturnFocus("filtered_grid", anime.hoshikaId)
                            onOpenAnimeDetails(anime.hoshikaId)
                        },
                        onFocusedAnime = { anime ->
                            viewModel.recordFocusedCard("filtered_grid", anime.hoshikaId)
                        },
                        onConsumeRestoreFocus = { viewModel.consumeCardFocusRestore() },
                        onLoadMore = { viewModel.loadMoreFiltered() },
                        onRetry = { viewModel.retryFilteredQuery() },
                        onResetFilters = { viewModel.resetFilters() }
                    )
                } else {
                    DiscoverLandingContent(
                        state = state,
                        listState = landingListState,
                        heroHeight = heroHeight,
                        heroContentMaxWidth = heroContentMaxWidth,
                        genreFocusRequester = allGenreChipFocusRequester,
                        heroWatchFocusRequester = heroWatchFocusRequester,
                        firstSectionFirstCardFocusRequester = firstSectionFirstCardFocusRequester,
                        onHeroFocusChanged = { focused -> viewModel.setHeroFocused(focused) },
                        onSelectHeroIndex = { index -> viewModel.selectHeroIndex(index) },
                        onPreviousHero = { viewModel.previousHero() },
                        onNextHero = { viewModel.nextHero() },
                        onWatchNow = { anime -> onPlayEpisode(anime.hoshikaId, 1, null) },
                        onOpenAnimeDetails = { sectionId, anime ->
                            viewModel.markCardClickedForReturnFocus(sectionId, anime.hoshikaId)
                            onOpenAnimeDetails(anime.hoshikaId)
                        },
                        onFocusedAnime = { sectionId, anime ->
                            viewModel.recordFocusedCard(sectionId, anime.hoshikaId)
                        },
                        onConsumeRestoreFocus = { viewModel.consumeCardFocusRestore() },
                        onLoadMoreDiscoverQueue = { viewModel.loadMoreDiscoverQueue() },
                        onRetryLanding = { viewModel.loadLandingFeed(forceRefresh = true) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverLandingContent(
    state: DiscoverUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    heroHeight: androidx.compose.ui.unit.Dp,
    heroContentMaxWidth: androidx.compose.ui.unit.Dp,
    genreFocusRequester: SafeFocusRequester,
    heroWatchFocusRequester: SafeFocusRequester,
    firstSectionFirstCardFocusRequester: SafeFocusRequester,
    onHeroFocusChanged: (Boolean) -> Unit,
    onSelectHeroIndex: (Int) -> Unit,
    onPreviousHero: () -> Unit,
    onNextHero: () -> Unit,
    onWatchNow: ( `in`.aniscroll.tv.domain.model.Anime ) -> Unit,
    onOpenAnimeDetails: (String, `in`.aniscroll.tv.domain.model.Anime) -> Unit,
    onFocusedAnime: (String, `in`.aniscroll.tv.domain.model.Anime) -> Unit,
    onConsumeRestoreFocus: () -> Unit,
    onLoadMoreDiscoverQueue: () -> Unit,
    onRetryLanding: () -> Unit
) {
    when {
        state.isInitialLoading && state.sections.isEmpty() && state.heroItems.isEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .height(176.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(`in`.aniscroll.tv.core.designsystem.rememberMonochromeShimmerBrush())
                        .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
                ) {
                    TvSectionLoadingHeader(
                        label = TvContentLoadingCopy.LOADING_ANIME,
                        detailText = "Building personalized Discover shelves...",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    )
                }
                TvAnimeRowSkeleton(sectionLabel = TvContentLoadingCopy.LOADING_ANIME, cardsCount = 6)
                TvAnimeRowSkeleton(cardsCount = 6)
            }
        }
        state.landingErrorMessage != null && state.sections.isEmpty() && state.heroItems.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                TvErrorBanner(
                    message = state.landingErrorMessage,
                    onRetry = onRetryLanding,
                    modifier = Modifier.widthIn(max = 520.dp)
                )
            }
        }
        else -> {
            val discoverMoreSection = remember(state.discoverQueueItems) {
                if (state.discoverQueueItems.isNotEmpty()) {
                    DiscoverSectionData(
                        id = "more_discoverable",
                        title = "More Discoverable Anime",
                        subtitle = null,
                        items = state.discoverQueueItems
                    )
                } else {
                    null
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover_landing_column"),
                contentPadding = PaddingValues(bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                if (state.isRefreshing) {
                    item(key = "discover_refreshing_banner") {
                        Box(modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) {
                            TvSectionLoadingHeader(
                                label = TvContentLoadingCopy.LOADING_ANIME,
                                detailText = "Refreshing Discover showcase..."
                            )
                        }
                    }
                }

                if (state.heroItems.isNotEmpty()) {
                    item(key = "discover_featured_hero") {
                        DiscoverFeaturedHeroCarousel(
                            heroItems = state.heroItems,
                            selectedIndex = state.selectedHeroIndex,
                            heroHeight = heroHeight,
                            contentMaxWidth = heroContentMaxWidth,
                            watchFocusRequester = heroWatchFocusRequester,
                            upFocusRequester = genreFocusRequester,
                            downFocusRequester = if (state.sections.isNotEmpty() || discoverMoreSection != null) {
                                firstSectionFirstCardFocusRequester
                            } else {
                                null
                            },
                            onHeroFocusChanged = onHeroFocusChanged,
                            onSelectIndex = onSelectHeroIndex,
                            onPrevious = onPreviousHero,
                            onNext = onNextHero,
                            onWatchNow = onWatchNow,
                            onOpenDetails = { anime -> onOpenAnimeDetails("hero", anime) }
                        )
                    }
                }

                itemsIndexed(
                    items = state.sections,
                    key = { _, section -> "discover_sec_${section.id}" }
                ) { rowIndex, section ->
                    val isFirstRow = rowIndex == 0
                    DiscoverCuratedRow(
                        section = section,
                        firstCardFocusRequester = if (isFirstRow) firstSectionFirstCardFocusRequester else null,
                        upFocusRequester = if (isFirstRow && state.heroItems.isNotEmpty()) {
                            heroWatchFocusRequester
                        } else if (isFirstRow) {
                            genreFocusRequester
                        } else {
                            null
                        },
                        restoredFocusSectionId = if (state.shouldRestoreCardFocus) state.lastFocusedSectionId else null,
                        restoredFocusAnimeId = state.lastFocusedAnimeId,
                        onConsumeRestoreFocus = onConsumeRestoreFocus,
                        onAnimeClick = { anime -> onOpenAnimeDetails(section.id, anime) },
                        onAnimeFocused = { anime -> onFocusedAnime(section.id, anime) }
                    )
                }

                if (discoverMoreSection != null) {
                    item(key = "discover_sec_more_discoverable") {
                        val isFirstRow = state.sections.isEmpty()
                        DiscoverCuratedRow(
                            section = discoverMoreSection,
                            firstCardFocusRequester = if (isFirstRow) firstSectionFirstCardFocusRequester else null,
                            upFocusRequester = if (isFirstRow && state.heroItems.isNotEmpty()) {
                                heroWatchFocusRequester
                            } else {
                                null
                            },
                            restoredFocusSectionId = if (state.shouldRestoreCardFocus) state.lastFocusedSectionId else null,
                            restoredFocusAnimeId = state.lastFocusedAnimeId,
                            isLoadingMoreAtEnd = state.isLoadingMoreDiscoverQueue,
                            hasMoreAtEnd = state.hasMoreDiscoverQueue,
                            loadMoreError = state.discoverQueueError,
                            onLoadMoreAtEnd = onLoadMoreDiscoverQueue,
                            onConsumeRestoreFocus = onConsumeRestoreFocus,
                            onAnimeClick = { anime -> onOpenAnimeDetails(discoverMoreSection.id, anime) },
                            onAnimeFocused = { anime -> onFocusedAnime(discoverMoreSection.id, anime) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverFilteredContent(
    state: DiscoverUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onOpenAnimeDetails: (`in`.aniscroll.tv.domain.model.Anime) -> Unit,
    onFocusedAnime: (`in`.aniscroll.tv.domain.model.Anime) -> Unit,
    onConsumeRestoreFocus: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onResetFilters: () -> Unit
) {
    // Auto-trigger next page as user approaches end of grid
    val shouldLoadMore by remember(state.filteredItems.size, state.hasMoreFiltered) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.hasMoreFiltered &&
                state.filteredItems.isNotEmpty() &&
                lastVisible >= (state.filteredItems.size - 6).coerceAtLeast(0)
        }
    }

    LaunchedEffect(
        shouldLoadMore,
        state.isFilteredLoading,
        state.isFilteredLoadingMore,
        state.filteredErrorMessage
    ) {
        if (shouldLoadMore &&
            !state.isFilteredLoading &&
            !state.isFilteredLoadingMore &&
            state.filteredErrorMessage == null
        ) {
            onLoadMore()
        }
    }

    when {
        state.isFilteredLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 12.dp)
            ) {
                TvPosterGridSkeleton(
                    title = TvContentLoadingCopy.LOADING_ANIME,
                    subtitle = buildFilterSummaryLabel(state.appliedFilter, totalCount = null)
                )
            }
        }
        state.filteredErrorMessage != null && state.filteredItems.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                TvErrorBanner(
                    message = state.filteredErrorMessage,
                    onRetry = onRetry,
                    modifier = Modifier.widthIn(max = 520.dp)
                )
            }
        }
        state.filteredItems.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                TvEmptyStateBanner(
                    title = "No anime matched these filters.",
                    subtitle = "Try broadening the genre, release year, format, or rating threshold.",
                    actionLabel = "Reset Filters",
                    onAction = onResetFilters
                )
            }
        }
        else -> {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 132.dp),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("discover_filtered_grid")
            ) {
                items(
                    items = state.filteredItems,
                    key = { anime -> "filtered_${anime.hoshikaId}" }
                ) { anime ->
                    val cardFocusRequester = remember(anime.hoshikaId) { SafeFocusRequester() }
                    LaunchedEffect(
                        state.shouldRestoreCardFocus,
                        state.lastFocusedSectionId,
                        state.lastFocusedAnimeId,
                        cardFocusRequester.isAttached
                    ) {
                        if (state.shouldRestoreCardFocus &&
                            state.lastFocusedSectionId == "filtered_grid" &&
                            state.lastFocusedAnimeId == anime.hoshikaId &&
                            cardFocusRequester.isAttached
                        ) {
                            onConsumeRestoreFocus()
                            cardFocusRequester.requestFocusSafely()
                        }
                    }

                    TvAnimePosterCard(
                        anime = anime,
                        onClick = { onOpenAnimeDetails(anime) },
                        onFocused = { onFocusedAnime(anime) },
                        modifier = Modifier
                            .safeFocusRequester(cardFocusRequester)
                            .testTag("discover_filtered_card_${anime.hoshikaId}")
                    )
                }

                if (state.isFilteredLoadingMore || state.hasMoreFiltered || state.filteredErrorMessage != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when {
                                state.isFilteredLoadingMore -> {
                                    TvSectionLoadingHeader(
                                        label = TvContentLoadingCopy.LOADING_ANIME,
                                        detailText = "Loading page ${state.filteredPage + 1}..."
                                    )
                                }
                                state.filteredErrorMessage != null -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = state.filteredErrorMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TvColors.SecondaryText
                                        )
                                        TvFocusableButton(
                                            text = "Retry Page",
                                            onClick = onRetry,
                                            modifier = Modifier.testTag("discover_filtered_retry_page_button")
                                        )
                                    }
                                }
                                state.hasMoreFiltered -> {
                                    TvFocusableButton(
                                        text = "Load More",
                                        onClick = onLoadMore,
                                        modifier = Modifier.testTag("discover_load_more_button")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildFilterSummaryLabel(filter: DiscoverQueryFilter, totalCount: Int?): String {
    val parts = buildList {
        filter.genre?.let { add(it) }
        filter.format?.let { add(it.replace("_", " ")) }
        filter.year?.let { add(it.toString()) }
        when (filter.status) {
            "RELEASING" -> add("Airing")
            "FINISHED" -> add("Finished")
            "NOT_YET_RELEASED" -> add("Upcoming")
        }
        filter.minRatingOutOf10?.let { add("${it}+ Rating") }
        add("Sort: ${filter.sortBy.label}")
    }
    val prefix = parts.joinToString(" • ")
    return if (totalCount != null && totalCount > 0) {
        "$prefix  ($totalCount+ titles)"
    } else {
        prefix
    }
}
