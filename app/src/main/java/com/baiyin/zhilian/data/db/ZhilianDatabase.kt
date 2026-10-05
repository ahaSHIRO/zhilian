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
    version = 3,
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

        /**
         * v2 → v3：题库对账模型（ADR-0017）。
         * - `questions.batch_id`：题目归属批次，撤销导入的定位键；按 `batch_order` 关联
         *   已处理记录回填（首版 batchOrder 唯一，够用）。
         * - `processed_batches.content_hash`：内容指纹；旧行留空串 = 「待对账」，
         *   故升级后首次前台会全量重算一次（幂等，且顺带补执行此前因导入顺序丢失的停用）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN batch_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE processed_batches ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE questions SET batch_id = COALESCE((" +
                        "SELECT pb.batch_id FROM processed_batches pb " +
                        "WHERE pb.batch_order = questions.batch_order), '')"
                )
            }
        }

        fun get(context: Context): ZhilianDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ZhilianDatabase::class.java,
                    "zhilian.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}

