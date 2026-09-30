# 预测性返回停用；二级页保留经典水平侧滑（不跟手、不淡入淡出）

**状态：已撤销（2026-09-29）。** 本文早先记录的「微信/iOS 风跟手侧滑 + 下层视差」方案已废弃，最终决策如下。

## 最终决策

1. **`enableOnBackInvokedCallback="false"`**：全 App 关闭预测性返回（predictive back），不做手势跟手 seek，也不接受系统 back-to-home 的跟手动画。返回走经典 `onBackPressed` 派发。
2. **二级页转场保留经典水平滑动**（不是 NavHost 默认 crossfade——维护者明确不接受淡入淡出）：
   - push：新页从右侧全屏滑入（`slideInHorizontally`，300ms）；
   - pop：当前页向右全屏滑出（`slideOutHorizontally`）；
   - **下层页完全静止**（`EnterTransition.None`/`ExitTransition.None`），不做位移、不做缩放、不做淡变；
   - 不配 `predictivePopEnterTransition/predictivePopExitTransition`。
3. **会话页退出确认框保持移除**（此为独立于预测性返回的交互决策，ADR-0003 的修订仍然有效）：手势/按键返回直接 pop 会话页、播放上述侧滑；结尾卡「完成」按钮同样直退。页面级不写 `BackHandler`。
4. **解析面板返回**：面板打开时由 M3 `ModalBottomSheet` 内部的返回处理优先消费（关闭面板），关闭后返回才到达 NavHost pop 会话页。经典派发路径下该优先级仍成立。

## 为什么撤销跟手方案

试验过 Navigation Compose 2.10 的 `predictivePop*` + `SeekableTransitionState` 视差实现，真机暴露两个不可接受点：

- **无法做左右镜像**：期望「左边缘返回上一页挤入右侧、右边缘返回挤入左侧」，但 NavHost 托管转场在 pop 方向上只给出固定的「上层右移」一套动画，边缘信息（`BackEventCompat.swipeEdge`）在 NavHost 转场 lambda 这一层取不到；要真镜像必须脱离 Nav 托管、自建共享容器手势状态机，复杂度与收益不匹配。
- **松手瞬间闪烁**：手势 seek 阶段与提交后的 tween 收尾在透明页面（Scaffold 容器透明、页面叠在雾蓝背景层上）上发生交接，叠加下层视差位移/缩放时出现可见闪动。暂停手势虽能看到层叠效果，但动态交接无法做到干净。

结论：个人自用、返回节奏以天计，不值得为跟手效果维护自绘状态机。经典单次侧滑动效确定、无交接闪烁、实现零维护成本，且满足「不要缩小居中、也不要淡入淡出」两个核心诉求。

## 边界

- tab 间主动点击切换的方向感知横滑（`tabDestination`，含 1/4 位移 + 淡变）不受本 ADR 影响、维持原状。
- 若未来重新尝试预测性返回，前置条件是先验证左右镜像可行性与透明背景下的 seek→tween 交接，再单开 ADR，不要在本文件上复活原方案。
