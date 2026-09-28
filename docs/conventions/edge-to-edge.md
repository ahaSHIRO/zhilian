# Edge-to-Edge（沉浸式）规范

状态栏与小白条（手势导航条）均沉浸：系统栏透明，内容绘制到系统栏背后，由 App 自行避让。本项目 targetSdk 37（Android 17），系统**强制** edge-to-edge，旧式 `fitsSystemWindows` / `setDecorFitsSystemWindows(true)` 退出通道已移除，全项目按本规范统一，禁止任何绕过。

## 全局规则

1. **入口一次性开启**：`MainActivity.onCreate` 中、`setContent` 之前调用 `androidx.activity.enableEdgeToEdge()`。全 App 仅此一处，禁止在其他地方重复设置系统栏样式。
   - MainActivity 的这次调用只负责开启沉浸窗口；**图标深浅色不依赖它的默认行为**，由 `ZhilianTheme` 统一接管（见下文「深浅色与对比度」）。
2. **禁止硬编码避让**：不允许写死状态栏高度（24dp）或导航条高度（48dp/16dp），一切通过 `WindowInsets` 读取。
3. **禁止双重避让**：避让只发生一次——要么容器（Scaffold/顶栏/底栏）消费，要么内容手动 `windowInsetsPadding`，不允许叠加导致大面积空白。

## 避让分工（Compose 标准做法）

| 区域 | 谁负责 | 做法 |
|---|---|---|
| 状态栏 | 各屏 Scaffold + 顶栏 | Scaffold 默认 `contentWindowInsets` 已含 `safeDrawing`；带 TopAppBar 的屏自动避让 |
| 小白条 | 底部导航栏 | `NavigationBar` 默认 `windowInsets = NavigationBarDefaults.windowInsets`，自动避让并染底色，勿覆盖 |
| 无底栏的屏 | 屏内容 | `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` 或 Scaffold `innerPadding` |
| 输入法 | 文本输入区 | 填空题输入处 `Modifier.imePadding()`；提交按钮贴 IME 上缘时用 `imePadding()` 而非 `navigationBarsPadding()` 叠加 |
| 底部弹层 / 半模态面板 | ModalBottomSheet | 组件自身处理导航条 inset，不在 content 里再加 navigationBarsPadding |

## 屏幕级约定

- **一题一屏（练习页）**：整页外层 Column 用 `Modifier.imePadding()` + `windowInsetsPadding(WindowInsets.navigationBars)` 避让键盘与小白条；题卡内部滚动区另加 `windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))`，保证键盘与小白条两种状态下题干与选项都不被遮挡。提交按钮在题卡滚动区内（与题目同屏），不单独固定底部。
- **列表屏（题库/错题）**：内容允许滚到状态栏底下制造沉浸感——给列表 `contentPadding = innerPadding`（而不是 padding 容器），滚动时内容穿过透明状态栏，静止首项仍可见。
- **解析半模态面板**（ADR-0007）：`ModalBottomSheet` 自带 safeDrawing 处理；面板内内容只需正常滚动，底部留足 padding 即可，不在 content 里再加 `navigationBarsPadding`。

## 深浅色与对比度

- 系统栏图标深浅色**跟随 App 主题**而非系统深色模式：`ZhilianTheme` 内 `LaunchedEffect(darkTheme)` 重复调用 `enableEdgeToEdge(statusBarStyle, navigationBarStyle)` 同步（全透明 scrim）。MainActivity 的初始 `enableEdgeToEdge()` 只负责启动窗口，样式由主题接管。
- 禁止在任何屏幕单独设置系统栏样式；主题切换即全局生效。
- 若未来某屏顶栏使用 `primary` 等深色背景且状态栏图标为深色导致对比不足，属设计问题，优先调整屏内配色而非系统栏。

## 其他系统栏相关

- 预测性返回：Manifest 中 `android:enableOnBackInvokedCallback="true"`，禁止自定义 `onBackPressed` 拦截（首版练习页退出确认用 Compose 内 `BackHandler` + 对话框实现）。
- 挖孔屏：默认不特殊处理（`WindowInsets.safeDrawing` 已含 display cutout）。

## 验收清单

- [ ] 无设备帧内出现状态栏底色或渐变遮罩
- [ ] 小白条区域：列表内容可见但可滚动穿过，底部导航不与其重叠
- [ ] 弹出键盘：输入框可见，提交按钮贴键盘上缘
- [ ] 深浅色切换：状态栏图标颜色自动翻转正确
