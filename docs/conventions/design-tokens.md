# 知练设计语言规范(间距 · 圆角 · 字体)

> **定位**:本文档规范知练前端三类设计 token 的取值与落地方式,与 [ADR-0005](../adr/0005-fixed-mist-blue-palette-and-unified-cards.md) 互补——ADR 定「为什么固定雾蓝 + 统一卡片」,本文档定「间距 / 圆角 / 字体的具体档位值表与落地约定」。
>
> **不覆盖**:颜色(见 ADR-0005 雾蓝色板)、动效、阴影高度(ADR-0005 已定卡片 2dp 柔和阴影)。
> **不进 CONTEXT.md**:设计 token 属实现细节,非业务领域词汇,不写入业务词汇表。
> **纯文字规范**:本文档全程用数值表与文字,不依赖任何示意图。

## 一、概述与决策依据

知练前端原有三类设计值长期以字面量散落:`.dp` 间距 76 处、`RoundedCornerShape(...dp)` 圆角 2 处字面量、`MaterialTheme.typography.*` 字体引用 57 处但 Theme.kt 从未传 `typography`(全回落 M3 默认)。本规范把三类值收口为命名 token,供后续 `Type.kt` / `Shape.kt` / `Spacing.kt` 实现。

**决策依据**(不单开 ADR,在此留痕):

- **为何 8 基准**:现有间距值 8/12/16/24 占 84%,主流已是 8 倍数;以 8 为节奏基准改动最小、迁移最顺。
- **为何保留 12**:12 是 Material3 组件(选项行、FilterChip 等)常用值,弃则与库默认圆角/内边距打架;保留 12 兼顾节奏与库习惯。
- **为何 small 复用 8**:项目当前无小于 8dp 的圆角需求(`extraSmall`/`small` 无需区分),YAGNI,复用 8 避免空档。
- **为何沿用系统默认字体（FontFamily.Default，跟随设备）**:不引入字体资源(离线 + 体积 + 维护成本最低,合 [ADR-0002](../adr/0002-ui-style-material-you.md)「避免重型依赖、个人自用维护成本最低」);只补 `Type.kt` 按知练语感定字重对比与关键档字号,57 处现有引用零改动即落地。
- **为何不进 CONTEXT.md / 不单开 ADR**:token 值表本质可迭代 convention,非架构级不可逆决策;ADR-0005 已记更根本的「固定雾蓝 + 具名卡片」,本文档开头留痕权衡即可,不臃肿 ADR 目录。

## 二、间距 Spacing

### 2.1 尺度档(8 基准)

| token | dp | 用途 |
|---|---|---|
| `xs` | 4 | 控件内微距(判断题选项内 padding) |
| `sm` | 8 | 元素小间距、紧凑列表行距、进度条顶距(归并自 6) |
| `md` | 12 | 紧凑卡内边距(列表卡)、元素中间距 |
| `lg` | 16 | 卡内边距(默认)、屏边距(默认)、元素大间距 |
| `xl` | 24 | 卡间距、屏底距、解析面板底距(归并自 32) |

### 2.2 语义别名

别名只给真正高频复用点,克制以避免「每 dp 起名」成浅封装。

| 别名 | 等于 | 用途 |
|---|---|---|
| `screenEdge` | `lg`(16) | 屏级 Column 水平边距(默认) |
| `cardInner` | `lg`(16) | ZhilianCard 默认卡内边距 |
| `cardInnerCompact` | `md`(12) | 紧凑列表卡(题库 / 批次)内边距 |
| `stackGap` | `md`(12) | 元素间距默认;密集场景用 `sm`(8) |

### 2.3 异值归并

> 解析面板形态见 [ADR-0007](../adr/0007-explanation-half-modal-panel.md);下表把原散落值归并到 token。

| 现值 | 出现位置 | 归并到 |
|---|---|---|
| `14.dp` | PracticeHomeScreen(2 处 spacedBy) | `lg`(16) |
| `20.dp` | PracticeSessionScreen(题卡 / 解析面板内边距) | `cardInner` = `lg`(16) |
| `32.dp` | PracticeSessionScreen(解析面板底距) | `xl`(24) |
| `6.dp` | PracticeSessionScreen(进度条顶距) | `sm`(8) |

