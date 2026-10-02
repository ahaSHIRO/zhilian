# AGENTS.md — AI 会话协作入口

本文件是任何 AI 会话（App 开发、出题、文档维护）在本仓库工作的**总入口**。只做路由不复制细节，细节以各专门文档为唯一权威源。

## 项目定位

「知练」：Android 刷题 App（Kotlin/ArkTS 学习用）。单 Activity + Compose + Room；题目来自 JSON 批次文件（Syncthing 同步到手机导入），批次由外部 AI 出题代理按规范产出、维护者验收投放。

## 常用命令

```powershell
.\gradlew.bat assembleDebug          # 构建
.\gradlew.bat test                   # 单测
python tools\batch-check.py <批次.json>   # 批次 JSON 预校验
python tools\batch-check.py --selftest    # 应用级规则跨端一致性自检（与 App 端同夹具）
python tools\bank-stats.py                # 题库存量盘面（科目/分类/题型/标签/程序题占比）
```

两个 Python 工具从环境变量 `ZHILIAN_BATCHES_DIR` 读本机批次目录（维护者机器已配置）；未设置的机器须用 `--batches-dir` 显式传入，否则跨批次核对会静默跳过。**本仓库已公开在 GitHub——任何个人路径、凭据不得入库，本机配置一律走环境变量。**

## 目录地图

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/baiyin/zhilian/` | `ui/`（screens/components/navigation/theme）、`data/`（db/practice/batch） |
| `app/src/test/` | JVM 单测（判分、掌握度、填空匹配、编排等） |
| `docs/conventions/` | question-authoring.md（出题规范）、pitfalls.md（踩坑手册）、edge-to-edge.md、design-tokens.md |
| `docs/schema/` | batch-v1.schema.json + batch-spec-v1.md（批次 JSON 权威契约） |
| `docs/adr/` | 已定案决策记录（0001–0013） |
| `tools/` | batch-check.py 批次预校验（Schema + 应用级规则 + 跨批次重复 + 标签词表拦截）；bank-stats.py 存量盘面；vocab.py 词表解析（§八 为唯一权威源） |
| `CONTEXT.md` | 领域词汇表（科目/分类/标签/题目的身份语义） |

## 按任务路由（先读再动）

| 你要做什么 | 先读 |
|---|---|
| 出题 / 改出题流程 | [docs/conventions/question-authoring.md](docs/conventions/question-authoring.md) 开头的「冷启动指引」块 |
| 改 UI 交互 | docs/adr/——已定案交互勿重开议题，修订走 ADR 修订段 |
| 改主题 / 间距 / 字体 | docs/conventions/design-tokens.md（token 档位值表） |
| 改底栏 / 玻璃效果 / 屏幕底部留白 | docs/adr/0010-bottom-bar-liquid-glass-and-content-through.md + docs/conventions/edge-to-edge.md §底栏穿透（**内容穿底栏是刻意的，勿当 bug 修**） |
| 遇到怪问题 | docs/conventions/pitfalls.md——先查有没有人踩过，踩了新坑修完追加 |
| 动批次 JSON 字段 | docs/schema/batch-spec-v1.md + batch-v1.schema.json（Schema 为权威） |

## 协作红线（违反即返工）

1. **git add 前必须审查完整 staged diff**——曾发生越权改动被夹带提交（见 pitfalls 3.1）；只精确 add 任务相关文件，禁 `git add -A` / `git add .`。
2. **提交（commit）仅在用户明确要求时执行**；提交信息含功能模块 + 关键变更点。
3. **默认在模拟器验证，且模拟器固定为 MuMu**：
   - **连接端口不写死，先读实际 `adb_port`**：跑 `MuMuManager.exe info -v all`（在 MuMu 安装目录的 `nx_main\` 下），从输出 JSON 里取 `adb_port`，再 `adb connect 127.0.0.1:<adb_port>`。端口随 MuMu 版本/配置变化——见过 7555 与 16384 两种，本文原写的 7555 在 MuMu 12 上已连不上。**设备列表里同时挂着真机与 AVD，后续所有 adb 命令必须带 `-s 127.0.0.1:<adb_port>`**，否则直接报 `more than one device`。
   - **不要另起 Android Studio AVD**：AVD（如 `Medium_Phone_API_36.1`）是 1080×2400@420dpi = **411dp** 宽，MuMu 是 1080×1920@480dpi = **360dp**，相差 14%。间距、折行、卡片宽度这类测量数只跟 dp 宽走，换机器整套数字不可比——**本项目历史验证数据一律以 360dp 为基准**。
   - 环境事实：MuMu = Android 15 / API 35，`wm size` 1080x1920、`wm density` 480 → **360dp**；真机 = 小米 25060RK16C（Android 17 / API 37），`wm size` 1280x2772。**真机的 dp 宽度会随系统「显示大小」设置变化**——同一台机器实测到过 `wm density 480` ≈ 427dp 与 `wm density 520` ≈ 394dp 两种，所以**每次用前现场读 `wm size` / `wm density`，不要沿用旧数字**。模拟器上看着折行的代码在真机上可能不折——**折行类结论必须在真机复核**。
   - **键盘 / ime 相关行为 MuMu 验不了**：它的输入法窗口高度恒为 0（`mInputShown=true` 但软键盘不占屏），`imePadding()` 永远加 0——在模拟器上得出的「键盘避让正常」是**假阴性**，一律走真机；自检判据见 pitfalls 2.18。
   - 除「模拟器复现不了」或用户明确要求真机外，不占用真机。确需真机时——自动化前确认手机空闲（`dumpsys activity activities` 查前台），**验证完立即息屏**（`adb shell input keyevent 26`，防 OLED 烧屏）。
4. **构建成功 ≠ 验证通过**：真机验证前先 `adb install -r` 新 APK。
5. **工具回执可能污染**：投放/删除等不可逆操作分步重验（存在性 → 列目录 → 双 hash 交叉核对），见 pitfalls 3.2。
6. **改动/审查/答疑前优先主动读当前磁盘文件**，不凭记忆或旧快照下结论。
7. **多会话并行时先隔离再动手**：同一个工作目录里的多个会话共享磁盘文件与 **git index**——一方暂存后，另一方跑一次不带路径的 `git commit` 就会把暂存卷走。① 提交一律用 `git commit --only -- <明确路径>`（对别人暂存了什么免疫），提交前核对 `git diff --cached --name-only`；② 若两个任务会改同一批文件（`strings.xml` / `CONTEXT.md` / `ZhilianApp.kt` 这类几乎必然撞），先 `git worktree add ../知练-<任务> -b <分支>` 给任务开独立目录——**同目录切分支不隔离任何东西，还会毁掉对方的未提交改动**；③ 合回 main 由人执行，AI 不自行 merge/push。

## 出题代理专用

你的完整工作流程、角色边界（§七）、标签词表（§八）全在 [docs/conventions/question-authoring.md](docs/conventions/question-authoring.md)。要点：交付物放 `<出题交付目录>\`，**投放（写入 Syncthing 目录）由维护者执行，出题代理不碰**；扩表提议只交提议文件，规范登记由维护者侧执行。
