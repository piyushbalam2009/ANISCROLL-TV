package `in`.aniscroll.tv.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "anime_cache")
data class AnimeEntity(
    @PrimaryKey
    @ColumnInfo(name = "hoshika_id")
    val hoshikaId: Long,
    @ColumnInfo(name = "anilist_id")
    val anilistId: Long? = null,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "english_title")
    val englishTitle: String? = null,
    @ColumnInfo(name = "japanese_title")
    val japaneseTitle: String? = null,
    @ColumnInfo(name = "description")
    val description: String? = null,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "banner_url")
    val bannerUrl: String? = null,
    @ColumnInfo(name = "logo_url")
    val logoUrl: String? = null,
    @ColumnInfo(name = "release_year")
    val releaseYear: Int? = null,
    @ColumnInfo(name = "season")
    val season: String? = null,
    @ColumnInfo(name = "status")
    val status: String? = null,
    @ColumnInfo(name = "format")
    val format: String? = null,
    @ColumnInfo(name = "total_episodes")
    val totalEpisodes: Int? = null,
    @ColumnInfo(name = "genres_csv")
    val genresCsv: String = "",
    @ColumnInfo(name = "tmdb_id")
    val tmdbId: Long? = null,
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)

@Entity(tableName = "anime_id_mappings")
data class AnimeIdMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "hoshika_id")
    val hoshikaId: Long,
    @ColumnInfo(name = "anilist_id")
    val anilistId: Long,
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["hoshika_id", "episode_number"]
)
data class WatchHistoryEntity(
    @ColumnInfo(name = "hoshika_id")
    val hoshikaId: Long,
    @ColumnInfo(name = "anilist_id")
    val anilistId: Long,
    @ColumnInfo(name = "episode_number")
    val episodeNumber: Int,
    @ColumnInfo(name = "language")
    val language: String,
    @ColumnInfo(name = "position")
    val position: Long,
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "completed")
    val completed: Boolean,
    @ColumnInfo(name = "last_watched")
    val lastWatched: Long,
    @ColumnInfo(name = "anime_title")
    val animeTitle: String = "",
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "banner_url")
    val bannerUrl: String? = null,
    @ColumnInfo(name = "logo_url")
    val logoUrl: String? = null
)

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey
    @ColumnInfo(name = "hoshika_id")
    val hoshikaId: Long,
    @ColumnInfo(name = "anime_title")
    val animeTitle: String,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "banner_url")
    val bannerUrl: String? = null,
    @ColumnInfo(name = "logo_url")
    val logoUrl: String? = null,
    @ColumnInfo(name = "release_year")
    val releaseYear: Int? = null,
    @ColumnInfo(name = "status")
    val status: String? = null,
    @ColumnInfo(name = "added_at")
    val addedAt: Long
)

@Entity(tableName = "logo_cache")
data class LogoCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "hoshika_id")
    val hoshikaId: Long,
    @ColumnInfo(name = "tmdb_id")
    val tmdbId: Long? = null,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "logo_url")
    val logoUrl: String? = null,
    @ColumnInfo(name = "language")
    val language: String? = null,
    @ColumnInfo(name = "width")
    val width: Int = 0,
    @ColumnInfo(name = "height")
    val height: Int = 0,
    @ColumnInfo(name = "validation_status")
    val validationStatus: String = "VALID",
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
