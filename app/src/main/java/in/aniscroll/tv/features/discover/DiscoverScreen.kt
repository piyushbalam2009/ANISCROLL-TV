package `in`.aniscroll.tv.features.discover

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvLoadingIndicator
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.domain.model.Anime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val DISCOVER_CATEGORIES = listOf(
    "Trending",
    "Popular",
    "Latest",
    "Upcoming",
    "Seasonal"
)

val HOSHIKA_GENRES = listOf(
    "Action",
    "Adventure",
    "Comedy",
    "Drama",
    "Fantasy",
    "Isekai",
    "Romance",
    "Sci-Fi",
    "Supernatural",
    "Thriller"
)

data class DiscoverUiState(
    val selectedCategory: String = "Trending",
    val selectedGenre: String? = null,
    val page: Int = 1,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val items: List<Anime> = emptyList(),
    val errorMessage: String? = null
)

class DiscoverViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var discoverJob: kotlinx.coroutines.Job? = null

    init {
        loadDiscover(resetPage = true)
    }

    fun selectCategory(category: String) {
        val current = _uiState.value
        if (current.selectedCategory == category && current.selectedGenre == null && !current.isLoading && current.errorMessage == null) {
            return
        }
        _uiState.update {
            it.copy(selectedCategory = category, selectedGenre = null, page = 1)
        }
        loadDiscover(resetPage = true)
    }

    fun selectGenre(genre: String) {
        val current = _uiState.value.selectedGenre
        val nextGenre = if (current == genre) null else genre
        _uiState.update {
            it.copy(selectedGenre = nextGenre, page = 1)
        }
        loadDiscover(resetPage = true)
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore || !current.canLoadMore) return
        _uiState.update { it.copy(page = current.page + 1, isLoadingMore = true) }
        loadDiscover(resetPage = false)
    }

    fun loadDiscover(resetPage: Boolean = true) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            val targetPage = if (resetPage) 1 else state.page
            if (resetPage) {
                _uiState.update { it.copy(isLoading = true, isLoadingMore = false, errorMessage = null, page = 1) }
            }

            when (val res = animeRepository.getDiscoverAnime(
                category = state.selectedCategory,
                genre = state.selectedGenre,
                page = targetPage
            )) {
                is ResourceResult.Success -> {
                    val combined = if (resetPage) {
                        res.data
                    } else {
                        (state.items + res.data).distinctBy { it.hoshikaId }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = combined,
                            canLoadMore = res.data.isNotEmpty(),
                            errorMessage = null
                        )
                    }
                }
                is ResourceResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = res.userMessage
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Discover Anime",
            style = MaterialTheme.typography.headlineMedium,
            color = TvColors.PrimaryText
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Category Filter Row
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(DISCOVER_CATEGORIES, key = { "cat_$it" }) { category ->
                TvSelectableChip(
                    label = category,
                    selected = state.selectedCategory == category && state.selectedGenre == null,
                    onClick = { viewModel.selectCategory(category) },
                    modifier = Modifier.testTag("discover_cat_$category")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Genre Filter Row
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(HOSHIKA_GENRES, key = { "genre_$it" }) { genre ->
                TvSelectableChip(
                    label = genre,
                    selected = state.selectedGenre == genre,
                    onClick = { viewModel.selectGenre(genre) },
                    modifier = Modifier.testTag("discover_genre_$genre")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        when {
            state.isLoading -> {
                `in`.aniscroll.tv.core.designsystem.TvPosterGridSkeleton(
                    title = `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy.LOADING_ANIME,
                    subtitle = "Loading ${state.selectedGenre ?: state.selectedCategory} anime..."
                )
            }
            state.errorMessage != null && state.items.isEmpty() -> {
                TvErrorBanner(
                    message = state.errorMessage!!,
                    onRetry = { viewModel.loadDiscover(resetPage = true) }
                )
            }
            state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    `in`.aniscroll.tv.core.designsystem.TvEmptyStateBanner(
                        title = "No anime found for the selected filter.",
                        subtitle = "Try selecting another category or genre above.",
                        actionLabel = "Retry",
                        onAction = { viewModel.loadDiscover(resetPage = true) }
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 128.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = state.items,
                        key = { "disc_${it.hoshikaId}" }
                    ) { anime ->
                        TvAnimePosterCard(
                            anime = anime,
                            onClick = { onOpenAnimeDetails(anime.hoshikaId) }
                        )
                    }

                    if (state.canLoadMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (state.isLoadingMore) {
                                    `in`.aniscroll.tv.core.designsystem.TvSectionLoadingHeader(
                                        label = `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy.LOADING_ANIME,
                                        detailText = "Loading more titles..."
                                    )
                                }
                                TvFocusableButton(
                                    text = if (state.isLoadingMore) "Loading..." else "Load More",
                                    enabled = !state.isLoadingMore,
                                    onClick = { viewModel.loadMore() },
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
