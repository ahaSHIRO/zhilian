# 出题流程规范

本规范约束「知练」题库的题目创作与把关流程。批次格式契约见 [`docs/schema/batch-spec-v1.md`](../schema/batch-spec-v1.md) 与 [`batch-v1.schema.json`](../schema/batch-v1.schema.json)；把关角色划分的依据见 [ADR-0006](../adr/0006-question-review-and-acceptance.md)。

> **冷启动指引（外部出题代理从这里开始）**：本仓库即你的工作环境。出题前按下序读完：
> 1. 本文全文——流程、单题质量标准、硬约束速查；
> 2. [`docs/schema/batch-spec-v1.md`](../schema/batch-spec-v1.md)（字段与格式契约）与 [`batch-v1.schema.json`](../schema/batch-v1.schema.json)（权威 Schema）；
> 3. [`CONTEXT.md`](../../CONTEXT.md)（领域词汇：科目/分类/标签/题目的身份语义）。
>
> 然后核对 §七 交接边界与 §八 标签词表。产出批次后必须自跑 §二.3 预校验并附通过输出，代码题自跑 §二.5 运行验证。你的角色边界在 §七，越界即返工。

## 一、角色分工

| 角色 | 承担者 | 管什么 |
|---|---|---|
| 出题 | 代理（独立上下文） | 依据素材创作题目与解析 |
| **复审** | 代理（**冷启动，未参与出题**；通常为出题代理派生的子代理，协议见 §七） | 事实性：答案对不对、有无歧义、解析能否自证 |
| **验收** | 维护者本人 | 体感与取舍：看得懂、深浅合适；同一考点可变式多考但考法不得雷同；一票否决 |

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

出题前先从素材抽考点，逐条标注两项后构成清单——清单是后续核对与变式安排的依据：

- **是否值得出题**：排除不值得的
- **重要级**：**核心考点**（基础点、被多个知识点连接引用）或**一般考点**。核心考点出 **2–4 道变式**反复考查，一般考点出 1 道；标注理由随清单附上，维护者验收时把关分级

例：读《Flow 冷热流》抽出「冷流每次订阅重跑」（核心）「StateFlow 去重」（一般）「shareIn 启动策略」（一般）。

**批次粒度**：一批对应**一个笔记（或一组强相关知识点）**，常规 **10–15 题**，核心知识单元 **15–20 题**（单批 20 封顶，大笔记拆上下两批，如 `batch-0005-Flow上` / `batch-0006-Flow下`）；不足 10 题须在清单中说明理由（如小而独立的 API）。Schema 上限 200 题/批不变。

### 2. 出题

- 一题对应**一个**考点
- **同一考点允许多题变式，且鼓励对核心考点反复考查**——但必须换花样，判定标准见下；跨批次亦可对已考考点再出变式（间隔复考，考点清单须列出与历史批次重叠的考点及本次变式角度）
- **变式判定**：与既有同考点题目相比，满足至少一条——①换题型（单选↔判断↔多选↔程序）②换考核角度（语义辨析 ↔ 行为预测 ↔ 错误诊断）③换场景载体。**仅改数字、变量名、措辞顺序的算伪变式，禁止**
- 同考点变式题须附**一句话差异说明**（换的是什么），随交付物提交，复审时核查；`batch-check.py` 的题干精确查重仍是底线
- 优先考**反直觉行为、常见误解、易混概念**；不考死记 API 参数
- 题干含代码用**围栏代码块并标注语言**（Kotlin 用 `kotlin`、Java 用 `java`——App 按标注做离线高亮，写错或省略会丢高亮）；**禁图片**（Schema 拦 `![`）、禁 HTML、禁表格
- 题目尽量原创改写，不大段复制来源文本
- `source` 必须可追溯（标题 + 访问日期 + 链接或笔记路径）
- `tags` 每题 **1–3 个**，只能选自 §八 词表（新标签须维护者扩表，见 §八）

### 3. JSON 预校验（PC 端，必须做）

```powershell
cd C:\Code\Android\知练
python tools\batch-check.py <批次文件路径>
```

> 批次目录由环境变量 `ZHILIAN_BATCHES_DIR` 指定（维护者机器已配置）；未设置的机器必须用 `--batches-dir` 显式传入，否则跨批次核对（清单 6/8）会静默跳过。

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

#### 负结果（证否型）的取证纪律（2026-10-04 新增）

「官方文档检索 X **零命中**」这类**负结果**，必须同时给出**检索有效性反证**——否则检索本身出错时会得出「官方没这么说」的**假结论**，而这种假结论**看起来恰好支持出题方**，是最危险的一类错误。

> **实测案例**：某轮复审用相对链接解析官方站，导致 `java.util.concurrent.locks` **整个子包漏抓**，而界面仍显示「0 命中」——差一点据此认定「官方从未定义 X」。

**三条反证要求**：

1. **对照词**：同时检索一个**必然命中**的词（例如查 `deadlock` 时另查 `synchronized`）。**对照词也 0 命中 ⇒ 是抓取失败，不是官方没说。**
2. **记录范围与计数**：写明检索了哪些页面／子包、共几个 URL、各词命中几次。只写「0 命中」不构成证据。
3. **第二种抓取方式交叉复核**：直连 URL 把原始 `.md`／HTML **落盘后本地 grep**，与线上检索结论比对。

