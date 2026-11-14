package `in`.aniscroll.tv.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.aniscroll.tv.domain.model.SubtitleBackground
import `in`.aniscroll.tv.domain.model.SubtitleColor
import `in`.aniscroll.tv.domain.model.SubtitlePosition
import `in`.aniscroll.tv.domain.model.SubtitlePreferences
import `in`.aniscroll.tv.domain.model.SubtitleSize
import `in`.aniscroll.tv.domain.model.SubtitleTrack
import `in`.aniscroll.tv.features.history.formatTimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val PureWhite = Color(0xFFFFFFFF)
private val PureBlack = Color(0xFF000000)

enum class PlayerSettingsPanel {
    NONE,
    SUBTITLES,
    AUDIO,
    QUALITY,
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
    SUBTITLES,
    AUDIO,
    SPEED,
    QUALITY
}

/**
 * Minimalist, circular, remote-controlled Android TV player controls overlay.
 *
 * Design principles:
 * - Every primary control uses [PlayerControlButton]: circular, transparent background with white icon (#FFFFFF)
 *   when unfocused, smoothly inverting to a solid white circle (#FFFFFF) with a black icon (#000000) and
 *   subtle 1.08x scale when focused with the TV remote.
 * - Center controls: Rewind, Play/Pause, Fast Forward.
 * - Bottom controls: Slim [PlayerProgressBar] with timestamps & seek preview, plus Previous/Next Episode
 *   (only when applicable) and circular Subtitle & Audio Track menu triggers.
 */
