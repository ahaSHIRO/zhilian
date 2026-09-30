# AGENTS.md — AI 会话协作入口

本文件是任何 AI 会话（App 开发、出题、文档维护）在本仓库工作的**总入口**。只做路由不复制细节，细节以各专门文档为唯一权威源。

## 项目定位

「知练」：Android 刷题 App（Kotlin/ArkTS 学习用）。单 Activity + Compose + Room；题目来自 JSON 批次文件（Syncthing 同步到手机导入），批次由外部 AI 出题代理按规范产出、维护者验收投放。

## 常用命令

```powershell
.\gradlew.bat assembleDebug          # 构建
.\gradlew.bat test                   # 单测
python tools\batch-check.py <批次.json>   # 批次 JSON 预校验
```

## 目录地图

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/baiyin/zhilian/` | `ui/`（screens/components/navigation/theme）、`data/`（db/practice/batch） |
| `app/src/test/` | JVM 单测（判分、掌握度、填空匹配等） |
| `docs/conventions/` | question-authoring.md（出题规范）、pitfalls.md（踩坑手册）、edge-to-edge.md、design-tokens.md |
| `docs/schema/` | batch-v1.schema.json + batch-spec-v1.md（批次 JSON 权威契约） |
| `docs/adr/` | 已定案决策记录（0001–0010） |
| `tools/batch-check.py` | 批次预校验（Schema + 应用级规则 + 跨批次重复） |
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
3. **真机自动化前确认手机空闲**（`dumpsys activity activities` 查前台），**验证完立即息屏**（`adb shell input keyevent 26`，防 OLED 烧屏）。
4. **构建成功 ≠ 验证通过**：真机验证前先 `adb install -r` 新 APK。
5. **工具回执可能污染**：投放/删除等不可逆操作分步重验（存在性 → 列目录 → 双 hash 交叉核对），见 pitfalls 3.2。
6. **用户练手环节只给步骤 + 原理，不代写**；用户明确说「你来 / 代我完成」才代做。改动/审查/答疑前优先主动读当前磁盘文件，不凭记忆或旧快照下结论。

## 出题代理专用

你的完整工作流程、角色边界（§七）、标签词表（§八）全在 [docs/conventions/question-authoring.md](docs/conventions/question-authoring.md)。要点：交付物放 `<出题交付目录>\`，**投放（写入 Syncthing 目录）由维护者执行，出题代理不碰**；扩表提议只交提议文件，规范登记由维护者侧执行。
