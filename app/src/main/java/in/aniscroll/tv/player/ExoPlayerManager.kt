package `in`.aniscroll.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import `in`.aniscroll.tv.domain.model.StreamData
import `in`.aniscroll.tv.domain.model.SubtitleTrack

/**
 * Manages the single shared AndroidX Media3 ExoPlayer instance, HLS media sources,
 * custom HTTP headers, external subtitle configurations, and deterministic lifecycle teardown.
 */
@OptIn(UnstableApi::class)
class ExoPlayerManager(private val context: Context) {

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var attachedListener: Player.Listener? = null

    private val lock = Any()

    fun setPlayerListener(listener: Player.Listener) {
        synchronized(lock) {
            val current = exoPlayer
            val previous = attachedListener
            if (current != null && previous != null && previous !== listener) {
                current.removeListener(previous)
            }
            attachedListener = listener
            if (current != null && previous !== listener) {
                current.addListener(listener)
            }
        }
    }

    /**
     * Returns the existing ExoPlayer without creating a new instance.
     * Always use this inside teardown, background, and polling paths.
     */
    fun peekPlayer(): ExoPlayer? = exoPlayer

    fun getOrCreatePlayer(): ExoPlayer {
        synchronized(lock) {
            exoPlayer?.let { return it }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val created = ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .build()

            attachedListener?.let { created.addListener(it) }
            exoPlayer = created
            return created
        }
    }

    fun loadStream(
        streamData: StreamData,
        selectedSourceUrl: String,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        playbackSpeed: Float = 1.0f
    ) {
        val player = getOrCreatePlayer()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                streamData.headers["User-Agent"]
                    ?: "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25_000)
            .setReadTimeoutMs(25_000)

        if (streamData.headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(streamData.headers)
        }

        val subtitleConfigs = streamData.subtitles.map { sub ->
            buildSubtitleConfiguration(sub)
        }

        val isHls = selectedSourceUrl.contains(".m3u8", ignoreCase = true) ||
            selectedSourceUrl.contains("/proxy/hls", ignoreCase = true) ||
            streamData.sources.any { it.url == selectedSourceUrl && it.isM3u8 }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(selectedSourceUrl))
            .setSubtitleConfigurations(subtitleConfigs)

        if (isHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val mediaItem = mediaItemBuilder.build()

        val mediaSource = if (isHls && subtitleConfigs.isEmpty()) {
            HlsMediaSource.Factory(httpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(httpFactory)
                .createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.setPlaybackSpeed(playbackSpeed.coerceIn(0.25f, 3.0f))
        player.prepare()
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = playWhenReady
    }

    private fun buildSubtitleConfiguration(sub: SubtitleTrack): MediaItem.SubtitleConfiguration {
        val lowerUrl = sub.url.lowercase()
        val mimeType = when {
            lowerUrl.endsWith(".srt") || lowerUrl.contains(".srt&") || lowerUrl.contains(".srt%") -> MimeTypes.APPLICATION_SUBRIP
            lowerUrl.endsWith(".ass") || lowerUrl.endsWith(".ssa") || lowerUrl.contains(".ass&") -> MimeTypes.TEXT_SSA
            lowerUrl.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT
        }

        val flags = if (sub.isDefault) C.SELECTION_FLAG_DEFAULT else 0

        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(mimeType)
            .setLanguage(sub.language)
            .setLabel(sub.label)
            .setSelectionFlags(flags)
            .build()
    }

    /**
     * Pauses active playback immediately without creating a player or allowing auto-resume.
     */
    fun pauseIfActive() {
        synchronized(lock) {
            val player = exoPlayer ?: return
            player.playWhenReady = false
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    /**
     * Stops playback and clears all loaded media items immediately.
     */
    fun stopAndClear() {
        synchronized(lock) {
            val player = exoPlayer ?: return
            player.playWhenReady = false
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    /**
     * Safely stops, detaches listeners, releases ExoPlayer resources, and nulls the reference.
     * Safe to invoke multiple times without crashing or leaking.
     */
    fun releasePlayer() {
        synchronized(lock) {
            val player = exoPlayer ?: return
            exoPlayer = null
            runCatching {
                attachedListener?.let { player.removeListener(it) }
            }
            runCatching {
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
            }
            runCatching {
                player.release()
            }
        }
    }
}
