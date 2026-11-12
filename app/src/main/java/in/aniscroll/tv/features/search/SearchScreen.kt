package `in`.aniscroll.tv.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<Anime> = emptyList(),
    val hasSearched: Boolean = false,
    val errorMessage: String? = null
)

class SearchViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val searchHistory: StateFlow<List<String>> = animeRepository.searchHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    fun appendCharacter(char: String) {
        updateQuery(_uiState.value.query + char)
    }

    fun backspace() {
        val current = _uiState.value.query
        if (current.isNotEmpty()) {
            updateQuery(current.dropLast(1))
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        _uiState.update {
            SearchUiState(query = "", isLoading = false, results = emptyList(), hasSearched = false)
        }
    }

    fun selectHistoryQuery(query: String) {
        updateQuery(query, immediate = true)
    }

    fun triggerSearchNow() {
        val current = _uiState.value.query.trim()
        if (current.isNotEmpty()) {
            updateQuery(current, immediate = true)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            animeRepository.clearSearchHistory()
        }
    }

    private fun updateQuery(newQuery: String, immediate: Boolean = false) {
        _uiState.update { it.copy(query = newQuery, errorMessage = null) }
        searchJob?.cancel()

        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, results = emptyList(), hasSearched = false) }
            return
        }

        searchJob = viewModelScope.launch {
            if (!immediate) {
                delay(420L) // Debounce remote key presses
            }
            _uiState.update { it.copy(isLoading = true) }
            when (val res = animeRepository.searchAnime(trimmed)) {
                is ResourceResult.Success -> {
                    if (trimmed.length >= 2) {
                        animeRepository.saveSearchQuery(trimmed)
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            results = res.data,
                            hasSearched = true,
                            errorMessage = null
                        )
                    }
                }
                is ResourceResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            hasSearched = true,
                            errorMessage = res.userMessage
                        )
                    }
                }
            }
        }
    }
}

private val TV_KEYBOARD_KEYS = listOf(
    "A", "B", "C", "D", "E", "F",
    "G", "H", "I", "J", "K", "L",
    "M", "N", "O", "P", "Q", "R",
    "S", "T", "U", "V", "W", "X",
    "Y", "Z", "0", "1", "2", "3",
    "4", "5", "6", "7", "8", "9"
)

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // Left Pane: Television On-Screen Keyboard & Search Input
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Search Hoshika",
                style = MaterialTheme.typography.headlineMedium,
                color = TvColors.PrimaryText
            )

            // Search query display box
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.CardBackground)
                    .border(1.5.dp, TvColors.PrimaryText, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Query",
                    tint = TvColors.PrimaryText
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = state.query.ifEmpty { "Use remote keyboard..." },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.query.isEmpty()) TvColors.MutedText else TvColors.PrimaryText
                )
            }

            // Action row: Space, Backspace, Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TvFocusableButton(
                    text = "Space",
                    icon = Icons.Default.SpaceBar,
                    onClick = { viewModel.appendCharacter(" ") },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("search_key_space")
                )
                TvFocusableButton(
                    text = "Del",
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    onClick = { viewModel.backspace() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("search_key_backspace")
                )
                TvFocusableButton(
                    text = "Clear",
                    icon = Icons.Default.Clear,
                    onClick = { viewModel.clearQuery() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("search_key_clear")
                )
            }

            // 6x6 On-Screen Character Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(TV_KEYBOARD_KEYS, key = { "key_$it" }) { keyChar ->
                    TvFocusableButton(
                        text = keyChar,
                        onClick = { viewModel.appendCharacter(keyChar.lowercase()) },
                        contentPadding = PaddingValues(vertical = 10.dp),
                        modifier = Modifier.testTag("search_key_$keyChar")
                    )
                }
            }

            TvFocusableButton(
                text = "Search Now",
                icon = Icons.Default.Search,
                isPrimary = true,
                onClick = { viewModel.triggerSearchNow() },
                modifier = Modifier.fillMaxWidth().testTag("search_submit_button")
            )
        }

        // Right Pane: Recent Searches & Search Results Grid
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            if (history.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Recent Searches",
                            tint = TvColors.SecondaryText
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recent Searches",
                            style = MaterialTheme.typography.titleMedium,
                            color = TvColors.SecondaryText
                        )
                    }
                    TvFocusableButton(
                        text = "Clear History",
                        onClick = { viewModel.clearHistory() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { "hist_$it" }) { item ->
                        TvSelectableChip(
                            label = item,
                            selected = state.query.equals(item, ignoreCase = true),
                            onClick = { viewModel.selectHistoryQuery(item) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            when {
                state.isLoading -> {
                    TvLoadingIndicator(message = "Searching Hoshika for \"${state.query}\"...")
                }
                state.errorMessage != null -> {
                    TvErrorBanner(
                        message = state.errorMessage!!,
                        onRetry = { viewModel.triggerSearchNow() }
                    )
                }
                state.hasSearched && state.results.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No anime found matching \"${state.query}\".",
                            style = MaterialTheme.typography.titleLarge,
                            color = TvColors.SecondaryText
                        )
                    }
                }
                state.results.isNotEmpty() -> {
                    Text(
                        text = "Search Results (${state.results.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TvColors.PrimaryText
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = state.results,
                            key = { "search_res_${it.hoshikaId}" }
                        ) { anime ->
                            TvAnimePosterCard(
                                anime = anime,
                                onClick = { onOpenAnimeDetails(anime.hoshikaId) }
                            )
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Enter an anime title on the left keyboard to search Hoshika.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TvColors.MutedText
                        )
                    }
                }
            }
        }
    }
}
