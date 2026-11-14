package `in`.aniscroll.tv.features.watchorder

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvLoadingIndicator
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.domain.model.FranchiseWatchOrder
import `in`.aniscroll.tv.domain.model.WatchOrderEntry
import `in`.aniscroll.tv.domain.model.WatchOrderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchOrderUiState(
    val hoshikaId: Long = -1L,
    val isLoading: Boolean = true,
    val watchOrder: FranchiseWatchOrder? = null,
    val selectedMode: WatchOrderMode = WatchOrderMode.RECOMMENDED,
    val lastSelectedAnilistId: Long = -1L,
    val errorMessage: String? = null
)

class WatchOrderViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchOrderUiState())
    val uiState: StateFlow<WatchOrderUiState> = _uiState.asStateFlow()

    private val selectedModeByFranchise = mutableMapOf<Long, WatchOrderMode>()
    private val lastSelectedAnilistIdByFranchise = mutableMapOf<Long, Long>()
    private var loadWatchOrderJob: kotlinx.coroutines.Job? = null

    fun loadWatchOrder(hoshikaId: Long, forceRefresh: Boolean = false) {
        val currentState = _uiState.value
        val preservedMode = selectedModeByFranchise[hoshikaId] ?: WatchOrderMode.RECOMMENDED
        val preservedSelection = lastSelectedAnilistIdByFranchise[hoshikaId] ?: -1L

        if (!forceRefresh &&
            currentState.hoshikaId == hoshikaId &&
            currentState.watchOrder != null &&
            currentState.errorMessage == null
        ) {
            _uiState.update {
                it.copy(
                    selectedMode = preservedMode,
                    lastSelectedAnilistId = preservedSelection
                )
            }
            // Refresh watch status labels in background without showing a loading spinner
            viewModelScope.launch {
                val res = animeRepository.getFranchiseWatchOrder(hoshikaId, forceRefresh = false)
                if (res is ResourceResult.Success) {
                    _uiState.update { state ->
                        if (state.hoshikaId == hoshikaId) {
                            state.copy(watchOrder = res.data)
                        } else {
                            state
                        }
                    }
                }
            }
            return
        }

        loadWatchOrderJob?.cancel()
        loadWatchOrderJob = viewModelScope.launch {
            _uiState.update {
                WatchOrderUiState(
                    hoshikaId = hoshikaId,
                    isLoading = true,
                    watchOrder = null,
                    selectedMode = preservedMode,
                    lastSelectedAnilistId = preservedSelection,
                    errorMessage = null
                )
            }

            when (val res = animeRepository.getFranchiseWatchOrder(hoshikaId, forceRefresh = forceRefresh)) {
                is ResourceResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            watchOrder = res.data,
                            selectedMode = preservedMode,
                            lastSelectedAnilistId = preservedSelection,
                            errorMessage = null
                        )
                    }
                }
                is ResourceResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = res.userMessage
                        )
                    }
                }
            }
        }
    }

    fun selectOrderMode(mode: WatchOrderMode) {
        val hoshikaId = _uiState.value.hoshikaId
        if (hoshikaId > 0L) {
            selectedModeByFranchise[hoshikaId] = mode
        }
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun onSelectAnimeEntry(
        entry: WatchOrderEntry,
        onNavigateToDetails: (Long) -> Unit
    ) {
        val currentFranchiseId = _uiState.value.hoshikaId
        if (currentFranchiseId > 0L) {
            lastSelectedAnilistIdByFranchise[currentFranchiseId] = entry.anilistId
        }
        _uiState.update { it.copy(lastSelectedAnilistId = entry.anilistId) }

        viewModelScope.launch {
            val resolvedHoshikaId = animeRepository.resolveAndCacheWatchOrderEntry(entry)
            onNavigateToDetails(resolvedHoshikaId)
        }
    }
}

