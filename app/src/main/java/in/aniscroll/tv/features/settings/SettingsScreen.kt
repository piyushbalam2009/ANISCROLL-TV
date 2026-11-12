package `in`.aniscroll.tv.features.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import `in`.aniscroll.tv.core.database.AniScrollDao
import `in`.aniscroll.tv.core.datastore.UserPreferencesRepository
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvConfirmDialog
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.domain.model.AppSettings
import `in`.aniscroll.tv.domain.model.SubtitleBackground
import `in`.aniscroll.tv.domain.model.SubtitleColor
import `in`.aniscroll.tv.domain.model.SubtitlePosition
import `in`.aniscroll.tv.domain.model.SubtitleSize
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DestructiveAction(val title: String, val message: String) {
    CLEAR_IMAGE_CACHE(
        "Clear Image & Logo Cache",
        "Remove cached posters, banners, and TMDB/Fanart title logos from local storage?"
    ),
    CLEAR_SEARCH_HISTORY(
        "Clear Search History",
        "Delete all saved recent search queries from this device?"
    ),
    CLEAR_WATCH_HISTORY(
        "Clear Watch History",
        "Remove all watch progress and Continue Watching entries?"
    ),
    CLEAR_ALL_LOCAL_DATA(
        "Clear All Local Data",
        "Reset all My List items, watch history, ID mappings, cached logos, and preferences?"
    )
}

