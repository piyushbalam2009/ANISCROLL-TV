package `in`.aniscroll.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import `in`.aniscroll.tv.domain.model.StreamQualitySource

data class AvailableQualityOption(
    val label: String, // "Auto", "1080p", "720p", "480p", "360p"
    val height: Int?,
    val sourceUrl: String? = null,
    val trackGroupIndex: Int? = null,
    val trackIndex: Int? = null
)

/**
 * Manages HLS adaptive and specific resolution selection in Media3 ExoPlayer (Section 18).
 * Only exposes qualities actually returned by the API or present in the HLS master playlist.
 */
@OptIn(UnstableApi::class)
class QualityManager {

    fun extractAvailableQualities(
        sources: List<StreamQualitySource>,
        tracks: Tracks?
    ): List<AvailableQualityOption> {
        val options = mutableListOf<AvailableQualityOption>()
        options.add(AvailableQualityOption(label = "Auto", height = null))

        // 1. Inspect Media3 HLS video track groups for adaptive master playlist variants
        if (tracks != null) {
            for ((groupIndex, group) in tracks.groups.withIndex()) {
                if (group.type == C.TRACK_TYPE_VIDEO) {
                    for (trackIdx in 0 until group.length) {
                        if (group.isTrackSupported(trackIdx)) {
                            val format = group.getTrackFormat(trackIdx)
                            val height = format.height
                            if (height > 0) {
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

        // 2. Also include distinct quality URLs returned directly by the AniScroll API
        for (source in sources) {
            val label = source.quality
            if (!label.equals("Auto", ignoreCase = true) && options.none { it.label.equals(label, ignoreCase = true) }) {
                val parsedHeight = label.filter { it.isDigit() }.toIntOrNull()
                options.add(
                    AvailableQualityOption(
                        label = label,
                        height = parsedHeight,
                        sourceUrl = source.url
                    )
                )
            }
        }

        return options.sortedWith(
            compareBy<AvailableQualityOption> { if (it.label == "Auto") 0 else 1 }
                .thenByDescending { it.height ?: 0 }
        )
    }

    fun applyQualitySelection(
        player: ExoPlayer,
        option: AvailableQualityOption
    ) {
        val paramsBuilder = player.trackSelectionParameters.buildUpon()
        if (option.label.equals("Auto", ignoreCase = true) || option.height == null) {
            paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            paramsBuilder.setMaxVideoSizeSd()
            paramsBuilder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            val tracks = player.currentTracks
            var appliedOverride = false
            if (option.trackGroupIndex != null && option.trackIndex != null &&
                option.trackGroupIndex < tracks.groups.size
            ) {
                val group = tracks.groups[option.trackGroupIndex]
                paramsBuilder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                )
                appliedOverride = true
            }
            if (!appliedOverride) {
                paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                paramsBuilder.setMaxVideoSize(Int.MAX_VALUE, option.height)
            }
        }
        player.trackSelectionParameters = paramsBuilder.build()
    }
}