### 2.4 例外与边界

- **练习页卡片流通栏**（[ADR-0003](../adr/0003-practice-card-pager.md) 修订段,2026-09-30）:**一屏只呈现本题卡片**。屏边距取 **0**（卡片顶到屏幕左右缘,正文的左右 16dp 由卡片内边距 `lg` 承担,故正文仍与全 App 屏边距对齐）;页间距取 `lg`(16)——**页间距大于屏边距**,相邻卡即彻底移出屏幕;进度区水平内边距与卡片内文对齐（同为 `lg`）。此前的「peek 露边 24dp、不纳入 token」例外条款随之作废。
- **不纳入间距 token**:边框宽度(`1.dp`)、卡片阴影高度(`2dp`,ADR-0005 已定)、`elevation`——沿用 ADR-0005,不另设 token。
- **窗口避让**:状态栏 / 小白条 / 输入法避让走 `WindowInsets`,不属间距 token,见 [edge-to-edge.md](./edge-to-edge.md)。

## 三、圆角 Shapes

### 3.1 M3 Shapes 槽位

对齐 Material3 `Shapes` 五槽,值与现有具名组件一致,补 8 / 20 供扩展。

| 槽 | dp | 用途 |
|---|---|---|
| `extraSmall` | 8 | 与 `small` 复用(项目无更小需求) |
| `small` | 8 | 预留(小元素) |
| `medium` | 12 | ZhilianOptionRow 选项行 |
| `large` | 16 | ZhilianCard 主卡 |
| `extraLarge` | 20 | 预留(半模态面板等大容器) |

### 3.2 组件归属

| 组件 | 取 | 现值 |
|---|---|---|
| `ZhilianCard` | `MaterialTheme.shapes.large` | 16dp |
| `ZhilianOptionRow` | `MaterialTheme.shapes.medium` | 12dp |

代码块圆角沿用库 `LocalMarkdownDimens.current.codeBackgroundCornerSize`,非本项目 token,不在本规范范围。

## 四、字体 Typography

### 4.1 档位规范(只列被引用的 11 档)

全库共 57 处 `MaterialTheme.typography.*` 引用,集中在以下 11 档;未引用的 `displayLarge/Medium/Small` 留 M3 默认,不指定。

| 档 | sp | 字重 | 行高(sp) | 用途 |
|---|---|---|---|---|
| `bodyLarge` | 16 | Normal | 24 | 摘要正文 |
| `bodyMedium` | 14 | Normal | 20 | 题干预览 / 解析正文 / 列表项 |
| `bodySmall` | 12 | Normal | 16 | 来源 / 提示 / badge 辅助 |
| `titleLarge` | 22 | Medium | 28 | 反馈横幅(答对 / 答错) |
| `titleMedium` | 16 | Medium | 24 | 卡标题 / 选项序号 |
| `titleSmall` | 14 | Medium | 20 | 详情小标题 |
| `headlineMedium` | 28 | Medium | 34 | 统计数字 / 题量值 |
| `headlineSmall` | 24 | Medium | 30 | 结尾卡标题 |
| `labelLarge` | 14 | Medium | 20 | section 标签 |
| `labelMedium` | 12 | Medium | 16 | 分类 / 题型 / 状态标签 |
| `labelSmall` | 11 | Medium | 16 | badge(错题 / 收藏) |

> 行高为建议初值(正文约 1.4–1.5×、标题约 1.2–1.3×),实现后按真机语感微调。

### 4.2 字重对比约定

- **Normal**:`body*` 正文。
- **Medium**:`title*` / `headline*` / `label*` 标签与标题。
- **Bold(强调)**:仅用于三类场景——**统计数字**、**会话得分**、**错题标记**;在对应 typography 档上调用方覆写 `FontWeight.Bold`。全库 `FontWeight.Bold` 限定这三类,禁止散落。

### 4.3 落地状态

`Theme.kt` 已传 `typography` / `shapes`（`ZhilianTypography` / `ZhilianShapes`），全库 `MaterialTheme.typography.*` 引用即落到本规范各档。此前「typography 缺失、57 处引用全回落 M3 默认」的表述已作废。