→ 与 §三「source 四档」的 **② 证否型**配套：**证否型 `source` 必须附检索词、检索范围与对照词结果**，否则复审无法核实其真实性。

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

#### 进程调用纪律（2026-10-04 新增，防「满屏 java 终端弹窗」）

§二.4 第 3 条要求关键结论**重复运行 10–30 次**，第 1 条要求 stdout/stderr **分流各跑一次**。两条要求都对，
但**把它们实现成「N 次启动 JVM」会出事**：`java.exe` 是控制台子系统程序，**每次启动 Windows 都新建一个控制台窗口**。

> **2026-10-04 实测事故**：某冷复审脚本写成「6 道题 × 30 轮 × 2 次（`2>$null` 一趟、`2>&1` 再一趟）
> = **360 次 `java.exe` 启动**」，导致**满屏 java 终端反复弹出**数分钟。

**四条纪律**：

1. **把重复收进一次 JVM**：探针内部 `for (int i = 0; i < 30; i++) { … }`，**一次启动**打印 30 组结果。
   证据反而更好审——一个文件里就是 30 组对照。
2. **两条流一次分离**：`java … 1>out.txt 2>err.txt` —— **一次启动**同时得到 stdout、stderr 与退出码。
   **不要**为了分流把同一段代码跑两遍（这是那次 360 次里 ×2 的来源）。
3. **确需多次启动时**（例如换不同 `-XX` 开关各跑一遍），用
   `Start-Process java -ArgumentList … -NoNewWindow -Wait -RedirectStandardOutput out.txt -RedirectStandardError err.txt`
   —— **`-NoNewWindow` 不新建窗口**。
4. **长驻探针必须硬性自退**：主循环计数到时限直接 `System.exit(0)`，**不依赖「跑完」**。
   实测教训：一个 `java -Xmx256m -cp . Probe` 活了约 **10 分钟**，而当时任务书要求 90 秒自退。
   收尾还要主动确认无残留：`Get-Process java`。

按此换算：360 次启动 → **6 次**。

### 6. 定稿与投放

验收通过后：

