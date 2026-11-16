package `in`.aniscroll.tv.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import `in`.aniscroll.tv.core.designsystem.TvColors
import `in`.aniscroll.tv.core.designsystem.TvErrorBanner
import `in`.aniscroll.tv.core.designsystem.TvFocusableButton
import `in`.aniscroll.tv.domain.model.SubtitlePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROLS_AUTO_HIDE_MS = 3500L

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
    var activeSettingsPanel by remember { mutableStateOf(PlayerSettingsPanel.NONE) }
    var lastFocusTarget by remember { mutableStateOf(PlayerFocusTarget.PLAY_PAUSE) }
    var isSeeking by remember { mutableStateOf(false) }

    val surfaceFocusRequester = remember { FocusRequester() }
    val skipButtonFocusRequester = remember { FocusRequester() }

    val exoPlayer = remember(hoshikaId, episodeNumber) {
        controller.exoPlayerManager.getOrCreatePlayer()
    }

    LaunchedEffect(hoshikaId, episodeNumber) {
        controller.startPlaybackSession(
            hoshikaId = hoshikaId,
            episodeNumber = episodeNumber,
            requestedLanguage = initialLanguage
        )
    }

    // Strict Android TV lifecycle integration:
    // - ON_PAUSE / ON_STOP: pause immediately without auto-resuming when returning.
    // - onDispose: immediately stop playback, save progress, and release ExoPlayer.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                controller.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.exitAndReleasePlayer()
        }
    }

    // Auto-hide controls after inactivity while playing (never while seeking or while a menu is open)
    LaunchedEffect(controlsVisible, lastUserInteractionMs, state.isPlaying, activeSettingsPanel, isSeeking) {
        if (controlsVisible && state.isPlaying && activeSettingsPanel == PlayerSettingsPanel.NONE && !isSeeking) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Keep root focusable surface focused when controls hide so remote presses wake controls predictably
    LaunchedEffect(controlsVisible, state.activeSkipPrompt) {
        if (!controlsVisible && state.activeSkipPrompt == null) {
            runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    // Auto-dismiss transient toast messages
    LaunchedEffect(state.transientToastMessage) {
        if (state.transientToastMessage != null) {
            delay(2800L)
            controller.clearTransientMessage()
        }
    }

    // Back button closes settings panels first (restoring focus to the button that opened it),
    // then immediately stops & exits the player
    BackHandler {
        when {
            activeSettingsPanel != PlayerSettingsPanel.NONE -> {
                activeSettingsPanel = PlayerSettingsPanel.NONE
                lastUserInteractionMs = System.currentTimeMillis()
            }
            else -> {
                controller.exitAndReleasePlayer()
                onExitPlayer()
            }
        }
    }

    val recordActivity: () -> Unit = remember {
        { lastUserInteractionMs = System.currentTimeMillis() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.MainBackground)
            .focusRequester(surfaceFocusRequester)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    lastUserInteractionMs = System.currentTimeMillis()
                    val keyCode = event.nativeKeyEvent.keyCode

                    when (keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            controller.togglePlayPause()
                            controlsVisible = true
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                        KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                        KeyEvent.KEYCODE_BUTTON_L1 -> {
                            controller.seekRelative(-10_000L)
                            controlsVisible = true
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                        KeyEvent.KEYCODE_BUTTON_R1 -> {
                            controller.seekRelative(10_000L)
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
                        KeyEvent.KEYCODE_MENU -> {
                            if (activeSettingsPanel != PlayerSettingsPanel.NONE) {
                                activeSettingsPanel = PlayerSettingsPanel.NONE
                            } else {
                                controlsVisible = !controlsVisible
                            }
                            return@onPreviewKeyEvent true
                        }
                    }

                    if (!controlsVisible && state.errorMessage == null &&
                        keyCode in listOf(
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BUTTON_A,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT
                        )
                    ) {
                        // Allow AniSkip button activation if skip button currently holds focus on ENTER/CENTER
                        val isCenterKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                            keyCode == KeyEvent.KEYCODE_BUTTON_A
                        if (isCenterKey && state.activeSkipPrompt != null) {
                            return@onPreviewKeyEvent false
                        }
                        controlsVisible = true
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable()
    ) {
        // 1. Stationary Media3 PlayerView Surface
        PlayerVideoSurface(
            exoPlayer = exoPlayer,
            subtitlePrefs = settings.subtitlePreferences,
            subtitleManager = controller.subtitleManager
        )

        // 2. Minimal Buffering / Loading Indicator
        if (state.isLoading || state.isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(TvColors.MainBackground.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = TvColors.PrimaryText,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (state.isLoading) "Loading..." else "Buffering...",
                        style = MaterialTheme.typography.bodySmall,
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
                    modifier = Modifier.widthIn(max = 480.dp),
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
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val altLang = if (state.audioLanguage == "sub") "dub" else "sub"
                        TvFocusableButton(
                            text = "Try ${altLang.uppercase()}",
                            onClick = { controller.switchAudioLanguage(altLang) }
                        )
                        TvFocusableButton(
                            text = "Exit Player",
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            onClick = {
                                controller.exitAndReleasePlayer()
                                onExitPlayer()
                            }
                        )
                    }
                }
            }
        }

        // 4. Transient status toast
        state.transientToastMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(TvColors.SecondaryBackground.copy(alpha = 0.90f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelLarge,
                    color = TvColors.PrimaryText
                )
            }
        }

        // 5. Minimal White-Theme Skip Intro / Skip Outro Pill Button
        AnimatedVisibility(
            visible = state.activeSkipPrompt != null && state.errorMessage == null && activeSettingsPanel == PlayerSettingsPanel.NONE,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = if (controlsVisible) 104.dp else 36.dp)
        ) {
            state.activeSkipPrompt?.let { prompt ->
                LaunchedEffect(prompt.type, controlsVisible) {
                    if (!controlsVisible) {
                        runCatching { skipButtonFocusRequester.requestFocus() }
                    }
                }
                SkipIntroOutroButton(
                    prompt = prompt,
                    focusRequester = skipButtonFocusRequester,
                    onFocused = recordActivity,
                    onClick = {
                        recordActivity()
                        controller.performSkip(prompt)
                    }
                )
            }
        }

        // 6. Auto Next Episode Countdown Overlay
        AnimatedVisibility(
            visible = state.showNextEpisodeCountdown && state.nextEpisode != null && activeSettingsPanel == PlayerSettingsPanel.NONE,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = 96.dp)
        ) {
            state.nextEpisode?.let { nextEp ->
                Column(
                    modifier = Modifier
                        .widthIn(min = 240.dp, max = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TvColors.MainBackground.copy(alpha = 0.94f))
                        .border(1.dp, TvColors.PrimaryText.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Next in ${state.nextEpisodeCountdownSec}s",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TvColors.PrimaryText
                    )
                    Text(
                        text = "Ep ${nextEp.number}${nextEp.title?.let { " • $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvColors.SecondaryText,
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvFocusableButton(
                            text = "Play Now",
                            isPrimary = true,
                            onClick = { controller.playNextEpisodeNow() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("next_ep_play_now")
                        )
                        TvFocusableButton(
                            text = "Cancel",
                            onClick = { controller.cancelNextEpisodeCountdown() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("next_ep_cancel")
                        )
                    }
                }
            }
        }

        // 7. Minimalist Circular Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible && state.errorMessage == null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerControlsOverlay(
                animeTitle = state.anime?.preferredTitle?.takeIf { it.isNotBlank() }
                    ?: state.currentEpisodeTitle?.takeIf { it.isNotBlank() }
                    ?: "Episode ${state.currentEpisodeNumber}",
                episodeNumber = state.currentEpisodeNumber,
                episodeTitle = state.currentEpisodeTitle,
                audioLanguage = state.audioLanguage,
                selectedQualityLabel = state.selectedQualityLabel,
                playbackSpeed = state.playbackSpeed,
                isPlaying = state.isPlaying,
                hasPreviousEpisode = state.previousEpisode != null,
                hasNextEpisode = state.nextEpisode != null,
                progressStateFlow = controller.progressState,
                activePanel = activeSettingsPanel,
                canInteract = controlsVisible && state.errorMessage == null,
                lastFocusTarget = lastFocusTarget,
                isMovie = `in`.aniscroll.tv.data.repository.StreamingSourceResolver.isMovie(state.anime?.format),
                onSeekingChanged = { seeking ->
                    isSeeking = seeking
                    if (seeking) recordActivity()
                },
                onFocusTargetChanged = { lastFocusTarget = it },
                onUserActivity = recordActivity,
                onBackClick = {
                    controller.exitAndReleasePlayer()
                    onExitPlayer()
                },
                onPreviousEpisode = { controller.playPreviousEpisode() },
                onSeekRelative = { delta -> controller.seekRelative(delta) },
                onTogglePlayPause = { controller.togglePlayPause() },
                onNextEpisode = { controller.playNextEpisodeNow() },
                onToggleAudioLanguage = {
                    lastFocusTarget = PlayerFocusTarget.AUDIO
                    activeSettingsPanel = PlayerSettingsPanel.AUDIO
                },
                onOpenSubtitlesPanel = {
                    lastFocusTarget = PlayerFocusTarget.SUBTITLES
                    activeSettingsPanel = PlayerSettingsPanel.SUBTITLES
                },
                onOpenSpeedPanel = {
                    lastFocusTarget = PlayerFocusTarget.SPEED
                    activeSettingsPanel = PlayerSettingsPanel.SPEED
                },
                onOpenQualityPanel = {
                    lastFocusTarget = PlayerFocusTarget.QUALITY
                    activeSettingsPanel = PlayerSettingsPanel.QUALITY
                }
            )
        }

        // 8. Compact Subtitle & Audio Track Selection Menus
        CompactPlayerSettingsOverlay(
            activePanel = activeSettingsPanel,
            state = state,
            subtitlePrefs = settings.subtitlePreferences,
            onSelectQuality = { option -> controller.selectQuality(option) },
            onSelectSpeed = { speed -> controller.setPlaybackSpeed(speed) },
            onSelectAudioLanguage = { lang -> controller.switchAudioLanguage(lang) },
            onSelectSubtitleTrack = { lang, enabled -> controller.selectSubtitleTrack(lang, enabled) },
            onUpdateSubtitleSize = { size ->
                controller.viewModelScope.launch {
                    controller.preferencesRepository.setSubtitleSize(size)
                }
            },
            onUpdateSubtitleColor = { color ->
                controller.viewModelScope.launch {
                    controller.preferencesRepository.setSubtitleColor(color)
                }
            },
            onUpdateSubtitleBackground = { bg ->
                controller.viewModelScope.launch {
                    controller.preferencesRepository.setSubtitleBackground(bg)
                }
            },
            onUpdateSubtitleOutline = { outline ->
                controller.viewModelScope.launch {
                    controller.preferencesRepository.setSubtitleOutline(outline)
                }
            },
            onUpdateSubtitlePosition = { position ->
                controller.viewModelScope.launch {
                    controller.preferencesRepository.setSubtitlePosition(position)
                }
            },
            onUserActivity = recordActivity,
            onDismiss = {
                activeSettingsPanel = PlayerSettingsPanel.NONE
                recordActivity()
            }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerVideoSurface(
    exoPlayer: ExoPlayer,
    subtitlePrefs: SubtitlePreferences,
    subtitleManager: SubtitleManager
) {
    var lastAppliedSubtitlePrefs by remember { mutableStateOf<SubtitlePreferences?>(null) }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = exoPlayer
                keepScreenOn = true
                subtitleView?.let { subView ->
                    subtitleManager.applySubtitlePreferencesToView(subView, subtitlePrefs)
                    lastAppliedSubtitlePrefs = subtitlePrefs
                }
            }
        },
        update = { playerView ->
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }
            if (lastAppliedSubtitlePrefs != subtitlePrefs) {
                playerView.subtitleView?.let { subView ->
                    subtitleManager.applySubtitlePreferencesToView(subView, subtitlePrefs)
                    lastAppliedSubtitlePrefs = subtitlePrefs
                }
            }
        },
        onRelease = { playerView ->
            playerView.player = null
        },
        modifier = Modifier
            .fillMaxSize()
            .testTag("player_video_surface")
    )
}
