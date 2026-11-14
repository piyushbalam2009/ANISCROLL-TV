package `in`.aniscroll.tv.domain.model

import kotlinx.coroutines.flow.Flow

data class Anime(
    val hoshikaId: Long,
    val anilistId: Long? = null,
    val malId: Long? = null,
    val tmdbId: Long? = null,
    val title: String,
    val englishTitle: String? = null,
    val japaneseTitle: String? = null,
    val description: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val logoUrl: String? = null,
    val releaseYear: Int? = null,
    val season: String? = null,
    val status: String? = null,
    val format: String? = null,
    val totalEpisodes: Int? = null,
    val currentEpisodes: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null
) {
    val preferredTitle: String
        get() = englishTitle?.takeIf { it.isNotBlank() } ?: title
}

data class Episode(
    val id: String,
    val hoshikaId: Long,
    val number: Int,
    val title: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val airDate: String? = null,
    val isFiller: Boolean = false
)

data class ScheduleItem(
    val id: String,
    val anime: Anime,
    val dayOfWeek: String,
    val episodeNumber: Int? = null,
    val airingTimestampMs: Long? = null,
    val formattedLocalTime: String? = null,
    val releaseStatus: String? = null
)

data class AnimeIdMapping(
    val hoshikaId: Long,
    val anilistId: Long,
    val lastUpdated: Long
)

data class LogoInfo(
    val hoshikaId: Long,
    val tmdbId: Long?,
    val provider: String, // "TMDB", "FANART", "NONE"
    val logoUrl: String?,
    val language: String?,
    val width: Int,
    val height: Int,
    val lastUpdated: Long,
    val isValid: Boolean
)

data class StreamQualitySource(
    val url: String,
    val quality: String, // "Auto", "1080p", "720p", "480p", "360p"
    val isM3u8: Boolean = true
)

data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String,
    val isDefault: Boolean = false
)

data class StreamData(
    val anilistId: Long,
    val episodeNumber: Int,
    val language: String, // "sub" or "dub"
    val sources: List<StreamQualitySource>,
    val subtitles: List<SubtitleTrack>,
    val headers: Map<String, String> = emptyMap(),
    val introStartSec: Float? = null,
    val introEndSec: Float? = null,
    val outroStartSec: Float? = null,
    val outroEndSec: Float? = null
)

data class TimeRange(
    val startTimeSec: Float,
    val endTimeSec: Float,
    val skipId: String = ""
)

data class SkipIntervals(
    val intro: TimeRange? = null,
    val outro: TimeRange? = null
)

enum class SubtitleSize(val label: String, val fontSizeSp: Float) {
    SMALL("Small", 16f),
    MEDIUM("Medium", 22f),
    LARGE("Large", 28f),
    EXTRA_LARGE("Extra Large", 34f)
}

enum class SubtitleColor(val label: String, val argb: Int) {
    WHITE("White", android.graphics.Color.WHITE),
    YELLOW("Yellow", android.graphics.Color.YELLOW),
    CYAN("Cyan", android.graphics.Color.CYAN),
    GREEN("Green", android.graphics.Color.rgb(34, 197, 94))
}

enum class SubtitleBackground(val label: String, val baseAlpha: Int) {
    TRANSPARENT("Transparent", 0),
    SEMI_TRANSPARENT("Semi-transparent black", 160),
    SOLID_BLACK("Solid black", 255)
}

enum class SubtitlePosition(val label: String, val bottomPaddingFraction: Float) {
    BOTTOM("Bottom", 0.06f),
    LOWER_MIDDLE("Lower-middle", 0.16f)
}

data class SubtitlePreferences(
    val enabled: Boolean = true,
    val preferredLanguage: String = "English",
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.SEMI_TRANSPARENT,
    val backgroundOpacityPercent: Int = 75,
    val outlineEnabled: Boolean = true,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM
)

data class AppSettings(
    val defaultQuality: String = "Auto",
    val preferredAudioLanguage: String = "sub", // "sub" or "dub"
    val autoNextEpisode: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val resumePlayback: Boolean = true,
    val subtitlePreferences: SubtitlePreferences = SubtitlePreferences()
)

data class WatchProgressItem(
    val hoshikaId: Long,
    val anilistId: Long,
    val animeTitle: String,
    val posterUrl: String?,
    val bannerUrl: String?,
    val logoUrl: String?,
    val episodeNumber: Int,
    val language: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val lastWatched: Long
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

enum class WatchOrderMode(val label: String, val subtitle: String) {
    RECOMMENDED(
        label = "Recommended",
        subtitle = "Optimal viewing sequence preserving story reveals and pacing"
    ),
    RELEASE(
        label = "Release Order",
        subtitle = "Ordered strictly by original broadcast and theatrical release dates"
    ),
    CHRONOLOGICAL(
        label = "Chronological",
        subtitle = "Ordered by in-universe narrative timeline from earliest events"
    )
}

enum class FranchiseRelationCategory {
    MAIN_SERIES,
    DIRECT_SEQUEL,
    DIRECT_PREQUEL,
    CANONICAL_MOVIE,
    OVA_SPECIAL,
    SIDE_STORY,
    SPIN_OFF,
    SUMMARY_RECAP,
    ALTERNATIVE
}

data class WatchOrderEntry(
    val position: Int,
    val anilistId: Long,
    val hoshikaId: Long,
    val malId: Long? = null,
    val title: String,
    val englishTitle: String? = null,
    val japaneseTitle: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val description: String? = null,
    val format: String = "TV",
    val releaseYear: Int? = null,
    val startDateNumeric: Int = 99999999,
    val episodes: Int? = null,
    val status: String? = null,
    val rawRelationType: String = "MAIN",
    val category: FranchiseRelationCategory = FranchiseRelationCategory.MAIN_SERIES,
    val relationshipBadge: String = "Main Series",
    val relationshipExplanation: String = "Core installment of the main storyline.",
    val isOptional: Boolean = false,
    val viewingTierBadge: String = "Essential",
    val isCurrentAnime: Boolean = false,
    val watchStatusLabel: String? = null
) {
    val preferredTitle: String
        get() = englishTitle?.takeIf { it.isNotBlank() } ?: title
}

data class FranchiseWatchOrder(
    val rootHoshikaId: Long,
    val rootAnilistId: Long,
    val franchiseTitle: String,
    val franchiseBannerUrl: String? = null,
    val franchiseCoverUrl: String? = null,
    val sourceMaterialNote: String? = null,
    val totalEntries: Int,
    val recommendedOrder: List<WatchOrderEntry>,
    val releaseOrder: List<WatchOrderEntry>,
    val chronologicalOrder: List<WatchOrderEntry>
) {
    fun entriesForMode(mode: WatchOrderMode): List<WatchOrderEntry> = when (mode) {
        WatchOrderMode.RECOMMENDED -> recommendedOrder
        WatchOrderMode.RELEASE -> releaseOrder
        WatchOrderMode.CHRONOLOGICAL -> chronologicalOrder
    }
}