@Composable
fun WatchOrderScreen(
    hoshikaId: Long,
    viewModel: WatchOrderViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(hoshikaId) {
        viewModel.loadWatchOrder(hoshikaId)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .testTag("watch_order_screen")
    ) {
        when {
            state.isLoading -> {
                `in`.aniscroll.tv.core.designsystem.TvWatchOrderTimelineSkeleton(
                    title = `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy.FETCHING_DETAILS,
                    subtitle = "Building franchise watch order from AniList relations...",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("watch_order_loading")
                )
            }
            state.errorMessage != null || state.watchOrder == null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 520.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TvErrorBanner(
                        message = state.errorMessage ?: "Unable to load watch order.",
                        onRetry = { viewModel.loadWatchOrder(hoshikaId, forceRefresh = true) },
                        modifier = Modifier.testTag("watch_order_error_banner")
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TvFocusableButton(
                        text = "Back to Details",
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onBack,
                        modifier = Modifier.testTag("watch_order_back_button")
                    )
                }
            }
            else -> {
                val watchOrder = state.watchOrder!!
                val entries = watchOrder.entriesForMode(state.selectedMode)
                val firstEntryFocusRequester = remember { FocusRequester() }

                // Backdrop Banner + Dark Cinematic Gradient
                if (!watchOrder.franchiseBannerUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(watchOrder.franchiseBannerUrl)
                            .crossfade(true)
                            .size(1280, 720)
                            .build(),
                        contentDescription = watchOrder.franchiseTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    TvColors.MainBackground.copy(alpha = 0.68f),
                                    TvColors.MainBackground.copy(alpha = 0.95f),
                                    TvColors.MainBackground
                                )
                            )
                        )
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("watch_order_timeline_list"),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Header Section: Back button, Franchise Cover/Banner, Title, Total Count, Mode Selector
                    item(key = "watch_order_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            TvFocusableButton(
                                text = "Back to Details",
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = onBack,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("watch_order_back_button")
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Franchise Cover Poster
                                Box(
                                    modifier = Modifier
                                        .width(108.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(TvColors.CardBackground)
                                        .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
                                ) {
                                    if (!watchOrder.franchiseCoverUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(watchOrder.franchiseCoverUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = watchOrder.franchiseTitle,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "FRANCHISE WATCH ORDER",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TvColors.SecondaryText
                                    )

                                    Text(
                                        text = watchOrder.franchiseTitle,
                                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                        color = TvColors.PrimaryText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("watch_order_franchise_title")
                                    )

                                    // Metadata summary badges
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val countLabel = if (watchOrder.totalEntries == 1) {
                                            "1 Standalone Anime"
                                        } else {
                                            "${watchOrder.totalEntries} Related Anime"
                                        }
                                        WatchOrderMetaBadge(
                                            text = countLabel,
                                            isHighlighted = true,
                                            modifier = Modifier.testTag("watch_order_total_count")
                                        )

                                        watchOrder.sourceMaterialNote?.let { note ->
                                            WatchOrderMetaBadge(text = note)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Order Mode Selector: Recommended (default), Release Order, Chronological Order
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        WatchOrderMode.entries.forEach { mode ->
                                            TvSelectableChip(
                                                label = mode.label,
                                                selected = state.selectedMode == mode,
                                                onClick = { viewModel.selectOrderMode(mode) },
                                                modifier = Modifier.testTag("watch_order_mode_${mode.name}")
                                            )
                                        }
                                    }

                                    Text(
                                        text = state.selectedMode.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TvColors.SecondaryText
                                    )
                                }
                            }

                            if (watchOrder.totalEntries <= 1) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(TvColors.CardBackground)
                                        .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
                                        .padding(16.dp)
                                        .testTag("watch_order_standalone_banner")
                                ) {
                                    Text(
                                        text = "Standalone Anime — No direct prequels, sequels, or spin-off entries were found for this title. You can watch it directly from Episode 1.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TvColors.SecondaryText
                                    )
                                }
                            }
                        }
                    }

                    // Timeline Entries
                    itemsIndexed(
                        items = entries,
                        key = { _, entry -> "wo_${state.selectedMode.name}_${entry.anilistId}" }
                    ) { index, entry ->
                        val itemFocusRequester = remember(entry.anilistId, state.selectedMode) {
                            if (index == 0 && state.lastSelectedAnilistId <= 0L) {
                                firstEntryFocusRequester
                            } else {
                                FocusRequester()
                            }
                        }

                        LaunchedEffect(state.lastSelectedAnilistId, state.selectedMode) {
                            if (state.lastSelectedAnilistId == entry.anilistId) {
                                runCatching { itemFocusRequester.requestFocus() }
                            } else if (state.lastSelectedAnilistId <= 0L && index == 0) {
                                runCatching { firstEntryFocusRequester.requestFocus() }
                            }
                        }

                        WatchOrderTimelineRow(
                            entry = entry,
                            isFirst = index == 0,
                            isLast = index == entries.lastIndex,
                            focusRequester = itemFocusRequester,
                            onSelect = {
                                viewModel.onSelectAnimeEntry(entry, onOpenAnimeDetails)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchOrderTimelineRow(
    entry: WatchOrderEntry,
    isFirst: Boolean,
    isLast: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.015f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "timeline_card_scale"
    )
    val cardBackground by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            entry.isCurrentAnime -> TvColors.ElevatedSurface
            else -> TvColors.CardBackground
        },
        animationSpec = tween(durationMillis = 150),
        label = "timeline_card_bg"
    )
    val primaryContentColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black else TvColors.PrimaryText,
        animationSpec = tween(durationMillis = 150),
        label = "timeline_card_fg"
    )
    val secondaryContentColor by animateColorAsState(
        targetValue = if (isFocused) Color(0xFF262626) else TvColors.SecondaryText,
        animationSpec = tween(durationMillis = 150),
        label = "timeline_card_sub_fg"
    )
    val circleBg by animateColorAsState(
        targetValue = if (isFocused || entry.isCurrentAnime) Color.White else TvColors.ElevatedSurface,
        animationSpec = tween(durationMillis = 150),
        label = "timeline_circle_bg"
    )
    val circleFg by animateColorAsState(
        targetValue = if (isFocused || entry.isCurrentAnime) Color.Black else Color.White,
        animationSpec = tween(durationMillis = 150),
        label = "timeline_circle_fg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Vertical Timeline Spine Column (Line + Position Circle + Line)
        Column(
            modifier = Modifier
                .width(44.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(22.dp)
                    .background(if (isFirst) Color.Transparent else TvColors.Divider)
            )

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(circleBg)
                    .border(
                        width = 1.5.dp,
                        color = if (isFocused || entry.isCurrentAnime) Color.White else TvColors.Divider,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${entry.position}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = circleFg
                )
            }

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (isLast) Color.Transparent else TvColors.Divider)
            )
        }

        // Selectable Franchise Anime Card
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 16.dp)
                .scale(scale)
                .clip(RoundedCornerShape(10.dp))
                .background(cardBackground)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = when {
                        isFocused -> Color.White
                        entry.isCurrentAnime -> Color.White.copy(alpha = 0.65f)
                        else -> TvColors.Divider
                    },
                    shape = RoundedCornerShape(10.dp)
                )
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.key == Key.DirectionCenter ||
                            keyEvent.key == Key.Enter ||
                            keyEvent.key == Key.NumPadEnter)
                    ) {
                        onSelect()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSelect
                )
                .padding(14.dp)
                .testTag("watch_order_item_${entry.position}"),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Individual Anime Poster
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.SecondaryBackground)
                    .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp))
            ) {
                if (!entry.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(entry.posterUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = entry.preferredTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.preferredTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = TvColors.SecondaryText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Anime Details, Badges, and Relationship Explanation
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Relationship & Status Badges Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimelinePillBadge(
                        text = entry.relationshipBadge,
                        isFilled = !entry.isOptional,
                        isParentFocused = isFocused
                    )

                    TimelinePillBadge(
                        text = entry.viewingTierBadge,
                        isFilled = false,
                        isParentFocused = isFocused
                    )

                    if (entry.isCurrentAnime) {
                        TimelinePillBadge(
                            text = "Current Anime",
                            isFilled = true,
                            isParentFocused = isFocused
                        )
                    }

                    entry.watchStatusLabel?.let { statusLabel ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isFocused) Color(0xFF1A1A1A) else TvColors.ElevatedSurface)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }

                // Title
                Text(
                    text = entry.preferredTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = primaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Format, Release Year, Episodes
                val metaParts = buildList {
                    add(entry.format)
                    entry.releaseYear?.let { add("$it") }
                    entry.episodes?.takeIf { it > 0 }?.let { epCount ->
                        add(if (epCount == 1) "1 Episode" else "$epCount Episodes")
                    }
                    entry.status?.takeIf { it.isNotBlank() }?.let { st ->
                        add(st.replace("_", " "))
                    }
                }
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelLarge,
                    color = secondaryContentColor
                )

                // Simple Relationship Explanation
                Text(
                    text = entry.relationshipExplanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryContentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open ${entry.preferredTitle}",
                tint = primaryContentColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun WatchOrderMetaBadge(
    text: String,
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHighlighted) Color.White else TvColors.ElevatedSurface)
            .border(
                width = 1.dp,
                color = if (isHighlighted) Color.White else TvColors.Divider,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isHighlighted) Color.Black else TvColors.PrimaryText
        )
    }
}

@Composable
private fun TimelinePillBadge(
    text: String,
    isFilled: Boolean,
    isParentFocused: Boolean
) {
    val bgColor = when {
        isParentFocused && isFilled -> Color.Black
        isParentFocused && !isFilled -> Color.Transparent
        !isParentFocused && isFilled -> Color.White
        else -> TvColors.SecondaryBackground
    }
    val fgColor = when {
        isParentFocused && isFilled -> Color.White
        isParentFocused && !isFilled -> Color.Black
        !isParentFocused && isFilled -> Color.Black
        else -> TvColors.SecondaryText
    }
    val borderColor = when {
        isParentFocused -> Color.Black.copy(alpha = 0.6f)
        isFilled -> Color.White
        else -> TvColors.Divider
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fgColor
        )
    }
}
