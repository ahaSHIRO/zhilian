package com.baiyin.zhilian.data.batch

import androidx.room.withTransaction
import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.StemOwnerRow
import com.baiyin.zhilian.data.db.ZhilianDatabase

/**
 * 批次导入的数据存取面（ADR-0013）。
 *
 * 把 [BatchImportService.importFromText] 依赖的 DAO 方法收成窄接口：生产接 Room
 * （[RoomImportStore]），测试接内存替身。事务边界也收在此。
 *
 * [findExistingIds] / [findStemOwners] 刻意返回**原始类型**（`List<String>` / `List<StemOwnerRow>`）：
 * 组装（`toSet` / `associate`）留给 [buildImportContext] 纯函数——若接口直接返回 `Set` / `Map`，
 * 组装逻辑就藏进实现、无法纯测。
 */
internal interface ImportStore {

    /** 事务边界：生产用 Room 事务，测试直接执行（不模拟事务语义） */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    /** 已存在的题目 ID（去重与组装留给 [buildImportContext]） */
    suspend fun findExistingIds(ids: List<String>): List<String>

    /** 与候选题干完全相同的既有题（疑似重复判定材料） */
    suspend fun findStemOwners(stems: List<String>): List<StemOwnerRow>

    /** 批次顺序号是否已被占用 */
    suspend fun isOrderTaken(batchOrder: Int): Boolean

    /** 落库新题 */
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    /** 停用既有题 */
    suspend fun markInactive(ids: List<String>)

    /** 持久化待决疑似重复 */
    suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>)

    /** 写入已处理批次记录 */
    suspend fun upsertBatch(record: ProcessedBatchEntity)
}

/** [ImportStore] 的 Room 实现（生产用） */
internal class RoomImportStore(private val db: ZhilianDatabase) : ImportStore {
    private val questionDao = db.questionDao()
    private val batchDao = db.processedBatchDao()
    private val duplicateDao = db.pendingDuplicateDao()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun findExistingIds(ids: List<String>): List<String> = questionDao.existingIds(ids)

    override suspend fun findStemOwners(stems: List<String>): List<StemOwnerRow> =
        questionDao.findStemOwners(stems)

    override suspend fun isOrderTaken(batchOrder: Int): Boolean = batchDao.isOrderTaken(batchOrder)

    override suspend fun insertQuestions(questions: List<QuestionEntity>) = questionDao.insertAll(questions)

    override suspend fun markInactive(ids: List<String>) = questionDao.markInactive(ids)

    override suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>) =
        duplicateDao.insertAll(items)

    override suspend fun upsertBatch(record: ProcessedBatchEntity) = batchDao.upsert(record)
}
