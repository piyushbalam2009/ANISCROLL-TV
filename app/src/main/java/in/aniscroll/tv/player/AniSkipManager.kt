package `in`.aniscroll.tv.player

import `in`.aniscroll.tv.domain.model.SkipIntervals
import `in`.aniscroll.tv.domain.model.TimeRange

enum class ActiveSkipType {
    INTRO,
    OUTRO
}

data class ActiveSkipPrompt(
    val type: ActiveSkipType,
    val label: String,
    val targetTimeMs: Long
)

/**
 * Manages AniSkip v2 Intro and Outro skip button visibility and seek targets (Section 19).
 *
 * Rules:
 * - Shows "Skip Intro >>" during valid intro interval.
 * - Shows "Skip Outro >>" during valid outro interval.
 * - Never shows both simultaneously.
 * - Disappears immediately after skipping or when interval ends.
 * - Respects manual user seeking.
 */
class AniSkipManager {

    private var intervals: SkipIntervals = SkipIntervals()
    private val dismissedSkipKeys = mutableSetOf<String>()

    fun setIntervals(newIntervals: SkipIntervals) {
        intervals = newIntervals
        dismissedSkipKeys.clear()
    }

    fun evaluateCurrentPosition(positionMs: Long): ActiveSkipPrompt? {
        val currentSec = positionMs / 1000f

        val intro = intervals.intro
        if (intro != null && isValidRange(intro)) {
            val key = "intro_${intro.startTimeSec.toInt()}"
            if (currentSec in intro.startTimeSec..(intro.endTimeSec - 0.5f)) {
                if (key !in dismissedSkipKeys) {
                    return ActiveSkipPrompt(
                        type = ActiveSkipType.INTRO,
                        label = "Skip Intro  >>",
                        targetTimeMs = (intro.endTimeSec * 1000L).toLong()
                    )
                }
            } else if (currentSec < intro.startTimeSec - 2f) {
                // User manually seeked before the intro: allow skip button again
                dismissedSkipKeys.remove(key)
            }
        }

        val outro = intervals.outro
        if (outro != null && isValidRange(outro)) {
            val key = "outro_${outro.startTimeSec.toInt()}"
            if (currentSec in outro.startTimeSec..(outro.endTimeSec - 0.5f)) {
                if (key !in dismissedSkipKeys) {
                    return ActiveSkipPrompt(
                        type = ActiveSkipType.OUTRO,
                        label = "Skip Outro  >>",
                        targetTimeMs = (outro.endTimeSec * 1000L).toLong()
                    )
                }
            } else if (currentSec < outro.startTimeSec - 2f) {
                dismissedSkipKeys.remove(key)
            }
        }

        return null
    }

    fun markSkipped(prompt: ActiveSkipPrompt) {
        when (prompt.type) {
            ActiveSkipType.INTRO -> intervals.intro?.let {
                dismissedSkipKeys.add("intro_${it.startTimeSec.toInt()}")
            }
            ActiveSkipType.OUTRO -> intervals.outro?.let {
                dismissedSkipKeys.add("outro_${it.startTimeSec.toInt()}")
            }
        }
    }

    private fun isValidRange(range: TimeRange): Boolean {
        return range.startTimeSec >= 0f &&
            range.endTimeSec > range.startTimeSec &&
            (range.endTimeSec - range.startTimeSec) in 5f..300f
    }
}
