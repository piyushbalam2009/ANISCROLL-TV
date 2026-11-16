package `in`.aniscroll.tv.features.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.domain.model.WatchProgressItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    val continueWatching: StateFlow<List<WatchProgressItem>> = animeRepository.continueWatchingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeItem(item: WatchProgressItem) {
        viewModelScope.launch {
            animeRepository.removeContinueWatching(item.hoshikaId, item.episodeNumber)
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onResumePlayback: (Long, Int, String) -> Unit,
    onOpenAnimeDetails: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val items by viewModel.continueWatching.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.headlineMedium,
            color = TvColors.PrimaryText
        )
        Text(
            text = "Resume episodes from your saved playback timestamp",
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.SecondaryText
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No episodes in progress. Start watching an anime to resume here.",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.MutedText
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = items,
                    key = { "cw_screen_${it.hoshikaId}_${it.episodeNumber}" }
                ) { item ->
                    ContinueWatchingRowCard(
                        item = item,
                        onResume = {
                            onResumePlayback(item.hoshikaId, item.episodeNumber, item.language)
                        },
                        onDetails = { onOpenAnimeDetails(item.hoshikaId) },
                        onRemove = { viewModel.removeItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRowCard(
    item: WatchProgressItem,
    onResume: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.CardBackground)
            .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(TvColors.SecondaryBackground)
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.posterUrl)
                        .crossfade(true)
                        .size(200, 300)
                        .build(),
                    contentDescription = item.animeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.animeTitle.ifBlank { "Episode ${item.episodeNumber}" },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = TvColors.PrimaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Episode ${item.episodeNumber}  •  ${item.language.uppercase()}  •  ${formatTimeMs(item.positionMs)} / ${formatTimeMs(item.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.SecondaryText
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { item.progressFraction },
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(4.dp),
                color = TvColors.PrimaryText,
                trackColor = TvColors.ElevatedSurface
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvFocusableButton(
                text = "Resume",
                icon = Icons.Default.PlayArrow,
                isPrimary = true,
                onClick = onResume
            )
            TvFocusableButton(
                text = "Details",
                icon = Icons.Default.Info,
                onClick = onDetails
            )
            TvFocusableButton(
                text = "Remove",
                icon = Icons.Default.DeleteOutline,
                onClick = onRemove
            )
        }
    }
}

fun formatTimeMs(timeMs: Long): String {
    val totalSec = (timeMs / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
    }
}
