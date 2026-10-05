# 目录为权威源：题库对账，电脑端改题手机自动生效

> **修订（2026-10-05，实现后复查）**：本文「两阶段写入」原写作“统一执行**全部变化批次**的 `retiredQuestionIds`”——这条口径把停用写成了「只在批次变化时执行一次的动作」。实际语义应是**目录级不变量的每轮重放**（含指纹未变的批次），否则「撤销批次导入后重导」会让另一批声明停用的题永久回到可用态，与本文开篇的 0018 事故同形。规则条中已改写，坑见 pitfalls 2.21 / 2.22。

> **修订（2026-10-05，ADR-0018）**：本文「撤销批次导入」的语义（`undoBatch` 整批删题+作答+待决+处理记录，文件不删）不变；[ADR-0018](./0018-batch-manage-ui-merge-orphan-and-undo-following-removal.md) 新增其 UI 层前置约束——撤销仅对**文件已不在目录的批次**开放（文件还在时撤销会被下次 `ON_RESUME` 自动对账重导抵消，属矛盾操作）。这是从本文「目录为权威源」理念直接推出的可用范围限定，非撤销语义变更。

**电脑端才是题库的定稿点，手机端应自动对账生效、不再手动点导入。** 但旧模型下电脑端改批次后手机拿不到更新，三道闸门各自独立、任一处都拦住：

1. **按 `batchId` 判「已处理」**：`ProcessedBatchEntity` 只要 `batchId` 命中且 `status=IMPORTED` 就不给导入按钮；
2. **按 `questionId` 判「已存在则跳过」**：`plan()` 对已入库 ID 一律跳过并报告；
3. **`batchOrder` 冲突整批拒绝**：顺序号被占即整批不入。

且 `questions` 只追加（不原位改内容）、`processed_batches` 无内容指纹，于是「改后进不来」没有任何自动通道。另有一次真实事故：用户直接点「全部导入」使 `batch-0018` 先于 `batch-0011` 入库，而 0018 的 `retiredQuestionIds` 指向 0011 的新题——导入 0018 时该题尚未存在，停用被记作「不在库」提示后**静默丢失**；随后 0011 入库又把它建成可用题，题库因此多出一道本应停用的题。

对此本决策为：**把 Syncthing 批次目录作为本机题库的权威源，用「题库对账」取代「逐题导入」**。规则如下：

- **对账模型（幂等）**：扫描目录 → 按 `questionId` 增量核对——**新增**目录中尚未入库的题；对已入库的**同 ID 题原位覆盖内容**（除 `questionId` 外，文件可表达的全部列都覆盖，含分类/标签/`batchOrder`/批内顺序）；**只增与更新，不自动删**（文件被移走/删除不动库里已有题）。
- **本地状态不被覆盖**：`inactive`、`consecutivePerfect`、`hasEverWrong`、`favorite`、`importedAt` 一律保留——作答历史与掌握度**跟 `questionId` 走**，内容更新不清零。
- **触发**：App 每次回到前台自动跑一次（`LifecycleEventEffect(ON_RESUME)`），保留手动「立即对账」按钮兜底；**不做 WorkManager 后台**。
- **内容指纹**：`processed_batches.content_hash` 存上次对账指纹；指纹一致即「已对账」，**只免内容写入**，不免该批的停用声明（见下条）。不一致才处理。指纹只含 `batchOrder`/`subject`/逐题内容/`retiredQuestionIds`，**排除 `createdAt`/`formatVersion`**。
- **两阶段写入（要害）**：单事务内**先写所有批次的新增与更新，再统一执行停用**。停用集合取**目录内全部有效批次**（含本轮指纹未变者；被拒批次不算）的 `retiredQuestionIds` 并集，且只重放「本轮写入后处于可用态」的目标——已停用的不重复计入，所以 `markInactive` 是幂等空转，`retired` 计数等于真正新停用的题数（否则每次开 App 都会留一条虚高摘要）。停用放最后且取目录级，使批次入库顺序与「某批本轮是否被跳过」都不再影响结果——上述 0018 事故与撤销重导两条通道因此均消失。
- **坏批次进「待处理」**：Schema/解析失败、批内 ID 重复、顺序号与其他批次冲突 → 整批不应用、给原因、下次前台自动重试；其余批次照常。
- **疑似重复仍人工裁决**：新题题干与库内某题相同时照旧进 `pending_duplicates`，不自动放过。
- **手机端唯一保留的写操作是「撤销批次导入」**：整批删除该批题目 + 那些题的作答记录 + 该批待决项 + 该批已处理记录；撤销后文件仍在目录，可被下次对账正常重导。**重导时若该批的题目被别的批停用，停用靠目录级重放补回**（见上条）——旧实现只重放变化批次，会使重导的题停在可用态。
- **测试替身得与它替代的那条 SQL 逐列对齐**：`FakeReconcileStore.updateQuestions` 曾直接整行替换（等同误用 `@Update`），把「本地状态保留」这条核心声明在编排测试里建模成了反义；现按 `updateContent` 同口径保留五列，并由 `同 ID 更新不清本地状态` 断言守住（pitfalls 2.22）。
- **摘要留痕**：有变更时把一次对账摘要（变更批数/新增/更新/停用/待处理）持久化到本机设置，在「批次导入」页留一条；平时不弹窗。