@Composable
fun PlayerControlsOverlay(
    animeTitle: String,
    episodeNumber: Int,
    episodeTitle: String?,
    audioLanguage: String,
    selectedQualityLabel: String = "Auto",
    playbackSpeed: Float = 1.0f,
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
    onOpenSpeedPanel: () -> Unit = {},
    onOpenQualityPanel: () -> Unit = {},
    modifier: Modifier = Modifier,
    isMovie: Boolean = false,
    onSeekingChanged: (Boolean) -> Unit = {}
) {
    val backFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    val prevFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }

    val showPrevEpisode = hasPreviousEpisode && !isMovie
    val showNextEpisode = hasNextEpisode && !isMovie
    val noPanelOpen = activePanel == PlayerSettingsPanel.NONE && canInteract

    LaunchedEffect(canInteract, activePanel) {
        if (canInteract && activePanel == PlayerSettingsPanel.NONE) {
            val targetRequester = when (lastFocusTarget) {
                PlayerFocusTarget.BACK -> backFocus
                PlayerFocusTarget.SEEK_BACK -> rewindFocus
                PlayerFocusTarget.PLAY_PAUSE -> playPauseFocus
                PlayerFocusTarget.SEEK_FORWARD -> forwardFocus
                PlayerFocusTarget.TIMELINE -> timelineFocus
                PlayerFocusTarget.PREV_EPISODE -> if (showPrevEpisode) prevFocus else playPauseFocus
                PlayerFocusTarget.NEXT_EPISODE -> if (showNextEpisode) nextFocus else playPauseFocus
                PlayerFocusTarget.SUBTITLES -> subtitlesFocus
                PlayerFocusTarget.AUDIO -> audioFocus
                PlayerFocusTarget.SPEED,
                PlayerFocusTarget.QUALITY -> subtitlesFocus
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
                        PureBlack.copy(alpha = 0.52f),
                        Color.Transparent,
                        Color.Transparent,
                        PureBlack.copy(alpha = 0.76f)
                    )
                )
            )
    ) {
        // 1. Minimal Top Bar: Circular Back Control + Clean Title & Episode Subtitle
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerControlButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                enabled = noPanelOpen,
                size = 44.dp,
                iconSize = 22.dp,
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
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = animeTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp
                    ),
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitleText = buildString {
                    if (isMovie) {
                        append("Movie")
                    } else {
                        append("Episode $episodeNumber")
                        episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                    }
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = PureWhite.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 2. Center Controls: Rewind, Play/Pause, Fast Forward (Circular White Controls)
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerControlButton(
                icon = Icons.Default.Replay10,
                contentDescription = "Rewind 10 seconds",
                enabled = noPanelOpen,
                size = 52.dp,
                iconSize = 26.dp,
                focusRequester = rewindFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        up = backFocus
                        down = timelineFocus
                        right = playPauseFocus
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

            PlayerControlButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                enabled = noPanelOpen,
                size = 64.dp,
                iconSize = 34.dp,
                focusRequester = playPauseFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        up = backFocus
                        down = timelineFocus
                        left = rewindFocus
                        right = forwardFocus
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

            PlayerControlButton(
                icon = Icons.Default.Forward10,
                contentDescription = "Fast Forward 10 seconds",
                enabled = noPanelOpen,
                size = 52.dp,
                iconSize = 26.dp,
                focusRequester = forwardFocus,
                modifier = Modifier
                    .focusProperties {
                        canFocus = noPanelOpen
                        up = backFocus
                        down = timelineFocus
                        left = playPauseFocus
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
        }

        // 3. Bottom Controls: Slim Progress Bar + Minimal Circular Bottom Row
        val firstBottomFocus = when {
            showPrevEpisode -> prevFocus
            showNextEpisode -> nextFocus
            else -> subtitlesFocus
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlayerProgressBar(
                progressStateFlow = progressStateFlow,
                enabled = noPanelOpen,
                focusRequester = timelineFocus,
                upFocusRequester = playPauseFocus,
                downFocusRequester = firstBottomFocus,
                onSeekRelative = { delta ->
                    onUserActivity()
                    onSeekRelative(delta)
                },
                onTogglePlayPause = {
                    onUserActivity()
                    onTogglePlayPause()
                },
                onSeekingChanged = onSeekingChanged,
                onFocused = {
                    onFocusTargetChanged(PlayerFocusTarget.TIMELINE)
                    onUserActivity()
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left bottom group: Episode navigation controls (shown only when applicable)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showPrevEpisode) {
                        PlayerControlButton(
                            icon = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Episode",
                            enabled = noPanelOpen,
                            size = 44.dp,
                            iconSize = 22.dp,
                            focusRequester = prevFocus,
                            modifier = Modifier
                                .focusProperties {
                                    canFocus = noPanelOpen
                                    up = timelineFocus
                                    right = if (showNextEpisode) nextFocus else subtitlesFocus
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
                    }

                    if (showNextEpisode) {
                        PlayerControlButton(
                            icon = Icons.Default.SkipNext,
                            contentDescription = "Next Episode",
                            enabled = noPanelOpen,
                            size = 44.dp,
                            iconSize = 22.dp,
                            focusRequester = nextFocus,
                            modifier = Modifier
                                .focusProperties {
                                    canFocus = noPanelOpen
                                    up = timelineFocus
                                    left = if (showPrevEpisode) prevFocus else FocusRequester.Default
                                    right = subtitlesFocus
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
                }

                // Right bottom group: Subtitles & Audio Track circular controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerControlButton(
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = "Subtitles",
                        enabled = noPanelOpen,
                        size = 44.dp,
                        iconSize = 22.dp,
                        focusRequester = subtitlesFocus,
                        modifier = Modifier
                            .focusProperties {
                                canFocus = noPanelOpen
                                up = timelineFocus
                                left = when {
                                    showNextEpisode -> nextFocus
                                    showPrevEpisode -> prevFocus
                                    else -> FocusRequester.Default
                                }
                                right = audioFocus
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

                    PlayerControlButton(
                        icon = Icons.Default.Language,
                        contentDescription = "Audio Track (${audioLanguage.uppercase()})",
                        enabled = noPanelOpen,
                        size = 44.dp,
                        iconSize = 22.dp,
                        focusRequester = audioFocus,
                        modifier = Modifier
                            .focusProperties {
                                canFocus = noPanelOpen
                                up = timelineFocus
                                left = subtitlesFocus
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
                }
            }
        }
    }
}

/**
 * Circular white player control button.
 *
 * - Unfocused: White icon (#FFFFFF), transparent background, no border, scale 1.0.
 * - Focused: Solid white circular background (#FFFFFF), black icon (#000000), scale 1.08.
 */
@Composable
fun PlayerControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = if (enabled && isFocused) PureWhite else Color.Transparent,
        animationSpec = tween(durationMillis = 140),
        label = "player_control_bg"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            !enabled -> PureWhite.copy(alpha = 0.35f)
            isFocused -> PureBlack
            else -> PureWhite
        },
        animationSpec = tween(durationMillis = 140),
        label = "player_control_icon"
    )
    val scale by animateFloatAsState(
        targetValue = if (enabled && isFocused) 1.08f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "player_control_scale"
    )

    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Box(
        modifier = modifier
            .then(focusModifier)
            .minimumInteractiveComponentSize()
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(backgroundColor, CircleShape)
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
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Slim, remote-focusable playback progress bar with seek preview time.
 * Isolated from the rest of the player so 500ms playback updates only recompose this bar.
 */
@Composable
fun PlayerProgressBar(
    progressStateFlow: StateFlow<PlaybackProgressState>,
    enabled: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onSeekRelative: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekingChanged: (Boolean) -> Unit = {},
    onFocused: () -> Unit
) {
    val progressState by progressStateFlow.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var isActivelySeeking by remember { mutableStateOf(false) }
    var lastSeekKeyPressMs by remember { mutableLongStateOf(0L) }
    var seekPreviewPositionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused()
        } else if (isActivelySeeking) {
            isActivelySeeking = false
            onSeekingChanged(false)
        }
    }

    LaunchedEffect(isActivelySeeking, lastSeekKeyPressMs) {
        if (isActivelySeeking) {
            delay(1200L)
            isActivelySeeking = false
            onSeekingChanged(false)
        }
    }

    val duration = progressState.durationMs.coerceAtLeast(1L)
    val pos = progressState.currentPositionMs.coerceIn(0L, duration)
    val displayPos = if (isActivelySeeking) seekPreviewPositionMs.coerceIn(0L, duration) else pos
    val playedFraction = (displayPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (progressState.bufferedPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Seek preview time badge when actively seeking with Left/Right remote keys
        AnimatedVisibility(
            visible = isFocused && isActivelySeeking,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PureWhite)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatTimeMs(displayPos),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    color = PureBlack
                )
            }
        }

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
                            val target = (pos - 10_000L).coerceAtLeast(0L)
                            seekPreviewPositionMs = target
                            isActivelySeeking = true
                            lastSeekKeyPressMs = System.currentTimeMillis()
                            onSeekingChanged(true)
                            onSeekRelative(-10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val target = (pos + 10_000L).coerceAtMost(duration)
                            seekPreviewPositionMs = target
                            isActivelySeeking = true
                            lastSeekKeyPressMs = System.currentTimeMillis()
                            onSeekingChanged(true)
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
                text = formatTimeMs(displayPos),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontSize = 12.sp
                ),
                color = if (isFocused) PureWhite else PureWhite.copy(alpha = 0.72f),
                modifier = Modifier.widthIn(min = 46.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val trackHeight = if (isFocused) 3.dp else 2.dp
                // Subtle inactive track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .clip(CircleShape)
                        .background(PureWhite.copy(alpha = 0.22f))
                )
                // Subtle buffered track
                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferedFraction)
                        .height(trackHeight)
                        .clip(CircleShape)
                        .background(PureWhite.copy(alpha = 0.38f))
                )
                // White active progress indicator
                Box(
                    modifier = Modifier
                        .fillMaxWidth(playedFraction)
                        .height(trackHeight)
                        .clip(CircleShape)
                        .background(PureWhite)
                )
                // Circular focus indicator thumb
                if (isFocused) {
                    val thumbSize = 10.dp
                    val thumbOffset = (maxWidth - thumbSize) * playedFraction
                    Box(
                        modifier = Modifier
                            .offset(x = thumbOffset)
                            .size(thumbSize)
                            .clip(CircleShape)
                            .background(PureWhite, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = formatTimeMs(progressState.durationMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontSize = 12.sp
                ),
                color = PureWhite.copy(alpha = 0.72f),
                modifier = Modifier.widthIn(min = 46.dp)
            )
        }
    }
}

/**
 * Minimal White-Theme Skip Intro / Skip Outro pill button.
 *
 * - Unfocused: White text (#FFFFFF) and white skip icon (#FFFFFF) on a completely transparent background.
 * - Focused: Solid white background (#FFFFFF) with black text (#000000) and black skip icon (#000000),
 *   plus subtle 1.08x scale increase.
 * - Never uses green colors.
 */
@Composable
fun SkipIntroOutroButton(
    prompt: ActiveSkipPrompt,
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

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) PureWhite else Color.Transparent,
        animationSpec = tween(durationMillis = 140),
        label = "skip_btn_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isFocused) PureBlack else PureWhite,
        animationSpec = tween(durationMillis = 140),
        label = "skip_btn_fg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "skip_btn_scale"
    )

    val cleanLabel = when (prompt.type) {
        ActiveSkipType.INTRO -> "Skip Intro"
        ActiveSkipType.OUTRO -> "Skip Outro"
    }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = modifier
            .then(focusModifier)
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(backgroundColor, RoundedCornerShape(50))
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("player_aniskip_button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = cleanLabel,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = contentColor
        )
        Icon(
            imageVector = Icons.Default.SkipNext,
            contentDescription = cleanLabel,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Compact minimal Subtitle Selection & Customization Menu.
 *
 * - Minimal black background, white text, clear selected-state indicator.
 * - Full remote D-pad navigation; Back closes the menu and restores focus to the Subtitles control.
 * - Preserves all subtitle customization: track, font size, font color, background, outline, and position.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleSelectionMenu(
    availableSubtitles: List<SubtitleTrack>,
    subtitlesEnabled: Boolean,
    selectedSubtitleLanguage: String,
    subtitlePrefs: SubtitlePreferences,
    onSelectSubtitleTrack: (String?, Boolean) -> Unit,
    onUpdateSubtitleSize: (SubtitleSize) -> Unit,
    onUpdateSubtitleColor: (SubtitleColor) -> Unit,
    onUpdateSubtitleBackground: (SubtitleBackground) -> Unit,
    onUpdateSubtitleOutline: (Boolean) -> Unit,
    onUpdateSubtitlePosition: (SubtitlePosition) -> Unit,
    onUserActivity: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstOptionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstOptionFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(end = 32.dp, bottom = 76.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PureBlack.copy(alpha = 0.94f),
            modifier = Modifier
                .widthIn(min = 280.dp, max = 440.dp)
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .testTag("subtitle_selection_menu")
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Subtitles",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = PureWhite
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MinimalMenuOptionPill(
                        label = "Off",
                        selected = !subtitlesEnabled,
                        focusRequester = firstOptionFocus,
                        onFocused = onUserActivity,
                        onClick = {
                            onUserActivity()
                            onSelectSubtitleTrack(null, false)
                        }
                    )
                    availableSubtitles.forEach { track ->
                        MinimalMenuOptionPill(
                            label = track.label,
                            selected = subtitlesEnabled &&
                                selectedSubtitleLanguage.equals(track.language, ignoreCase = true),
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
                    style = MaterialTheme.typography.labelSmall,
                    color = PureWhite.copy(alpha = 0.65f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SubtitleSize.entries.forEach { size ->
                        MinimalMenuOptionPill(
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
                    text = "Color & Background",
                    style = MaterialTheme.typography.labelSmall,
                    color = PureWhite.copy(alpha = 0.65f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SubtitleColor.entries.forEach { color ->
                        MinimalMenuOptionPill(
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
                            SubtitleBackground.SOLID_BLACK -> "Solid BG"
                        }
                        MinimalMenuOptionPill(
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

                Text(
                    text = "Outline & Position",
                    style = MaterialTheme.typography.labelSmall,
                    color = PureWhite.copy(alpha = 0.65f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MinimalMenuOptionPill(
                        label = if (subtitlePrefs.outlineEnabled) "Outline: On" else "Outline: Off",
                        selected = subtitlePrefs.outlineEnabled,
                        onFocused = onUserActivity,
                        onClick = {
                            onUserActivity()
                            onUpdateSubtitleOutline(!subtitlePrefs.outlineEnabled)
                        }
                    )
                    SubtitlePosition.entries.forEach { position ->
                        MinimalMenuOptionPill(
                            label = position.label,
                            selected = subtitlePrefs.position == position,
                            onFocused = onUserActivity,
                            onClick = {
                                onUserActivity()
                                onUpdateSubtitlePosition(position)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact minimal Audio Track Selection Menu.
 *
 * - Minimal black background, white text, clear selected-state indicator.
 * - Full remote D-pad navigation; Back closes the menu and restores focus to the Audio control.
 */
@Composable
fun AudioTrackSelectionMenu(
    selectedAudioLanguage: String,
    onSelectAudioLanguage: (String) -> Unit,
    onUserActivity: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subFocus = remember { FocusRequester() }
    val dubFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val target = if (selectedAudioLanguage.equals("dub", ignoreCase = true)) dubFocus else subFocus
        runCatching { target.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(end = 32.dp, bottom = 76.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PureBlack.copy(alpha = 0.94f),
            modifier = Modifier
                .widthIn(min = 220.dp, max = 300.dp)
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .testTag("audio_track_selection_menu")
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Audio Track",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = PureWhite
                )

                val tracks = listOf(
                    "sub" to "Japanese (Original • SUB)",
                    "dub" to "English (Dubbed • DUB)"
                )
                tracks.forEach { (code, label) ->
                    val isSelected = selectedAudioLanguage.equals(code, ignoreCase = true)
                    MinimalMenuOptionPill(
                        label = label,
                        selected = isSelected,
                        focusRequester = if (code == "sub") subFocus else dubFocus,
                        onFocused = onUserActivity,
                        onClick = {
                            onUserActivity()
                            onSelectAudioLanguage(code)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("audio_option_$code")
                    )
                }
            }
        }
    }
}

/**
 * Unified settings dispatcher for SubtitleSelectionMenu and AudioTrackSelectionMenu.
 */
@Composable
fun CompactPlayerSettingsOverlay(
    activePanel: PlayerSettingsPanel,
    state: PlaybackUiState,
    subtitlePrefs: SubtitlePreferences,
    onSelectQuality: (AvailableQualityOption) -> Unit = {},
    onSelectSpeed: (Float) -> Unit = {},
    onSelectAudioLanguage: (String) -> Unit = {},
    onSelectSubtitleTrack: (String?, Boolean) -> Unit,
    onUpdateSubtitleSize: (SubtitleSize) -> Unit,
    onUpdateSubtitleColor: (SubtitleColor) -> Unit,
    onUpdateSubtitleBackground: (SubtitleBackground) -> Unit,
    onUpdateSubtitleOutline: (Boolean) -> Unit = {},
    onUpdateSubtitlePosition: (SubtitlePosition) -> Unit = {},
    onUserActivity: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (activePanel) {
        PlayerSettingsPanel.SUBTITLES -> {
            SubtitleSelectionMenu(
                availableSubtitles = state.availableSubtitles,
                subtitlesEnabled = state.subtitlesEnabled,
                selectedSubtitleLanguage = state.selectedSubtitleLanguage,
                subtitlePrefs = subtitlePrefs,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                onUpdateSubtitleSize = onUpdateSubtitleSize,
                onUpdateSubtitleColor = onUpdateSubtitleColor,
                onUpdateSubtitleBackground = onUpdateSubtitleBackground,
                onUpdateSubtitleOutline = onUpdateSubtitleOutline,
                onUpdateSubtitlePosition = onUpdateSubtitlePosition,
                onUserActivity = onUserActivity,
                onDismiss = onDismiss,
                modifier = modifier
            )
        }
        PlayerSettingsPanel.AUDIO -> {
            AudioTrackSelectionMenu(
                selectedAudioLanguage = state.audioLanguage,
                onSelectAudioLanguage = onSelectAudioLanguage,
                onUserActivity = onUserActivity,
                onDismiss = onDismiss,
                modifier = modifier
            )
        }
        PlayerSettingsPanel.QUALITY,
        PlayerSettingsPanel.SPEED,
        PlayerSettingsPanel.NONE -> Unit
    }
}

@Composable
private fun MinimalMenuOptionPill(
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

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> PureWhite
            selected -> PureWhite.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
        label = "menu_option_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> PureBlack
            selected -> PureWhite
            else -> PureWhite.copy(alpha = 0.78f)
        },
        animationSpec = tween(durationMillis = 120),
        label = "menu_option_fg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "menu_option_scale"
    )

    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = modifier
            .then(focusMod)
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(backgroundColor, RoundedCornerShape(50))
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 12.sp
            ),
            color = contentColor,
            maxLines = 1
        )
    }
}
