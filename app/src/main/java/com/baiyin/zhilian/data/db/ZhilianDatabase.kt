package com.baiyin.zhilian.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QuestionEntity::class,
        AnswerRecordEntity::class,
        ProcessedBatchEntity::class,
        PendingDuplicateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ZhilianDatabase : RoomDatabase() {

    abstract fun questionDao(): QuestionDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun processedBatchDao(): ProcessedBatchDao
    abstract fun pendingDuplicateDao(): PendingDuplicateDao

    companion object {
        @Volatile
        private var instance: ZhilianDatabase? = null

        /**
         * v1 → v2：pending_duplicates 增加 subject 列。
         * 待决疑似重复项需要能脱离批次顶层字段独立恢复成题目，故随项保存科目。
         * 旧行（v1 时期只有 kotlin 科目）回填 'kotlin' 即可保持语义。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_duplicates ADD COLUMN subject TEXT NOT NULL DEFAULT 'kotlin'"
                )
            }
        }

        fun get(context: Context): ZhilianDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ZhilianDatabase::class.java,
                    "zhilian.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}

