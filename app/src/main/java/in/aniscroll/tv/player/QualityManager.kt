package `in`.aniscroll.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import `in`.aniscroll.tv.core.network.NetworkTier
import `in`.aniscroll.tv.domain.model.StreamQualitySource

data class AvailableQualityOption(
    val label: String, // "Auto", "1080p", "720p", "480p", "360p"
    val height: Int?,
    val sourceUrl: String? = null,
    val trackGroupIndex: Int? = null,
    val trackIndex: Int? = null
)

/**
 * Manages HLS adaptive and specific resolution selection in Media3 ExoPlayer.
 * Supports hardware-aware resolution sizing and automatic step-down on repeated rebuffering
 * while guaranteeing that the video track is never disabled when constraints are exceeded.
 */
@OptIn(UnstableApi::class)
class QualityManager {

    companion object {
        private const val REBUFFER_STEPDOWN_HEIGHT = 720
        private const val SEVERE_REBUFFER_STEPDOWN_HEIGHT = 480
    }

    fun extractAvailableQualities(
        sources: List<StreamQualitySource>,
        tracks: Tracks?,
        maxSupportedHeight: Int = 2160
    ): List<AvailableQualityOption> {
        val options = mutableListOf<AvailableQualityOption>()
        options.add(AvailableQualityOption(label = "Auto", height = null))

        // 1. Inspect Media3 HLS video track groups for adaptive master playlist variants supported by the device decoder
        if (tracks != null) {
            for ((groupIndex, group) in tracks.groups.withIndex()) {
                if (group.type == C.TRACK_TYPE_VIDEO) {
                    for (trackIdx in 0 until group.length) {
                        if (group.isTrackSupported(trackIdx)) {
                            val format = group.getTrackFormat(trackIdx)
                            val height = format.height
                            if (height in 1..maxSupportedHeight) {
                                val label = "${height}p"
                                if (options.none { it.label == label }) {
                                    options.add(
                                        AvailableQualityOption(
                                            label = label,
                                            height = height,
                                            trackGroupIndex = groupIndex,
                                            trackIndex = trackIdx
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Also include distinct quality URLs returned directly by the AniScroll API within hardware limits
        for (source in sources) {
            val label = source.quality
            if (!label.equals("Auto", ignoreCase = true) &&
                !label.equals("Direct HLS", ignoreCase = true) &&
                options.none { it.label.equals(label, ignoreCase = true) }
            ) {
                val parsedHeight = label.filter { it.isDigit() }.toIntOrNull()
                if (parsedHeight == null || parsedHeight <= maxSupportedHeight) {
                    options.add(
                        AvailableQualityOption(
                            label = label,
                            height = parsedHeight,
                            sourceUrl = source.url
                        )
                    )
                }
            }
        }

        return options.sortedWith(
            compareBy<AvailableQualityOption> { if (it.label == "Auto") 0 else 1 }
                .thenByDescending { it.height ?: 0 }
        )
    }

    fun applyQualitySelection(
        player: ExoPlayer,
        option: AvailableQualityOption,
        networkTier: NetworkTier = NetworkTier.FAST_WIFI,
        maxHardwareHeight: Int = 2160
    ) {
        val maxAllowedHeight = maxHardwareHeight.coerceAtLeast(720)
        val maxAllowedWidth = if (maxAllowedHeight >= 2160) 3840 else 1920
        val paramsBuilder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)

        if (option.label.equals("Auto", ignoreCase = true) || option.height == null) {
            paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            paramsBuilder.setMaxVideoSize(maxAllowedWidth, maxAllowedHeight)
            paramsBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
        } else {
            val targetHeight = minOf(option.height, maxAllowedHeight)
            val tracks = player.currentTracks
            var appliedOverride = false
            if (option.trackGroupIndex != null && option.trackIndex != null &&
                option.trackGroupIndex < tracks.groups.size &&
                option.height <= maxAllowedHeight
            ) {
                val group = tracks.groups[option.trackGroupIndex]
                if (group.isTrackSupported(option.trackIndex)) {
                    paramsBuilder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                    )
                    appliedOverride = true
                }
            }
            if (!appliedOverride) {
                paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                paramsBuilder.setMaxVideoSize(maxAllowedWidth, targetHeight)
                paramsBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
            }
        }
        player.trackSelectionParameters = paramsBuilder.build()
    }

    /**
     * Automatically steps down maximum adaptive resolution when repeated rebuffering occurs in "Auto" mode,
     * while preserving video playback if the stream only has a higher-resolution variant.
     */
    fun applyAdaptiveRebufferConstraint(
        player: ExoPlayer,
        selectedQualityLabel: String,
        rebufferCount: Int,
        networkTier: NetworkTier,
        maxHardwareHeight: Int = 2160
    ) {
        if (!selectedQualityLabel.equals("Auto", ignoreCase = true)) return

        val maxAllowedHeight = maxHardwareHeight.coerceAtLeast(720)
        val maxAllowedWidth = if (maxAllowedHeight >= 2160) 3840 else 1920
        val paramsBuilder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)

        when {
            rebufferCount >= 4 -> {
                paramsBuilder.setMaxVideoSize(854, SEVERE_REBUFFER_STEPDOWN_HEIGHT)
                paramsBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
                StreamingDiagnostics.logEvent(
                    "abr_stepdown reason=severe_rebuffering count=$rebufferCount max_height=${SEVERE_REBUFFER_STEPDOWN_HEIGHT}p"
                )
            }
            rebufferCount >= 2 -> {
                paramsBuilder.setMaxVideoSize(1280, minOf(REBUFFER_STEPDOWN_HEIGHT, maxAllowedHeight))
                paramsBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
                StreamingDiagnostics.logEvent(
                    "abr_stepdown reason=rebuffering count=$rebufferCount max_height=${REBUFFER_STEPDOWN_HEIGHT}p"
                )
            }
            else -> {
                paramsBuilder.setMaxVideoSize(maxAllowedWidth, maxAllowedHeight)
                paramsBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
            }
        }
        player.trackSelectionParameters = paramsBuilder.build()
    }

    /**
     * Ensures the video renderer is enabled and clears any restrictive video overrides so a single-variant
     * or high-profile stream is never left with 0 selected video tracks while audio continues.
     */
    fun ensureVideoTrackEnabled(
        player: ExoPlayer,
        maxHardwareHeight: Int = 2160
    ) {
        val maxAllowedHeight = maxHardwareHeight.coerceAtLeast(720)
        val maxAllowedWidth = if (maxAllowedHeight >= 2160) 3840 else 1920
        val paramsBuilder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(maxAllowedWidth, maxAllowedHeight)
            .setMaxVideoBitrate(Int.MAX_VALUE)
        player.trackSelectionParameters = paramsBuilder.build()
    }
}
