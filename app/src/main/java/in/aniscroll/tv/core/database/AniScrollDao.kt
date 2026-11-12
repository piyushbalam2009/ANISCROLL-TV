package `in`.aniscroll.tv.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AniScrollDao {

    // --- Anime Cache ---
    @Query("SELECT * FROM anime_cache WHERE hoshika_id = :hoshikaId LIMIT 1")
    suspend fun getCachedAnime(hoshikaId: Long): AnimeEntity?

    @Query("SELECT * FROM anime_cache ORDER BY last_updated DESC LIMIT :limit")
    suspend fun getRecentCachedAnime(limit: Int = 40): List<AnimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnime(anime: AnimeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimeList(animeList: List<AnimeEntity>)

    @Query("SELECT COUNT(*) FROM anime_cache")
    fun observeCachedAnimeCount(): Flow<Int>

    // --- Anime ID Mappings (Hoshika -> AniList) ---
    @Query("SELECT * FROM anime_id_mappings WHERE hoshika_id = :hoshikaId LIMIT 1")
    suspend fun getMapping(hoshikaId: Long): AnimeIdMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: AnimeIdMappingEntity)

    @Query("SELECT COUNT(*) FROM anime_id_mappings")
    fun observeMappingCount(): Flow<Int>

    // --- Logo Cache ---
    @Query("SELECT * FROM logo_cache WHERE hoshika_id = :hoshikaId LIMIT 1")
    suspend fun getLogoCache(hoshikaId: Long): LogoCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogoCache(logoCache: LogoCacheEntity)

    @Query("DELETE FROM logo_cache")
    suspend fun clearLogoCache()

    // --- Watchlist (My List) ---
    @Query("SELECT * FROM watchlist ORDER BY added_at DESC")
    fun observeWatchlist(): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE hoshika_id = :hoshikaId)")
    fun observeIsInWatchlist(hoshikaId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE hoshika_id = :hoshikaId)")
    suspend fun isInWatchlist(hoshikaId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(entity: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE hoshika_id = :hoshikaId")
    suspend fun removeFromWatchlist(hoshikaId: Long)

    @Query("DELETE FROM watchlist")
    suspend fun clearWatchlist()

    // --- Watch History & Continue Watching ---
    @Query("SELECT * FROM watch_history WHERE completed = 0 AND position > 0 ORDER BY last_watched DESC")
    fun observeContinueWatching(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY last_watched DESC")
    fun observeAllWatchHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE hoshika_id = :hoshikaId ORDER BY episode_number ASC")
    fun observeWatchHistoryForAnime(hoshikaId: Long): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE hoshika_id = :hoshikaId AND episode_number = :episodeNumber LIMIT 1")
    suspend fun getEpisodeProgress(hoshikaId: Long, episodeNumber: Int): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE hoshika_id = :hoshikaId ORDER BY last_watched DESC LIMIT 1")
    suspend fun getLastWatchedForAnime(hoshikaId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchHistory(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE hoshika_id = :hoshikaId AND episode_number = :episodeNumber")
    suspend fun removeContinueWatchingItem(hoshikaId: Long, episodeNumber: Int)

    @Query("DELETE FROM watch_history WHERE hoshika_id = :hoshikaId")
    suspend fun removeAllHistoryForAnime(hoshikaId: Long)

    @Query("DELETE FROM watch_history")
    suspend fun clearWatchHistory()

    // --- Search History ---
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 15")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchQuery(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearchQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    // --- Full Local Reset ---
    @Query("DELETE FROM anime_cache")
    suspend fun clearAnimeCache()

    @Query("DELETE FROM anime_id_mappings")
    suspend fun clearIdMappings()
}
