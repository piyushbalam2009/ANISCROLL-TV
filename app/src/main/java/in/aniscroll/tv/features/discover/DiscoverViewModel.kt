package `in`.aniscroll.tv.features.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.DiscoverQueryFilter
import `in`.aniscroll.tv.data.repository.DiscoverSortKey
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.domain.model.Anime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class DiscoverViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var landingJob: Job? = null
    private var filteredJob: Job? = null
    private var discoverQueueJob: Job? = null

    // Saved scroll coordinates across Back navigation from Details / Search
    var savedLandingScrollIndex: Int = 0
        private set
    var savedLandingScrollOffset: Int = 0
        private set
    var savedGridScrollIndex: Int = 0
        private set
    var savedGridScrollOffset: Int = 0
        private set

    init {
        loadLandingFeed()
    }

    fun updateLandingScrollPosition(index: Int, offset: Int) {
        savedLandingScrollIndex = index
        savedLandingScrollOffset = offset
    }

    fun updateGridScrollPosition(index: Int, offset: Int) {
        savedGridScrollIndex = index
        savedGridScrollOffset = offset
    }

    fun loadLandingFeed(forceRefresh: Boolean = false) {
        if (!forceRefresh && landingJob?.isActive == true) return
        landingJob?.cancel()
        landingJob = viewModelScope.launch {
            val current = _uiState.value
            val hasContent = current.sections.isNotEmpty() || current.heroItems.isNotEmpty()
            _uiState.update {
                it.copy(
                    isInitialLoading = !hasContent,
                    isRefreshing = hasContent,
                    landingErrorMessage = null
                )
            }

            val feed = animeRepository.getDiscoverLandingFeed()
            _uiState.update { state ->
                val resolvedHeroIndex = if (feed.heroItems.isNotEmpty()) {
                    state.selectedHeroIndex.coerceIn(0, feed.heroItems.lastIndex)
                } else {
                    0
                }
                state.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    isFromOfflineCache = feed.isFromOfflineCache,
                    landingErrorMessage = if (feed.sections.isEmpty() && feed.heroItems.isEmpty()) {
                        feed.errorMessage
                    } else {
                        null
                    },
                    heroItems = feed.heroItems.ifEmpty { state.heroItems },
                    selectedHeroIndex = resolvedHeroIndex,
                    sections = feed.sections.ifEmpty { state.sections },
                    genres = feed.genres.ifEmpty { state.genres },
                    seasonLabel = feed.seasonLabel ?: state.seasonLabel,
                    discoverQueueItems = feed.discoverQueueItems.ifEmpty { state.discoverQueueItems },
                    discoverNextOffset = feed.discoverNextOffset ?: state.discoverNextOffset,
                    hasMoreDiscoverQueue = feed.hasMoreDiscover,
                    discoverQueueError = null
                )
            }
        }
    }

    fun setHeroFocused(focused: Boolean) {
        if (_uiState.value.isHeroFocused != focused) {
            _uiState.update { it.copy(isHeroFocused = focused) }
        }
    }

    fun selectHeroIndex(index: Int) {
        val count = _uiState.value.heroItems.size
        if (count <= 0) return
        _uiState.update {
            it.copy(selectedHeroIndex = ((index % count) + count) % count)
        }
    }

    fun nextHero() {
        val current = _uiState.value
        if (current.heroItems.size <= 1) return
        selectHeroIndex(current.selectedHeroIndex + 1)
    }

    fun previousHero() {
        val current = _uiState.value
        if (current.heroItems.size <= 1) return
        selectHeroIndex(current.selectedHeroIndex - 1)
    }

    fun selectGenre(genre: String?) {
        val current = _uiState.value
        val targetGenre = when {
            genre == null -> null
            current.appliedFilter.genre.equals(genre, ignoreCase = true) -> null
            else -> genre
        }
        val nextFilter = current.appliedFilter.copy(genre = targetGenre)
        applyFilterImmediately(nextFilter)
    }

    fun toggleFilterPanel() {
        _uiState.update { state ->
            val opening = !state.isFilterPanelExpanded
            state.copy(
                isFilterPanelExpanded = opening,
                draftFilter = if (opening) state.appliedFilter else state.draftFilter
            )
        }
    }

    fun closeFilterPanel() {
        if (_uiState.value.isFilterPanelExpanded) {
            _uiState.update { it.copy(isFilterPanelExpanded = false) }
        }
    }

    fun updateDraftGenre(genre: String?) {
        _uiState.update {
            it.copy(draftFilter = it.draftFilter.copy(genre = genre))
        }
    }

    fun updateDraftFormat(format: String?) {
        _uiState.update {
            it.copy(draftFilter = it.draftFilter.copy(format = format))
        }
    }

    fun updateDraftStatus(status: String?) {
        _uiState.update {
            it.copy(draftFilter = it.draftFilter.copy(status = status))
        }
    }

    fun updateDraftYear(year: Int?) {
        _uiState.update {
            it.copy(draftFilter = it.draftFilter.copy(year = year))
        }
    }

    fun updateDraftMinRating(minRating: Double?) {
        _uiState.update {
            it.copy(draftFilter = it.draftFilter.copy(minRatingOutOf10 = minRating))
        }
    }

    fun updateDraftSort(sortBy: DiscoverSortKey) {
        _uiState.update {
            it.copy(draftFilter = it.draftFilter.copy(sortBy = sortBy))
        }
    }

    fun applyDraftFilters() {
        val draft = _uiState.value.draftFilter
        _uiState.update { it.copy(isFilterPanelExpanded = false) }
        applyFilterImmediately(draft)
    }

    fun resetFilters() {
        val empty = DiscoverQueryFilter()
        filteredJob?.cancel()
        savedGridScrollIndex = 0
        savedGridScrollOffset = 0
        _uiState.update {
            it.copy(
                appliedFilter = empty,
                draftFilter = empty,
                isFilterPanelExpanded = false,
                isFilteredLoading = false,
                isFilteredLoadingMore = false,
                filteredItems = emptyList(),
                filteredPage = 1,
                hasMoreFiltered = false,
                filteredTotalCount = null,
                filteredErrorMessage = null
            )
        }
    }

    private fun applyFilterImmediately(filter: DiscoverQueryFilter) {
        savedGridScrollIndex = 0
        savedGridScrollOffset = 0
        if (!filter.hasActiveFilters) {
            resetFilters()
            return
        }
        _uiState.update {
            it.copy(
                appliedFilter = filter,
                draftFilter = filter,
                filteredPage = 1,
                hasMoreFiltered = true,
                filteredErrorMessage = null
            )
        }
        executeFilteredQuery(resetPage = true)
    }

    fun loadMoreFiltered() {
        val current = _uiState.value
        if (!current.isFilteredMode ||
            current.isFilteredLoading ||
            current.isFilteredLoadingMore ||
            !current.hasMoreFiltered
        ) {
            return
        }
        _uiState.update { it.copy(isFilteredLoadingMore = true, filteredErrorMessage = null) }
        executeFilteredQuery(resetPage = false)
    }

    fun retryFilteredQuery() {
        val current = _uiState.value
        if (!current.isFilteredMode) {
            loadLandingFeed(forceRefresh = true)
            return
        }
        if (current.filteredItems.isEmpty()) {
            executeFilteredQuery(resetPage = true)
        } else {
            _uiState.update { it.copy(isFilteredLoadingMore = true, filteredErrorMessage = null) }
            executeFilteredQuery(resetPage = false)
        }
    }

    private fun executeFilteredQuery(resetPage: Boolean) {
        filteredJob?.cancel()
        filteredJob = viewModelScope.launch {
            val startState = _uiState.value
            val filter = startState.appliedFilter
            if (!filter.hasActiveFilters) return@launch

            if (resetPage) {
                _uiState.update {
                    it.copy(
                        isFilteredLoading = true,
                        isFilteredLoadingMore = false,
                        filteredErrorMessage = null,
                        filteredItems = emptyList(),
                        filteredPage = 1,
                        hasMoreFiltered = true,
                        filteredTotalCount = null
                    )
                }
            }

            var currentPageToFetch = if (resetPage) 1 else startState.filteredPage + 1
            val accumulatedNew = mutableListOf<Anime>()
            var lastHasMore = false
            var lastTotalCount: Int? = startState.filteredTotalCount
            var lastError: String? = null
            var anySuccess = false

            // Auto-advance up to 3 pages on initial reset if client-side minRating/client filter
            // narrows page 1 to a tiny batch while the catalog still has additional pages.
            val maxBatchPages = if (resetPage && filter.minRatingOutOf10 != null) 3 else 1
            var fetchedInBatch = 0
            var lastFetchedSuccessPage = if (resetPage) 1 else startState.filteredPage
            while (fetchedInBatch < maxBatchPages) {
                when (val res = animeRepository.queryDiscoverCatalog(filter, page = currentPageToFetch)) {
                    is ResourceResult.Success -> {
                        anySuccess = true
                        lastFetchedSuccessPage = currentPageToFetch
                        accumulatedNew.addAll(res.data.items)
                        lastHasMore = res.data.hasMore
                        if (res.data.total != null) {
                            lastTotalCount = res.data.total
                        }
                        fetchedInBatch++
                        if (fetchedInBatch >= maxBatchPages || !lastHasMore || accumulatedNew.size >= 12) {
                            break
                        }
                        currentPageToFetch++
                    }
                    is ResourceResult.Error -> {
                        lastError = res.userMessage
                        break
                    }
                }
            }

            if (anySuccess) {
                _uiState.update { state ->
                    val combinedRaw = if (resetPage) {
                        accumulatedNew
                    } else {
                        state.filteredItems + accumulatedNew
                    }.distinctBy { it.hoshikaId }

                    val finalItems = if (filter.sortBy == DiscoverSortKey.TITLE) {
                        combinedRaw.sortedBy { it.preferredTitle.lowercase(Locale.ROOT) }
                    } else {
                        combinedRaw
                    }

                    state.copy(
                        isFilteredLoading = false,
                        isFilteredLoadingMore = false,
                        filteredItems = finalItems,
                        filteredPage = lastFetchedSuccessPage,
                        hasMoreFiltered = lastHasMore,
                        filteredTotalCount = lastTotalCount,
                        filteredErrorMessage = null
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        isFilteredLoading = false,
                        isFilteredLoadingMore = false,
                        filteredErrorMessage = lastError ?: "Unable to load filtered results."
                    )
                }
            }
        }
    }

    fun loadMoreDiscoverQueue() {
        val current = _uiState.value
        val offset = current.discoverNextOffset ?: current.discoverQueueItems.size
        if (current.isLoadingMoreDiscoverQueue || !current.hasMoreDiscoverQueue) return
        if (discoverQueueJob?.isActive == true) return

        discoverQueueJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreDiscoverQueue = true, discoverQueueError = null) }
            when (val res = animeRepository.loadMoreDiscoverQueue(offset = offset)) {
                is ResourceResult.Success -> {
                    _uiState.update { state ->
                        val merged = (state.discoverQueueItems + res.data.items)
                            .distinctBy { it.hoshikaId }
                        state.copy(
                            isLoadingMoreDiscoverQueue = false,
                            discoverQueueItems = merged,
                            discoverNextOffset = res.data.nextOffset ?: (offset + res.data.items.size),
                            hasMoreDiscoverQueue = res.data.hasMore && res.data.items.isNotEmpty(),
                            discoverQueueError = null
                        )
                    }
                }
                is ResourceResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            isLoadingMoreDiscoverQueue = false,
                            discoverQueueError = res.userMessage
                        )
                    }
                }
            }
        }
    }

    fun recordFocusedCard(sectionId: String, hoshikaId: Long) {
        _uiState.update {
            it.copy(lastFocusedSectionId = sectionId, lastFocusedAnimeId = hoshikaId)
        }
    }

    fun markCardClickedForReturnFocus(sectionId: String, hoshikaId: Long) {
        _uiState.update {
            it.copy(
                lastFocusedSectionId = sectionId,
                lastFocusedAnimeId = hoshikaId,
                shouldRestoreCardFocus = true
            )
        }
    }

    fun consumeCardFocusRestore() {
        if (_uiState.value.shouldRestoreCardFocus) {
            _uiState.update { it.copy(shouldRestoreCardFocus = false) }
        }
    }
}
