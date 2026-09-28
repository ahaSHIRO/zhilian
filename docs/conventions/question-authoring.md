# 出题流程规范

本规范约束「知练」题库的题目创作与把关流程。批次格式契约见 [`docs/schema/batch-spec-v1.md`](../schema/batch-spec-v1.md) 与 [`batch-v1.schema.json`](../schema/batch-v1.schema.json)；把关角色划分的依据见 [ADR-0006](../adr/0006-question-review-and-acceptance.md)。

## 一、角色分工

| 角色 | 承担者 | 管什么 |
|---|---|---|
| 出题 | 代理（独立上下文） | 依据素材创作题目与解析 |
| **复审** | 代理（**冷启动，未参与出题**） | 事实性：答案对不对、有无歧义、解析能否自证 |
| **验收** | 维护者本人 | 体感与取舍：看得懂、深浅合适、不重复；一票否决 |

**关键：维护者不判断正确性。** 维护者正是本题库的学习者，让不会的人去审核会的人该判断的内容，等于没有质量门。正确性一律由复审承担。

**冷复审的硬要求**：复审代理拿到的是**剥去答案与解析的题目**，且**不给笔记原文**。它必须独立作答，再与出题答案比对。同一上下文自审会共享盲点——它只是"再想一遍"，不是对抗。

## 二、流程

```
选素材 → 抽考点清单 → 出题（代理） → JSON 预校验（PC 端脚本）
      → 冷复审（代理，独立作答 + 逐项挑刺）
      → 运行验证（含代码题，真实编译运行）
      → 裁决：通过 / 退回修改 / 否决
      → 验收（维护者体感） → 定稿放入 Syncthing 批次目录
```

### 1. 抽考点清单

出题前先从素材抽考点，逐条标注是否值得出题。清单是后续核对的依据。例：读《Flow 冷热流》抽出「冷流每次订阅重跑」「StateFlow 去重」「shareIn 启动策略」。

### 2. 出题

