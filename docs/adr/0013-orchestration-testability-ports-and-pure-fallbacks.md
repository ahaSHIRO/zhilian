# 编排层可测化：窄接口收窄 I/O 依赖 + 纯函数下沉，不开 Android 测试环境

`submitAnswer`（ADR-0004）与 `importFromUri` 的事务编排此前**零测试**：判分 / 转移 / 规划的纯函数测了，「事务里怎么调用这些纯函数」（判首答、读库内当前值、`ImportContext` 组装、`plan → ImportOutcome` 映射）没测。ADR-0004 当年修的「读陈旧实体快照」bug 正是编排层的**取值决策错**，却无回归测试守住它。

对此本决策为：**编排层可测化走「窄接口 + 纯函数下沉」，不开 Android 测试环境**：

1. **窄接口收窄 I/O 依赖**：`PracticeStore`（`submitAnswer` 的数据存取面）与 `ImportStore`（`importFromUri` 的数据存取面）各收成一个窄接口，生产接 Room DAO（`RoomPracticeStore` / `RoomImportStore`），测试接内存替身（有限可变状态：写入能读，**不模拟事务原子性与并发**）。
2. **下沉编排里的可纯化段**为纯函数：`currentMasteryOf(loaded, question)`（「库内当前值 vs 传入快照」的取值决策）、`buildImportContext(existingIds, stemOwners, orderTaken)`（`ImportContext` 组装：去重 / 映射）、`toOutcome(plan, batchId, batchOrder, fileName)`（`ImportPlan → ImportOutcome.Completed` 映射）。
3. **拆 `importFromUri` → `importFromText`**：SAF 文件读取（`context.contentResolver.openInputStream`）留薄壳，编排收进 `importFromText(text, fileName)`，测试只测后者。
4. **不开 Android 测试环境**（无 Robolectric / room-testing / androidTest）：真实 Room 事务与 SQL 走端到端验证（MuMu，判据见 ADR-0012 的端到端段）。

## 理由

- **与项目既有 seam 同构**：`PracticeSessionLoader`（注入 `loadQuestions`）、`PracticeNavigation`（注入 `currentRoute` / `navigateTo`）、`BatchDirectoryScan`（注入 `listFiles` / `parse`）都是「收窄依赖 + 内存实现」的先例，本条把同一手法推到 `submitAnswer` / `importFromUri` 的 DAO 依赖。
- **编排 bug 是取值决策错，内存替身足以复现**：「读陈旧快照」这类 bug 的本质是「用了传入快照而非库内当前值」，内存替身能构造「库内值 ≠ 传入快照」并断言取值，不必真 Room。
- **不开 Android 测试环境是刻意取向**：项目 21 个测试全 JVM 纯测试、依赖面只剩 junit，为两个 adapter 引 Robolectric / room-testing 不成比例；且编排的**事务原子性**（多 DAO 同事务）本来就靠端到端守住，替身模拟不了也不该假装能。

## 代价与边界

- **真实 Room 事务 / SQL 无自动化覆盖**：`inTransaction` 在测试里直接执行（无事务语义），多 DAO 原子性靠端到端（MuMu）。**挂起重议条件**：若编排 bug 频发、DB 层改动频繁、或 DAO 方法持续膨胀导致接口失控，重审是否引入 Robolectric + room-testing。
- **`resolveDuplicate` 与 observe 方法暂走 db**：`ImportStore` 只覆盖 `importFromUri` 的数据存取面，`resolveDuplicate`（状态规则已在 ADR-0012 收口）与 `observePendingDuplicates` / `observeProcessedBatches` 暂直接用 `ZhilianDatabase`——渐进重构，后续可纳入统一 store。
- **窄接口暴露 Room 实体**（`AnswerRecordEntity` / `QuestionEntity` / `PendingDuplicateEntity` / `ProcessedBatchEntity`）：纯函数已产出它们，测试替身构造成本低，不额外抽象「值对象 ↔ 实体」转换层。
- **`findExistingIds` / `findStemOwners` 返回原始类型**（`List<String>` / `List<StemOwnerRow>`）：组装（`toSet` / `associate`）留给 `buildImportContext` 纯函数——若接口直接返回 `Set` / `Map`，组装逻辑就藏进实现、无法纯测。
- **同一手法已扩展到题库对账（2026-10-05 补充）**：`ReconcileStore`（生产 `RoomReconcileStore`）是 [ADR-0017](./0017-directory-authoritative-reconciliation.md) 的对账编排 `BatchReconcileService` 的数据存取面，形状与 `ImportStore` 一致；SAF 读取仍留壳（`BatchSaf`），测试接内存替身（`BatchReconcileServiceTest`）。

## 关键签名

```kotlin
// PracticeStore（submitAnswer 的数据存取面）
internal interface PracticeStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun countByQuestion(questionId: String): Int
    suspend fun getMastery(questionId: String): Mastery?
    suspend fun insertRecord(record: AnswerRecordEntity)
    suspend fun updateMastery(questionId: String, mastery: Mastery)
}

// ImportStore（importFromUri 的数据存取面）
internal interface ImportStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun findExistingIds(ids: List<String>): List<String>
    suspend fun findStemOwners(stems: List<String>): List<StemOwnerRow>
    suspend fun isOrderTaken(batchOrder: Int): Boolean
    suspend fun insertQuestions(questions: List<QuestionEntity>)
    suspend fun markInactive(ids: List<String>)
    suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>)
    suspend fun upsertBatch(record: ProcessedBatchEntity)
}

// 下沉的纯函数
internal fun currentMasteryOf(loaded: Mastery?, question: QuestionEntity): Mastery
internal fun buildImportContext(
    existingIds: List<String>,
    stemOwners: List<StemOwnerRow>,
    orderTaken: Boolean,
): ImportContext
internal fun toOutcome(
    plan: ImportPlan,
    batchId: String,
    batchOrder: Int,
    fileName: String,
): ImportOutcome.Completed
```
