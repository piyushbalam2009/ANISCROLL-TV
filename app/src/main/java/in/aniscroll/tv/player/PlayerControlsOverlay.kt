package `in`.aniscroll.tv.player

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.domain.model.SubtitleBackground
import `in`.aniscroll.tv.domain.model.SubtitleColor
import `in`.aniscroll.tv.domain.model.SubtitlePreferences
import `in`.aniscroll.tv.domain.model.SubtitleSize
import `in`.aniscroll.tv.features.history.formatTimeMs
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class PlayerSettingsPanel {
    NONE,
    QUALITY,
    SUBTITLES,
    SPEED
}

enum class PlayerFocusTarget {
    BACK,
    PREV_EPISODE,
    SEEK_BACK,
    PLAY_PAUSE,
    SEEK_FORWARD,
    NEXT_EPISODE,
    TIMELINE,
    AUDIO,
    SUBTITLES,
    SPEED,
    QUALITY
}

/**
 * Minimal, compact Netflix-style Android TV player controls overlay.
 *
 * Key anti-shake & remote navigation guarantees:
 * - Zero scale animations or layout size/border-width jumps on focus.
 * - Progress bar state is collected ONLY inside [CompactTimelineBar], so 500ms ticks never recompose controls.
 * - Uses static FlowRow layouts inside compact settings panels (no scrolling LazyRows that jump on focus).
 * - Explicit vertical FocusRequester links between Top Back, Center Transport, Thin Timeline, and Settings Strip.
 */
