package `in`.aniscroll.tv.features.search

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
    private var activeNetworkQuery: String? = null
    private var lastCompletedTrimmedQuery: String? = null

    fun onQueryChanged(newQuery: String) {
        if (newQuery == _uiState.value.query) return
        updateQuery(newQuery, immediate = false)
    }

    fun clearQuery() {
        searchJob?.cancel()
        searchJob = null
        activeNetworkQuery = null
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
            updateQuery(_uiState.value.query, immediate = true)
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

        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            searchJob?.cancel()
            searchJob = null
            activeNetworkQuery = null
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

        // Prevent duplicate API requests if the exact same query already succeeded and has no error
        if (trimmed == lastCompletedTrimmedQuery &&
            _uiState.value.hasSearched &&
            _uiState.value.errorMessage == null &&
            !_uiState.value.isLoading
        ) {
            searchJob?.cancel()
            searchJob = null
            return
        }

        // Prevent duplicate API requests if the exact same query is already actively fetching over the network
        if (trimmed == activeNetworkQuery &&
            _uiState.value.isLoading &&
            searchJob?.isActive == true
        ) {
            return
        }

        searchJob?.cancel()
        activeNetworkQuery = null

        searchJob = viewModelScope.launch {
            try {
                if (!immediate) {
                    delay(400L) // Debounce keyboard input (~400 ms)
                }
                activeNetworkQuery = trimmed
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                when (val res = animeRepository.searchAnime(trimmed)) {
                    is ResourceResult.Success -> {
                        val deduped = res.data.distinctBy { it.hoshikaId }
                        activeNetworkQuery = null
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
                        activeNetworkQuery = null
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
                activeNetworkQuery = null
                SearchLogger.d("Cancelled obsolete search job for query='$trimmed'")
                throw ce
            }
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rootView = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    var isInputFieldFocused by remember { mutableStateOf(false) }
    var isSoftwareKeyboardOpen by remember { mutableStateOf(false) }
    val isImeInsetVisible = WindowInsets.ime.getBottom(density) > 0

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length)
            )
        )
    }

    // Keep TextFieldValue synced when query is updated externally (e.g. Clear or Recent Search chip)
    // while preserving cursor position and selection during active typing/editing
    LaunchedEffect(state.query) {
        if (state.query != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length)
            )
        }
    }

    val inputFocusRequester = remember { FocusRequester() }

    // Sync software keyboard state with system IME window insets when available
    LaunchedEffect(isImeInsetVisible) {
        if (isImeInsetVisible) {
            isSoftwareKeyboardOpen = true
        } else if (isSoftwareKeyboardOpen) {
            isSoftwareKeyboardOpen = false
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    // Initial focus on the search input field when opening SearchScreen (unless returning to a selected result card)
    LaunchedEffect(Unit) {
        if (state.lastSelectedResultId <= 0L) {
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    // Pressing BACK on the Android TV remote while the system keyboard is open closes the keyboard
    // and keeps focus on the search field without clearing the query
    BackHandler(enabled = isImeInsetVisible || isSoftwareKeyboardOpen) {
        hideNativeKeyboard(context, rootView, keyboardController)
        isSoftwareKeyboardOpen = false
        runCatching { inputFocusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Search Hoshika",
            style = MaterialTheme.typography.headlineMedium,
            color = TvColors.PrimaryText
        )

        // Search Bar Row: Native Android TextField + Clear & Search Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.CardBackground)
                    .border(
                        width = 1.5.dp,
                        color = if (isInputFieldFocused) TvColors.FocusOutline else TvColors.PrimaryText.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        runCatching { inputFocusRequester.requestFocus() }
                        showNativeKeyboard(context, rootView, keyboardController)
                        isSoftwareKeyboardOpen = true
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Query",
                    tint = TvColors.PrimaryText
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = "Search anime titles...",
                            style = MaterialTheme.typography.titleMedium,
                            color = TvColors.MutedText
                        )
                    }
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != textFieldValue.text
                            textFieldValue = newValue
                            if (textChanged) {
                                viewModel.onQueryChanged(newValue.text)
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = TvColors.PrimaryText),
                        cursorBrush = SolidColor(TvColors.PrimaryText),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                hideNativeKeyboard(context, rootView, keyboardController)
                                isSoftwareKeyboardOpen = false
                                viewModel.triggerSearchNow()
                                runCatching { inputFocusRequester.requestFocus() }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { focusState ->
                                isInputFieldFocused = focusState.isFocused
                                if (!focusState.isFocused) {
                                    isSoftwareKeyboardOpen = false
                                }
                            }
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                            showNativeKeyboard(context, rootView, keyboardController)
                                            isSoftwareKeyboardOpen = true
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            isSoftwareKeyboardOpen = false
                                            hideNativeKeyboard(context, rootView, keyboardController)
                                            runCatching { focusManager.moveFocus(FocusDirection.Down) }
                                                .getOrDefault(false)
                                        }
                                        Key.DirectionLeft -> {
                                            if (textFieldValue.selection.start == 0 && textFieldValue.selection.end == 0) {
                                                isSoftwareKeyboardOpen = false
                                                hideNativeKeyboard(context, rootView, keyboardController)
                                                runCatching { focusManager.moveFocus(FocusDirection.Left) }
                                                    .getOrDefault(false)
                                            } else {
                                                false
                                            }
                                        }
                                        Key.DirectionRight -> {
                                            val atEnd = textFieldValue.selection.start == textFieldValue.text.length &&
                                                textFieldValue.selection.end == textFieldValue.text.length
                                            if (atEnd) {
                                                runCatching { focusManager.moveFocus(FocusDirection.Right) }
                                                    .getOrDefault(false)
                                            } else {
                                                false
                                            }
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            }
                            .testTag("search_input_field")
                    )
                }
            }

            if (state.query.isNotEmpty()) {
                TvFocusableButton(
                    text = "Clear",
                    icon = Icons.Default.Clear,
                    onClick = {
                        viewModel.clearQuery()
                        runCatching { inputFocusRequester.requestFocus() }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    modifier = Modifier.testTag("search_clear_button")
                )
            }

            TvFocusableButton(
                text = "Search",
                icon = Icons.Default.Search,
                isPrimary = true,
                onClick = {
                    hideNativeKeyboard(context, rootView, keyboardController)
                    isSoftwareKeyboardOpen = false
                    viewModel.triggerSearchNow()
                },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                modifier = Modifier.testTag("search_submit_button")
            )
        }

        // Recent Searches Row
        if (history.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("search_clear_history_button")
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { "hist_$it" }) { item ->
                        TvSelectableChip(
                            label = item,
                            selected = state.query.equals(item, ignoreCase = true),
                            onClick = {
                                hideNativeKeyboard(context, rootView, keyboardController)
                                isSoftwareKeyboardOpen = false
                                viewModel.selectHistoryQuery(item)
                            }
                        )
                    }
                }
            }
        }

        // Search Results / States Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                state.isLoading && state.results.isEmpty() -> {
                    `in`.aniscroll.tv.core.designsystem.TvPosterGridSkeleton(
                        title = `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy.SEARCHING,
                        subtitle = "Searching Hoshika for \"${state.query}\"...",
                        modifier = Modifier.testTag("search_loading_indicator")
                    )
                }
                state.errorMessage != null && state.results.isEmpty() -> {
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
                        `in`.aniscroll.tv.core.designsystem.TvEmptyStateBanner(
                            title = "No anime found matching \"${state.query}\".",
                            subtitle = "Try a different title, English or Japanese Romaji name."
                        )
                    }
                }
                state.results.isNotEmpty() -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Search Results (${state.results.size})",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TvColors.PrimaryText
                            )
                            if (state.isLoading) {
                                `in`.aniscroll.tv.core.designsystem.TvSectionLoadingHeader(
                                    label = `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy.SEARCHING,
                                    detailText = "\"${state.query}\"",
                                    modifier = Modifier.testTag("search_loading_indicator")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 124.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("search_idle_prompt"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select the search bar above to search Hoshika.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TvColors.MutedText
                        )
                    }
                }
            }
        }
    }
}

private fun showNativeKeyboard(
    context: Context,
    view: View,
    keyboardController: SoftwareKeyboardController?
) {
    runCatching {
        keyboardController?.show()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

private fun hideNativeKeyboard(
    context: Context,
    view: View,
    keyboardController: SoftwareKeyboardController?
) {
    runCatching {
        keyboardController?.hide()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
