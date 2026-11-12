package `in`.aniscroll.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnimeEntity::class,
        AnimeIdMappingEntity::class,
        WatchHistoryEntity::class,
        WatchlistEntity::class,
        LogoCacheEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AniScrollDatabase : RoomDatabase() {
    abstract fun dao(): AniScrollDao

    companion object {
        @Volatile
        private var INSTANCE: AniScrollDatabase? = null

        fun getInstance(context: Context): AniScrollDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AniScrollDatabase::class.java,
                    "aniscroll_tv.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