@Composable
fun PlayerControlsOverlay(
    animeTitle: String,
    episodeNumber: Int,
    episodeTitle: String?,
    audioLanguage: String,
    selectedQualityLabel: String,
    playbackSpeed: Float,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    progressStateFlow: StateFlow<PlaybackProgressState>,
    activePanel: PlayerSettingsPanel,
    canInteract: Boolean,
    lastFocusTarget: PlayerFocusTarget,
    onFocusTargetChanged: (PlayerFocusTarget) -> Unit,
    onUserActivity: () -> Unit,
    onBackClick: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextEpisode: () -> Unit,
    onToggleAudioLanguage: () -> Unit,
    onOpenSubtitlesPanel: () -> Unit,
    onOpenSpeedPanel: () -> Unit,
    onOpenQualityPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backFocus = remember { FocusRequester() }
    val prevFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }

    val noPanelOpen = activePanel == PlayerSettingsPanel.NONE && canInteract

    LaunchedEffect(canInteract, activePanel) {
        if (canInteract && activePanel == PlayerSettingsPanel.NONE) {
            val targetRequester = when (lastFocusTarget) {
                PlayerFocusTarget.BACK -> backFocus
                PlayerFocusTarget.PREV_EPISODE -> if (hasPreviousEpisode) prevFocus else playPauseFocus
                PlayerFocusTarget.SEEK_BACK -> rewindFocus
                PlayerFocusTarget.PLAY_PAUSE -> playPauseFocus
                PlayerFocusTarget.SEEK_FORWARD -> forwardFocus
                PlayerFocusTarget.NEXT_EPISODE -> if (hasNextEpisode) nextFocus else playPauseFocus
                PlayerFocusTarget.TIMELINE -> timelineFocus
                PlayerFocusTarget.AUDIO -> audioFocus
                PlayerFocusTarget.SUBTITLES -> subtitlesFocus
                PlayerFocusTarget.SPEED -> speedFocus
                PlayerFocusTarget.QUALITY -> qualityFocus
            }
            runCatching { targetRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        TvColors.MainBackground.copy(alpha = 0.65f),
                        Color.Transparent,
                        Color.Transparent,
                        TvColors.MainBackground.copy(alpha = 0.80f)
                    )
                )
            )
    ) {
        // 1. Compact Top Area: Small Back Icon + Concise Title + Ep/Audio/Quality Metadata
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactPlayerIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                enabled = noPanelOpen,
                focusRequester = backFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        down = playPauseFocus
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocusTargetChanged(PlayerFocusTarget.BACK)
                            onUserActivity()
                        }
                    }
                    .testTag("player_back_button"),
                onClick = {
                    onUserActivity()
                    onBackClick()
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = animeTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    ),
                    color = TvColors.PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val metaLine = buildString {
                    append("Ep $episodeNumber")
                    episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                    append(" • ${audioLanguage.uppercase()}")
                    append(" • $selectedQualityLabel")
                }
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = TvColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 2. Minimal Center Transport Controls: Prev, -10s, Play/Pause, +10s, Next
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactPlayerIconButton(
                icon = Icons.Default.SkipPrevious,
                contentDescription = "Previous Episode",
                enabled = noPanelOpen && hasPreviousEpisode,
                focusRequester = prevFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen && hasPreviousEpisode
                        up = backFocus
                        down = timelineFocus
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocusTargetChanged(PlayerFocusTarget.PREV_EPISODE)
                            onUserActivity()
                        }
                    }
                    .testTag("player_prev_episode"),
                onClick = {
                    onUserActivity()
                    onPreviousEpisode()
                }
            )
            CompactPlayerIconButton(
                icon = Icons.Default.Replay10,
                contentDescription = "Rewind 10 seconds",
                enabled = noPanelOpen,
                focusRequester = rewindFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        up = backFocus
                        down = timelineFocus
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocusTargetChanged(PlayerFocusTarget.SEEK_BACK)
                            onUserActivity()
                        }
                    }
                    .testTag("player_seek_back"),
                onClick = {
                    onUserActivity()
                    onSeekRelative(-10_000L)
                }
            )
            CompactPlayerIconButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                isPrimaryCenter = true,
                enabled = noPanelOpen,
                focusRequester = playPauseFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        up = backFocus
                        down = timelineFocus
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocusTargetChanged(PlayerFocusTarget.PLAY_PAUSE)
                            onUserActivity()
                        }
                    }
                    .testTag("player_play_pause"),
                onClick = {
                    onUserActivity()
                    onTogglePlayPause()
                }
            )
            CompactPlayerIconButton(
                icon = Icons.Default.Forward10,
                contentDescription = "Forward 10 seconds",
                enabled = noPanelOpen,
                focusRequester = forwardFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        up = backFocus
                        down = timelineFocus
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocusTargetChanged(PlayerFocusTarget.SEEK_FORWARD)
                            onUserActivity()
                        }
                    }
                    .testTag("player_seek_forward"),
                onClick = {
                    onUserActivity()
                    onSeekRelative(10_000L)
                }
            )
            CompactPlayerIconButton(
                icon = Icons.Default.SkipNext,
                contentDescription = "Next Episode",
                enabled = noPanelOpen && hasNextEpisode,
                focusRequester = nextFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen && hasNextEpisode
                        up = backFocus
                        down = timelineFocus
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocusTargetChanged(PlayerFocusTarget.NEXT_EPISODE)
                            onUserActivity()
                        }
                    }
                    .testTag("player_next_episode"),
                onClick = {
                    onUserActivity()
                    onNextEpisode()
                }
            )
        }

        // 3. Bottom Area: Ultra-thin Seekable Timeline + Compact Settings Strip
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactTimelineBar(
                progressStateFlow = progressStateFlow,
                enabled = noPanelOpen,
                focusRequester = timelineFocus,
                upFocusRequester = playPauseFocus,
                downFocusRequester = audioFocus,
                onSeekRelative = { delta ->
                    onUserActivity()
                    onSeekRelative(delta)
                },
                onTogglePlayPause = {
                    onUserActivity()
                    onTogglePlayPause()
                },
                onFocused = {
                    onFocusTargetChanged(PlayerFocusTarget.TIMELINE)
                    onUserActivity()
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactUtilityButton(
                        text = if (audioLanguage == "sub") "SUB" else "DUB",
                        icon = Icons.Default.Language,
                        enabled = noPanelOpen,
                        focusRequester = audioFocus,
                        modifier = Modifier
                            .focusProperties {
                                canFocus = noPanelOpen
                                up = timelineFocus
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    onFocusTargetChanged(PlayerFocusTarget.AUDIO)
                                    onUserActivity()
                                }
                            }
                            .testTag("player_audio_toggle"),
                        onClick = {
                            onUserActivity()
                            onToggleAudioLanguage()
                        }
                    )
                    CompactUtilityButton(
                        text = "Subtitles",
                        icon = Icons.Default.ClosedCaption,
                        enabled = noPanelOpen,
                        focusRequester = subtitlesFocus,
                        modifier = Modifier
                            .focusProperties {
                                canFocus = noPanelOpen
                                up = timelineFocus
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    onFocusTargetChanged(PlayerFocusTarget.SUBTITLES)
                                    onUserActivity()
                                }
                            }
                            .testTag("player_subtitles_button"),
                        onClick = {
                            onUserActivity()
                            onOpenSubtitlesPanel()
                        }
                    )
                    val speedLabel = if (playbackSpeed == 1.0f) "1x" else {
                        String.format(Locale.US, "%.2gx", playbackSpeed)
                    }
                    CompactUtilityButton(
                        text = speedLabel,
                        icon = Icons.Default.Speed,
                        enabled = noPanelOpen,
                        focusRequester = speedFocus,
                        modifier = Modifier
                            .focusProperties {
                                canFocus = noPanelOpen
                                up = timelineFocus
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    onFocusTargetChanged(PlayerFocusTarget.SPEED)
                                    onUserActivity()
                                }
                            }
                            .testTag("player_speed_button"),
                        onClick = {
                            onUserActivity()
                            onOpenSpeedPanel()
                        }
                    )
                    CompactUtilityButton(
                        text = selectedQualityLabel,
                        icon = Icons.Default.HighQuality,
                        enabled = noPanelOpen,
                        focusRequester = qualityFocus,
                        modifier = Modifier
                            .focusProperties {
                                canFocus = noPanelOpen
                                up = timelineFocus
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    onFocusTargetChanged(PlayerFocusTarget.QUALITY)
                                    onUserActivity()
                                }
                            }
                            .testTag("player_quality_button"),
                        onClick = {
                            onUserActivity()
                            onOpenQualityPanel()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Isolated thin progress bar & tabular timestamps.
 * Only this composable recomposes during 500ms playback position updates.
 */
@Composable
private fun CompactTimelineBar(
    progressStateFlow: StateFlow<PlaybackProgressState>,
    enabled: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onSeekRelative: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onFocused: () -> Unit
) {
    val progressState by progressStateFlow.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }

    val duration = progressState.durationMs.coerceAtLeast(1L)
    val pos = progressState.currentPositionMs.coerceIn(0L, duration)
    val playedFraction = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (progressState.bufferedPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                up = upFocusRequester
                down = downFocusRequester
            }
            .onKeyEvent { event ->
                if (!enabled || event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeekRelative(-10_000L)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeekRelative(10_000L)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onTogglePlayPause()
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .padding(vertical = 4.dp)
            .testTag("player_timeline_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTimeMs(pos),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum",
                fontSize = 12.sp
            ),
            color = if (isFocused) TvColors.PrimaryText else TvColors.SecondaryText,
            modifier = Modifier.widthIn(min = 48.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackHeight = if (isFocused) 4.dp else 2.5.dp
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TvColors.PrimaryText.copy(alpha = 0.22f))
            )
            // Buffered track
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedFraction)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TvColors.PrimaryText.copy(alpha = 0.42f))
            )
            // Active played track
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFraction)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TvColors.PrimaryText)
            )
            // Small focus thumb indicator (fixed layout space via BoxWithConstraints, zero reflow)
            if (isFocused) {
                val thumbOffset = (maxWidth - 10.dp) * playedFraction
                Box(
                    modifier = Modifier
                        .offset(x = thumbOffset)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(TvColors.PrimaryText)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatTimeMs(progressState.durationMs),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum",
                fontSize = 12.sp
            ),
            color = TvColors.SecondaryText,
            modifier = Modifier.widthIn(min = 48.dp)
        )
    }
}

