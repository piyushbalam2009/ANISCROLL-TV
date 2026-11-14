package `in`.aniscroll.tv.data.repository

enum class StreamingProvider(val sourceId: String) {
    MEGA_PLAY("megaplay"),
    ZOKO_ANIME("zokoanime");

    val slug: String
        get() = sourceId
}

/**
 * Centralized streaming source resolver enforcing ANISCROLL-TV's mandatory routing rules:
 * - Anime format == "MOVIE" -> "zokoanime" (GET /api/watch/zokoanime/{id}/{episode}/{type})
 * - All TV series and episodic formats ("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL") -> "megaplay"
 *   (GET /api/watch/megaplay/{id}/{episode}/{type})
 *
 * Kept strictly separate from the UI so the UI can never manually override or misroute a provider.
 */
object StreamingSourceResolver {

    val SERIES_FORMATS: Set<String> = setOf(
        "TV",
        "TV_SHORT",
        "OVA",
        "ONA",
        "SPECIAL"
    )

    /**
     * Resolves the mandatory streaming provider from the AniList `format` field.
     * - "MOVIE" -> [StreamingProvider.ZOKO_ANIME] ("zokoanime")
     * - "TV", "TV_SHORT", "OVA", "ONA", "SPECIAL", or any non-movie format -> [StreamingProvider.MEGA_PLAY] ("megaplay")
     */
    fun resolveSource(format: String?): StreamingProvider {
        val normalized = format?.trim()?.uppercase()
        return if (normalized == "MOVIE") {
            StreamingProvider.ZOKO_ANIME
        } else {
            StreamingProvider.MEGA_PLAY
        }
    }

    /**
     * Returns true strictly when the anime's AniList format is "MOVIE".
     */
    fun isMovie(format: String?): Boolean {
        return resolveSource(format) == StreamingProvider.ZOKO_ANIME
    }

    /**
     * Resolves the episode number parameter for the `/api/watch/{source}/{id}/{episode}/{type}` route.
     * Movies are handled as a single feature film (always episode 1).
     */
    fun resolveEpisodeNumber(format: String?, requestedEpisode: Int): Int {
        return if (isMovie(format)) {
            1
        } else {
            requestedEpisode.coerceAtLeast(1)
        }
    }
}
