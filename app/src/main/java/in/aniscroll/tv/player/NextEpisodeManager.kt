package `in`.aniscroll.tv.player

import `in`.aniscroll.tv.domain.model.Episode

/**
 * Resolves Previous and Next episodes strictly from Hoshika's actual episode list (Section 22).
 * Never assumes episode numbers are consecutive or fabricates unconfirmed episodes.
 */
class NextEpisodeManager {

    fun findNextEpisode(
        episodes: List<Episode>,
        currentEpisodeNumber: Int
    ): Episode? {
        if (episodes.isEmpty()) return null
        val sorted = episodes.sortedBy { it.number }
        val currentIndex = sorted.indexOfFirst { it.number == currentEpisodeNumber }
        if (currentIndex != -1 && currentIndex + 1 < sorted.size) {
            return sorted[currentIndex + 1]
        }
        return sorted.firstOrNull { it.number > currentEpisodeNumber }
    }

    fun findPreviousEpisode(
        episodes: List<Episode>,
        currentEpisodeNumber: Int
    ): Episode? {
        if (episodes.isEmpty()) return null
        val sorted = episodes.sortedBy { it.number }
        val currentIndex = sorted.indexOfFirst { it.number == currentEpisodeNumber }
        if (currentIndex > 0) {
            return sorted[currentIndex - 1]
        }
        return sorted.lastOrNull { it.number < currentEpisodeNumber }
    }
}
