# 题目批次格式规格 v1

电脑端 AI 出题与手机端 App 导入的共同契约。Schema 本体：[batch-v1.schema.json](./batch-v1.schema.json)（JSON Schema draft 2020-12，校验库 networknt json-schema-validator 2.x）。

## 文件形态与命名

- 一个批次 = 一个 JSON 文件，UTF-8（无 BOM）编码，单行或多行均可。
- 文件命名约定：`batch-{batchOrder 四位}.json`，如 `batch-0001.json`。仅为人工审核便利；**App 以文件内容中的 `batchId` 为准，不依赖文件名**。
- 审核通过后原样放入 Syncthing 批次目录，不再修改；需要修正时生成新批次（新 batchId），不覆盖已同步文件。

## 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| formatVersion | 常量 1 | ✓ | 格式版本 |
| batchId | UUID | ✓ | 批次唯一 ID，永不复用 |
| batchOrder | 整数 1–9999 | ✓ | 批次顺序号，决定顺序练习追加次序 |
| createdAt | date | ✓ | 创建日期 YYYY-MM-DD |
| subject | 枚举 | ✓ | 科目代码，小写；当前为 `kotlin`、`java`。扩科目须改 Schema |
| questions | 数组 1–200 | ✓ | 新增题目，数组顺序即批次文件内顺序 |
| retiredQuestionIds | UUID 数组 | ✗ | 本批次停用的既有题目 ID，默认空 |

## 题型与答案表示

| type | 必有字段 | 答案语义 |
|---|---|---|
| single_choice | options, answer | answer 为单个选项 ID（如 `"A"`） |
| multiple_choice | options, answer | answer 为选项 ID 数组，≥2 且互异；评分按集合比对 |
| true_false | answer | answer 为布尔；App 固定渲染「正确/错误」两选项，批次不含 options |
| fill_in_blank | acceptableAnswers | 可接受答案数组；用户答案**去除首尾空白后区分大小写精确比对** |

选项 `optionId` 为 A–F 单字母，`options` 数组顺序即展示顺序。多选评分沿用 README 公式 `max(0, 正确选中数 − 错误选中数) / 正确选项总数`。

## ID 与身份规范

- **batchId / questionId**：小写 UUID v4，全局唯一、永不复用、与内容无关联。题目修订 = 新 questionId（见 ADR-0001），旧题与其历史不变。
- **科目**：小写代码，Schema 枚举锁定。新科目必须改 Schema——这是有意的防错设计，防止出题端笔误在题库中制造野科目。
- **分类**：名字即身份。同一科目内，Unicode NFC 规范化 + trim 后的精确字符串相同即为同一分类。单层无层级；分类无独立 ID，**分类名一旦使用即不建议改名**（改名会形成新分类，旧题归属不变）。
- **标签**：身份规则同分类（NFC + trim 精确匹配、区分大小写），出题规约建议全小写；跨科目允许同名。

## Unicode 与大小写

- 所有身份比对（分类、标签）与填空匹配均在 Unicode **NFC 规范化**后进行；出题端应输出 NFC 形式。
- 除「标签建议小写」外无大小写折叠；填空匹配区分大小写（README 已定）。

## Markdown 方言

题干、选项、解析为 Markdown 纯文本（首版不含图片）：

- 允许：段落、粗体/斜体、行内代码、围栏代码块（建议标注语言，Kotlin 题用 `kotlin`，App 离线高亮）、有序/无序列表、引用。
- 禁止：图片（`![`，Schema 已拦截）、HTML 标签、表格（首版渲染器不支持，出题端勿用）。
- 代码块是题干的一部分，高亮由 App 端渲染，批次格式不做代码级拆分。

## 应用级校验清单（Schema 之外，App 逐题执行）

JSON Schema 无法表达的规则，App 导入时必须校验并给出中文原因：

1. `answer` 引用的每个 `optionId` 必须存在于该题 `options`。
2. `options` 内 `optionId` 互不重复。
3. 批次内 `questionId` 互不重复；与已导入题库比对，重复者跳过并报告（README 导入韧性）。
4. `retiredQuestionIds` 不得包含本批次 `questions` 中的任何 ID。
5. 分类名 NFC + trim 归一化后非空且合法（双保险，Schema pattern 之外）。
6. `batchOrder` 不得与已导入批次重复（否则顺序练习排序歧义）。
7. `formatVersion ≠ 1`：不导入整个批次，报告「格式版本不兼容，需升级 App 或更换批次」（README）。
8. 疑似重复判定：候选题 `stem` NFC + trim 归一化后与本机某题完全相同，且 questionId 不同 → 标记疑似重复，等待人工选择（README）。阈值即精确匹配，首版不做模糊相似度。

## 版本迁移策略

- `formatVersion` 当前为常量 `1`。
- **向后兼容演进**（新增可选字段、放宽约束）不升版本号：旧 App 读新批次仍按 v1 语义处理。
- **破坏性演进**（删必填字段、改字段语义、改枚举值）必须升为 `2` 并另立 Schema 文件与规格文档，本文档冻结为 v1 历史。
- App 端只实现 v1；遇到未知版本整批拒收并提示，不做静默猜测。

## 出题端（AI）生成约定

- 每题必须独立可校验：不依赖题外上下文、不出现「如上图」「以下代码」之外的悬空引用。
- 填空题题干用 `______`（六连下划线）标示空位，仅装饰性，App 不解析空位。
- 干扰项与正确项长度、风格尽量一致，避免泄漏。
- `retiredQuestionIds` 仅在确需停用旧题时提供，其余批次省略或空数组。