@Composable
private fun CompactPlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimaryCenter: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            isFocused -> TvColors.PrimaryText
            isPrimaryCenter -> TvColors.MainBackground.copy(alpha = 0.55f)
            else -> TvColors.MainBackground.copy(alpha = 0.35f)
        },
        animationSpec = tween(durationMillis = 120),
        label = "icon_btn_bg"
    )
    val fgColor by animateColorAsState(
        targetValue = when {
            !enabled -> TvColors.MutedText.copy(alpha = 0.45f)
            isFocused -> TvColors.FocusText
            else -> TvColors.PrimaryText
        },
        animationSpec = tween(durationMillis = 120),
        label = "icon_btn_fg"
    )
    val borderColor = when {
        !enabled -> TvColors.Divider.copy(alpha = 0.35f)
        isFocused -> TvColors.PrimaryText
        isPrimaryCenter -> TvColors.PrimaryText.copy(alpha = 0.55f)
        else -> TvColors.PrimaryText.copy(alpha = 0.25f)
    }

    val buttonSize = if (isPrimaryCenter) 48.dp else 40.dp
    val iconSize = if (isPrimaryCenter) 26.dp else 20.dp
    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = modifier
            .then(focusMod)
            .minimumInteractiveComponentSize()
            .size(buttonSize)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .onKeyEvent { event ->
                if (enabled && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fgColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun CompactUtilityButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> TvColors.CardBackground.copy(alpha = 0.4f)
            isFocused -> TvColors.PrimaryText
            else -> TvColors.MainBackground.copy(alpha = 0.55f)
        },
        animationSpec = tween(durationMillis = 120),
        label = "util_bg"
    )
    val fgColor by animateColorAsState(
        targetValue = when {
            !enabled -> TvColors.MutedText
            isFocused -> TvColors.FocusText
            else -> TvColors.PrimaryText
        },
        animationSpec = tween(durationMillis = 120),
        label = "util_fg"
    )
    val borderColor = if (isFocused) TvColors.PrimaryText else TvColors.PrimaryText.copy(alpha = 0.28f)
    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = modifier
            .then(focusMod)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(6.dp))
            .onKeyEvent { event ->
                if (enabled && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = fgColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = fgColor,
            maxLines = 1
        )
    }
}

