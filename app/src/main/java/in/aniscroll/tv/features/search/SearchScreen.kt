package `in`.aniscroll.tv.features.search

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.CancellationException
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
    val errorMessage: String? = null,
    val lastSelectedResultId: Long = -1L
)

class SearchViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val searchHistory: StateFlow<List<String>> = animeRepository.searchHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null
    private var lastCompletedTrimmedQuery: String? = null

    fun onQueryChanged(newQuery: String) {
        if (newQuery == _uiState.value.query) return
        updateQuery(newQuery, immediate = false)
    }

    fun appendCharacter(char: String) {
        updateQuery(_uiState.value.query + char, immediate = false)
    }

    fun backspace() {
        val current = _uiState.value.query
        if (current.isNotEmpty()) {
            updateQuery(current.dropLast(1), immediate = false)
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        searchJob = null
        lastCompletedTrimmedQuery = null
        SearchLogger.d("Search query cleared")
        _uiState.update {
            SearchUiState(
                query = "",
                isLoading = false,
                results = emptyList(),
                hasSearched = false,
                errorMessage = null,
                lastSelectedResultId = -1L
            )
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

    fun recordSelectedResult(hoshikaId: Long) {
        _uiState.update { it.copy(lastSelectedResultId = hoshikaId) }
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
            lastCompletedTrimmedQuery = null
            _uiState.update {
                it.copy(
                    isLoading = false,
                    results = emptyList(),
                    hasSearched = false,
                    errorMessage = null
                )
            }
            return
        }

        // Avoid duplicate API calls if the same query already succeeded and this isn't a manual retry
        if (!immediate &&
            trimmed == lastCompletedTrimmedQuery &&
            _uiState.value.hasSearched &&
            _uiState.value.errorMessage == null
        ) {
            return
        }

        searchJob = viewModelScope.launch {
            try {
                if (!immediate) {
                    delay(400L) // Debounce remote & keyboard input (~400 ms)
                }
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                when (val res = animeRepository.searchAnime(trimmed)) {
                    is ResourceResult.Success -> {
                        val deduped = res.data.distinctBy { it.hoshikaId }
                        lastCompletedTrimmedQuery = trimmed
                        if (trimmed.length >= 2) {
                            animeRepository.saveSearchQuery(trimmed)
                        }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                results = deduped,
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
            } catch (ce: CancellationException) {
                SearchLogger.d("Cancelled obsolete search job for query='$trimmed'")
                throw ce
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
    val keyboardController = LocalSoftwareKeyboardController.current

    var isInputFieldFocused by remember { mutableStateOf(false) }
    var isSoftwareKeyboardOpen by remember { mutableStateOf(false) }

    val inputFocusRequester = remember { FocusRequester() }
    val firstKeyFocusRequester = remember { FocusRequester() }

    // Initial focus when opening SearchScreen (or restore to selected result when returning from Details)
    LaunchedEffect(Unit) {
        if (state.lastSelectedResultId <= 0L) {
            runCatching { firstKeyFocusRequester.requestFocus() }
        }
    }

    // Pressing BACK while the software keyboard / text field is active closes the keyboard first
    BackHandler(enabled = isSoftwareKeyboardOpen || isInputFieldFocused) {
        keyboardController?.hide()
        isSoftwareKeyboardOpen = false
        isInputFieldFocused = false
        runCatching { firstKeyFocusRequester.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left Pane: Television On-Screen Keyboard & Search Input
        Column(
            modifier = Modifier
                .width(332.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Search Hoshika",
                style = MaterialTheme.typography.headlineMedium,
                color = TvColors.PrimaryText
            )

            // Interactive Search query input box (supports both physical/IME keyboard & on-screen keys)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.CardBackground)
                    .border(
                        width = 1.5.dp,
                        color = if (isInputFieldFocused) TvColors.FocusOutline else TvColors.PrimaryText.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Query",
                    tint = TvColors.PrimaryText
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (state.query.isEmpty()) {
                        Text(
                            text = "Type or use remote keyboard...",
                            style = MaterialTheme.typography.titleMedium,
                            color = TvColors.MutedText
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = { viewModel.onQueryChanged(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = TvColors.PrimaryText),
                        cursorBrush = SolidColor(TvColors.PrimaryText),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                isSoftwareKeyboardOpen = false
                                viewModel.triggerSearchNow()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { focusState ->
                                isInputFieldFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    isSoftwareKeyboardOpen = true
                                }
                            }
                            .testTag("search_input_field")
                    )
                }
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
                itemsIndexed(TV_KEYBOARD_KEYS, key = { _, keyChar -> "key_$keyChar" }) { index, keyChar ->
                    TvFocusableButton(
                        text = keyChar,
                        focusRequester = if (index == 0) firstKeyFocusRequester else null,
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
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { "hist_$it" }) { item ->
                        TvSelectableChip(
                            label = item,
                            selected = state.query.equals(item, ignoreCase = true),
                            onClick = { viewModel.selectHistoryQuery(item) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            when {
                state.isLoading -> {
                    TvLoadingIndicator(
                        message = "Searching Hoshika for \"${state.query}\"...",
                        modifier = Modifier.testTag("search_loading_indicator")
                    )
                }
                state.errorMessage != null -> {
                    TvErrorBanner(
                        message = state.errorMessage!!,
                        onRetry = { viewModel.triggerSearchNow() },
                        modifier = Modifier.testTag("search_error_banner")
                    )
                }
                state.hasSearched && state.results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("search_empty_state"),
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
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("search_results_grid")
                    ) {
                        itemsIndexed(
                            items = state.results,
                            key = { _, anime -> "search_res_${anime.hoshikaId}" }
                        ) { _, anime ->
                            val cardFocusRequester = remember(anime.hoshikaId) { FocusRequester() }
                            LaunchedEffect(Unit) {
                                if (state.lastSelectedResultId == anime.hoshikaId) {
                                    runCatching { cardFocusRequester.requestFocus() }
                                }
                            }
                            TvAnimePosterCard(
                                anime = anime,
                                focusRequester = cardFocusRequester,
                                onClick = {
                                    viewModel.recordSelectedResult(anime.hoshikaId)
                                    onOpenAnimeDetails(anime.hoshikaId)
                                },
                                modifier = Modifier.testTag("search_result_card_${anime.hoshikaId}")
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