class SettingsViewModel(
    val preferencesRepository: UserPreferencesRepository,
    private val dao: AniScrollDao
) : ViewModel() {

    val settings: StateFlow<AppSettings> = preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val cachedAnimeCount: StateFlow<Int> = dao.observeCachedAnimeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val mappingCount: StateFlow<Int> = dao.observeMappingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun clearImageAndLogoCache(context: Context) {
        viewModelScope.launch {
            dao.clearLogoCache()
            runCatching {
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
            }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { dao.clearSearchHistory() }
    }

    fun clearWatchHistory() {
        viewModelScope.launch { dao.clearWatchHistory() }
    }

    fun clearAllLocalData(context: Context) {
        viewModelScope.launch {
            dao.clearWatchlist()
            dao.clearWatchHistory()
            dao.clearSearchHistory()
            dao.clearLogoCache()
            dao.clearAnimeCache()
            dao.clearIdMappings()
            preferencesRepository.resetAllSettings()
            runCatching {
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cachedCount by viewModel.cachedAnimeCount.collectAsStateWithLifecycle()
    val mappingCount by viewModel.mappingCount.collectAsStateWithLifecycle()
    val subPrefs = settings.subtitlePreferences

    var pendingDestructiveAction by remember { mutableStateOf<DestructiveAction?>(null) }
    var statusBannerText by rememberSaveable { mutableStateOf<String?>(null) }

    pendingDestructiveAction?.let { action ->
        TvConfirmDialog(
            title = action.title,
            message = action.message,
            confirmText = "Confirm",
            cancelText = "Cancel",
            onConfirm = {
                when (action) {
                    DestructiveAction.CLEAR_IMAGE_CACHE -> {
                        viewModel.clearImageAndLogoCache(context)
                        statusBannerText = "Image & logo cache cleared."
                    }
                    DestructiveAction.CLEAR_SEARCH_HISTORY -> {
                        viewModel.clearSearchHistory()
                        statusBannerText = "Search history cleared."
                    }
                    DestructiveAction.CLEAR_WATCH_HISTORY -> {
                        viewModel.clearWatchHistory()
                        statusBannerText = "Watch history cleared."
                    }
                    DestructiveAction.CLEAR_ALL_LOCAL_DATA -> {
                        viewModel.clearAllLocalData(context)
                        statusBannerText = "All local data and preferences have been reset."
                    }
                }
            },
            onDismiss = { pendingDestructiveAction = null }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .padding(horizontal = 36.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TvColors.PrimaryText
                )
                Text(
                    text = "Configure playback, subtitle customization, and local television storage",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText
                )
                if (!statusBannerText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvColors.ElevatedSurface)
                            .border(1.dp, TvColors.PrimaryText, RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = statusBannerText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvColors.PrimaryText
                        )
                    }
                }
            }
        }

        // 1. PLAYBACK SETTINGS
        item {
            SettingsSectionCard(title = "Playback") {
                SettingsRowItem(label = "Default Playback Quality") {
                    val qualities = listOf("Auto", "1080p", "720p", "480p", "360p")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(qualities) { q ->
                            TvSelectableChip(
                                label = q,
                                selected = settings.defaultQuality.equals(q, ignoreCase = true),
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setDefaultQuality(q)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsRowItem(label = "Default Audio Language") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("sub" to "SUB (Japanese)", "dub" to "DUB (English)").forEach { (code, label) ->
                            TvSelectableChip(
                                label = label,
                                selected = settings.preferredAudioLanguage.equals(code, ignoreCase = true),
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setPreferredLanguage(code)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsRowItem(label = "Auto Next Episode") {
                    TvSelectableChip(
                        label = if (settings.autoNextEpisode) "Enabled" else "Disabled",
                        selected = settings.autoNextEpisode,
                        onClick = {
                            viewModel.viewModelScope.launch {
                                viewModel.preferencesRepository.setAutoNextEpisode(!settings.autoNextEpisode)
                            }
                        }
                    )
                }

                SettingsRowItem(label = "Skip Intro Automatically (AniSkip)") {
                    TvSelectableChip(
                        label = if (settings.autoSkipIntro) "Enabled" else "Manual Button",
                        selected = settings.autoSkipIntro,
                        onClick = {
                            viewModel.viewModelScope.launch {
                                viewModel.preferencesRepository.setAutoSkipIntro(!settings.autoSkipIntro)
                            }
                        }
                    )
                }

                SettingsRowItem(label = "Resume Playback from Saved Timestamp") {
                    TvSelectableChip(
                        label = if (settings.resumePlayback) "Enabled" else "Start from Beginning",
                        selected = settings.resumePlayback,
                        onClick = {
                            viewModel.viewModelScope.launch {
                                viewModel.preferencesRepository.setResumePlayback(!settings.resumePlayback)
                            }
                        }
                    )
                }
            }
        }

        // 2. SUBTITLE CUSTOMIZATION
        item {
            SettingsSectionCard(title = "Subtitles") {
                // Live Subtitle Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TvColors.MainBackground)
                        .border(1.dp, TvColors.Divider, RoundedCornerShape(6.dp)),
                    contentAlignment = if (subPrefs.position == SubtitlePosition.LOWER_MIDDLE) {
                        Alignment.Center
                    } else {
                        Alignment.BottomCenter
                    }
                ) {
                    val bgColor = when (subPrefs.background) {
                        SubtitleBackground.TRANSPARENT -> Color.Transparent
                        SubtitleBackground.SEMI_TRANSPARENT -> Color.Black.copy(
                            alpha = (subPrefs.backgroundOpacityPercent / 100f).coerceIn(0f, 1f)
                        )
                        SubtitleBackground.SOLID_BLACK -> Color.Black
                    }
                    val textColor = when (subPrefs.color) {
                        SubtitleColor.WHITE -> Color.White
                        SubtitleColor.YELLOW -> Color.Yellow
                        SubtitleColor.CYAN -> Color.Cyan
                        SubtitleColor.GREEN -> TvColors.SkipGreen
                    }
                    Box(
                        modifier = Modifier
                            .padding(bottom = 10.dp)
                            .background(bgColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (subPrefs.enabled) {
                                "Subtitle Preview (${subPrefs.size.label} • ${subPrefs.color.label})"
                            } else {
                                "Subtitles Disabled"
                            },
                            fontSize = (subPrefs.size.fontSizeSp * 0.72f).sp,
                            fontWeight = FontWeight.Bold,
                            color = if (subPrefs.enabled) textColor else TvColors.MutedText
                        )
                    }
                }

                SettingsRowItem(label = "Subtitles") {
                    TvSelectableChip(
                        label = if (subPrefs.enabled) "Enabled" else "Disabled",
                        selected = subPrefs.enabled,
                        onClick = {
                            viewModel.viewModelScope.launch {
                                viewModel.preferencesRepository.setSubtitleEnabled(!subPrefs.enabled)
                            }
                        }
                    )
                }

                SettingsRowItem(label = "Subtitle Size") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubtitleSize.entries) { size ->
                            TvSelectableChip(
                                label = size.label,
                                selected = subPrefs.size == size,
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setSubtitleSize(size)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsRowItem(label = "Subtitle Colour") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubtitleColor.entries) { color ->
                            TvSelectableChip(
                                label = color.label,
                                selected = subPrefs.color == color,
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setSubtitleColor(color)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsRowItem(label = "Subtitle Background") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubtitleBackground.entries) { bg ->
                            TvSelectableChip(
                                label = bg.label,
                                selected = subPrefs.background == bg,
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setSubtitleBackground(bg)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsRowItem(label = "Background Opacity") {
                    val opacities = listOf(25, 50, 75, 100)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        opacities.forEach { pct ->
                            TvSelectableChip(
                                label = "$pct%",
                                selected = subPrefs.backgroundOpacityPercent == pct,
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setSubtitleOpacity(pct)
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsRowItem(label = "Subtitle Outline") {
                    TvSelectableChip(
                        label = if (subPrefs.outlineEnabled) "Outline On" else "Outline Off",
                        selected = subPrefs.outlineEnabled,
                        onClick = {
                            viewModel.viewModelScope.launch {
                                viewModel.preferencesRepository.setSubtitleOutline(!subPrefs.outlineEnabled)
                            }
                        }
                    )
                }

                SettingsRowItem(label = "Subtitle Position") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtitlePosition.entries.forEach { pos ->
                            TvSelectableChip(
                                label = pos.label,
                                selected = subPrefs.position == pos,
                                onClick = {
                                    viewModel.viewModelScope.launch {
                                        viewModel.preferencesRepository.setSubtitlePosition(pos)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. STORAGE & CACHE
        item {
            SettingsSectionCard(title = "Storage & Local Database") {
                Text(
                    text = "Cached Hoshika Anime Entries: $cachedCount  •  Cached Hoshika→AniList ID Mappings: $mappingCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvFocusableButton(
                        text = "Clear Image Cache",
                        onClick = { pendingDestructiveAction = DestructiveAction.CLEAR_IMAGE_CACHE },
                        modifier = Modifier.testTag("settings_clear_image_cache")
                    )
                    TvFocusableButton(
                        text = "Clear Search History",
                        onClick = { pendingDestructiveAction = DestructiveAction.CLEAR_SEARCH_HISTORY },
                        modifier = Modifier.testTag("settings_clear_search_history")
                    )
                    TvFocusableButton(
                        text = "Clear Watch History",
                        onClick = { pendingDestructiveAction = DestructiveAction.CLEAR_WATCH_HISTORY },
                        modifier = Modifier.testTag("settings_clear_watch_history")
                    )
                    TvFocusableButton(
                        text = "Clear All Local Data",
                        onClick = { pendingDestructiveAction = DestructiveAction.CLEAR_ALL_LOCAL_DATA },
                        modifier = Modifier.testTag("settings_clear_all_data")
                    )
                }
            }
        }

        // 4. GENERAL / ABOUT
        item {
            SettingsSectionCard(title = "General & About") {
                Text(
                    text = "AniScroll TV v1.0.0 (Package: in.aniscroll.tv)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.PrimaryText
                )
                Text(
                    text = "Metadata: Hoshika API  •  Streaming: AniScroll HLS  •  Skip: AniSkip v2  •  Logos: TMDB & Fanart.tv",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.SecondaryText
                )
                Text(
                    text = "Designed for Android TV, Google TV, and Amazon Fire TV remote navigation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.MutedText
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.CardBackground)
            .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TvColors.PrimaryText
        )
        content()
    }
}

@Composable
private fun SettingsRowItem(
    label: String,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = TvColors.SecondaryText,
            modifier = Modifier.weight(1f)
        )
        control()
    }
}
