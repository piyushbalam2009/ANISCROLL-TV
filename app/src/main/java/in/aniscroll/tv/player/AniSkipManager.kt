package `in`.aniscroll.tv.player

import `in`.aniscroll.tv.domain.model.SkipIntervals
import `in`.aniscroll.tv.domain.model.TimeRange

enum class ActiveSkipType {
    RECAP,
    INTRO,
    OUTRO
}

data class ActiveSkipPrompt(
    val type: ActiveSkipType,
    val label: String,
    val targetTimeMs: Long,
    val startTimeMs: Long = 0L
)

/**
 * Manages Skip Intro, Skip Recap, and Skip Outro segment detection, timestamp validation,
 * visibility transitions, and seek targets.
 *
 * Rules:
 * - Shows "Skip Recap" during valid recap interval.
 * - Shows "Skip Intro" during valid intro/opening interval.
 * - Shows "Skip Outro" during valid outro/ending interval.
 * - Disappears immediately after being activated or when the segment ends.
 * - Resets cleanly on episode switch so stale timestamps from another episode never appear.
 * - Validates start < end, minimum length, and bounds against episode duration when known.
 */
class AniSkipManager {

    companion object {
        private const val MIN_SEGMENT_DURATION_SEC = 2.0f
        private const val MAX_SEGMENT_DURATION_SEC = 600.0f
        private const val END_MARGIN_MS = 350L
    }

    private var currentEpisodeNumber: Int? = null
    private var intervals: SkipIntervals = SkipIntervals()
    private val dismissedSkipTypes = mutableSetOf<ActiveSkipType>()
    private var lastActiveType: ActiveSkipType? = null

    fun getIntervals(): SkipIntervals = intervals

    /**
     * Immediately clears previous episode segment state when starting or switching episodes
     * so stale buttons never appear while the new episode is loading.
     */
    fun resetForEpisode(episodeNumber: Int? = null) {
        currentEpisodeNumber = episodeNumber
        intervals = SkipIntervals(episodeNumber = episodeNumber)
        dismissedSkipTypes.clear()
        if (lastActiveType != null) {
            StreamingDiagnostics.logEvent(
                "skip_state_reset episode=${episodeNumber ?: -1} cleared_previous_prompt=$lastActiveType"
            )
            lastActiveType = null
        } else {
            StreamingDiagnostics.logEvent("skip_state_reset episode=${episodeNumber ?: -1}")
        }
    }

    /**
     * Updates active skip intervals for the current episode.
     * Preserves already-dismissed segments if enriching the same episode asynchronously.
     */
    fun setIntervals(newIntervals: SkipIntervals) {
        val incomingEpisode = newIntervals.episodeNumber
        if (incomingEpisode != null && currentEpisodeNumber != null && incomingEpisode != currentEpisodeNumber) {
            dismissedSkipTypes.clear()
        }
        if (incomingEpisode != null) {
            currentEpisodeNumber = incomingEpisode
        }
        intervals = newIntervals

        StreamingDiagnostics.logEvent(
            "skip_intervals_updated ep=${currentEpisodeNumber ?: -1} " +
                "anilist_id=${newIntervals.anilistId ?: -1} mal_id=${newIntervals.malId ?: -1} " +
                "intro=${newIntervals.intro?.let { "${it.startTimeMs}..${it.endTimeMs}ms" } ?: "none"} " +
                "outro=${newIntervals.outro?.let { "${it.startTimeMs}..${it.endTimeMs}ms" } ?: "none"} " +
                "recap=${newIntervals.recap?.let { "${it.startTimeMs}..${it.endTimeMs}ms" } ?: "none"}"
        )
    }

