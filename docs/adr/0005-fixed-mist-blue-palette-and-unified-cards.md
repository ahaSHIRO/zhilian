# 弃 Material You 动态取色，改固定雾蓝配色与统一卡片样式

知练弃用 `dynamicLight/DarkColorScheme`（跟随壁纸取色），改用项目内置的固定"雾蓝/灰蓝" ColorScheme。同时抽出 `ZhilianCard`（主容器卡，16dp 圆角 + 2dp 柔和阴影 + 白底）与 `ZhilianOptionRow`（选项行，12dp 圆角 + 无阴影 + 边框色按 选中/揭示/正误 派生）两个具名组件，统一各屏原本散落、参数不一的 `Card` 调用。

配色三色分工：primary `#4A7AA8`（淡蓝，白底文字对比 4.53 达 WCAG AA，兼可读与淡蓝感）、primaryContainer `#9db7d4`（雾蓝填充层，配深蓝 `#0E2238` 文字对比 7.79）、accent `#95feff`（透亮青蓝，仅作进度条/icon 等小面积点缀，不要求文字对比）。背景由纯色改为错落的蓝色 radialGradient 光雾层（`radialGradient` 衰减本身即高斯式柔和），Scaffold 底透明让光雾透出，卡片不透明白底在光雾上悬浮。

理由：动态取色随壁纸不可控，实测出现整屏偏粉等非预期色调；维护者偏好简洁可控的品牌色。固定雾蓝兼顾"淡蓝/透蓝"的审美与可读性，三色各司其职（主色撑骨架、雾蓝作填充、透亮青作点缀）避免单一亮色既不可读又寡淡。卡片样式集中到两个组件，消除各屏圆角/阴影/边框参数漂移；圆角/间距/字体 token 规范见 [docs/conventions/design-tokens.md](../conventions/design-tokens.md)。背景光雾层替代死板纯色，符合 minimalist 的"soft radial light spots"背景深度，零图片资源、静态层不影响滚动性能。

不补 RenderEffect 高斯模糊：`radialGradient` 的衰减曲线已天然柔和如雾，多数场景足够；真机若觉不够散，可作为后续增强项再加（API 31+ 的 RenderEffect 已可用，留作可选）。
