package it.parrocchia.coro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        MassPlanEntity::class,
        MassPhaseEntity::class,
        PlannedTrackEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CoroDatabase : RoomDatabase() {
    abstract fun coroDao(): CoroDao

    companion object {
        @Volatile
        private var instance: CoroDatabase? = null

        fun getInstance(context: Context): CoroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoroDatabase::class.java,
                    "coro_parrocchiale.db"
                ).build().also { instance = it }
            }
    }
}
