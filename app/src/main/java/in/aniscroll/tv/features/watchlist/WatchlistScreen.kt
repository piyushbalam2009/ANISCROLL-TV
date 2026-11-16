package `in`.aniscroll.tv.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import `in`.aniscroll.tv.core.designsystem.TvAnimePosterCard
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.domain.model.Anime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WatchlistViewModel(
    private val animeRepository: AnimeRepository
) : ViewModel() {

    val watchlist: StateFlow<List<Anime>> = animeRepository.watchlistFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeFromWatchlist(anime: Anime) {
        viewModelScope.launch {
            animeRepository.toggleWatchlist(anime)
        }
    }
}

@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    onOpenAnimeDetails: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val items by viewModel.watchlist.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Text(
            text = "My List",
            style = MaterialTheme.typography.headlineMedium,
            color = TvColors.PrimaryText
        )
        Text(
            text = "Saved locally on this device • Available offline",
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
                    text = "Your My List is empty. Add anime from Home or Details.",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.MutedText
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = items,
                    key = { "watchlist_${it.hoshikaId}" }
                ) { anime ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TvAnimePosterCard(
                            anime = anime,
                            onClick = { onOpenAnimeDetails(anime.hoshikaId) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TvFocusableButton(
                            text = "Remove",
                            onClick = { viewModel.removeFromWatchlist(anime) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
