package dev.pocketshare.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShareEntity::class, TrafficSampleEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PocketShareDatabase : RoomDatabase() {
    abstract fun shareDao(): ShareDao

    abstract fun trafficDao(): TrafficSampleDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS `traffic_samples` (" +
                            "`bucketStartEpochMs` INTEGER NOT NULL, " +
                            "`sentBytes` INTEGER NOT NULL, " +
                            "`receivedBytes` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`bucketStartEpochMs`))",
                    )
                }
            }
    }
}
