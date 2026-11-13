package `in`.aniscroll.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import `in`.aniscroll.tv.core.datastore.UserPreferencesRepository
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.PlaybackRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.data.repository.StreamLoadResult
import `in`.aniscroll.tv.domain.model.Anime
import `in`.aniscroll.tv.domain.model.AppSettings
import `in`.aniscroll.tv.domain.model.Episode
import `in`.aniscroll.tv.domain.model.StreamData
import `in`.aniscroll.tv.domain.model.SubtitleTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Isolated fast-tick playback timeline state so 500ms position updates ONLY recompose the
 * thin timeline bar and never recompose PlayerScreen, VideoSurface, or control buttons.
 */
data class PlaybackProgressState(
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

data class PlaybackUiState(
    val isLoading: Boolean = true,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val anime: Anime? = null,
    val episodes: List<Episode> = emptyList(),
    val currentEpisodeNumber: Int = 1,
    val currentEpisodeTitle: String? = null,
    val audioLanguage: String = "sub", // "sub" or "dub"
    val playbackSpeed: Float = 1.0f,
    val availableQualities: List<AvailableQualityOption> = listOf(AvailableQualityOption("Auto", null)),
    val selectedQualityLabel: String = "Auto",
    val availableSubtitles: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleLanguage: String = "English",
    val subtitlesEnabled: Boolean = true,
    val activeSkipPrompt: ActiveSkipPrompt? = null,
    val previousEpisode: Episode? = null,
    val nextEpisode: Episode? = null,
    val showNextEpisodeCountdown: Boolean = false,
    val nextEpisodeCountdownSec: Int = 8,
    val errorMessage: String? = null,
    val transientToastMessage: String? = null
)

class PlaybackController(
    val exoPlayerManager: ExoPlayerManager,
    private val animeRepository: AnimeRepository,
    private val playbackRepository: PlaybackRepository,
    val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val qualityManager = QualityManager()
    val subtitleManager = SubtitleManager()
    private val aniSkipManager = AniSkipManager()
    private val nextEpisodeManager = NextEpisodeManager()

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _progressState = MutableStateFlow(PlaybackProgressState())
    val progressState: StateFlow<PlaybackProgressState> = _progressState.asStateFlow()

    val appSettings: StateFlow<AppSettings> = preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private var currentStreamData: StreamData? = null
    private var sessionJob: Job? = null
    private var audioSwitchJob: Job? = null
    private var progressPollingJob: Job? = null
    private var countdownJob: Job? = null
    private var streamRetryCount = 0

    @Volatile
    private var sessionGeneration: Long = 0L

    @Volatile
    private var isSessionActive: Boolean = false

    @Volatile
    private var isAppInBackground: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isSessionActive) return
            val player = exoPlayerManager.peekPlayer() ?: return
            val dur = player.duration.coerceAtLeast(0L)
            _progressState.update { it.copy(durationMs = dur) }
            _uiState.update {
                it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
            }
            if (playbackState == Player.STATE_ENDED && !isAppInBackground) {
                onEpisodeEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isSessionActive) return
            if (isPlaying && isAppInBackground) {
                // Defensive guard: never allow audio/video playback while app is backgrounded
                exoPlayerManager.pauseIfActive()
                _uiState.update { it.copy(isPlaying = false) }
                return
            }
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) {
                persistCurrentProgress()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!isSessionActive) return
            val stream = currentStreamData ?: return
            val qualities = qualityManager.extractAvailableQualities(stream.sources, tracks)
            _uiState.update { it.copy(availableQualities = qualities) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isSessionActive) return
            val player = exoPlayerManager.peekPlayer() ?: return
            val stream = currentStreamData
            if (stream != null && streamRetryCount < stream.sources.size + 1) {
                streamRetryCount++
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                val nextIndex = streamRetryCount.coerceAtMost(stream.sources.lastIndex)
                val fallbackSource = stream.sources.getOrNull(nextIndex)
                if (fallbackSource != null) {
                    exoPlayerManager.loadStream(
                        streamData = stream,
                        selectedSourceUrl = fallbackSource.url,
                        startPositionMs = currentPos,
                        playWhenReady = !isAppInBackground,
                        playbackSpeed = _uiState.value.playbackSpeed
                    )
                    return
                }
                player.prepare()
                if (!isAppInBackground) {
                    player.play()
                }
            } else {
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = "No playable streaming source was found."
                    )
                }
            }
        }
    }

    init {
        exoPlayerManager.setPlayerListener(playerListener)
    }

    fun startPlaybackSession(
        hoshikaId: Long,
        episodeNumber: Int,
        requestedLanguage: String? = null
    ) {
        sessionJob?.cancel()
        audioSwitchJob?.cancel()
        progressPollingJob?.cancel()
        countdownJob?.cancel()

        val generation = ++sessionGeneration
        isSessionActive = true
        isAppInBackground = false
        streamRetryCount = 0
        currentStreamData = null

        // Ensure listener is attached before player creation
        exoPlayerManager.setPlayerListener(playerListener)
        exoPlayerManager.stopAndClear()

        sessionJob = viewModelScope.launch {
            val prefs = preferencesRepository.settingsFlow.first()
            if (!isSessionActive || generation != sessionGeneration) return@launch

            val language = (requestedLanguage ?: prefs.preferredAudioLanguage).lowercase()

            _progressState.value = PlaybackProgressState()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = null,
                    currentEpisodeNumber = episodeNumber,
                    audioLanguage = language,
                    subtitlesEnabled = prefs.subtitlePreferences.enabled,
                    selectedSubtitleLanguage = prefs.subtitlePreferences.preferredLanguage,
                    selectedQualityLabel = prefs.defaultQuality,
                    showNextEpisodeCountdown = false,
                    activeSkipPrompt = null
                )
            }

            // 1. Load anime metadata & episode list from Hoshika
            val animeRes = animeRepository.getAnimeDetails(hoshikaId)
            if (!isSessionActive || generation != sessionGeneration) return@launch

            val anime = when (animeRes) {
                is ResourceResult.Success -> animeRes.data
                is ResourceResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = animeRes.userMessage)
                    }
                    return@launch
                }
            }

            val episodesRes = animeRepository.getAnimeEpisodes(hoshikaId)
            if (!isSessionActive || generation != sessionGeneration) return@launch

            val episodes = (episodesRes as? ResourceResult.Success)?.data ?: emptyList()
            val currentEpObj = episodes.find { it.number == episodeNumber }
            val prevEp = nextEpisodeManager.findPreviousEpisode(episodes, episodeNumber)
            val nextEp = nextEpisodeManager.findNextEpisode(episodes, episodeNumber)

            _uiState.update {
                it.copy(
                    anime = anime,
                    episodes = episodes,
                    currentEpisodeTitle = currentEpObj?.title,
                    previousEpisode = prevEp,
                    nextEpisode = nextEp
                )
            }

            // 2. Resolve AniScroll HLS stream + AniSkip v2 intervals
            loadStreamForCurrentEpisode(
                anime = anime,
                episodeNumber = episodeNumber,
                language = language,
                overridePositionMs = null,
                prefs = prefs,
                generation = generation
            )
        }
    }

    private suspend fun loadStreamForCurrentEpisode(
        anime: Anime,
        episodeNumber: Int,
        language: String,
        overridePositionMs: Long?,
        prefs: AppSettings,
        generation: Long
    ) {
        when (val result = playbackRepository.resolveEpisodeStream(anime, episodeNumber, language)) {
            is StreamLoadResult.Error -> {
                if (!isSessionActive || generation != sessionGeneration) return
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = result.userMessage
                    )
                }
            }
            is StreamLoadResult.Success -> {
                if (!isSessionActive || generation != sessionGeneration) return

                val streamData = result.streamData
                currentStreamData = streamData
                aniSkipManager.setIntervals(result.skipIntervals)

                val preferredQuality = prefs.defaultQuality
                val chosenSource = streamData.sources.find {
                    it.quality.equals(preferredQuality, ignoreCase = true)
                } ?: streamData.sources.first()

                val startPos = overridePositionMs
                    ?: if (prefs.resumePlayback) result.savedPositionMs else 0L

                val qualities = qualityManager.extractAvailableQualities(streamData.sources, null)
                val speed = _uiState.value.playbackSpeed

                _progressState.update { it.copy(currentPositionMs = startPos) }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        audioLanguage = language,
                        availableQualities = qualities,
                        selectedQualityLabel = chosenSource.quality,
                        availableSubtitles = streamData.subtitles
                    )
                }

                // Do NOT auto-play if app moved to background while network request was resolving
                val shouldAutoPlay = !isAppInBackground && isSessionActive && generation == sessionGeneration
                exoPlayerManager.loadStream(
                    streamData = streamData,
                    selectedSourceUrl = chosenSource.url,
                    startPositionMs = startPos,
                    playWhenReady = shouldAutoPlay,
                    playbackSpeed = speed
                )

                val player = exoPlayerManager.peekPlayer()
                if (player != null) {
                    subtitleManager.applyTrackSelection(
                        player = player,
                        enabled = prefs.subtitlePreferences.enabled,
                        preferredLanguage = prefs.subtitlePreferences.preferredLanguage,
                        availableSubtitles = streamData.subtitles
                    )
                }

                startProgressPolling(generation)
            }
        }
    }

    fun switchAudioLanguage(newLanguage: String) {
        if (!isSessionActive) return
        val current = _uiState.value
        val anime = current.anime ?: return
        val normalized = newLanguage.lowercase()
        if (normalized == current.audioLanguage && current.errorMessage == null) return

        val savedPosition = exoPlayerManager.peekPlayer()?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val generation = sessionGeneration

        audioSwitchJob?.cancel()
        audioSwitchJob = viewModelScope.launch {
            preferencesRepository.setPreferredLanguage(normalized)
            _uiState.update { it.copy(isBuffering = true, transientToastMessage = null) }

            val result = playbackRepository.resolveEpisodeStream(
                anime = anime,
                episodeNumber = current.currentEpisodeNumber,
                language = normalized
            )
            if (!isSessionActive || generation != sessionGeneration) return@launch

            when (result) {
                is StreamLoadResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            transientToastMessage = result.userMessage
                        )
                    }
                }
                is StreamLoadResult.Success -> {
                    currentStreamData = result.streamData
                    aniSkipManager.setIntervals(result.skipIntervals)
                    val chosenSource = result.streamData.sources.first()
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            audioLanguage = normalized,
                            availableSubtitles = result.streamData.subtitles,
                            transientToastMessage = "Audio: ${normalized.uppercase()}"
                        )
                    }
                    exoPlayerManager.loadStream(
                        streamData = result.streamData,
                        selectedSourceUrl = chosenSource.url,
                        startPositionMs = savedPosition,
                        playWhenReady = !isAppInBackground,
                        playbackSpeed = _uiState.value.playbackSpeed
                    )
                }
            }
        }
    }

    fun selectQuality(option: AvailableQualityOption) {
        val player = exoPlayerManager.peekPlayer() ?: return
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        val stream = currentStreamData

        _uiState.update { it.copy(selectedQualityLabel = option.label) }
        viewModelScope.launch {
            preferencesRepository.setDefaultQuality(option.label)
        }

        if (option.sourceUrl != null && stream != null) {
            exoPlayerManager.loadStream(
                streamData = stream,
                selectedSourceUrl = option.sourceUrl,
                startPositionMs = currentPos,
                playWhenReady = player.playWhenReady && !isAppInBackground,
                playbackSpeed = _uiState.value.playbackSpeed
            )
        } else {
            qualityManager.applyQualitySelection(player, option)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        exoPlayerManager.peekPlayer()?.setPlaybackSpeed(clamped)
    }

    fun selectSubtitleTrack(language: String?, enabled: Boolean) {
        val player = exoPlayerManager.peekPlayer() ?: return
        val stream = currentStreamData
        val lang = language ?: _uiState.value.selectedSubtitleLanguage

        _uiState.update {
            it.copy(
                subtitlesEnabled = enabled,
                selectedSubtitleLanguage = lang
            )
        }

        viewModelScope.launch {
            preferencesRepository.setSubtitleEnabled(enabled)
            if (language != null) {
                preferencesRepository.setSubtitleLanguage(language)
            }
        }

        subtitleManager.applyTrackSelection(
            player = player,
            enabled = enabled,
            preferredLanguage = lang,
            availableSubtitles = stream?.subtitles ?: emptyList()
        )
    }

    fun togglePlayPause() {
        val player = exoPlayerManager.peekPlayer() ?: return
        isAppInBackground = false
        if (player.isPlaying) {
            player.playWhenReady = false
            player.pause()
        } else {
            player.playWhenReady = true
            player.play()
        }
    }

    fun seekRelative(deltaMs: Long) {
        val player = exoPlayerManager.peekPlayer() ?: return
        val duration = player.duration.coerceAtLeast(0L)
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
        player.seekTo(target)
        _progressState.update { it.copy(currentPositionMs = target) }
        _uiState.update {
            it.copy(activeSkipPrompt = aniSkipManager.evaluateCurrentPosition(target))
        }
    }

    fun performSkip(prompt: ActiveSkipPrompt) {
        val player = exoPlayerManager.peekPlayer() ?: return
        aniSkipManager.markSkipped(prompt)
        player.seekTo(prompt.targetTimeMs)
        _progressState.update { it.copy(currentPositionMs = prompt.targetTimeMs) }
        _uiState.update { it.copy(activeSkipPrompt = null) }
    }

    fun playNextEpisodeNow() {
        val state = _uiState.value
        val anime = state.anime ?: return
        val nextEp = state.nextEpisode ?: return
        countdownJob?.cancel()
        startPlaybackSession(
            hoshikaId = anime.hoshikaId,
            episodeNumber = nextEp.number,
            requestedLanguage = state.audioLanguage
        )
    }

    fun playPreviousEpisode() {
        val state = _uiState.value
        val anime = state.anime ?: return
        val prevEp = state.previousEpisode ?: return
        countdownJob?.cancel()
        startPlaybackSession(
            hoshikaId = anime.hoshikaId,
            episodeNumber = prevEp.number,
            requestedLanguage = state.audioLanguage
        )
    }

    fun cancelNextEpisodeCountdown() {
        countdownJob?.cancel()
        _uiState.update { it.copy(showNextEpisodeCountdown = false) }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientToastMessage = null) }
    }

    private fun startProgressPolling(generation: Long) {
        progressPollingJob?.cancel()
        progressPollingJob = viewModelScope.launch {
            var ticks = 0
            while (isActive && isSessionActive && generation == sessionGeneration) {
                val player = exoPlayerManager.peekPlayer()
                if (player != null) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.coerceAtLeast(0L)
                    val buf = player.bufferedPosition.coerceAtLeast(0L)

                    val skipPrompt = aniSkipManager.evaluateCurrentPosition(pos)
                    val settings = appSettings.value

                    if (skipPrompt != null &&
                        skipPrompt.type == ActiveSkipType.INTRO &&
                        settings.autoSkipIntro &&
                        !isAppInBackground
                    ) {
                        performSkip(skipPrompt)
                    } else {
                        _progressState.update { current ->
                            if (current.currentPositionMs == pos &&
                                current.durationMs == dur &&
                                current.bufferedPositionMs == buf
                            ) {
                                current
                            } else {
                                current.copy(
                                    currentPositionMs = pos,
                                    durationMs = dur,
                                    bufferedPositionMs = buf
                                )
                            }
                        }
                        if (_uiState.value.activeSkipPrompt != skipPrompt) {
                            _uiState.update { it.copy(activeSkipPrompt = skipPrompt) }
                        }
                    }

                    ticks++
                    if (ticks % 10 == 0 && player.isPlaying) {
                        persistCurrentProgress()
                    }
                }
                delay(500L)
            }
        }
    }

    private fun onEpisodeEnded() {
        persistCurrentProgress()
        val state = _uiState.value
        val nextEp = state.nextEpisode ?: return
        if (!appSettings.value.autoNextEpisode) return

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remaining in 8 downTo 1) {
                if (!isSessionActive || isAppInBackground) return@launch
                _uiState.update {
                    it.copy(
                        showNextEpisodeCountdown = true,
                        nextEpisodeCountdownSec = remaining
                    )
                }
                delay(1000L)
            }
            if (isSessionActive && !isAppInBackground) {
                playNextEpisodeNow()
            }
        }
    }

    fun persistCurrentProgress() {
        val state = _uiState.value
        val anime = state.anime ?: return
        val stream = currentStreamData ?: return
        val player = exoPlayerManager.peekPlayer() ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.coerceAtLeast(0L)
        if (dur <= 0L) return

        viewModelScope.launch {
            withContext(NonCancellable) {
                playbackRepository.savePlaybackProgress(
                    anime = anime,
                    anilistId = stream.anilistId,
                    episodeNumber = state.currentEpisodeNumber,
                    language = state.audioLanguage,
                    positionMs = pos,
                    durationMs = dur
                )
            }
        }
    }

    /**
     * Pauses playback immediately when the Android TV app enters the background (Home button / task switch).
     * Never auto-resumes when returning unless the user explicitly presses Play.
     */
    fun onAppBackgrounded() {
        isAppInBackground = true
        countdownJob?.cancel()
        exoPlayerManager.pauseIfActive()
        persistCurrentProgress()
        _uiState.update {
            it.copy(
                isPlaying = false,
                showNextEpisodeCountdown = false
            )
        }
    }

    /**
     * Immediately stops playback, saves progress, cancels all pending stream/polling coroutines,
     * and releases ExoPlayer when leaving PlayerScreen or exiting the app.
     */
    fun exitAndReleasePlayer() {
        if (!isSessionActive && exoPlayerManager.peekPlayer() == null) {
            return
        }
        isSessionActive = false
        sessionGeneration++
        sessionJob?.cancel()
        sessionJob = null
        audioSwitchJob?.cancel()
        audioSwitchJob = null
        progressPollingJob?.cancel()
        progressPollingJob = null
        countdownJob?.cancel()
        countdownJob = null

        persistCurrentProgress()
        currentStreamData = null
        exoPlayerManager.releasePlayer()

        _progressState.value = PlaybackProgressState()
        _uiState.update {
            it.copy(
                isLoading = false,
                isBuffering = false,
                isPlaying = false,
                activeSkipPrompt = null,
                showNextEpisodeCountdown = false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        exitAndReleasePlayer()
    }
}