    fun evaluateCurrentPosition(
        positionMs: Long,
        durationMs: Long = 0L
    ): ActiveSkipPrompt? {
        if (positionMs < 0L) return null

        val candidatePrompt = evaluateSegment(
            type = ActiveSkipType.RECAP,
            label = "Skip Recap",
            range = intervals.recap,
            positionMs = positionMs,
            durationMs = durationMs
        ) ?: evaluateSegment(
            type = ActiveSkipType.INTRO,
            label = "Skip Intro",
            range = intervals.intro,
            positionMs = positionMs,
            durationMs = durationMs
        ) ?: evaluateSegment(
            type = ActiveSkipType.OUTRO,
            label = "Skip Outro",
            range = intervals.outro,
            positionMs = positionMs,
            durationMs = durationMs
        )

        val newType = candidatePrompt?.type
        if (newType != lastActiveType) {
            if (candidatePrompt != null) {
                StreamingDiagnostics.logEvent(
                    "skip_button_visible type=${candidatePrompt.type} label=\"${candidatePrompt.label}\" " +
                        "pos_ms=$positionMs start_ms=${candidatePrompt.startTimeMs} target_ms=${candidatePrompt.targetTimeMs} " +
                        "duration_ms=$durationMs"
                )
            } else if (lastActiveType != null) {
                StreamingDiagnostics.logEvent(
                    "skip_button_hidden previous_type=$lastActiveType pos_ms=$positionMs duration_ms=$durationMs"
                )
            }
            lastActiveType = newType
        }

        return candidatePrompt
    }

    private fun evaluateSegment(
        type: ActiveSkipType,
        label: String,
        range: TimeRange?,
        positionMs: Long,
        durationMs: Long
    ): ActiveSkipPrompt? {
        if (range == null || !isValidRange(range)) return null
        if (type in dismissedSkipTypes) return null

        val startMs = range.startTimeMs
        val rawEndMs = range.endTimeMs

        // Ensure segment timestamps are within episode duration when duration is known (> 0)
        if (durationMs > 0L && startMs >= durationMs - 500L) {
            return null
        }

        val effectiveEndMs = if (durationMs > 0L && rawEndMs > durationMs) {
            durationMs
        } else {
            rawEndMs
        }

        if (effectiveEndMs <= startMs + 1_000L) {
            return null
        }

        val activeWindowEndMs = (effectiveEndMs - END_MARGIN_MS).coerceAtLeast(startMs + 500L)
        if (positionMs in startMs until activeWindowEndMs) {
            return ActiveSkipPrompt(
                type = type,
                label = label,
                targetTimeMs = effectiveEndMs,
                startTimeMs = startMs
            )
        }

        return null
    }

    /**
     * Called when the user manually seeks backward so any segment that starts after the new position
     * can be skipped again if desired.
     */
    fun onManualUserSeek(newPositionMs: Long) {
        intervals.recap?.let {
            if (newPositionMs < it.startTimeMs - 1_500L) {
                dismissedSkipTypes.remove(ActiveSkipType.RECAP)
            }
        }
        intervals.intro?.let {
            if (newPositionMs < it.startTimeMs - 1_500L) {
                dismissedSkipTypes.remove(ActiveSkipType.INTRO)
            }
        }
        intervals.outro?.let {
            if (newPositionMs < it.startTimeMs - 1_500L) {
                dismissedSkipTypes.remove(ActiveSkipType.OUTRO)
            }
        }
    }

    fun markSkipped(prompt: ActiveSkipPrompt) {
        dismissedSkipTypes.add(prompt.type)
        if (lastActiveType == prompt.type) {
            lastActiveType = null
        }
        StreamingDiagnostics.logEvent(
            "skip_segment_activated type=${prompt.type} start_ms=${prompt.startTimeMs} target_ms=${prompt.targetTimeMs}"
        )
    }

    private fun isValidRange(range: TimeRange): Boolean {
        if (range.startTimeSec.isNaN() || range.endTimeSec.isNaN()) return false
        val durationSec = range.endTimeSec - range.startTimeSec
        return range.startTimeSec >= 0f &&
            range.endTimeSec > range.startTimeSec &&
            durationSec in MIN_SEGMENT_DURATION_SEC..MAX_SEGMENT_DURATION_SEC
    }
}