1. 放入 `<Syncthing 批次目录>\`，文件名 `batch-{四位顺序号}[-{主题}].json`（如 `batch-0005-Flow冷热流.json`，主题可选）；App 按文件内容中的 `batchOrder` 识别，文件名仅作人工辨识
2. 等 Syncthing 同步到手机 `/sdcard/Download/zhilian`
3. App「设置 → 批次导入」导入

**注意**：此目录是双向同步目录——放进去的测试文件会被同步回 PC 端，**验证后必须两边都清理**。

## 三、单题质量标准

### 内容类型配比（每批必须满足）

每批题目按**内容类型**分两类，比例有硬约束：

| 内容类型 | 占比 | 说明 |
|---|---|---|
| **程序题**（含可独立编译运行的代码） | **< 20%** | 题干含**标注了语言的**围栏代码块（Kotlin 用 `kotlin`、Java 用 `java`），问输出/行为/异常。可用运行验证，事实性强 |
| **概念题**（无代码，考原理/辨析/场景） | **> 80%** | 题干纯文字，考理解而非读代码。无法运行验证，靠复审 + 来源核查把关 |

> **比例改定与生效范围（2026-10-04）**：原为「程序题 ≤ 60% / 概念题 ≥ 40%」，现改为 **程序题 < 20% / 概念题 > 80%**。
> **只对改定之后新派单的批次生效；存量与在制批次一律不追溯**——`batch-0001~0018`（已导入）、`batch-0019~0022`（本批已交付）、`batch-0023~0026`（在制）均按当时比例判定，**不回炉、不改判**。
>
> **换算成题数**（**严格小于** 20%，比「≤」更严）：
> **20 题批 → 程序题最多 3 道**（3/20 = 15% ✓；给 4 道就是 20%，**不合规**）；**15 题批 → 最多 2 道**（2/15 ≈ 13.3% ✓；给 3 道 = 20%，**不合规**）。
>
> **连带影响（出题前先想清楚）**：概念题升到 >80% 之后，**绝大多数题不可运行验证**，质量全压在「复审 + 来源核查」这条通道上。因此本节「概念题三形态」（辨析／反直觉／场景判断）与「排除名词背诵型」、以及 §三「source 四档」**从建议变为主要防线**，必须逐题落实——**这是本次改比例最大的代价，不是免费调参。**

> **「程序题 / 概念题」的机械口径（2026-10-04 定，权威）**：以**题干是否含围栏代码块**判定——即在 `stem` 中检索围栏起始标记（连续三个反引号），命中即计**程序题**，否则计**概念题**。`tools/bank-stats.py` 的盘面用的就是这个口径，**预校验与验收都以它为准**。
> 若出题代理按「代码是不是考点主体」另行统计，可能与机械口径差 1–2 题——**允许差异**，但必须在考点清单里**同时写出两个数字与差异原因**（实测例：batch-0020 机械口径 10/20 = 50%、代理自报程序题 40%，差在「代码只作载体、考点是概念」的那两道题上——**该批适用旧比例，不在改定的追溯范围内**）。**两种口径只要都落在本节比例内，即视为配比达标。**

**概念题的三种形态**（出题时必须落到其中之一，排除"名词背诵型"）：

1. **辨析题**：两个易混概念的差异（如 `coroutineScope` vs `supervisorScope` 的失败传播、`delay` vs `Thread.sleep` 的阻塞语义、`Dispatchers.IO` vs `Default` 的线程池关系）
2. **反直觉题**：不写代码也能问的反直觉事实（如"`Dispatchers.IO` 和 `Default` 是两条独立线程池吗"、"`async` 未被 `await` 是否会被取消"）
3. **场景判断题**：给一个场景问该怎么做（如"主线程要做网络请求该用哪个 Dispatcher"、"多个并发结果要先全部启动再 `await` 还是逐个 `await`")

**排除"名词背诵型"**：不考"协程的三大特性是什么"、"`CoroutineStart` 有几个枚举值"这类纯记忆题——刷完记不住也用不上。

**难度梯度（软约束，不进 batch-check）**：受众含新手同学（ADR-0011），每批宜含 2–3 道**入门题**——定义正反、基础 API 语义等新手可答对的题，为批内铺难度坡度；程序脑内执行题宜避免连续超过 2 道。本条仅"宜"，由验收时维护者体感把关；难度不建模为 Schema 字段（取舍见 ADR-0011）。

### 格式配比（题型分布）

单选约 45%、判断约 25%、多选约 20%、填空约 10%。单选与判断歧义少、易把关；多选与填空歧义多，控制比例。

### 解析分级（必写 + 按需）

解析**不再强制五段齐全**。分两级：

**必写（每题都要有）：**
1. **为什么对**——正确答案的依据
2. **为什么错**——干扰项各自错在哪（不只是"选 A 对"）

**按需写（只在确实有价值时才写，不灌水）：**
3. **前置概念**——该题依赖的知识铺垫（简单题不需要）
4. **常见误解**——点出容易想错的地方（仅当存在真实易错点时）
5. **变体提示**——"把 X 换成 Y 会怎样"（仅当变体有教学价值时）

**目标长度**：简单题 150–300 字，复杂题（如 `supervisorScope` 的失败传播）该长就长。长度由内容决定，不由清单决定。解析在半模态面板中可滚动展示（ADR-0007），不再受卡片高度限制。

### 概念题的验证手段

概念题无法运行验证，必须用以下手段把关（出题时标注，复审时核查）：

1. **来源必须指向可独立核实的权威材料**（不只引 Obsidian 笔记）——本条的**目的是可核实**，不是限定必须出自 Oracle 一家。四档见下方「source 四档」
2. **复审独立复述原理**：复审代理不看答案，独立判断陈述真假，再与出题答案比对
3. **术语核查**：若选项或解析使用了技术术语，复审须核实该术语在官方源码/文档中真实存在（见 §二.4「复审必查的三类硬伤」第 2 条的方法）

**source 四档（按强度递减，2026-10-04 定，适用全部科目）**

| 档 | 情形 | `source` 怎么写 | 例 |
|---|---|---|---|
| ① 官方明写 | 结论就是官方文档的原话 | 引该官方 URL | `ThreadMXBean` 明写「包含虚拟线程的环不会被本方法发现」 |
| ② **证否型** | 结论是「官方页检索 X 零命中」 | 引**被检索的那个官方页** URL，并在解析里给出**检索词与结论** | 官方类页把 `happens-before` 拼成 `happen-before`（四个类页 `happens-before` 实测 0 次） |
| ③ **JDK 源码型** | javadoc 零命中，只有源码能证 | 引 OpenJDK 源码位置，并在 `source.title` 或 `note` 里**标明「JDK 源码，非 javadoc」+ 版本号** | JDK 21 的 AQS 已整体重构（`addWaiter`／`SIGNAL`／`PROPAGATE` 在 javadoc 上 0 命中） |
| ④ 一手文献型 | 源头是论文／教科书 | 引书目（含年份，有 DOI 更好），**不得冒充官方出处** | 死锁四条件 = Coffman, Elphick & Shoshani, *System Deadlocks*, ACM Computing Surveys 3(2), 1971 |

- **社区来源（博客／教程站／聚合站）不得替代 ①–④**，只能作交叉验证，且**不得**作为正面考点的唯一来源。引 URL 前**必须亲自读过该页**（搜索摘要不是来源）。
- **版本纪律**：③ 档必须带版本（如「JDK 21.0.9」）。已有实测教训：`synchronized` 的 pinning 在 **JDK 24 由 JEP 491 修掉**，不标版本即为错题。
- 凡「官方根本没这么说」的结论，按 ② 或 ④ 记录**真实来源**，不得挂 Oracle 名下（反面写法见 §二.4 第 2 条）。

### 逐题自查

- [ ] 考点明确，且值得考（不是背 API 参数、不是名词背诵）
- [ ] 内容类型明确（程序题 / 辨析题 / 反直觉题 / 场景判断题）
- [ ] 答案唯一无歧义（多选尤其：不能有"少选也对"的灰区）
- [ ] 干扰项长度风格一致，不泄漏答案，无"两个都对"
- [ ] 解析必写部分齐全（为什么对 + 为什么错），按需部分不灌水
- [ ] 来源真实、访问日期正确、与题目相关；概念题的 `source` 指向**可独立核实的权威材料**（官方文档 / JDK 源码 / 一手文献，四档见 §三「source 四档」；社区来源只能作交叉验证）
- [ ] 分类用词与 §五 一致；tags 每题 1–3 个且全部选自 §八 词表
- [ ] 含代码者，代码自包含且**已实际运行核验**

## 四、JSON 预校验清单

脚本 `tools/batch-check.py` 检查（前者由 `jsonschema` 按权威 Schema 校验，后者为 Schema 表达不了的）：

1. `answer` 引用的每个 `optionId` 必须存在于该题 `options`
2. `options` 内 `optionId` 互不重复
3. 批次内 `questionId` 互不重复
4. `retiredQuestionIds` 不得包含本批次 `questions` 中的任何 ID
5. 分类名 NFC+trim 后非空
6. `batchOrder` 不得与**已导入批次**重复（脚本会读 `<Syncthing 批次目录>\` 下现有批次核对）
7. `formatVersion` 必须为 1
8. 疑似重复：候选 `stem` NFC+trim 后与已有题目完全相同且 ID 不同 → 提示

## 五、分类体系

分类名**就是身份**：同一科目内 NFC+trim 精确相同即为同一分类；**改名等于新建分类**，旧题归属不变。所以分类**一次定死**。分类**挂在科目下**——不同科目的同名分类是不同分类（Java 的"集合"与 Kotlin 的"集合"互不干扰）。

单层结构，命名与既有风格一致（2–4 汉字名词）。当前科目与分类：

### Kotlin

| 分类 | 内容 |
|---|---|
| 基础语法 | val/var、运算符、基本声明 |
| 空安全 | 可空类型、`?.`、`?:`、`!!` |
| 集合 | List/Map、只读与可变、常用操作 |
| 函数 | 函数声明、Lambda、高阶函数 |
| 协程基础 | 挂起函数、协程构建器、结构化并发 |
| 协程调度 | Dispatchers、线程切换、主线程与 ANR |
| 通道 | Channel 容量与会合、发送接收、关闭语义 |
| 数据流 | Flow 冷热流、操作符、异常处理 |
| 共享状态 | Mutex、原子类、线程封闭 |
| 测试&调试 | runTest 虚拟时间、debug 模式与探针 |

### Java

按需追加。建议分类：基础语法、集合框架、**内存与引用**、并发编程、IO、面向对象、常用接口。

> 「内存与引用」2026-10-04 依素材库 `Java\01-内存与引用\`（GC 算法与回收器、四大引用、JVM 内存模型、直接内存与堆外内存、JVM 内存诊断、WeakHashMap，6 篇）登记：原建议分类缺此项，而该章整章未开采。分类一次定死，出首批前先登记。
> 「集合框架」暂不单独立类——素材把集合内容放在 `Java\05-常用接口\`（集合底层、List/Set/Map/Queue/Deque、Comparable/Comparator），待出到该章再定，以遵循下文「不要一次铺开只有少量题的空分类」。

**推进路线（2026-10-04 定）**：Kotlin 素材已产出完毕（`Kotlin\01-协程\` 12 篇考点笔记全部成批，题库 kotlin 164 题），后续转向 **Java**，按素材章节顺序推进，起手 `Java\01-内存与引用\`；ArkTS 暂缓（见 §六、§八）。Java 已开「基础语法」（batch-0008）与「并发编程」（batch-0017）两类。注意 Java 素材的**面试角度**已被 batch-0009/0010 消耗一轮（计入 `interview` 科目），语言学习角度仍近空白，二者可对同一笔记分别出题（§九）。

后续按需追加科目（扩科目须改 Schema `subject` 枚举，见 §六）。**不要一次铺开只有少量题的空分类**——练习配置页的分类 chips 每行 3 个，分类多而空会让筛选区变长且无用。

## 六、硬约束速查（违反必被拒）

- 首版**只追加**：不原位覆盖、不物理删除。修订 = **新 questionId**，旧题走 `retiredQuestionIds` 停用，且**停用不可恢复**
- `subject` 枚举当前为 `kotlin`、`java` 与 `arkts`（ArkTS 为占位，题库暂无题；**素材库已有 20 篇 ArkTS 笔记**，2026-10-04 核，按既定路线暂缓）；要扩其他科目须改 Schema 与 App 侧适配
- 每批 `questions` **1–200** 题（Schema 上限）；**批次规模常态 10–20 题**（常规单元 10–15、核心单元 15–20，见 §二.1），不足 10 须在考点清单说明理由
- `batchOrder` **1–9999** 且不得与已导入批次重复
- 顶层 `additionalProperties: false`——**任何未定义字段都会导致整批校验失败**
- `batchId` / `questionId` 为小写 UUID（`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`）
- 文本首尾不得空白（`^\S(.*\S)?$`）

## 七、外部出题代理交接边界

出题由**独立上下文的代理**承担（通常为同机新会话）。边界如下，越界即返工：

**环境与能力**：本机新会话，可读本仓库全部文件，可运行 `python` 与 §二.5 的 Kotlin 运行验证（本机路径对你可用）。

**素材供给**：素材由维护者**每批指定**（Obsidian 笔记内容或菜鸟教程页面），出题代理不得自行选取网络来源。素材是菜鸟教程时，概念题的 `source` **仍必须指向可独立核实的权威材料**（四档见 §三「source 四档」）——菜鸟教程只作为理解素材，不作为事实权威，可与权威出处并列附上。

**必须自跑**（产出随批次一并交付）：
1. §二.3 `batch-check.py` 预校验，附**通过输出**（0 错误）
2. 代码题的 §二.5 运行验证，附实际运行结果（含 §二.4「复审必查的三类硬伤」第 1 条的 stdout/stderr 分流与稳定性实测）
3. 冷复审（执行方式见下方「子代理协议」）

**冷复审的执行（子代理协议）**：出题代理**必须在交付前完成冷复审**，方式按环境**三选一**：

1. **派生独立上下文的子代理**（环境支持时优先）：
   - 复审提示词**机械化拼装**，只允许包含两部分：①剥敏题目（题干、选项、题型；**剥离** `answer`、`acceptableAnswers`、`explanation`、`source`）②本文 §二.4 的复审任务描述与裁决模板**原文**
   - **禁止**向子代理附加任何评价性引导语、笔记原文或出题意图——独立性由"复审方不接触答案与出题意图"保证
   - 子代理独立作答 + 逐项挑刺（§二.4），产出结构化裁决
   - **复审完整输出与所用提示词原样随批次交付**，维护者据此审计；发现泄漏答案或引导措辞，整批退回
   - REVISE 返工后可再派新一轮复审直至 PASS；与复审各执一词时按 §二.4 升级（换模型重审 / 运行验证加码 / 交维护者裁）
2. **声明弃权**：环境不支持派生子代理时，交付物中明确声明"未复审"，由维护者另起会话执行 §二.4
3. **零上下文继承的独立执行**（2026-10-04 新增，供部署不支持派生时用）：本部署的 `maxDepth=1` 使出题代理**无法**再派 subagent，此时可用任何**不继承出题上下文**的独立通道（`workflow` 工具、任务看板会话、另起新会话），但须满足：
   - 复审方同样只拿到 ①剥敏题目 ②§二.4 原文，**独立性的实质要求不变**
   - 交付物中**写明所用机制**（走哪条通道、上下文是否零继承），供维护者审计
   - 与路径 1 同等承担"完整输出与提示词原样交付"的义务

> **落盘纪律（2026-10-04 新增）**：复审的**每一轮**提示词与裁决输出都必须落在**交付目录**（`batch-XXXX\`）里，**不得只留在系统 temp**——temp 会被清理，而"原样交付"是 §七 的硬要求。
> 已发生的教训：**batch-0020 第 1–7 轮的完整裁决随 temp 清理丢失**，仅存摘要 + SHA，§七 对该批未完全满足（代理已如实登记，维护者接受本次，但机制必须堵住）。

**禁止**：
- **跳过或伪造冷复审**——交付物缺复审记录视为未复审，提示词泄漏答案或含引导措辞整批退回
- 修改规范文档、Schema、工具脚本——发现规范缺口或词表缺词，**报告维护者**按 §八 流程扩表，不得自行绕过。**扩表提议只交付提议文件（如 `batch-XXXX-扩表提议.md`），规范文档的登记由维护者侧执行**——出题代理自行写入规范即视为越界（2026-09-28 batch-0004 教训：代理直改 §五/§八 被后续提交夹带，返工时已点破）
- 触碰 Syncthing 批次目录——投放由维护者验收后执行（§二.6）
- **修改 Obsidian 素材库**（README「产品目标」段：本项目独立开发，不修改本地 Obsidian 库或其中的笔记）。**且代理产出——含日志、记忆、临时文件——一律落在交付目录或仓库内，不得落在 Obsidian 库内**：该库是 Syncthing 同步目录，写进去会扩散到维护者的其他设备。已发生的教训：2026-10-05 某核查代理把记录写进了 `Obsidian_document\.workbuddy\memory\`，却自述"库内文件一个字没动"。

**素材勘误必须可复现（2026-10-05 新增）**：凡报告素材库（Obsidian）错误，**必须给出「篇目 + 行号 + 原文摘录 + 官方出处」四项**。只有结论、没有定位的勘误**一律不予收录、不得进入任何待办清单**——维护者与后续核查都无法复现它。已发生的教训：2026-10-05 第二轮报的 4 条勘误里，`Callable` 链接那条因无篇目无行号，独立核查复现不了，我方全库扫描后确认**是误报**（全库 `Callable` 链接 3 处目标全对、`java/lang/Callable` 0 命中）。

**产出物清单**：批次 JSON 文件 + 预校验通过输出 + 代码题运行验证记录 + 冷复审记录（所用提示词 + 裁决输出）+ 考点清单（§二.1）。

## 八、标签词表

标签是练习配置页**最细的筛选维度**（App 中标签行跟随已选分类收窄），词表失控会让筛选直接退化。治理为**存量定死 + 增量扩表**的封闭词表。词表外标签与冻结标签在新批次中由 `tools/batch-check.py` 机械拦截（词表自本文档 §八 实时解析，此处登记即生效）。

### 增量规则（出题代理逐条遵守）

1. 每题 1–3 个，只能选自下表；跨分类复用是正常的（既有先例：`operators`、`collections`、`coroutines`）
2. 全小写英文；API 名照抄小写（`withcontext`）；多词用连字符（`null-safety`）
3. **禁止形态变体**：单复数、去连字符、缩写都视同新标签——同一概念只允许一个词形（`coroutines` ≠ `coroutine`），变体进词表只会分裂筛选、产生重复 chip
4. 词表缺词：向维护者提议（标签 + 理由 + 拟归属分类），扩表后方可使用
5. 新标签进入词表时同步登记到下表对应分类

### Kotlin（存量 v7，2026-10-01 batch-0015 扩「协程调度」类 `viewmodelscope`、`lifecycle`、`launchedeffect`〔`lifecycle` 与 `lifecyclescope` 二选一取前者——一词覆盖 lifecycleScope/repeatOnLifecycle/State/Owner 整条线，拆开割裂；`remembercoroutinescope` 等按提议推迟〕；v6 2026-10-01 batch-0014 扩「基础语法」类 `sealed-class`、`when-exhaustive`、`enum-class`、`ui-state`〔驳回 `sealed-interface`——仅 1 题使用，改挂 `sealed-class`，将来题量起再登记；`when-exhaustive` 与 `sealed-class` 正交：穷尽性对枚举/Boolean/可空同样成立〕；v5 2026-10-01 batch-0013 扩「测试&调试」类 `advancetimeby`、`backgroundscope`、`debug-probes`、`test-dispatcher`〔暂缓 runcurrent/advanceuntilidle——与 advancetimeby 同考点强耦合；uncompletedcoroutines——异常类名非知识维度；setmain——未出题不预登记〕；`test-dispatcher` 与协程调度类存量 `unconfined` 是不同身份：`UnconfinedTestDispatcher` 跳 delay 而 `Dispatchers.Unconfined` 不跳，1.10.2 实测差 3000ms vs 2ms；v4 2026-10-01 batch-0012 扩「共享状态」类 `semaphore`、`trylock`，驳回 `withlock`〔语法糖无独立筛选语义〕与 `owner`〔全局语义过泛，将来如需用收敛词形 owner-token 再提〕；v3 2026-09-30 追认 batch-0006 在用的七个数据流标签并移除零使用的 channel-flow；v2 2026-09-28 自 4 个批次整理，新增通道／数据流／共享状态／测试与调试四类及其标签）

| 分类 | 合法标签 |
|---|---|
| 基础语法 | basics\*、enum-class、equality、operators、sealed-class、ui-state、variables、when-exhaustive |
| 空安全 | null-safety、operators、collections |
| 集合 | collections、immutable |
| 函数 | functions、basics\* |
| 协程基础 | async、awaitall、cancellation、concurrency、coroutines、coroutinescope、exception-handler、global-scope、launch、runblocking、select、structured-concurrency、supervisorscope、withtimeout |
| 协程调度 | android、anr、coroutine-start、coroutines、delay、dispatchers、launchedeffect、lifecycle、main-thread、suspending-functions、unconfined、undispatched、viewmodelscope、withcontext |
| 通道 | channel、capacity、collect、exception |
| 数据流 | flow、state-flow、shared-flow、replay、flowon、collect、exception、capacity、flow-operators、zip、combine、flatmap、transform、operator-fusion、buffer |
| 共享状态 | mutex、semaphore、synchronized、trylock |
| 测试&调试 | advancetimeby、backgroundscope、coroutine-name、debug-agent、debug-probes、runtest、test-dispatcher、virtual-time |

\* `basics` 为弱标签（语义过泛），**冻结**——存量题保留，新题不得再使用。

### Java（存量 v4，2026-10-04 两次扩表合并登记：batch-0019~0022 扩「内存与引用」类 32 个标签，batch-0023~0026 扩「并发编程」类 29 个标签〔原有 9 个保留不动，末尾追加〕。跨科目同名复用（按 §八 既有裁定，标签筛选按 subject 隔离、不串味）：`gc`、`lock`、`concurrency` 与 interview 词表同名，`semaphore` 与 Kotlin 词表同名。**`jmm` 只指 Java 内存模型（并发、happens-before），属「并发编程」类；运行时数据区一律用 `runtime-data-area`**。跨批共用标签**拼写须完全一致**：`gc-root`（batch-0020/0021）、`spurious-wakeup`（batch-0023/0026）、`cas`／`fairness`／`reentrantlock`／`barging`／`timeout`（batch-0025/0026）。**禁造形态变体清单见 §十**（词表外写法一律拦截）；v3 2026-10-04 batch-0019~0022 扩「内存与引用」类 32 个标签；v2 2026-10-01 batch-0017 新增「并发编程」类 `thread`、`thread-state`、`interrupt`、`volatile`、`visibility`、`happens-before`、`atomicity`、`daemon-thread`、`jmm`〔自驳候选存档：`synchronized`——与 Kotlin 词表同名且本批未出题，将来出互斥语义题再提；`monitor-lock`——并入 thread-state 的 BLOCKED 语义；`visibility-model`/`memory-visibility`——visibility 同义变体；`thread-lifecycle`——与 thread 重叠；`atomic`——与 atomicity 去后缀变体；`volatile-ordering`——volatile+jmm 组合可表达〕；v1 2026-09-29 自 batch-0008 建档）

| 分类 | 合法标签 |
|---|---|
| 基础语法 | bitwise、shift、complement |
| 并发编程 | thread、thread-state、interrupt、volatile、visibility、happens-before、atomicity、daemon-thread、jmm、process、concurrency、sync-async、wait-notify、spurious-wakeup、runnable、callable、executor、future、futuretask、completablefuture、pessimistic-lock、optimistic-lock、optimistic-read、cas、stampedlock、lock、readwritelock、reentrantlock、fairness、countdownlatch、cyclicbarrier、semaphore、phaser、barging、timeout、aqs、condition、tryacquire |
| 内存与引用 | runtime-data-area、heap、stack、stack-overflow、metaspace、outofmemoryerror、direct-memory、maxdirectmemorysize、cleaner、bytebuffer、gc、generational、collector、g1、zgc、stop-the-world、gc-log、system-gc、gc-root、reachability、memory-leak、reference-chain、weak-reference、weakhashmap、referencequeue、implicit-reference、jcmd、jmap、jstat、heap-dump、nmt、mxbean |

### ArkTS

标签暂无存量。**素材库已有 20 篇 ArkTS 笔记（2026-10-04 核，约 190 KB：MVVM、RDB、Navigation、循环家族、AppStorageV2/PersistenceV2、Map/Location Kit、atManager、Emitter、fileIo 等）**——旧说法「暂无素材与题」中的「暂无素材」已作废，「暂无题」仍成立。按 2026-10-04 定下的路线 **ArkTS 暂缓**；解禁后首个批次由出题代理按增量规则提议、维护者扩表建档。

### 面试（subject: interview，存量 v1，2026-09-29 自 batch-0009 建档）

分类「基础面试题」：

| 分类 | 合法标签 |
|---|---|
| 基础面试题 | primitive-types、equality、keywords、boxing、string、data-structure、collections、gc、references、clone、oop、overload-override、abstract-interface、hashcode、design-patterns |

> `equality`、`collections` 与 Kotlin 词表同名，经维护者裁定**跨科目同名复用**（标签筛选按 subject 隔离，不串味）。

### 常见面试题（subject: interview，存量 v1，2026-09-29 自 batch-0010 建档）

| 分类 | 合法标签 |
|---|---|
| 常见面试题 | collections（复用「基础面试题」）、serialization、hashmap、concurrency、thread-safety、lock、volatile、thread-pool |

> `collections` 跨分类复用（B1-2 集合类层，与「基础面试题」的原理层共用同一容器标签，二者靠分类区分）；`hashmap` 与既有 `hashcode` 是不同身份（容器 vs equals/hashCode 契约），不合并、不造 `hash-map` 变体。

## 九、面试科目（interview）出题原则

面试科目与语言学习科目（kotlin/java）平级独立，素材是面试题清单（原题干）而非知识笔记。四条硬规则：

1. **拆封闭题**：开放问答式原题（「说说 X」「X 和 Y 的区别」）不得原样作题干——现有四题型均需标准答案判分。原题作**素材主题**拆成封闭题干，一道宽原题可拆 2–3 道（如「线程池核心参数」拆参数含义 / 执行顺序 / 核心线程数确定）。
2. **解析 = 面试答题要点**：教材式五段结构不变，「为什么对」的内容即面试官想听到的答案要点；「变体提示」可写面试追问方向。
3. **标注原题干变体**：每道拆出题的解析首段须标注来源，格式：`**由面试原题「〈原题干〉」（〈基础面试题/常见面试题〉）拆出。**`——刷题时可对回原题，复习按面试原题串联。
4. **跨科目重叠不受变式规则约束**：面试科目与 kotlin/java 科目的同名知识点（如 ArrayList 扩容）是独立身份，无需变式设计；但题干雷同仍由 batch-check 拦截（疑似重复警告），拆题时须重述措辞。

## 十、标签禁造形态变体（词表外写法一律拦截）

§八 的表格是**唯一合法词表**（`tools/vocab.py` 按该节解析）。本节是**治理参考**：下列写法在两个方向上都不可用——① 它们不在 §八 里，`tools/batch-check.py` 会机械拦截；② 即便登记，同一概念的多个词形也会分裂筛选 chip（§八 增量规则 3）。

> **位置约定（重要，别把本节内容挪进 §八）**：`vocab.py` 解析 §八 时会读取**该节内所有表格行**的第 2 列。把本节的禁造表放进 §八，会被当成标签解析出垃圾条目——2026-10-04 实测：插入一张 `| 禁写 | 正确词形 |` 表后，java 词表凭空多出 `` `runtime-data-area` ``、`正确词形` 等项。所以本节刻意放在 §八 **之外**。

### 通用反例

- **单复数**：同一概念只留一个词形，复数形一律不建
- **缩写与全称**：`stw` → `stop-the-world`；但 `nmt` 反过来保留缩写（更常用），禁 `native-memory-tracking`
- **去/加连字符**：`gcroot`、`heapdump`、`stamped-lock` 一律非法
- **API 名照抄小写**：`weakhashmap`、`completablefuture`、`maxdirectmemorysize`（同 `withcontext`、`advancetimeby` 先例），不加连字符

### Java · 内存与引用（batch-0019~0022）

| 禁写 | 正确词形 |
|---|---|
| `memory-model`、`memorymodel`、`jvm-memory-model` | `runtime-data-area` |
| `off-heap`、`directbuffer`、`direct-buffer`、`offheap` | `direct-memory` |
| `generation` | `generational` |
| `gcroot`、`gc-roots` | `gc-root` |
| `stw` | `stop-the-world` |
| `weakref`、`weakreference` | `weak-reference` |
| `weak-map`、`weak-hash-map` | `weakhashmap` |
| `reference-queue` | `referencequeue` |
| `oom`、`heap-oom` | `outofmemoryerror` |
| `leak` | `memory-leak` |
| `heapdump` | `heap-dump` |
| `native-memory-tracking` | `nmt` |
| `max-direct-memory-size` | `maxdirectmemorysize` |
| `mx-bean` | `mxbean` |
| `diagnostics`、`jvm-diagnostics` | 用具体工具名 `jcmd`／`jmap`／`jstat` |

### Java · 并发编程（batch-0023~0026 新增部分）

| 禁写 | 正确词形 |
|---|---|
| `synchronous-asynchronous`、单独用 `sync`／`async` | `sync-async` |
| 把 `wait`／`notify`／`notifyall` 拆开 | `wait-notify` |
| `spurious-wakeups` | `spurious-wakeup` |
| `process-thread` | `process` 与 `thread` 两个标签 |
| `concurrent`、`concurrent-programming` | `concurrency` |
| `executors`、`executor-service`、`executorservice` | `executor` |
| `future-task` | `futuretask` |
| `completable-future`、`completion-stage`、`cf` | `completablefuture` |
| `runnable-task`、`callable-task` | `runnable`／`callable` |
| 单独用 `pessimistic`／`optimistic` | `pessimistic-lock`／`optimistic-lock` |
| `optimistic-reading` | `optimistic-read` |
| `compare-and-swap`、`compareandset` | `cas` |
| `stamped-lock`、`stamp-lock` | `stampedlock` |
| `rwlock`、`read-write-lock` | `readwritelock` |
| `reentrant-lock`、`rlock` | `reentrantlock` |
| `fair`、`fair-lock`、`fair-mode` | `fairness` |
| `latch`、`count-down-latch`、`cdl` | `countdownlatch` |
| `barrier`、`cyclic-barrier`、`cb` | `cyclicbarrier` |
| `phasor`、`phasers`（单复数） | `phaser` |
| `semaphores`（单复数） | `semaphore` |
| `barge`、`barging-in` | `barging` |
| `timeouts`、`time-out`、`timed-wait` | `timeout` |
| `synchronizer`、`sync-tool`、`sync-tools` | 用具体类名或 `aqs` |
| `abstractqueuedsynchronizer`、`abstract-queued-synchronizer`、`a-q-s` | `aqs` |
| `try-acquire`、`tryacquireshared`、`try-acquire-shared` | `tryacquire` |
| `condition-variable`、`condition-queue`、单独用 `await`／`signal` | `condition` |
| `clh`、`clh-queue`、`node`、`aqs-node`、`park`、`unpark` | 挂 `aqs` |

### 易与既有标签混淆者（**不是变体，是身份不同**）

| 标签 | 区别 |
|---|---|
| `async`（**Kotlin** 词表，协程构建器） | **不得**在 java 侧表示「异步」——java 侧用 `sync-async`。这不是跨科目同名复用，是**不同身份** |
| `jmm`（并发语义） | 与「JVM 运行时数据区」无关；后者用 `runtime-data-area`。中文名只差一个词，是本库记录在案的同名陷阱 |
| `atomicity`（JLS 术语语义） | 与 `cas`（原子类／乐观锁策略）是不同身份 |
| `happens-before`（语义） | 与「官方类页把这个词**拼错**成 `happen-before`」这件**事实**不同——拼写事实题挂对应类标签（如 `countdownlatch`） |
| `thread`（线程本身） | 与 `runnable`／`callable`（任务形态）是不同身份 |
| `lock`（接口层契约） | 与 `reentrantlock`／`readwritelock`（具体类）是不同身份 |
