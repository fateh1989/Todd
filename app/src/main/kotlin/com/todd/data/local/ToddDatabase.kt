package com.todd.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.todd.core.model.*

@Database(
    entities = [
        Project::class,
        Task::class,
        ScheduledTask::class,
        MemoryEntry::class,
        FailureRecord::class,
        Rule::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ToddDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun failureDao(): FailureDao
    abstract fun ruleDao(): RuleDao

    companion object {
        @Volatile
        private var INSTANCE: ToddDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        projectId TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        firstRunAt INTEGER NOT NULL,
                        intervalMinutes INTEGER,
                        enabled INTEGER NOT NULL,
                        lastRunAt INTEGER,
                        nextRunAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): ToddDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ToddDatabase::class.java,
                    "todd_database"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
