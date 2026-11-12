package `in`.aniscroll.tv.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.core.designsystem.TvSelectableChip
import `in`.aniscroll.tv.domain.model.SubtitleBackground
import `in`.aniscroll.tv.domain.model.SubtitleColor
import `in`.aniscroll.tv.domain.model.SubtitlePosition
import `in`.aniscroll.tv.domain.model.SubtitleSize
import `in`.aniscroll.tv.features.history.formatTimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    hoshikaId: Long,
    episodeNumber: Int,
    initialLanguage: String?,
    controller: PlaybackController,
    onExitPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val settings by controller.appSettings.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var controlsVisible by remember { mutableStateOf(true) }
    var lastUserInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    val playPauseFocusRequester = remember { FocusRequester() }
    val skipButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(hoshikaId, episodeNumber) {
        controller.startPlaybackSession(
            hoshikaId = hoshikaId,
            episodeNumber = episodeNumber,
            requestedLanguage = initialLanguage
        )
    }

    // Save progress and pause when entering background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                controller.pauseAndSaveOnStop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.pauseAndSaveOnStop()
        }
    }

    // Auto-hide controls after 4.5s of inactivity while video is playing
    LaunchedEffect(controlsVisible, lastUserInteractionMs, state.isPlaying, showQualityDialog, showSubtitleDialog) {
        if (controlsVisible && state.isPlaying && !showQualityDialog && !showSubtitleDialog) {
            delay(4500L)
            controlsVisible = false
        }
    }

    // Auto-dismiss transient toast messages
    LaunchedEffect(state.transientToastMessage) {
        if (state.transientToastMessage != null) {
            delay(3200L)
            controller.clearTransientMessage()
        }
    }

    // Back button behavior (Section 18):
    // Pressing Back hides controls first; a subsequent Back action returns to the previous screen.
    BackHandler {
        when {
            showQualityDialog -> showQualityDialog = false
            showSubtitleDialog -> showSubtitleDialog = false
            controlsVisible -> controlsVisible = false
            else -> {
                controller.pauseAndSaveOnStop()
                onExitPlayer()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    lastUserInteractionMs = System.currentTimeMillis()
                    val keyCode = event.nativeKeyEvent.keyCode

                    // Media hardware buttons
                    when (keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            controller.togglePlayPause()
                            controlsVisible = true
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            controller.playNextEpisodeNow()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            controller.playPreviousEpisode()
                            return@onPreviewKeyEvent true
                        }
                    }

                    // If controls are hidden and no skip prompt is focused, wake controls on D-pad press
                    if (!controlsVisible && state.activeSkipPrompt == null &&
                        keyCode in listOf(
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_MENU
                        )
                    ) {
                        controlsVisible = true
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable()
    ) {
        // 1. Native AndroidX Media3 PlayerView Surface
        val exoPlayer = remember { controller.exoPlayerManager.getOrCreatePlayer() }
        val subtitlePrefs = settings.subtitlePreferences

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    keepScreenOn = true
                    subtitleView?.let { subView ->
                        controller.subtitleManager.applySubtitlePreferencesToView(subView, subtitlePrefs)
                    }
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.subtitleView?.let { subView ->
                    controller.subtitleManager.applySubtitlePreferencesToView(subView, subtitlePrefs)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Buffering / Loading Indicator (shown separately from progress bar)
        if (state.isLoading || state.isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvColors.MainBackground.copy(alpha = 0.72f))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = TvColors.PrimaryText,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (state.isLoading) "Preparing stream..." else "Buffering...",
                        style = MaterialTheme.typography.titleMedium,
                        color = TvColors.PrimaryText
                    )
                }
            }
        }

        // 3. Error Overlay
        if (state.errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvColors.MainBackground.copy(alpha = 0.9f))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 520.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TvErrorBanner(
                        message = state.errorMessage!!,
                        onRetry = {
                            controller.startPlaybackSession(
                                hoshikaId = hoshikaId,
                                episodeNumber = state.currentEpisodeNumber,
                                requestedLanguage = state.audioLanguage
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val altLang = if (state.audioLanguage == "sub") "dub" else "sub"
                        TvFocusableButton(
                            text = "Try ${altLang.uppercase()}",
                            onClick = { controller.switchAudioLanguage(altLang) }
                        )
                        TvFocusableButton(
                            text = "Exit Player",
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            onClick = onExitPlayer
                        )
                    }
                }
            }
        }

        // 4. Transient status toast (e.g., SUB/DUB switch notice)
        state.transientToastMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.SecondaryBackground.copy(alpha = 0.9f))
                    .border(1.dp, TvColors.PrimaryText, RoundedCornerShape(6.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.PrimaryText
                )
            }
        }

        // 5. RIGHT-SIDE ANISKIP OVERLAY BUTTON ("Skip Intro  >>" / "Skip Outro  >>")
        // Minimal transparent style with green text/accent (#22C55E) on the right side of the player.
        AnimatedVisibility(
            visible = state.activeSkipPrompt != null && state.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 36.dp)
        ) {
            state.activeSkipPrompt?.let { prompt ->
                LaunchedEffect(prompt.type, controlsVisible) {
                    if (!controlsVisible) {
                        runCatching { skipButtonFocusRequester.requestFocus() }
                    }
                }
                TvFocusableButton(
                    text = prompt.label,
                    isAccentGreen = true,
                    focusRequester = skipButtonFocusRequester,
                    onClick = { controller.performSkip(prompt) },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("player_aniskip_button")
                )
            }
        }

        // 6. AUTO NEXT EPISODE COUNTDOWN OVERLAY (Section 22)
        AnimatedVisibility(
            visible = state.showNextEpisodeCountdown && state.nextEpisode != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = 110.dp)
        ) {
            state.nextEpisode?.let { nextEp ->
                Column(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 380.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TvColors.SecondaryBackground.copy(alpha = 0.95f))
                        .border(1.5.dp, TvColors.PrimaryText, RoundedCornerShape(8.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Next Episode in ${state.nextEpisodeCountdownSec}s",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TvColors.PrimaryText
                    )
                    Text(
                        text = "Episode ${nextEp.number}${nextEp.title?.let { " — $it" } ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.SecondaryText,
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvFocusableButton(
                            text = "Play Now",
                            isPrimary = true,
                            onClick = { controller.playNextEpisodeNow() },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("next_ep_play_now")
                        )
                        TvFocusableButton(
                            text = "Cancel",
                            onClick = { controller.cancelNextEpisodeCountdown() },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("next_ep_cancel")
                        )
                    }
                }
            }
        }

        // 7. CUSTOM TELEVISION PLAYER CONTROLS OVERLAY
        AnimatedVisibility(
            visible = controlsVisible && state.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                TvColors.MainBackground.copy(alpha = 0.78f),
                                Color.Transparent,
                                Color.Transparent,
                                TvColors.MainBackground.copy(alpha = 0.90f)
                            )
                        )
                    )
            ) {
                // Top Bar: Back, Anime Title, Episode Number & Title, Current Stream Badges
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 22.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvFocusableButton(
                            text = "Back",
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            onClick = {
                                controller.pauseAndSaveOnStop()
                                onExitPlayer()
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("player_back_button")
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = state.anime?.preferredTitle ?: "Playing Anime",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TvColors.PrimaryText
                            )
                            val epSubtitle = buildString {
                                append("Episode ${state.currentEpisodeNumber}")
                                state.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let {
                                    append("  •  $it")
                                }
                            }
                            Text(
                                text = epSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvColors.SecondaryText
                            )
                        }
                    }

                    // Active stream badges
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvColors.ElevatedSurface)
                                .border(1.dp, TvColors.Divider, RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = state.audioLanguage.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = TvColors.PrimaryText
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TvColors.ElevatedSurface)
                                .border(1.dp, TvColors.Divider, RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = state.selectedQualityLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = TvColors.PrimaryText
                            )
                        }
                    }
                }

                // Bottom Control Deck: Thin minimalist progress bar + D-pad transport & track buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 22.dp)
                ) {
                    // Thin minimalist progress bar + current & remaining time
                    val duration = state.durationMs.coerceAtLeast(1L)
                    val progressFraction = (state.currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    val remainingMs = (state.durationMs - state.currentPositionMs).coerceAtLeast(0L)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimeMs(state.currentPositionMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = TvColors.PrimaryText
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp),
                            color = TvColors.PrimaryText,
                            trackColor = TvColors.Divider
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "-${formatTimeMs(remainingMs)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = TvColors.SecondaryText
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Control Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Transport Controls: Prev Ep, -10s, Play/Pause, +10s, Next Ep
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvFocusableButton(
                                text = "Prev",
                                icon = Icons.Default.SkipPrevious,
                                enabled = state.previousEpisode != null,
                                onClick = { controller.playPreviousEpisode() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_prev_episode")
                            )
                            TvFocusableButton(
                                text = "-10s",
                                icon = Icons.Default.Replay10,
                                onClick = { controller.seekRelative(-10_000L) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_seek_back")
                            )
                            TvFocusableButton(
                                text = if (state.isPlaying) "Pause" else "Play",
                                icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                isPrimary = true,
                                focusRequester = playPauseFocusRequester,
                                onClick = { controller.togglePlayPause() },
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_play_pause")
                            )
                            TvFocusableButton(
                                text = "+10s",
                                icon = Icons.Default.Forward10,
                                onClick = { controller.seekRelative(10_000L) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_seek_forward")
                            )
                            TvFocusableButton(
                                text = "Next",
                                icon = Icons.Default.SkipNext,
                                enabled = state.nextEpisode != null,
                                onClick = { controller.playNextEpisodeNow() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_next_episode")
                            )
                        }

                        // Right Settings Controls: SUB/DUB, Subtitles, Quality
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvFocusableButton(
                                text = if (state.audioLanguage == "sub") "Audio: SUB" else "Audio: DUB",
                                icon = Icons.Default.Language,
                                onClick = {
                                    val nextLang = if (state.audioLanguage == "sub") "dub" else "sub"
                                    controller.switchAudioLanguage(nextLang)
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_audio_toggle")
                            )
                            TvFocusableButton(
                                text = "Subtitles",
                                icon = Icons.Default.ClosedCaption,
                                onClick = { showSubtitleDialog = true },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_subtitles_button")
                            )
                            TvFocusableButton(
                                text = "Quality: ${state.selectedQualityLabel}",
                                icon = Icons.Default.HighQuality,
                                onClick = { showQualityDialog = true },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("player_quality_button")
                            )
                        }
                    }
                }

                LaunchedEffect(controlsVisible) {
                    if (controlsVisible) {
                        runCatching { playPauseFocusRequester.requestFocus() }
                    }
                }
            }
        }

        // 8. Quality Selection Dialog
        if (showQualityDialog) {
            Dialog(onDismissRequest = { showQualityDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvColors.SecondaryBackground,
                    border = BorderStroke(1.dp, TvColors.Divider),
                    modifier = Modifier.widthIn(min = 340.dp, max = 480.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Stream Quality",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TvColors.PrimaryText
                        )
                        Text(
                            text = "Only qualities returned by the stream or HLS master playlist are shown.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvColors.SecondaryText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.availableQualities) { option ->
                                TvSelectableChip(
                                    label = option.label,
                                    selected = state.selectedQualityLabel.equals(option.label, ignoreCase = true),
                                    onClick = {
                                        controller.selectQuality(option)
                                        showQualityDialog = false
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TvFocusableButton(
                                text = "Close",
                                onClick = { showQualityDialog = false }
                            )
                        }
                    }
                }
            }
        }

        // 9. Subtitle Track & Customization Dialog
        if (showSubtitleDialog) {
            Dialog(onDismissRequest = { showSubtitleDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TvColors.SecondaryBackground,
                    border = BorderStroke(1.dp, TvColors.Divider),
                    modifier = Modifier.widthIn(min = 420.dp, max = 560.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Subtitles & Styling",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TvColors.PrimaryText
                        )

                        // Track selection
                        if (state.availableSubtitles.isEmpty()) {
                            Text(
                                text = "No external subtitles returned (embedded HLS captions will render if enabled).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvColors.SecondaryText
                            )
                        } else {
                            Text(
                                text = "Available Tracks",
                                style = MaterialTheme.typography.titleMedium,
                                color = TvColors.SecondaryText
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    TvSelectableChip(
                                        label = "Off",
                                        selected = !state.subtitlesEnabled,
                                        onClick = { controller.selectSubtitleTrack(null, enabled = false) }
                                    )
                                }
                                items(state.availableSubtitles) { track ->
                                    TvSelectableChip(
                                        label = track.label,
                                        selected = state.subtitlesEnabled &&
                                            state.selectedSubtitleLanguage.equals(track.language, ignoreCase = true),
                                        onClick = {
                                            controller.selectSubtitleTrack(track.language, enabled = true)
                                        }
                                    )
                                }
                            }
                        }

                        // Quick Size & Colour Customization
                        Text(
                            text = "Subtitle Size",
                            style = MaterialTheme.typography.titleMedium,
                            color = TvColors.SecondaryText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SubtitleSize.entries) { size ->
                                TvSelectableChip(
                                    label = size.label,
                                    selected = subtitlePrefs.size == size,
                                    onClick = {
                                        controller.viewModelScope.launch {
                                            controller.preferencesRepository.setSubtitleSize(size)
                                        }
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Subtitle Colour",
                            style = MaterialTheme.typography.titleMedium,
                            color = TvColors.SecondaryText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SubtitleColor.entries) { color ->
                                TvSelectableChip(
                                    label = color.label,
                                    selected = subtitlePrefs.color == color,
                                    onClick = {
                                        controller.viewModelScope.launch {
                                            controller.preferencesRepository.setSubtitleColor(color)
                                        }
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Background & Position",
                            style = MaterialTheme.typography.titleMedium,
                            color = TvColors.SecondaryText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SubtitleBackground.entries) { bg ->
                                TvSelectableChip(
                                    label = bg.label,
                                    selected = subtitlePrefs.background == bg,
                                    onClick = {
                                        controller.viewModelScope.launch {
                                            controller.preferencesRepository.setSubtitleBackground(bg)
                                        }
                                    }
                                )
                            }
                            items(SubtitlePosition.entries) { pos ->
                                TvSelectableChip(
                                    label = pos.label,
                                    selected = subtitlePrefs.position == pos,
                                    onClick = {
                                        controller.viewModelScope.launch {
                                            controller.preferencesRepository.setSubtitlePosition(pos)
                                        }
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TvFocusableButton(
                                text = "Done",
                                isPrimary = true,
                                onClick = { showSubtitleDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