- 一题对应**一个**考点
- 优先考**反直觉行为、常见误解、易混概念**；不考死记 API 参数
- 题干含代码用 ```` ```kotlin ```` 围栏；**禁图片**（Schema 拦 `![`）、禁 HTML、禁表格
- 题目尽量原创改写，不大段复制来源文本
- `source` 必须可追溯（标题 + 访问日期 + 链接或笔记路径）

### 3. JSON 预校验（PC 端，必须做）

```powershell
python C:\Users\29335\.local\bin\zl-batch-check.py <批次文件路径>
```

App 对 Schema 不合格的批次是**整批拒绝**且只回报前 5 条错误——同步到手机才发现，就要走"回炉 → 重传 → 重导"一整圈。预校验脚本复用同一份 Schema，并额外检查 Schema 表达不了的应用级规则（见 §四）。

### 4. 冷复审

复审代理收到的输入**只有**：题目（题干 + 选项 + 题型），**不含** `answer`、`acceptableAnswers`、`explanation`、`source`，也**不含**笔记原文。它要做两件事：

1. **独立作答**：逐题给出自己的答案
2. **逐项挑刺**：按下表核验

产出**结构化裁决**（每题一组，便于自动流转）：

```
answer_correct       答案是否与事实一致
answer_unique        是否有且仅有一个正确项（多选无"少选也算对"的灰区）
options_well_formed  无"两个都对/都不对/明显凑数"的干扰项
explanation_sound    解析能自证，不循环论证、不与答案矛盾
source_valid         来源可追溯且与题目相关
verdict              PASS / REVISE(附具体怎么改) / REJECT
```

`REVISE` **必须附具体修改建议**，让出题方能直接返工。只有复审与出题方**各执一词**时才升级更强制裁（换模型 / 加运行验证 / 交维护者定夺）。

#### 复审必查的三类硬伤（首次实测踩出来的）

这三类靠"读一遍解析"发现不了，必须在复审时**主动查**：

1. **答案键与可观察行为是否一致**——尤其涉及 stdout 与 stderr 分流的题。实测案例：`supervisorScope` 里子协程失败，异常**不向父作用域传播**（`runBlocking` 正常返回、退出码 0），但**stderr 仍打印完整堆栈**。"异常不传播"与"控制台无输出"是两件事，若选项把二者混在一句里就会产生双解。**复审必须用 stdout/stderr 分离方式实测**（`java ... 2>$null` 与 `2>&1` 各跑一次，并记退出码）。
2. **干扰项里是否混入了不存在的术语**——用不存在的概念当错误选项，会让学生把它当成知识点背下来。实测案例：某选项用了 withContext 的"有粘性(sticky)"，而解压 `kotlinx-coroutines-core-jvm-<版本>-sources.jar` 全文检索 `sticky` **命中 0 行**；官方 KDoc 只有 "shift back to the original dispatcher"。**术语核查方法**：从 gradle 缓存取对应版本的 `-sources.jar` 解压后 grep 该术语，0 命中即不可用。
3. **题干是否写死了非契约值**——线程名、耗时、执行顺序稳定性。实测案例：题干写"三行依次是 `DefaultDispatcher-worker-1`…"，而 30 次重复实测中**出现过 1 次 `worker-2`**。线程名不是契约（取决于线程池状态与机器核心数）。凡引用此类值，必须加注"随机器/状态而变，不是契约"，或改用会暴露该事实的问法。**稳定性也要实测**：关键题建议重复运行 10–30 次统计是否 100% 一致。

### 5. 运行验证（含代码题的硬证据）

**凡题目含可独立编译运行的代码，一律实际编译运行核验答案**，不用"我认为会输出 X"。

本机已实测可用（零额外下载，单文件约 4 秒）：

```powershell
$CO = "C:\Tools\scoop\apps\gradle\current\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2\4a9f78ef49483748e2c129f3d124b8fa249dafbf\kotlinx-coroutines-core-jvm-1.10.2.jar"
kotlinc <题名>.kt -cp $CO -include-runtime -d <题名>.jar
java -cp "<题名>.jar;$CO" <题名>Kt      # 必须 -cp；-jar 会 NoClassDefFoundError
```

最省事方式（免建 jar，同为约 4 秒）：`kotlin -cp $CO <脚本>.kts`

**编译器版本锁定项目版本 2.4.20**。PATH 上的 `kotlinc` 是 2.3.10，与项目不一致；涉及版本语义的题需改用缓存中的 `kotlin-compiler-embeddable-2.4.20.jar`（走 preloader 直调编译器，绕过 bat）。

已知坑（实测）：

- **`kotlinc.bat` / `kotlin.bat` 遇多 jar 分号 classpath 会崩**（bat 剥引号后 cmd 把 `;` 当命令分隔符，后续 jar 被当源文件）。解法：①把 jar 合并成一个胖 jar（`jar xf` 后重新 `jar cf`）；②绕过 bat，直接
  `java -cp "<AS>\plugins\Kotlin\kotlinc\lib\kotlin-preloader.jar" org.jetbrains.kotlin.preloading.Preloader -cp "<AS>\...\kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler Demo.kt -cp "<依赖>" -d out`
- **pwsh 里 `;` 是语句分隔符**，`-cp $a;$b` 会语法报错 → 命令写进 `.ps1`/`.kts`
- **`Dispatchers.Main` 在纯 JVM 上不可用**（`RuntimeException: Stub!`，即使加 android.jar）。涉及 `Main`/`viewModelScope`/`lifecycleScope` 的题，纯 JVM 只能验证逻辑顺序（先用 `Dispatchers.setMain(Dispatchers.Default)`），验证不了真实主线程调度语义；要验真需走模拟器/真机的 instrumented test
- 中文路径无障碍（已实测），但临时文件建议放英文 temp 目录，且**不要污染项目目录**

### 6. 定稿与投放

验收通过后：

1. 放入 `<Syncthing 批次目录>\`，文件名 `batch-{四位顺序号}.json`
2. 等 Syncthing 同步到手机 `/sdcard/Download/zhilian`
3. App「设置 → 批次导入」导入

**注意**：此目录是双向同步目录——放进去的测试文件会被同步回 PC 端，**验证后必须两边都清理**。

## 三、单题质量标准

### 题型配比（建议）

单选约 45%、判断约 25%、多选约 20%、填空约 10%。单选与判断歧义少、易把关；多选与填空歧义多，控制比例。

### 解析是教材，不是答案复述

解析必须包含：

1. **为什么对**——正确答案的依据
2. **为什么错**——干扰项各自错在哪（不只是"选 A 对"）
3. **前置概念**——一句话铺垫该题依赖的知识
4. **常见误解**——点出容易想错的地方
5. **变体提示**——"把 X 换成 Y 会怎样"

这是「学习向」定位的落地方式，不改批次格式（见 ADR-0006 与 `README.md` 的「解析深度」）。

### 逐题自查

- [ ] 考点明确，且值得考（不是背 API 参数）
- [ ] 答案唯一无歧义（多选尤其：不能有"少选也对"的灰区）
- [ ] 干扰项长度风格一致，不泄漏答案，无"两个都对"
- [ ] 解析能自证，且覆盖上述五项
- [ ] 来源真实、访问日期正确、与题目相关
- [ ] 分类/标签用词与既有题库一致
- [ ] 含代码者，代码自包含且**已实际运行核验**

## 四、JSON 预校验清单

脚本 `zl-batch-check.py` 检查（前者由 `jsonschema` 按权威 Schema 校验，后者为 Schema 表达不了的）：

1. `answer` 引用的每个 `optionId` 必须存在于该题 `options`
2. `options` 内 `optionId` 互不重复
3. 批次内 `questionId` 互不重复
4. `retiredQuestionIds` 不得包含本批次 `questions` 中的任何 ID
5. 分类名 NFC+trim 后非空
6. `batchOrder` 不得与**已导入批次**重复（脚本会读 `<Syncthing 批次目录>\` 下现有批次核对）
7. `formatVersion` 必须为 1
8. 疑似重复：候选 `stem` NFC+trim 后与已有题目完全相同且 ID 不同 → 提示

## 五、分类体系

分类名**就是身份**：同一科目内 NFC+trim 精确相同即为同一分类；**改名等于新建分类**，旧题归属不变。所以分类**一次定死**。

单层结构，命名与既有风格一致（2–4 汉字名词）。当前分类：

| 分类 | 内容 |
|---|---|
| 基础语法 | val/var、运算符、基本声明 |
| 空安全 | 可空类型、`?.`、`?:`、`!!` |
| 集合 | List/Map、只读与可变、常用操作 |
| 函数 | 函数声明、Lambda、高阶函数 |
| 协程基础 | 挂起函数、协程构建器、结构化并发 |
| 协程调度 | Dispatchers、线程切换、主线程与 ANR |

后续按需追加（Flow、Channel、并发同步、协程测试调试）。**不要一次铺开只有少量题的空分类**——练习配置页的分类 chips 每行 3 个，分类多而空会让筛选区变长且无用。

## 六、硬约束速查（违反必被拒）

- 首版**只追加**：不原位覆盖、不物理删除。修订 = **新 questionId**，旧题走 `retiredQuestionIds` 停用，且**停用不可恢复**
- `subject` 枚举当前**仅 `kotlin`**；要出 Java/ArkTS 题必须先改 Schema 与 App 侧适配
- 每批 `questions` **1–200** 题；`batchOrder` **1–9999** 且不得与已导入批次重复
- 顶层 `additionalProperties: false`——**任何未定义字段都会导致整批校验失败**
- `batchId` / `questionId` 为小写 UUID（`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`）
- 文本首尾不得空白（`^\S(.*\S)?$`）