## 理由

- **电脑端才是定稿点**：出题代理与维护者共用出题交付目录，手机端不该是「另一个需要人工确认的关口」。把目录当权威源，手机端只需忠实反映它。
- **「只增不删」是安全底线**：自动删除会因一次误移文件就丢题与历史；删除改为显式「撤销批次导入」，代价可控、范围明确。
- **两阶段是唯一能根治顺序依赖的做法**：只要停用与写题在同一轮且停用在后，跨批次停用就与文件出现顺序无关——不需要「先导哪一批」这类人工口径。
- **内容指纹让自动对账不空转**：每次前台都扫目录，若无指纹则每批都要逐题比对；指纹一致直接跳过，26 批的开销退化为读文件 + 哈希。
- **本地状态保留**：用户的学习历史属于手机，不属于电脑端文件；`questionId` 是身份锚点，内容刷新不改身份。

## 代价与边界

- **`questions` 增加 `batch_id`**：撤销的定位键。不用 `batchOrder`——对账更新会按文件覆盖它，无法稳定标识来源。v2→v3 迁移按 `batch_order` 关联 `processed_batches` 回填。
- **升级首启会全量对账一次**：迁移把旧行的 `content_hash` 留空 = 「待对账」，故首次前台会重算并执行一遍全部停用——这**顺带补上此前丢失的停用**（如 `0011` 第 2 题），无需清库重导。
- **停用不可逆仍然成立**：对账只「把题置停用」，不提供「取消停用」；某批把某题移出 `retiredQuestionIds` 也不会把它改回可用。
- **更新不做疑似重复判定**：同 ID 更新的身份是稳定的，不查重复；若某题的新题干恰好等于另一题题干，会形成同题干两题——属已知边界，交由验收/复审在电脑端把关。
- **保留的手动兜底**：`BatchImportService.importFromText`（旧「逐题加入」）仍保留，但只对**尚无记录的批次**开放（新批次兜底）；已对账/待对账的统一走自动对账，避免「点了导入却不更新」的误导。
- **ADR-0008 的「入库始终是显式用户动作」被本决策取代**：入库现为前台自动对账，手动按钮降为兜底。ADR-0008 的「发现自动、不做后台静默入库」仍是边界——**App 未打开时不会入库**。

## 关键签名

```kotlin
// 纯模块：目录级规划（指纹跳过 / 同 ID 更新 / 两阶段停用 / 顺序号冲突 / 疑似重复）
object BatchReconcilePlanner {
    fun plan(
        candidates: List<BatchCandidate>,   // 已解析成功（含指纹）
        context: ReconcileContext,          // existing / storedFingerprints / ordersInUse
        now: Long,
        parseBlocked: List<BlockedBatch> = emptyList(),
    ): ReconcilePlan
}

// 内容指纹（排除 createdAt / formatVersion / batchId）
object BatchFingerprint { fun of(batch: BatchFileDto): String }

// 编排：单事务内 先写题 → 后统一停用；撤销批次导入
class BatchReconcileService internal constructor(
    private val store: ReconcileStore,
    private val schemaValidator: BatchSchemaValidator,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(sources: List<BatchSource>): ReconcileDigest
    suspend fun undoBatch(batchId: String)
}
```