## 五、落地约定

> 本章为**落地记录与取值依据**:三份 token 文件已存在并在用,本文档此后是这三类值的**取值权威**——代码与本文档不符时,改代码并同步本文档。

### 5.1 文件与暴露

| 文件 | 内容 | 暴露方式 |
|---|---|---|
| `ui/theme/Spacing.kt`(新建) | `object ZhilianSpacing { … }` | 直接对象属性 `ZhilianSpacing.lg` 等 |
| `ui/theme/Shape.kt`(新建) | `val ZhilianShapes = Shapes(…)` | 经 `MaterialTheme.shapes` |
| `ui/theme/Type.kt`(新建) | `val ZhilianTypography = Typography(…)` | 经 `MaterialTheme.typography` |
| `ui/theme/Theme.kt`(改) | `MaterialTheme` 增传 `typography` / `shapes` | — |

### 5.2 骨架代码

**Spacing.kt**
```kotlin
object ZhilianSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    // 语义别名(克制,只给高频复用点)
    val screenEdge get() = lg
    val cardInner get() = lg
    val cardInnerCompact get() = md
    val stackGap get() = md
}
```

**Shape.kt**
```kotlin
val ZhilianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
```

**Type.kt**(行高为初值,可微调)
```kotlin
val ZhilianTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    // displayLarge/Medium/Small 未用,留 M3 默认不指定
)
```

**Theme.kt 改动**
```kotlin
MaterialTheme(
    colorScheme = colorScheme,
    typography = ZhilianTypography,
    shapes = ZhilianShapes,
    content = { … },
)
```

**ZhilianCard.kt / ZhilianOptionRow.kt 改读主题**
```kotlin
// ZhilianCard:      shape = RoundedCornerShape(16.dp)  →  shape = MaterialTheme.shapes.large
// ZhilianOptionRow: shape = RoundedCornerShape(12.dp)  →  shape = MaterialTheme.shapes.medium
```

### 5.3 迁移顺序(由安全到风险递增)

1. **Typography**:新建 `Type.kt` + Theme.kt 传 `typography`。**57 处引用零改动**,视觉无突变(先以本表值逐档对齐现有观感),最安全,先做。
2. **Shapes**:新建 `Shape.kt` + Theme.kt 传 `shapes` + ZhilianCard / ZhilianOptionRow 改读 `MaterialTheme.shapes.large` / `.medium`。**2 处改动**,值与现有一致,视觉零变化。
3. **Spacing**:新建 `Spacing.kt` + 逐文件把 76 处 `.dp` 字面量替换为 `ZhilianSpacing.*` / 语义别名,并按 2.3 归并异值(14→16、20→16、32→24、6→8)。改动面最大,放最后,逐文件提交通道。

### 5.4 测试面

- `ZhilianSpacing` / `ZhilianShapes` / `ZhilianTypography` 均为纯数据对象,可单测断言各档值;
- ZhilianCard / ZhilianOptionRow 通过其公开 interface 测试(传入状态验证圆角 / 边框派生),不绕到内部;
- 字重 Bold 仅三类场景,可在调用点 grep `FontWeight.Bold` 断言不超范围。

## 六、验收清单

- [x] 三维档位表完整(间距 5 档 + 4 语义别名 / 圆角 5 槽 / 字体 11 档)
- [x] 异值归并表覆盖 14 / 20 / 32 / 6
- [x] 字重三档对比明确,Bold 限定三类场景
- [x] 落地约定含 Type.kt / Shape.kt / Spacing.kt 骨架 + Theme.kt 传参 + 迁移顺序
- [x] 决策依据段记录 5 个「为何」
- [x] 练习页卡片流的屏边距/页间距取值与「不露相邻卡」的关系有说明(ADR-0003 修订段)
- [x] 不抄颜色、不进 CONTEXT.md、不单开 ADR
- [x] 纯文字 + 表格,无图

---

*本文档是间距 / 圆角 / 字体三类 token 的取值权威;代码改动若与本文档不符,以本文档为准并同步更新。*
