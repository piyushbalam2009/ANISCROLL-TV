package `in`.aniscroll.tv.features.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import `in`.aniscroll.tv.core.designsystem.TvTitleLogoOrText
import `in`.aniscroll.tv.data.local.UiPreviewFixtures
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import `in`.aniscroll.tv.features.episodes.EpisodeListRowItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailsUiState(
    val isLoading: Boolean = true,
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

    fun loadAnime(hoshikaId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // Observe local watchlist and watch history for this anime
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

            // Check if this is one of the isolated UI preview fixture IDs
            val fixtureAnime = UiPreviewFixtures.sampleAnimeList.find { it.hoshikaId == hoshikaId }
            val animeResult = animeRepository.getAnimeDetails(hoshikaId)
            val resolvedAnime = when (animeResult) {
                is ResourceResult.Success -> animeResult.data
                is ResourceResult.Error -> fixtureAnime
            }

            if (resolvedAnime == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = (animeResult as? ResourceResult.Error)?.userMessage
                            ?: "Anime information is unavailable."
                    )
                }
                return@launch
            }

            val episodesResult = animeRepository.getAnimeEpisodes(hoshikaId)
            val resolvedEpisodes = when (episodesResult) {
                is ResourceResult.Success -> episodesResult.data
                is ResourceResult.Error -> {
                    if (fixtureAnime != null) UiPreviewFixtures.getPreviewEpisodes(hoshikaId)
                    else emptyList()
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    anime = resolvedAnime,
                    episodes = resolvedEpisodes,
                    errorMessage = null
                )
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

@Composable
fun DetailsScreen(
    hoshikaId: Long,
    viewModel: DetailsViewModel,
    onPlayEpisode: (Long, Int, String?) -> Unit,
    onOpenEpisodesScreen: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val primaryActionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(hoshikaId) {
        viewModel.loadAnime(hoshikaId)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
    ) {
        when {
            state.isLoading -> {
                TvLoadingIndicator(
                    message = "Loading anime details...",
                    modifier = Modifier.align(Alignment.Center)
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
                        onRetry = { viewModel.loadAnime(hoshikaId) }
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

                // Wide background artwork + dark cinematic gradient
                if (!bannerUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(bannerUrl)
                            .crossfade(true)
                            .size(1280, 720)
                            .build(),
                        contentDescription = anime.preferredTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    TvColors.MainBackground.copy(alpha = 0.55f),
                                    TvColors.MainBackground.copy(alpha = 0.92f),
                                    TvColors.MainBackground
                                )
                            )
                        )
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Top Bar + Main Details Header
                    item {
                        Column {
                            TvFocusableButton(
                                text = "Back",
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = onBack,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("details_back_button")
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Anime Poster
                                Box(
                                    modifier = Modifier
                                        .width(176.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(TvColors.CardBackground)
                                        .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
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
                                            modifier = Modifier.fillMaxSize().padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = anime.preferredTitle,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TvColors.SecondaryText
                                            )
                                        }
                                    }
                                }

                                // Title Logo, Titles, Metadata, Description, Action Buttons
                                Column(modifier = Modifier.weight(1f)) {
                                    TvTitleLogoOrText(
                                        logoUrl = anime.logoUrl,
                                        fallbackTitle = anime.preferredTitle,
                                        maxHeight = 90.dp,
                                        maxWidth = 380.dp
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // English + Japanese titles if distinct
                                    if (!anime.englishTitle.isNullOrBlank() && anime.englishTitle != anime.title) {
                                        Text(
                                            text = anime.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TvColors.SecondaryText
                                        )
                                    }
                                    if (!anime.japaneseTitle.isNullOrBlank()) {
                                        Text(
                                            text = anime.japaneseTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TvColors.MutedText
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

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
                                                        .background(TvColors.ElevatedSurface)
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
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }

                                    // Genres
                                    if (anime.genres.isNotEmpty()) {
                                        Text(
                                            text = "Genres: ${anime.genres.joinToString(" • ")}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TvColors.SecondaryText
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }

                                    // Remote-focusable scrollable description
                                    if (!anime.description.isNullOrBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(TvColors.SecondaryBackground.copy(alpha = 0.7f))
                                                .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp))
                                                .focusable()
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = anime.description,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TvColors.SecondaryText
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(18.dp))
                                    }

                                    // Action Buttons: 1. Play, 2. Resume, 3. Episodes, 4. Add to My List
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val firstEpNum = state.episodes.firstOrNull()?.number ?: 1
                                        TvFocusableButton(
                                            text = "Play Ep $firstEpNum",
                                            icon = Icons.Default.PlayArrow,
                                            isPrimary = true,
                                            focusRequester = primaryActionFocusRequester,
                                            onClick = { onPlayEpisode(anime.hoshikaId, firstEpNum, null) },
                                            modifier = Modifier.testTag("details_play_button")
                                        )

                                        state.lastWatchedEpisode?.let { lastEp ->
                                            TvFocusableButton(
                                                text = "Resume Ep ${lastEp.episodeNumber}",
                                                icon = Icons.Default.Replay,
                                                onClick = {
                                                    onPlayEpisode(
                                                        anime.hoshikaId,
                                                        lastEp.episodeNumber,
                                                        lastEp.language
                                                    )
                                                },
                                                modifier = Modifier.testTag("details_resume_button")
                                            )
                                        }

                                        TvFocusableButton(
                                            text = "Episodes",
                                            icon = Icons.AutoMirrored.Filled.List,
                                            onClick = { onOpenEpisodesScreen(anime.hoshikaId) },
                                            modifier = Modifier.testTag("details_episodes_button")
                                        )

                                        TvFocusableButton(
                                            text = if (state.isInWatchlist) "In My List" else "Add to My List",
                                            icon = if (state.isInWatchlist) Icons.Default.Check else Icons.Default.Add,
                                            onClick = { viewModel.toggleWatchlist() },
                                            modifier = Modifier.testTag("details_watchlist_button")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inline Episode List Preview
                    if (state.episodes.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                                    onClick = { onOpenEpisodesScreen(anime.hoshikaId) }
                                )
                            }
                        }

                        items(
                            items = state.episodes.take(15),
                            key = { "detail_ep_${it.id}" }
                        ) { episode ->
                            val epProgress = state.watchProgress.find { it.episodeNumber == episode.number }
                            EpisodeListRowItem(
                                episode = episode,
                                progressItem = epProgress,
                                isCurrentlyPlaying = state.lastWatchedEpisode?.episodeNumber == episode.number &&
                                    state.lastWatchedEpisode?.completed == false,
                                onClick = {
                                    onPlayEpisode(anime.hoshikaId, episode.number, epProgress?.language)
                                }
                            )
                        }
                    }
                }

                LaunchedEffect(anime.hoshikaId) {
                    runCatching { primaryActionFocusRequester.requestFocus() }
                }
            }
        }
    }
}
