package `in`.aniscroll.tv.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import `in`.aniscroll.tv.core.datastore.UserPreferencesRepository
import `in`.aniscroll.tv.core.network.NetworkMonitor
import `in`.aniscroll.tv.core.network.NetworkTier
import `in`.aniscroll.tv.data.repository.AnimeRepository
import `in`.aniscroll.tv.data.repository.PlaybackRepository
import `in`.aniscroll.tv.data.repository.ResourceResult
import `in`.aniscroll.tv.data.repository.StreamLoadResult
import `in`.aniscroll.tv.data.repository.StreamingSourceResolver
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
    val transientToastMessage: String? = null,
    val surfaceRecoveryEpoch: Int = 0,
    val videoRecoveryCount: Int = 0
)

class PlaybackController(
    val exoPlayerManager: ExoPlayerManager,
    private val animeRepository: AnimeRepository,
    private val playbackRepository: PlaybackRepository,
    val preferencesRepository: UserPreferencesRepository,
    private val networkMonitor: NetworkMonitor? = null
) : ViewModel() {

    companion object {
        const val MAX_VIDEO_RECOVERY_ATTEMPTS = 2
        private const val VIDEO_STALL_AUDIO_ADVANCE_THRESHOLD_MS = 4_000L
        private const val RECOVERY_COOLDOWN_MS = 5_000L
    }

    val qualityManager = QualityManager()
    val subtitleManager = SubtitleManager()
    val aniSkipManager = AniSkipManager()
    private val nextEpisodeManager = NextEpisodeManager()

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _progressState = MutableStateFlow(PlaybackProgressState())
    val progressState: StateFlow<PlaybackProgressState> = _progressState.asStateFlow()

    val appSettings: StateFlow<AppSettings> = preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private var currentStreamData: StreamData? = null
    private var currentSelectedSourceUrl: String? = null
    private var sessionJob: Job? = null
    private var episodesLoadJob: Job? = null
    private var aniSkipJob: Job? = null
    private var prewarmNextEpisodeJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var audioSwitchJob: Job? = null
    private var progressPollingJob: Job? = null
    private var countdownJob: Job? = null
    private var streamRetryCount = 0
    private var hasRefreshedStreamOnError = false
    private var prewarmedEpisodeNumber: Int? = null

    @Volatile
    private var activeMetrics: StreamingSessionMetrics? = null

    @Volatile
    private var bufferingStartedElapsedMs: Long? = null

    @Volatile
    private var hasRenderedFirstFrameForSession: Boolean = false

    @Volatile
    private var hasCompletedInitialBufferForSession: Boolean = false

    @Volatile
    private var videoRecoveryAttempts: Int = 0

    @Volatile
    private var lastVideoRecoveryElapsedMs: Long = 0L

    @Volatile
    private var lastObservedRenderedFrames: Int = -1

    @Volatile
    private var lastObservedAudioPosForFrameCheckMs: Long = -1L

    @Volatile
    private var stalledFrameAudioAdvanceMs: Long = 0L

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

            val nowElapsed = SystemClock.elapsedRealtime()
            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingStartedElapsedMs == null) {
                    bufferingStartedElapsedMs = nowElapsed
                }
            } else {
                val bufferStart = bufferingStartedElapsedMs
                if (bufferStart != null) {
                    bufferingStartedElapsedMs = null
                    val stallMs = (nowElapsed - bufferStart).coerceAtLeast(0L)
                    activeMetrics?.let { currentMetrics ->
                        val isRebuffer = hasCompletedInitialBufferForSession
                        val updated = StreamingDiagnostics.recordBufferingCompleted(
                            session = currentMetrics,
                            stallDurationMs = stallMs,
                            isRebuffer = isRebuffer
                        )
                        activeMetrics = updated
                        if (isRebuffer) {
                            qualityManager.applyAdaptiveRebufferConstraint(
                                player = player,
                                selectedQualityLabel = _uiState.value.selectedQualityLabel,
                                rebufferCount = updated.rebufferCount,
                                networkTier = networkMonitor?.currentTier() ?: NetworkTier.STANDARD,
                                maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                            )
                        }
                    }
                }
                if (playbackState == Player.STATE_READY) {
                    hasCompletedInitialBufferForSession = true
                }
            }

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
            val player = exoPlayerManager.peekPlayer()
            if (player != null) {
                var hasVideoTrackGroup = false
                var isAnyVideoTrackSelected = false
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.length > 0) {
                        hasVideoTrackGroup = true
                        if (group.isSelected) {
                            isAnyVideoTrackSelected = true
                        }
                    }
                }
                if (hasVideoTrackGroup && !isAnyVideoTrackSelected) {
                    StreamingDiagnostics.logEvent(
                        "video_track_deselected_detected_on_tracks_changed re_enabling_video_track=true"
                    )
                    qualityManager.ensureVideoTrackEnabled(
                        player = player,
                        maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                    )
                }

                // Verify discovered HLS audio renditions and enforce English (dub) / Japanese (sub) when available
                exoPlayerManager.audioTrackManager.inspectAndEnsureAudioSelection(
                    player = player,
                    tracks = tracks,
                    desiredAudioType = _uiState.value.audioLanguage
                )
            }

            val stream = currentStreamData ?: return
            val qualities = qualityManager.extractAvailableQualities(
                sources = stream.sources,
                tracks = tracks,
                maxSupportedHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
            )
            _uiState.update { it.copy(availableQualities = qualities) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isSessionActive) return
            val player = exoPlayerManager.peekPlayer() ?: return
            val stream = currentStreamData
            val currentPos = player.currentPosition.coerceAtLeast(_progressState.value.currentPositionMs).coerceAtLeast(0L)

            StreamingDiagnostics.logEvent(
                "player_error code=${error.errorCodeName} pos_ms=$currentPos retry_count=$streamRetryCount"
            )

            // 1. If device is temporarily offline, wait for reconnection and resume smoothly without losing position
            val currentTier = networkMonitor?.refreshState()
            if (currentTier == NetworkTier.OFFLINE) {
                scheduleNetworkReconnectRecovery(currentPos)
                return
            }

            // 2. If hardware video decoder failed (init or mid-stream decoding error), recover with software decoder fallback
            val isDecoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            if (isDecoderError && !exoPlayerManager.preferSoftwareDecoderFallback) {
                if (attemptVideoRenderingRecovery(
                        reason = "player_decoder_error_${error.errorCodeName}",
                        forceSoftwareFallback = true
                    )
                ) {
                    return
                }
            }

            // 3. Try fallback quality/source URLs in the current StreamData payload first
            if (stream != null && streamRetryCount < stream.sources.size) {
                streamRetryCount++
                val nextIndex = streamRetryCount.coerceAtMost(stream.sources.lastIndex)
                val fallbackSource = stream.sources.getOrNull(nextIndex)
                if (fallbackSource != null) {
                    currentSelectedSourceUrl = fallbackSource.url
                    exoPlayerManager.loadStream(
                        streamData = stream,
                        selectedSourceUrl = fallbackSource.url,
                        startPositionMs = currentPos,
                        playWhenReady = !isAppInBackground,
                        playbackSpeed = _uiState.value.playbackSpeed,
                        preferredAudioType = _uiState.value.audioLanguage
                    )
                    return
                }
            }

            // 4. If all sources failed (e.g. expired CDN token), invalidate cache and refresh once from the same provider
            val activeAnime = _uiState.value.anime
            if (activeAnime != null && !hasRefreshedStreamOnError) {
                hasRefreshedStreamOnError = true
                streamRetryCount = 0
                stream?.let {
                    playbackRepository.invalidateCachedStream(
                        anilistId = it.anilistId,
                        episodeNumber = _uiState.value.currentEpisodeNumber,
                        language = _uiState.value.audioLanguage
                    )
                }
                val generation = sessionGeneration
                viewModelScope.launch {
                    val prefs = appSettings.value
                    if (!isSessionActive || generation != sessionGeneration) return@launch
                    loadStreamForCurrentEpisode(
                        anime = activeAnime,
                        episodeNumber = _uiState.value.currentEpisodeNumber,
                        language = _uiState.value.audioLanguage,
                        overridePositionMs = currentPos,
                        prefs = prefs,
                        generation = generation,
                        forceRefresh = true
                    )
                }
                return
            }

            _uiState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "Playback failed or stream link expired. Press Retry to fetch a fresh stream."
                )
            }
        }
    }

    init {
        exoPlayerManager.setPlayerListener(playerListener)
        exoPlayerManager.setPerformanceCallbacks(
            onManifestLoaded = { durationMs ->
                activeMetrics?.let { current ->
                    if (current.hlsManifestLoadDurationMs == null) {
                        activeMetrics = StreamingDiagnostics.recordHlsManifestLoaded(current, durationMs)
                    }
                }
            },
            onFirstFrameRendered = {
                hasRenderedFirstFrameForSession = true
                stalledFrameAudioAdvanceMs = 0L
                activeMetrics?.let { current ->
                    activeMetrics = StreamingDiagnostics.recordFirstFrameRendered(current)
                }
            },
            onVideoFormatChanged = { width, height, bitrate ->
                activeMetrics?.let { current ->
                    activeMetrics = StreamingDiagnostics.recordVideoFormat(current, width, height, bitrate)
                }
            },
            onVideoCodecError = { errorDetail ->
                if (isSessionActive && !isAppInBackground) {
                    viewModelScope.launch {
                        attemptVideoRenderingRecovery(
                            reason = "codec_error_$errorDetail",
                            forceSoftwareFallback = true
                        )
                    }
                }
            }
        )
    }

    private fun scheduleNetworkReconnectRecovery(resumePositionMs: Long) {
        val monitor = networkMonitor ?: return
        networkRecoveryJob?.cancel()
        _uiState.update {
            it.copy(
                isBuffering = true,
                transientToastMessage = "Waiting for network connection..."
            )
        }
        val generation = sessionGeneration
        networkRecoveryJob = viewModelScope.launch {
            monitor.isConnected.first { it }
            if (!isSessionActive || generation != sessionGeneration) return@launch
            val player = exoPlayerManager.peekPlayer() ?: return@launch
            StreamingDiagnostics.logEvent("network_reconnected resuming_pos_ms=$resumePositionMs")
            _uiState.update { it.copy(transientToastMessage = "Connection restored") }
            player.seekTo(resumePositionMs)
            player.prepare()
            if (!isAppInBackground) {
                player.playWhenReady = true
                player.play()
            }
        }
    }

    fun startPlaybackSession(
        hoshikaId: Long,
        episodeNumber: Int,
        requestedLanguage: String? = null
    ) {
        val previousState = _uiState.value
        val isEpisodeSwitch = isSessionActive &&
            previousState.anime?.hoshikaId == hoshikaId &&
            previousState.currentEpisodeNumber != episodeNumber

        // Cancel all obsolete jobs from the previous episode/session immediately
        sessionJob?.cancel()
        episodesLoadJob?.cancel()
        aniSkipJob?.cancel()
        prewarmNextEpisodeJob?.cancel()
        networkRecoveryJob?.cancel()
        audioSwitchJob?.cancel()
        progressPollingJob?.cancel()
        countdownJob?.cancel()

        val generation = ++sessionGeneration
        isSessionActive = true
        isAppInBackground = false
        streamRetryCount = 0
        hasRefreshedStreamOnError = false
        prewarmedEpisodeNumber = null
        currentStreamData = null
        currentSelectedSourceUrl = null
        bufferingStartedElapsedMs = SystemClock.elapsedRealtime()
        hasRenderedFirstFrameForSession = false
        hasCompletedInitialBufferForSession = false
        videoRecoveryAttempts = 0
        lastVideoRecoveryElapsedMs = 0L
        lastObservedRenderedFrames = -1
        lastObservedAudioPosForFrameCheckMs = -1L
        stalledFrameAudioAdvanceMs = 0L

        // Immediately clear previous episode skip intervals so stale buttons never appear during loading
        aniSkipManager.resetForEpisode(episodeNumber.coerceAtLeast(1))
        exoPlayerManager.resetDecoderFallbackForNewSession()
        exoPlayerManager.audioTrackManager.resetSessionDiagnostics()

        val currentTier = networkMonitor?.refreshState() ?: NetworkTier.STANDARD
        activeMetrics = StreamingDiagnostics.startSession(
            episodeNumber = episodeNumber.coerceAtLeast(1),
            isEpisodeSwitch = isEpisodeSwitch,
            networkTier = currentTier.name
        )

        // Reuse the existing ExoPlayer instance without rebuilding it
        exoPlayerManager.setPlayerListener(playerListener)
        exoPlayerManager.stopAndClear()

        sessionJob = viewModelScope.launch {
            val prefs = preferencesRepository.settingsFlow.first()
            if (!isSessionActive || generation != sessionGeneration) return@launch

            val language = AudioTrackManager.normalizeAudioType(requestedLanguage)
                ?: AudioTrackManager.normalizeAudioType(prefs.preferredAudioLanguage)
                ?: AudioTrackManager.AUDIO_SUB
            val requestedEp = episodeNumber.coerceAtLeast(1)

            // Fast-path: if we are switching episodes for the already-loaded anime, reuse metadata & episodes in 0ms
            val existingAnime = previousState.anime?.takeIf {
                it.hoshikaId == hoshikaId && !it.format.isNullOrBlank()
            }
            val existingEpisodes = if (existingAnime != null) previousState.episodes else emptyList()

            _progressState.value = PlaybackProgressState()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = null,
                    currentEpisodeNumber = requestedEp,
                    audioLanguage = language,
                    subtitlesEnabled = prefs.subtitlePreferences.enabled,
                    selectedSubtitleLanguage = prefs.subtitlePreferences.preferredLanguage,
                    selectedQualityLabel = prefs.defaultQuality,
                    showNextEpisodeCountdown = false,
                    activeSkipPrompt = null
                )
            }

            // 1. Obtain anime metadata using the fast playback path (never blocks on TMDB/Fanart logo enrichment)
            val anime = if (existingAnime != null) {
                existingAnime
            } else {
                when (val animeRes = animeRepository.getAnimeForPlayback(hoshikaId)) {
                    is ResourceResult.Success -> animeRes.data
                    is ResourceResult.Error -> {
                        if (!isSessionActive || generation != sessionGeneration) return@launch
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = animeRes.userMessage)
                        }
                        return@launch
                    }
                }
            }
            if (!isSessionActive || generation != sessionGeneration) return@launch

            val isMovie = StreamingSourceResolver.isMovie(anime.format)
            val effectiveEpisodeNumber = StreamingSourceResolver.resolveEpisodeNumber(
                format = anime.format,
                requestedEpisode = requestedEp
            )

            if (isMovie) {
                _uiState.update {
                    it.copy(
                        anime = anime,
                        episodes = emptyList(),
                        currentEpisodeNumber = effectiveEpisodeNumber,
                        currentEpisodeTitle = null,
                        previousEpisode = null,
                        nextEpisode = null
                    )
                }
            } else if (existingEpisodes.isNotEmpty()) {
                val currentEpObj = existingEpisodes.find { it.number == effectiveEpisodeNumber }
                val prevEp = nextEpisodeManager.findPreviousEpisode(existingEpisodes, effectiveEpisodeNumber)
                val nextEp = nextEpisodeManager.findNextEpisode(existingEpisodes, effectiveEpisodeNumber)
                _uiState.update {
                    it.copy(
                        anime = anime,
                        episodes = existingEpisodes,
                        currentEpisodeNumber = effectiveEpisodeNumber,
                        currentEpisodeTitle = currentEpObj?.title,
                        previousEpisode = prevEp,
                        nextEpisode = nextEp
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        anime = anime,
                        currentEpisodeNumber = effectiveEpisodeNumber
                    )
                }
                // Fetch episode list concurrently in the background so it NEVER delays stream resolution
                episodesLoadJob = viewModelScope.launch {
                    val episodesRes = animeRepository.getAnimeEpisodes(hoshikaId)
                    if (!isSessionActive || generation != sessionGeneration) return@launch
                    val loadedEpisodes = (episodesRes as? ResourceResult.Success)?.data ?: emptyList()
                    val currentEpObj = loadedEpisodes.find { it.number == effectiveEpisodeNumber }
                    val prevEp = nextEpisodeManager.findPreviousEpisode(loadedEpisodes, effectiveEpisodeNumber)
                    val nextEp = nextEpisodeManager.findNextEpisode(loadedEpisodes, effectiveEpisodeNumber)
                    _uiState.update {
                        it.copy(
                            episodes = loadedEpisodes,
                            currentEpisodeTitle = currentEpObj?.title,
                            previousEpisode = prevEp,
                            nextEpisode = nextEp
                        )
                    }
                }
            }

            // 2. Immediately resolve the stream URL and start ExoPlayer preparation
            loadStreamForCurrentEpisode(
                anime = anime,
                episodeNumber = effectiveEpisodeNumber,
                language = language,
                overridePositionMs = null,
                prefs = prefs,
                generation = generation,
                forceRefresh = false
            )
        }
    }

    private suspend fun loadStreamForCurrentEpisode(
        anime: Anime,
        episodeNumber: Int,
        language: String,
        overridePositionMs: Long?,
        prefs: AppSettings,
        generation: Long,
        forceRefresh: Boolean = false
    ) {
        when (val result = playbackRepository.resolveEpisodeStream(anime, episodeNumber, language, forceRefresh)) {
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
                if (result.skipIntervals.hasAnyInterval || !aniSkipManager.getIntervals().hasAnyInterval) {
                    aniSkipManager.setIntervals(result.skipIntervals)
                }

                activeMetrics?.let { current ->
                    val afterRes = StreamingDiagnostics.recordServerResolution(
                        session = current,
                        anilistId = streamData.anilistId,
                        provider = result.provider.sourceId,
                        durationMs = result.serverResolutionDurationMs
                    )
                    activeMetrics = StreamingDiagnostics.recordApiAndPlayableUrl(
                        session = afterRes,
                        apiDurationMs = result.apiRequestDurationMs,
                        cacheHit = result.fromShortLivedCache
                    )
                }

                val preferredQuality = prefs.defaultQuality
                val chosenSource = streamData.sources.find {
                    it.quality.equals(preferredQuality, ignoreCase = true)
                } ?: streamData.sources.first()

                val startPos = overridePositionMs
                    ?: if (prefs.resumePlayback) result.savedPositionMs else 0L

                val qualities = qualityManager.extractAvailableQualities(
                    sources = streamData.sources,
                    tracks = null,
                    maxSupportedHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                )
                val speed = _uiState.value.playbackSpeed

                val streamTitle = `in`.aniscroll.tv.domain.model.sanitizeAnimeTitle(streamData.title)
                val updatedAnime = if (streamTitle != null && (anime.preferredTitle.isBlank() || anime.preferredTitle.startsWith("Anime #"))) {
                    anime.copy(
                        title = anime.title.takeIf { it.isNotBlank() } ?: streamTitle,
                        englishTitle = anime.englishTitle ?: streamTitle
                    )
                } else {
                    anime
                }

                _progressState.update { it.copy(currentPositionMs = startPos) }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        anime = updatedAnime,
                        audioLanguage = language,
                        availableQualities = qualities,
                        selectedQualityLabel = chosenSource.quality,
                        availableSubtitles = streamData.subtitles
                    )
                }

                // Start ExoPlayer preparation immediately after receiving the playable URL
                val shouldAutoPlay = !isAppInBackground && isSessionActive && generation == sessionGeneration
                currentSelectedSourceUrl = chosenSource.url
                exoPlayerManager.loadStream(
                    streamData = streamData,
                    selectedSourceUrl = chosenSource.url,
                    startPositionMs = startPos,
                    playWhenReady = shouldAutoPlay,
                    playbackSpeed = speed,
                    preferredAudioType = language
                )

                val player = exoPlayerManager.peekPlayer()
                if (player != null) {
                    val currentTier = networkMonitor?.currentTier() ?: NetworkTier.STANDARD
                    val initialOption = qualities.find {
                        it.label.equals(chosenSource.quality, ignoreCase = true)
                    } ?: AvailableQualityOption("Auto", null)
                    qualityManager.applyQualitySelection(
                        player = player,
                        option = initialOption,
                        networkTier = currentTier,
                        maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                    )

                    subtitleManager.applyTrackSelection(
                        player = player,
                        enabled = prefs.subtitlePreferences.enabled,
                        preferredLanguage = prefs.subtitlePreferences.preferredLanguage,
                        availableSubtitles = streamData.subtitles
                    )
                }

                // Fetch external AniSkip v2 intervals asynchronously in the background without delaying video start
                aniSkipJob?.cancel()
                aniSkipJob = viewModelScope.launch {
                    val enrichedIntervals = playbackRepository.fetchAniSkipIntervals(
                        anilistId = streamData.anilistId,
                        episodeNumber = episodeNumber,
                        streamData = streamData,
                        malId = streamData.malId ?: updatedAnime.malId
                    )
                    if (!isSessionActive || generation != sessionGeneration) return@launch
                    aniSkipManager.setIntervals(enrichedIntervals)
                    val activePlayer = exoPlayerManager.peekPlayer()
                    val currentPos = activePlayer?.currentPosition?.coerceAtLeast(0L) ?: startPos
                    val currentDur = activePlayer?.duration?.coerceAtLeast(0L) ?: 0L
                    val evaluatedPrompt = aniSkipManager.evaluateCurrentPosition(currentPos, currentDur)
                    if (_uiState.value.activeSkipPrompt != evaluatedPrompt) {
                        _uiState.update { it.copy(activeSkipPrompt = evaluatedPrompt) }
                    }
                }

                startProgressPolling(generation)
            }
        }
    }

    fun switchAudioLanguage(newLanguage: String) {
        if (!isSessionActive) return
        val current = _uiState.value
        val anime = current.anime ?: return
        val normalized = AudioTrackManager.normalizeAudioType(newLanguage) ?: return
        if (normalized == current.audioLanguage && current.errorMessage == null) return

        val savedPosition = exoPlayerManager.peekPlayer()?.currentPosition?.coerceAtLeast(0L)
            ?: _progressState.value.currentPositionMs.coerceAtLeast(0L)
        val generation = sessionGeneration

        StreamingDiagnostics.logEvent(
            "audio_language_switch_requested hoshika_id=${anime.hoshikaId} ep=${current.currentEpisodeNumber} " +
                "from=${current.audioLanguage} to=$normalized pos_ms=$savedPosition"
        )

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
                    val newStream = result.streamData
                    currentStreamData = newStream
                    streamRetryCount = 0
                    hasRefreshedStreamOnError = false
                    aniSkipManager.setIntervals(result.skipIntervals)

                    val preferredQuality = current.selectedQualityLabel
                    val chosenSource = newStream.sources.find {
                        it.quality.equals(preferredQuality, ignoreCase = true)
                    } ?: newStream.sources.first()
                    currentSelectedSourceUrl = chosenSource.url

                    val qualities = qualityManager.extractAvailableQualities(
                        sources = newStream.sources,
                        tracks = null,
                        maxSupportedHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                    )

                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = null,
                            audioLanguage = normalized,
                            availableQualities = qualities,
                            selectedQualityLabel = chosenSource.quality,
                            availableSubtitles = newStream.subtitles,
                            transientToastMessage = "Audio: ${normalized.uppercase()}"
                        )
                    }

                    exoPlayerManager.loadStream(
                        streamData = newStream,
                        selectedSourceUrl = chosenSource.url,
                        startPositionMs = savedPosition,
                        playWhenReady = !isAppInBackground,
                        playbackSpeed = _uiState.value.playbackSpeed,
                        preferredAudioType = normalized
                    )

                    val player = exoPlayerManager.peekPlayer()
                    if (player != null) {
                        val currentTier = networkMonitor?.currentTier() ?: NetworkTier.STANDARD
                        val initialOption = qualities.find {
                            it.label.equals(chosenSource.quality, ignoreCase = true)
                        } ?: AvailableQualityOption("Auto", null)
                        qualityManager.applyQualitySelection(
                            player = player,
                            option = initialOption,
                            networkTier = currentTier,
                            maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                        )
                        subtitleManager.applyTrackSelection(
                            player = player,
                            enabled = _uiState.value.subtitlesEnabled,
                            preferredLanguage = _uiState.value.selectedSubtitleLanguage,
                            availableSubtitles = newStream.subtitles
                        )
                    }

                    persistCurrentProgress(languageOverride = normalized)

                    aniSkipJob?.cancel()
                    aniSkipJob = viewModelScope.launch {
                        val enriched = playbackRepository.fetchAniSkipIntervals(
                            anilistId = newStream.anilistId,
                            episodeNumber = current.currentEpisodeNumber,
                            streamData = newStream,
                            malId = newStream.malId ?: anime.malId
                        )
                        if (!isSessionActive || generation != sessionGeneration) return@launch
                        aniSkipManager.setIntervals(enriched)
                    }
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
            currentSelectedSourceUrl = option.sourceUrl
            exoPlayerManager.loadStream(
                streamData = stream,
                selectedSourceUrl = option.sourceUrl,
                startPositionMs = currentPos,
                playWhenReady = player.playWhenReady && !isAppInBackground,
                playbackSpeed = _uiState.value.playbackSpeed,
                preferredAudioType = _uiState.value.audioLanguage
            )
        } else {
            val currentTier = networkMonitor?.currentTier() ?: NetworkTier.STANDARD
            qualityManager.applyQualitySelection(
                player = player,
                option = option,
                networkTier = currentTier,
                maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
            )
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
        if (deltaMs < 0L) {
            aniSkipManager.onManualUserSeek(target)
        }
        runCatching { player.seekTo(target) }
        _progressState.update { it.copy(currentPositionMs = target) }
        _uiState.update {
            it.copy(activeSkipPrompt = aniSkipManager.evaluateCurrentPosition(target, duration))
        }
    }

    fun performSkip(prompt: ActiveSkipPrompt) {
        aniSkipManager.markSkipped(prompt)
        _uiState.update { it.copy(activeSkipPrompt = null) }

        val player = exoPlayerManager.peekPlayer() ?: return
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.coerceAtLeast(0L)
        val safeTargetMs = if (duration > 0L) {
            prompt.targetTimeMs.coerceIn(0L, (duration - 250L).coerceAtLeast(0L))
        } else {
            prompt.targetTimeMs.coerceAtLeast(0L)
        }

        val seekSucceeded = runCatching {
            player.seekTo(safeTargetMs)
        }.onFailure { err ->
            StreamingDiagnostics.logEvent(
                "skip_seek_error type=${prompt.type} target_ms=$safeTargetMs error=${err.javaClass.simpleName}:${err.message ?: "none"}"
            )
        }.isSuccess

        if (seekSucceeded) {
            _progressState.update { it.copy(currentPositionMs = safeTargetMs) }
            StreamingDiagnostics.logEvent(
                "skip_seek_executed type=${prompt.type} from_ms=$currentPos target_ms=$safeTargetMs " +
                    "duration_ms=$duration play_when_ready=${player.playWhenReady}"
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

    /**
     * Evaluates whether video rendering has stalled or turned black while audio continues playing.
     * Returns true if a bounded recovery action was triggered.
     */
    fun evaluateVideoRenderingHealth(
        currentAudioPositionMs: Long,
        isPlaying: Boolean,
        playbackState: Int,
        snapshot: VideoRenderingHealthSnapshot? = exoPlayerManager.peekPlayer()?.let {
            exoPlayerManager.getVideoRenderingHealthSnapshot(it)
        }
    ): Boolean {
        if (!isSessionActive || isAppInBackground || !isPlaying || playbackState != Player.STATE_READY) {
            lastObservedAudioPosForFrameCheckMs = currentAudioPositionMs
            if (snapshot != null) {
                lastObservedRenderedFrames = snapshot.renderedOutputBufferCount
            }
            return false
        }
        val health = snapshot ?: return false

        // 1. Hardware video codec reported an error while audio continues
        if (health.lastVideoCodecError != null) {
            return attemptVideoRenderingRecovery(
                reason = "health_codec_error_${health.lastVideoCodecError}",
                forceSoftwareFallback = true
            )
        }

        // 2. Video track group exists in stream but was deselected by track constraints
        if (health.hasVideoTrackGroup && !health.isVideoTrackSelected) {
            val player = exoPlayerManager.peekPlayer()
            if (player != null) {
                qualityManager.ensureVideoTrackEnabled(
                    player = player,
                    maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
                )
            }
            return attemptVideoRenderingRecovery(
                reason = "video_track_deselected_while_audio_playing",
                forceSoftwareFallback = false
            )
        }

        // 3. Check if audio position is advancing normally while rendered video frames are completely frozen
        val prevAudioPos = lastObservedAudioPosForFrameCheckMs
        val prevRenderedFrames = lastObservedRenderedFrames
        lastObservedAudioPosForFrameCheckMs = currentAudioPositionMs
        lastObservedRenderedFrames = health.renderedOutputBufferCount

        if (hasRenderedFirstFrameForSession && prevAudioPos >= 0L && prevRenderedFrames > 0) {
            val audioDeltaMs = currentAudioPositionMs - prevAudioPos
            if (audioDeltaMs in 100L..3_000L) {
                if (health.renderedOutputBufferCount <= prevRenderedFrames) {
                    stalledFrameAudioAdvanceMs += audioDeltaMs
                    if (stalledFrameAudioAdvanceMs >= VIDEO_STALL_AUDIO_ADVANCE_THRESHOLD_MS) {
                        stalledFrameAudioAdvanceMs = 0L
                        return attemptVideoRenderingRecovery(
                            reason = "video_frames_stalled_after_${VIDEO_STALL_AUDIO_ADVANCE_THRESHOLD_MS}ms_audio_advance",
                            forceSoftwareFallback = videoRecoveryAttempts >= 1
                        )
                    }
                } else {
                    stalledFrameAudioAdvanceMs = 0L
                }
            } else if (audioDeltaMs < 0L || audioDeltaMs > 3_000L) {
                // User sought or jumped
                stalledFrameAudioAdvanceMs = 0L
            }
        }

        return false
    }

    /**
     * Performs a bounded recovery (max [MAX_VIDEO_RECOVERY_ATTEMPTS] per session) when video rendering
     * fails or stalls while audio continues playing, preserving the current playback position.
     */
    fun attemptVideoRenderingRecovery(
        reason: String,
        forceSoftwareFallback: Boolean = false
    ): Boolean {
        if (!isSessionActive || isAppInBackground) return false
        if (videoRecoveryAttempts >= MAX_VIDEO_RECOVERY_ATTEMPTS) {
            StreamingDiagnostics.logEvent(
                "video_recovery_skipped reason=$reason attempts_exhausted=$videoRecoveryAttempts"
            )
            return false
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastVideoRecoveryElapsedMs > 0L &&
            (nowElapsed - lastVideoRecoveryElapsedMs) < RECOVERY_COOLDOWN_MS &&
            !forceSoftwareFallback
        ) {
            return false
        }

        videoRecoveryAttempts++
        lastVideoRecoveryElapsedMs = nowElapsed
        stalledFrameAudioAdvanceMs = 0L
        lastObservedRenderedFrames = -1

        val currentPlayer = exoPlayerManager.peekPlayer()
        val resumePos = (currentPlayer?.currentPosition ?: 0L)
            .coerceAtLeast(_progressState.value.currentPositionMs)
            .coerceAtLeast(0L)
        val stream = currentStreamData

        activeMetrics?.let { current ->
            activeMetrics = StreamingDiagnostics.recordVideoRecoveryAttempt(
                session = current,
                attempt = videoRecoveryAttempts,
                reason = reason,
                positionMs = resumePos
            )
        } ?: StreamingDiagnostics.logEvent(
            "video_recovery_attempt attempt=$videoRecoveryAttempts pos_ms=$resumePos reason=$reason"
        )

        // Attempt 1 (soft recovery): re-enable video track constraints, rebind surface, and re-prepare at current position
        if (!forceSoftwareFallback && videoRecoveryAttempts == 1 && currentPlayer != null) {
            qualityManager.ensureVideoTrackEnabled(
                player = currentPlayer,
                maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
            )
            _uiState.update {
                it.copy(
                    surfaceRecoveryEpoch = it.surfaceRecoveryEpoch + 1,
                    videoRecoveryCount = videoRecoveryAttempts
                )
            }
            currentPlayer.seekTo(resumePos)
            currentPlayer.prepare()
            if (!isAppInBackground) {
                currentPlayer.playWhenReady = true
                currentPlayer.play()
            }
            return true
        }

        // Attempt 2 (or forced decoder fallback): switch to software decoder fallback and reload stream at resumePos
        val recreatedPlayer = exoPlayerManager.enableSoftwareDecoderFallbackAndRecreate()
        exoPlayerManager.setPlayerListener(playerListener)

        _uiState.update {
            it.copy(
                surfaceRecoveryEpoch = it.surfaceRecoveryEpoch + 1,
                videoRecoveryCount = videoRecoveryAttempts
            )
        }

        if (stream != null && stream.sources.isNotEmpty()) {
            // Try an alternate source URL if available (e.g. direct m3u8 vs proxy URL), otherwise reuse current source
            val activeUrl = currentSelectedSourceUrl ?: stream.sources.first().url
            val alternateSource = stream.sources.firstOrNull { it.url != activeUrl } ?: stream.sources.first()
            currentSelectedSourceUrl = alternateSource.url

            exoPlayerManager.loadStream(
                streamData = stream,
                selectedSourceUrl = alternateSource.url,
                startPositionMs = resumePos,
                playWhenReady = !isAppInBackground,
                playbackSpeed = _uiState.value.playbackSpeed,
                preferredAudioType = _uiState.value.audioLanguage
            )
            qualityManager.ensureVideoTrackEnabled(
                player = recreatedPlayer,
                maxHardwareHeight = exoPlayerManager.deviceProfile.effectiveMaxPlaybackHeight
            )
            subtitleManager.applyTrackSelection(
                player = recreatedPlayer,
                enabled = _uiState.value.subtitlesEnabled,
                preferredLanguage = _uiState.value.selectedSubtitleLanguage,
                availableSubtitles = stream.subtitles
            )
            return true
        }

        return false
    }

    private fun maybePrewarmNextEpisode(posMs: Long, durMs: Long, generation: Long) {
        if (durMs <= 0L) return
        val remainingMs = durMs - posMs
        if (remainingMs !in 1L..75_000L) return

        val state = _uiState.value
        val anime = state.anime ?: return
        val nextEp = state.nextEpisode ?: return
        if (prewarmedEpisodeNumber == nextEp.number) return

        prewarmedEpisodeNumber = nextEp.number
        prewarmNextEpisodeJob?.cancel()
        prewarmNextEpisodeJob = viewModelScope.launch {
            if (!isSessionActive || generation != sessionGeneration) return@launch
            StreamingDiagnostics.logEvent("prewarm_next_episode ep=${nextEp.number}")
            playbackRepository.resolveEpisodeStream(
                anime = anime,
                episodeNumber = nextEp.number,
                language = state.audioLanguage,
                forceRefresh = false
            )
        }
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

                    evaluateVideoRenderingHealth(
                        currentAudioPositionMs = pos,
                        isPlaying = player.isPlaying,
                        playbackState = player.playbackState
                    )

                    val skipPrompt = aniSkipManager.evaluateCurrentPosition(pos, dur)
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

                    maybePrewarmNextEpisode(pos, dur, generation)

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

    fun persistCurrentProgress(languageOverride: String? = null) {
        val state = _uiState.value
        val anime = state.anime ?: return
        val stream = currentStreamData ?: return
        val player = exoPlayerManager.peekPlayer() ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.coerceAtLeast(0L)
        if (dur <= 0L) return

        val effectiveLanguage = AudioTrackManager.normalizeAudioType(languageOverride)
            ?: AudioTrackManager.normalizeAudioType(state.audioLanguage)
            ?: AudioTrackManager.AUDIO_SUB

        viewModelScope.launch {
            withContext(NonCancellable) {
                playbackRepository.savePlaybackProgress(
                    anime = anime,
                    anilistId = stream.anilistId,
                    episodeNumber = state.currentEpisodeNumber,
                    language = effectiveLanguage,
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
        episodesLoadJob?.cancel()
        episodesLoadJob = null
        aniSkipJob?.cancel()
        aniSkipJob = null
        prewarmNextEpisodeJob?.cancel()
        prewarmNextEpisodeJob = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        audioSwitchJob?.cancel()
        audioSwitchJob = null
        progressPollingJob?.cancel()
        progressPollingJob = null
        countdownJob?.cancel()
        countdownJob = null

        persistCurrentProgress()
        currentStreamData = null
        bufferingStartedElapsedMs = null
        aniSkipManager.resetForEpisode(null)
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
