package `in`.aniscroll.tv.features.episodes

import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvContentLoadingCopy
import `in`.aniscroll.tv.core.designsystem.TvEpisodeListSkeleton
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvSectionLoadingHeader
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import `in`.aniscroll.tv.features.details.DetailsViewModel
import kotlinx.coroutines.launch

@Composable
fun EpisodesScreen(
    hoshikaId: Long,
    viewModel: DetailsViewModel,
    onPlayEpisode: (Long, Int, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var lastSelectedEpisodeNumber by rememberSaveable { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(hoshikaId) {
        viewModel.loadAnime(hoshikaId)
    }

    val chunkSize = 50
    val episodeChunks = remember(state.episodes) {
        state.episodes.chunked(chunkSize)
    }
    val activeChunkIndex by remember(episodeChunks.size) {
        derivedStateOf {
            if (episodeChunks.isEmpty()) 0
            else (listState.firstVisibleItemIndex / chunkSize).coerceIn(0, episodeChunks.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 36.dp, vertical = 24.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvFocusableButton(
                    text = "Back",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    modifier = Modifier.testTag("episodes_back_button")
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = state.anime?.preferredTitle ?: "Episodes",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TvColors.PrimaryText
                    )
                    Text(
                        text = if (state.isLoadingMoreEpisodes) {
                            "${state.episodes.size} Episodes Loaded (Fetching more...)"
                        } else {
                            "${state.episodes.size} Episodes Available"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.SecondaryText,
                        modifier = Modifier.testTag("episodes_available_count")
                    )
                }
            }

            state.lastWatchedEpisode?.let { lastWatched ->
                TvFocusableButton(
                    text = "Resume Episode ${lastWatched.episodeNumber}",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    onClick = {
                        onPlayEpisode(hoshikaId, lastWatched.episodeNumber, lastWatched.language)
                    },
                    modifier = Modifier.testTag("episodes_resume_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Range Jump Chips for long episode lists (jumps LazyColumn to that range while keeping all episodes in the list)
        if (episodeChunks.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("episodes_range_chips_row")
            ) {
                items(episodeChunks.size) { index ->
                    val chunk = episodeChunks[index]
                    val startEp = chunk.firstOrNull()?.number ?: 1
                    val endEp = chunk.lastOrNull()?.number ?: startEp
                    val targetIndex = index * chunkSize
                    TvSelectableChip(
                        label = "EP $startEp - $endEp",
                        selected = activeChunkIndex == index,
                        onClick = {
                            lastSelectedEpisodeNumber = startEp
                            coroutineScope.launch {
                                listState.scrollToItem(targetIndex.coerceIn(0, (state.episodes.size - 1).coerceAtLeast(0)))
                            }
                        },
                        modifier = Modifier.testTag("episodes_range_chip_${startEp}_$endEp")
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when {
            state.isLoading || (state.isEpisodesLoading && state.episodes.isEmpty()) -> {
                TvEpisodeListSkeleton(
                    title = TvContentLoadingCopy.LOADING_EPISODES,
                    subtitle = "Loading episodes...",
                    itemCount = 5
                )
            }
            state.episodes.isEmpty() -> {
                TvErrorBanner(
                    message = "No episode list is currently available from Hoshika for this anime.",
                    onRetry = { viewModel.loadAnime(hoshikaId, forceRefresh = true) }
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("episodes_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(
                        items = state.episodes,
                        key = { "ep_screen_${it.number}_${it.id}" }
                    ) { episode ->
                        val progress = state.watchProgress.find { it.episodeNumber == episode.number }
                        val isPlaying = state.lastWatchedEpisode?.episodeNumber == episode.number &&
                            state.lastWatchedEpisode?.completed == false
                        val focusRequester = remember(episode.number) { FocusRequester() }

                        LaunchedEffect(lastSelectedEpisodeNumber) {
                            if (lastSelectedEpisodeNumber == episode.number) {
                                runCatching { focusRequester.requestFocus() }
                            }
                        }

                        EpisodeListRowItem(
                            episode = episode,
                            progressItem = progress,
                            isCurrentlyPlaying = isPlaying,
                            focusRequester = focusRequester,
                            onClick = {
                                lastSelectedEpisodeNumber = episode.number
                                onPlayEpisode(hoshikaId, episode.number, progress?.language)
                            }
                        )
                    }

                    if (state.isLoadingMoreEpisodes) {
                        item(key = "episodes_screen_loading_more") {
                            TvSectionLoadingHeader(
                                label = TvContentLoadingCopy.LOADING_EPISODES,
                                detailText = "Fetching more episodes (${state.episodes.size} loaded)...",
                                modifier = Modifier.testTag("episodes_loading_more_indicator")
                            )
                        }
                    }

                    if (state.episodesPaginationError != null) {
                        item(key = "episodes_screen_pagination_error") {
                            TvErrorBanner(
                                message = state.episodesPaginationError!!,
                                onRetry = { viewModel.retryLoadMoreEpisodes(hoshikaId) },
                                modifier = Modifier.testTag("episodes_pagination_error_banner")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeListRowItem(
    episode: Episode,
    progressItem: WatchProgressItem?,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ep_scale"
    )

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(focusMod)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) TvColors.ElevatedSurface else TvColors.CardBackground)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvColors.FocusOutline else TvColors.Divider,
                shape = RoundedCornerShape(6.dp)
            )
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(12.dp)
            .testTag("episode_item_${episode.number}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or Consistent Fallback Box
        Box(
            modifier = Modifier
                .width(148.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(4.dp))
                .background(TvColors.SecondaryBackground)
                .border(0.5.dp, TvColors.Divider, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!episode.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(episode.thumbnailUrl)
                        .crossfade(true)
                        .size(320, 180)
                        .build(),
                    contentDescription = "Episode ${episode.number}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleOutline,
                        contentDescription = "Play Episode ${episode.number}",
                        tint = TvColors.PrimaryText,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "EP ${episode.number}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.SecondaryText
                    )
                }
            }

            if (progressItem != null && progressItem.progressFraction > 0f) {
                LinearProgressIndicator(
                    progress = { progressItem.progressFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = TvColors.PrimaryText,
                    trackColor = TvColors.ElevatedSurface
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Episode Number, Title, Air Date
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Episode ${episode.number}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TvColors.PrimaryText
                )
                if (isCurrentlyPlaying) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvColors.PrimaryText)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "IN PROGRESS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvColors.MainBackground
                        )
                    }
                } else if (progressItem?.completed == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = TvColors.SecondaryText,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvColors.SecondaryText
                        )
                    }
                }
            }

            if (!episode.title.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!episode.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = episode.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.MutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