/**
 * Compact anchored settings overlay panels (Quality, Subtitles, Playback Speed).
 * Uses static non-scrolling FlowRow layouts instead of LazyRow/LazyColumn so D-pad navigation
 * never causes viewport scrolling or visual shaking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactPlayerSettingsOverlay(
    activePanel: PlayerSettingsPanel,
    state: PlaybackUiState,
    subtitlePrefs: SubtitlePreferences,
    onSelectQuality: (AvailableQualityOption) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectSubtitleTrack: (String?, Boolean) -> Unit,
    onUpdateSubtitleSize: (SubtitleSize) -> Unit,
    onUpdateSubtitleColor: (SubtitleColor) -> Unit,
    onUpdateSubtitleBackground: (SubtitleBackground) -> Unit,
    onUserActivity: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activePanel == PlayerSettingsPanel.NONE) return

    val firstOptionFocus = remember(activePanel) { FocusRequester() }
    LaunchedEffect(activePanel) {
        runCatching { firstOptionFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(end = 28.dp, bottom = 72.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = TvColors.SecondaryBackground.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, TvColors.PrimaryText.copy(alpha = 0.35f)),
            modifier = Modifier
                .widthIn(min = 260.dp, max = 440.dp)
                .testTag("player_compact_settings_panel")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (activePanel) {
                    PlayerSettingsPanel.QUALITY -> {
                        Text(
                            text = "Quality",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TvColors.PrimaryText
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.availableQualities.forEachIndexed { idx, option ->
                                CompactSettingsChip(
                                    label = option.label,
                                    selected = state.selectedQualityLabel.equals(option.label, ignoreCase = true),
                                    focusRequester = if (idx == 0) firstOptionFocus else null,
                                    onFocused = onUserActivity,
                                    onClick = {
                                        onUserActivity()
                                        onSelectQuality(option)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    PlayerSettingsPanel.SPEED -> {
                        Text(
                            text = "Playback Speed",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TvColors.PrimaryText
                        )
                        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            speeds.forEachIndexed { idx, speed ->
                                val label = if (speed == 1.0f) "1.0x" else "${speed}x"
                                CompactSettingsChip(
                                    label = label,
                                    selected = kotlin.math.abs(state.playbackSpeed - speed) < 0.01f,
                                    focusRequester = if (idx == 0) firstOptionFocus else null,
                                    onFocused = onUserActivity,
                                    onClick = {
                                        onUserActivity()
                                        onSelectSpeed(speed)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    PlayerSettingsPanel.SUBTITLES -> {
                        Text(
                            text = "Subtitles",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TvColors.PrimaryText
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactSettingsChip(
                                label = "Off",
                                selected = !state.subtitlesEnabled,
                                focusRequester = firstOptionFocus,
                                onFocused = onUserActivity,
                                onClick = {
                                    onUserActivity()
                                    onSelectSubtitleTrack(null, false)
                                }
                            )
                            state.availableSubtitles.forEach { track ->
                                CompactSettingsChip(
                                    label = track.label,
                                    selected = state.subtitlesEnabled &&
                                        state.selectedSubtitleLanguage.equals(track.language, ignoreCase = true),
                                    onFocused = onUserActivity,
                                    onClick = {
                                        onUserActivity()
                                        onSelectSubtitleTrack(track.language, true)
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Size",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.SecondaryText
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SubtitleSize.entries.forEach { size ->
                                CompactSettingsChip(
                                    label = size.label,
                                    selected = subtitlePrefs.size == size,
                                    onFocused = onUserActivity,
                                    onClick = {
                                        onUserActivity()
                                        onUpdateSubtitleSize(size)
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Style",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.SecondaryText
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SubtitleColor.entries.forEach { color ->
                                CompactSettingsChip(
                                    label = color.label,
                                    selected = subtitlePrefs.color == color,
                                    onFocused = onUserActivity,
                                    onClick = {
                                        onUserActivity()
                                        onUpdateSubtitleColor(color)
                                    }
                                )
                            }
                            SubtitleBackground.entries.forEach { bg ->
                                val shortLabel = when (bg) {
                                    SubtitleBackground.TRANSPARENT -> "Clear BG"
                                    SubtitleBackground.SEMI_TRANSPARENT -> "Dim BG"
                                    SubtitleBackground.SOLID_BLACK -> "Black BG"
                                }
                                CompactSettingsChip(
                                    label = shortLabel,
                                    selected = subtitlePrefs.background == bg,
                                    onFocused = onUserActivity,
                                    onClick = {
                                        onUserActivity()
                                        onUpdateSubtitleBackground(bg)
                                    }
                                )
                            }
                        }
                    }

                    PlayerSettingsPanel.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun CompactSettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused?.invoke()
    }

    val bg = when {
        isFocused -> TvColors.PrimaryText
        selected -> TvColors.ElevatedSurface
        else -> TvColors.CardBackground
    }
    val fg = when {
        isFocused -> TvColors.FocusText
        selected -> TvColors.PrimaryText
        else -> TvColors.SecondaryText
    }
    val border = when {
        isFocused -> TvColors.PrimaryText
        selected -> TvColors.PrimaryText.copy(alpha = 0.7f)
        else -> TvColors.Divider
    }
    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = modifier
            .then(focusMod)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(5.dp))
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = fg,
            maxLines = 1
        )
    }
}
