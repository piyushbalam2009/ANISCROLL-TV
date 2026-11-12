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

data class PlaybackUiState(
    val isLoading: Boolean = true,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val anime: Anime? = null,
    val episodes: List<Episode> = emptyList(),
    val currentEpisodeNumber: Int = 1,
    val currentEpisodeTitle: String? = null,
    val audioLanguage: String = "sub", // "sub" or "dub"
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
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

    val appSettings: StateFlow<AppSettings> = preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private var currentStreamData: StreamData? = null
    private var progressPollingJob: Job? = null
    private var countdownJob: Job? = null
    private var streamRetryCount = 0

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = exoPlayerManager.getOrCreatePlayer()
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.coerceAtLeast(0L)
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                onEpisodeEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) {
                persistCurrentProgress()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val stream = currentStreamData ?: return
            val qualities = qualityManager.extractAvailableQualities(stream.sources, tracks)
            _uiState.update { it.copy(availableQualities = qualities) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = exoPlayerManager.getOrCreatePlayer()
            val stream = currentStreamData
            if (stream != null && streamRetryCount < stream.sources.size + 1) {
                streamRetryCount++
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                val nextIndex = (streamRetryCount).coerceAtMost(stream.sources.lastIndex)
                val fallbackSource = stream.sources.getOrNull(nextIndex)
                if (fallbackSource != null) {
                    exoPlayerManager.loadStream(
                        streamData = stream,
                        selectedSourceUrl = fallbackSource.url,
                        startPositionMs = currentPos,
                        playWhenReady = true
                    )
                    return
                }
                player.prepare()
                player.play()
            } else {
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        errorMessage = "No playable streaming source was found."
                    )
                }
            }
        }
    }

    init {
        exoPlayerManager.getOrCreatePlayer().addListener(playerListener)
    }

    fun startPlaybackSession(
        hoshikaId: Long,
        episodeNumber: Int,
        requestedLanguage: String? = null
    ) {
        viewModelScope.launch {
            countdownJob?.cancel()
            streamRetryCount = 0

            val prefs = preferencesRepository.settingsFlow.first()
            val language = (requestedLanguage ?: prefs.preferredAudioLanguage).lowercase()

            _uiState.update {
                it.copy(
                    isLoading = true,
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
                prefs = prefs
            )
        }
    }

    private suspend fun loadStreamForCurrentEpisode(
        anime: Anime,
        episodeNumber: Int,
        language: String,
        overridePositionMs: Long?,
        prefs: AppSettings
    ) {
        when (val result = playbackRepository.resolveEpisodeStream(anime, episodeNumber, language)) {
            is StreamLoadResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBuffering = false,
                        errorMessage = result.userMessage
                    )
                }
            }
            is StreamLoadResult.Success -> {
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

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        audioLanguage = language,
                        availableQualities = qualities,
                        selectedQualityLabel = chosenSource.quality,
                        availableSubtitles = streamData.subtitles,
                        currentPositionMs = startPos
                    )
                }

                exoPlayerManager.loadStream(
                    streamData = streamData,
                    selectedSourceUrl = chosenSource.url,
                    startPositionMs = startPos,
                    playWhenReady = true
                )

                val player = exoPlayerManager.getOrCreatePlayer()
                subtitleManager.applyTrackSelection(
                    player = player,
                    enabled = prefs.subtitlePreferences.enabled,
                    preferredLanguage = prefs.subtitlePreferences.preferredLanguage,
                    availableSubtitles = streamData.subtitles
                )

                startProgressPolling()
            }
        }
    }

    fun switchAudioLanguage(newLanguage: String) {
        val current = _uiState.value
        val anime = current.anime ?: return
        val normalized = newLanguage.lowercase()
        if (normalized == current.audioLanguage && current.errorMessage == null) return

        val savedPosition = exoPlayerManager.getOrCreatePlayer().currentPosition.coerceAtLeast(0L)
        viewModelScope.launch {
            preferencesRepository.setPreferredLanguage(normalized)
            val prefs = preferencesRepository.settingsFlow.first()

            _uiState.update { it.copy(isBuffering = true, transientToastMessage = null) }
            val result = playbackRepository.resolveEpisodeStream(
                anime = anime,
                episodeNumber = current.currentEpisodeNumber,
                language = normalized
            )
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
                            transientToastMessage = "Switched to ${normalized.uppercase()}"
                        )
                    }
                    exoPlayerManager.loadStream(
                        streamData = result.streamData,
                        selectedSourceUrl = chosenSource.url,
                        startPositionMs = savedPosition,
                        playWhenReady = true
                    )
                }
            }
        }
    }

    fun selectQuality(option: AvailableQualityOption) {
        val player = exoPlayerManager.getOrCreatePlayer()
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
                playWhenReady = player.playWhenReady
            )
        } else {
            qualityManager.applyQualitySelection(player, option)
        }
    }

    fun selectSubtitleTrack(language: String?, enabled: Boolean) {
        val player = exoPlayerManager.getOrCreatePlayer()
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
        val player = exoPlayerManager.getOrCreatePlayer()
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekRelative(deltaMs: Long) {
        val player = exoPlayerManager.getOrCreatePlayer()
        val duration = player.duration.coerceAtLeast(0L)
        val target = (player.currentPosition + deltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target)
        _uiState.update {
            it.copy(
                currentPositionMs = target,
                activeSkipPrompt = aniSkipManager.evaluateCurrentPosition(target)
            )
        }
    }

    fun performSkip(prompt: ActiveSkipPrompt) {
        val player = exoPlayerManager.getOrCreatePlayer()
        aniSkipManager.markSkipped(prompt)
        player.seekTo(prompt.targetTimeMs)
        _uiState.update {
            it.copy(
                currentPositionMs = prompt.targetTimeMs,
                activeSkipPrompt = null
            )
        }
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

    private fun startProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                val player = exoPlayerManager.getOrCreatePlayer()
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.coerceAtLeast(0L)
                val buf = player.bufferedPosition.coerceAtLeast(0L)

                val skipPrompt = aniSkipManager.evaluateCurrentPosition(pos)
                val settings = appSettings.value

                if (skipPrompt != null &&
                    skipPrompt.type == ActiveSkipType.INTRO &&
                    settings.autoSkipIntro
                ) {
                    performSkip(skipPrompt)
                } else {
                    _uiState.update {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = dur,
                            bufferedPositionMs = buf,
                            activeSkipPrompt = skipPrompt
                        )
                    }
                }

                ticks++
                if (ticks % 10 == 0 && player.isPlaying) {
                    persistCurrentProgress()
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
                _uiState.update {
                    it.copy(
                        showNextEpisodeCountdown = true,
                        nextEpisodeCountdownSec = remaining
                    )
                }
                delay(1000L)
            }
            playNextEpisodeNow()
        }
    }

    fun persistCurrentProgress() {
        val state = _uiState.value
        val anime = state.anime ?: return
        val stream = currentStreamData ?: return
        val player = exoPlayerManager.getOrCreatePlayer()
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.coerceAtLeast(0L)
        if (dur <= 0L) return

        viewModelScope.launch {
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

    fun pauseAndSaveOnStop() {
        val player = exoPlayerManager.getOrCreatePlayer()
        if (player.isPlaying) {
            player.pause()
        }
        persistCurrentProgress()
    }

    override fun onCleared() {
        super.onCleared()
        persistCurrentProgress()
        progressPollingJob?.cancel()
        countdownJob?.cancel()
        exoPlayerManager.releasePlayer()
    }
}
