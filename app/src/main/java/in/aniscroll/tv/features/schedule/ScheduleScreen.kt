package `in`.aniscroll.tv.features.schedule

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
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
import `in`.aniscroll.tv.core.designsystem.TvLoadingIndicator
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.domain.model.ScheduleItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val SCHEDULE_DAYS = listOf(
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday"
)

data class ScheduleUiState(
    val selectedDay: String = SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date())
        .takeIf { it in SCHEDULE_DAYS } ?: "Monday",
    val isLoading: Boolean = true,
    val items: List<ScheduleItem> = emptyList(),
    val errorMessage: String? = null
)

class ScheduleViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        loadSchedule(_uiState.value.selectedDay)
    }

    fun selectDay(day: String) {
        _uiState.update { it.copy(selectedDay = day) }
        loadSchedule(day)
    }

    fun loadSchedule(day: String = _uiState.value.selectedDay) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val res = animeRepository.getSchedule(day)) {
                is ResourceResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            items = res.data,
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
}

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
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
            text = "Airing Schedule",
            style = MaterialTheme.typography.headlineMedium,
            color = TvColors.PrimaryText
        )
        Text(
            text = "Airing times converted to your device's local timezone",
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.SecondaryText
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Horizontal Day Navigation
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(SCHEDULE_DAYS, key = { "day_$it" }) { day ->
                TvSelectableChip(
                    label = day,
                    selected = state.selectedDay.equals(day, ignoreCase = true),
                    onClick = { viewModel.selectDay(day) },
                    modifier = Modifier.testTag("schedule_day_$day")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        when {
            state.isLoading -> {
                TvLoadingIndicator(message = "Loading ${state.selectedDay} schedule from Hoshika...")
            }
            state.errorMessage != null -> {
                TvErrorBanner(
                    message = state.errorMessage!!,
                    onRetry = { viewModel.loadSchedule() }
                )
            }
            state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No scheduled broadcasts returned by Hoshika for ${state.selectedDay}.",
                        style = MaterialTheme.typography.titleMedium,
                        color = TvColors.SecondaryText
                    )
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = state.items,
                        key = { "sched_${it.id}" }
                    ) { item ->
                        ScheduleEntryRow(
                            item = item,
                            onClick = { onOpenAnimeDetails(item.anime.hoshikaId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleEntryRow(
    item: ScheduleItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sched_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time column (only if provided by Hoshika API; never fabricated)
        if (!item.formattedLocalTime.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .width(108.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TvColors.SecondaryBackground)
                    .border(1.dp, TvColors.Divider, RoundedCornerShape(4.dp))
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.formattedLocalTime,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = TvColors.PrimaryText
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        // Poster
        Box(
            modifier = Modifier
                .width(64.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .background(TvColors.SecondaryBackground)
        ) {
            if (!item.anime.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.anime.posterUrl)
                        .crossfade(true)
                        .size(160, 240)
                        .build(),
                    contentDescription = item.anime.preferredTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Anime Title + Episode Number + Release Status
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.anime.preferredTitle,
                style = MaterialTheme.typography.titleLarge,
                color = TvColors.PrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            val meta = buildList {
                item.episodeNumber?.let { add("Episode $it") }
                item.releaseStatus?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (item.anime.genres.isNotEmpty()) {
                    add(item.anime.genres.take(3).joinToString(" / "))
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText
                )
            }
        }
    }
}
