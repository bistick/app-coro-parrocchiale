package it.parrocchia.coro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        MassPlanEntity::class,
        MassPhaseEntity::class,
        PlannedTrackEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CoroDatabase : RoomDatabase() {
    abstract fun coroDao(): CoroDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN defaultMixerConfigJson TEXT")
            }
        }

        @Volatile
        private var instance: CoroDatabase? = null

        fun getInstance(context: Context): CoroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoroDatabase::class.java,
                    "coro_parrocchiale.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
