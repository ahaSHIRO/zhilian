package com.baiyin.zhilian.data.batch

import androidx.room.withTransaction
import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase

/**
 * 题库对账的数据存取面（ADR-0013 的同一手法）：把 [BatchReconcileService] 依赖的
 * DAO 方法收成窄接口，生产接 Room（[RoomReconcileStore]），测试接内存替身。
 * 事务边界也收在此。
 */
internal interface ReconcileStore {

    suspend fun <T> inTransaction(block: suspend () -> T): T

    /** 库内全部题目（含停用），对账现状快照 */
    suspend fun allQuestions(): List<QuestionEntity>

    /** 全部已处理批次记录（读指纹与占用顺序号） */
    suspend fun allBatchRecords(): List<ProcessedBatchEntity>

    suspend fun insertQuestions(questions: List<QuestionEntity>)

    /** 原位覆盖内容列（保留本地状态列） */
    suspend fun updateQuestions(questions: List<QuestionEntity>)

    suspend fun markInactive(ids: List<String>)

    suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>)

    suspend fun upsertBatchRecords(records: List<ProcessedBatchEntity>)

    // ---- 撤销批次导入 ----

    suspend fun questionIdsByBatch(batchId: String): List<String>

    suspend fun deleteQuestionsByBatch(batchId: String)

    suspend fun deleteAnswerRecords(questionIds: List<String>)

    suspend fun deletePendingDuplicatesByBatch(batchId: String)

    suspend fun deleteBatchRecord(batchId: String)
}

/** [ReconcileStore] 的 Room 实现（生产用） */
internal class RoomReconcileStore(private val db: ZhilianDatabase) : ReconcileStore {
    private val questionDao = db.questionDao()
    private val batchDao = db.processedBatchDao()
    private val duplicateDao = db.pendingDuplicateDao()
    private val answerDao = db.answerRecordDao()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun allQuestions(): List<QuestionEntity> = questionDao.all()

    override suspend fun allBatchRecords(): List<ProcessedBatchEntity> = batchDao.all()

    override suspend fun insertQuestions(questions: List<QuestionEntity>) = questionDao.insertAll(questions)

    override suspend fun updateQuestions(questions: List<QuestionEntity>) {
        questions.forEach {
            questionDao.updateContent(
                questionId = it.questionId,
                type = it.type,
                subject = it.subject,
                category = it.category,
                tagsJson = it.tagsJson,
                stem = it.stem,
                optionsJson = it.optionsJson,
                answerJson = it.answerJson,
                explanation = it.explanation,
                sourceJson = it.sourceJson,
                batchOrder = it.batchOrder,
                orderInBatch = it.orderInBatch,
                batchId = it.batchId,
            )
        }
    }

    override suspend fun markInactive(ids: List<String>) {
        if (ids.isNotEmpty()) questionDao.markInactive(ids)
    }

    override suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>) =
        duplicateDao.insertAll(items)

    override suspend fun upsertBatchRecords(records: List<ProcessedBatchEntity>) {
        records.forEach { batchDao.upsert(it) }
    }

    override suspend fun questionIdsByBatch(batchId: String): List<String> = questionDao.idsByBatch(batchId)

    override suspend fun deleteQuestionsByBatch(batchId: String) = questionDao.deleteByBatch(batchId)

    override suspend fun deleteAnswerRecords(questionIds: List<String>) {
        if (questionIds.isNotEmpty()) answerDao.deleteByQuestionIds(questionIds)
    }

    override suspend fun deletePendingDuplicatesByBatch(batchId: String) = duplicateDao.deleteByBatch(batchId)

    override suspend fun deleteBatchRecord(batchId: String) = batchDao.deleteById(batchId)
}