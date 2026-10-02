# 批次裁决状态判定收口同源纯函数，adapter 不得内联

批次导入的「批次是否完成（IMPORTED / PARTIAL）」原先有两处判定：`ImportPlan.status` 计算属性（`failedCount == 0 && duplicates.isEmpty()`，导入规划期）与 `BatchImportService.resolveDuplicate` 事务体内联（`remaining == 0 && record.failedCount == 0`，疑似重复裁决后）。两处规则同义却各自书写，且待决的判据微差——一处看导入规划产出的待决列表，一处看裁决后的剩余计数。改判定阈值要同步改两处，任一处漂移即产生「一处说完成、另一处说未完成」的分裂态。

对此本决策为：**状态判定收口为单一纯函数**，两处共用同源：

1. `BatchImportPlanner.batchStatus(failedCount: Int, hasPending: Boolean): String`——唯一的完成态判定；`ImportPlan.status` 与 `resolveOutcome` 都调它。
2. `BatchImportPlanner.resolveOutcome(failedCount, importedCount, import, remaining): ResolutionOutcome`——裁决后的状态升级与导入计数增量；窄字段入参（四个原始值），不耦合 `ProcessedBatchEntity`。
3. `resolveDuplicate` 退回「事务 + I/O + 按 `ResolutionOutcome` 写」的薄适配器：早退、删待决项、import 动作（解码 / 查重 / 落库）与记录读写留在 adapter，规则一律不进事务体。

## 理由

- **落实 ADR-0004 精神**：作答提交（`submitAnswer`）已把「判分 → 掌握度转移 → 记录构造」收口为 `planSubmission` 纯函数，事务只留读写。`resolveDuplicate` 是同处一个 adapter 的同类事务却漏了这一步——`importFromUri` 已委托 `BatchImportPlanner.plan`，它却把规则写在事务体内。本条把该模式补齐到批次裁决，使两个事务的形状一致。
- **双写是 bug 温床**：状态判定散在两处，改阈值要改两处；且两处判据的语义微差（规划期待决列表 vs 裁决后剩余计数）不会编译报错，只会在「待决清零但状态未升级」这类边界上表现。
- **窄字段比实体更可测**：`resolveOutcome` 只读 `ProcessedBatchEntity` 的两个字段，收窄成四个原始值入参后，单测不必构造含 `issuesJson` / `processedAt` / `fileName` 等无关字段的整个实体——与 `masteryTransition(current: Mastery, outcome)` 接收值对象而非 `QuestionEntity` 同一取舍。
- **同源可断言**：`batchStatus` 成为「完成态判定」的唯一落点后，四象限（失败有无 × 待决有无）可被 JVM 单测直接钉死，与 `BatchImportPlannerTest` 既有的 `plan.status` 断言同处。

## 代价与边界

- **纯函数只守规则，不守事务**：`resolveDuplicate` 的多 DAO 事务（删待决 + 可选落库 + 读剩余 + 写批次记录）的原子性仍靠端到端验证（MuMu：构造同题干异 ID 题 → 导入产生待决 → 逐条裁决 → 查状态升级与计数一致、重启不丢），纯函数单测覆盖不了「事务中途失败」这类边界。
- **adapter 仍持有 I/O 判断**：`item == null` 早退、import 动作的 `stillExists` 查重属 I/O，刻意留在 adapter——纯化它们收益为零，反而会把查询塞进纯函数。
- **挂起重议条件**：若批次状态将来从「IMPORTED / PARTIAL 二态」演进为显式多态（如把 FAILED 提升为独立状态、或引入 PENDING 中间态），本条的 `batchStatus` 二态签名需重审。
- `ImportPlan.status` 保持计算属性、interface 不变，既有 `BatchImportPlannerTest` 的六处 `plan.status` 断言不受影响。
