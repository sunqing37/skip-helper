# 项目架构

## 定位与环境

- Android 8.0+，无需 root；Android 11+ 可逐应用启用 OCR。
- 单一 `app` 模块；Kotlin 2.0.21、AGP 8.11.1、Gradle 8.13、JDK 17+、compile/target SDK 35、min SDK 26。
- Compose + Material 3；DataStore Preferences 保存版本化 JSON；Kotlin Serialization；ML Kit 内置中文和拉丁识别模型。
- 无服务器、账号、远程规则和联网权限。版本以 `app/build.gradle.kts` 为准，不在多个文档硬编码版本。

## 当前分层

```text
MainActivity / ui / BackgroundSettingsCard
  ├─ SettingsStore：StateFlow → Compose；配置修改 → DataStore
  └─ AppIconLoader：可见列表项加载，96 项 LRU

SkipAccessibilityService：前台会话、事件、生命周期、最终执行
  ├─ ScreenReader：窗口/控件快照；节点点击
  ├─ Matcher：通用与专属规则 → Candidate
  ├─ OcrReader：内存截图 → ML Kit → 同一 Screen/Candidate 通道
  ├─ ResidentNotification：现有无障碍服务的可选前台通知
  └─ SettingsStore：读取授权/配置，写入有限操作记录
```

- `MainActivity` 目前承载 UI 状态与事件编排，**尚未引入 ViewModel**，不要把现状描述为完整 MVVM。
- `data/Models.kt` 是持久化模型；`engine` 中的 `Screen/Element/Candidate` 是短生命周期快照，不持有 AccessibilityNodeInfo。
- 共享外观复用 `ui/DesignSystem.kt`；列表、概览和记录以纯参数回调组件组织。系统权限只由用户操作授权。
- 新功能若导致 Activity 状态明显膨胀，可在对应任务内渐进提取 ViewModel + Repository；本轮不做全工程架构迁移。

## 线程与生命周期

- 服务会话状态、取消、最终执行由主线程维护。窗口事件递增 generation；异步结果返回后校验 generation、session、授权、包名和窗口。
- 完整控件树读取使用 IO `limitedParallelism(1)`，扫描循环间隔至少 250ms；每次扫描最多 600 节点、40 层。不要跨线程共享或长期缓存节点对象。
- 前台元数据探测合并为最多每 250ms 一次；关闭总开关或未同意授权时不处理界面事件。元数据探测及最终节点点击仍涉及少量主线程 Binder 调用，不应宣称主线程完全没有 Binder。
- 截图转换和 OCR 在 Default 线程池；一帧最多两个识别器，单个 OcrReader 同时仅允许一个任务；模型任务结束后才释放 Bitmap，取消时仍回收结果。
- 图标按可见项加载，最多两个加载任务并行；96×96 像素、最多缓存 96 项。Compose 离屏任务取消后不再更新旧 UI，Bitmap 由 GC 管理，不手动回收仍可能展示的缓存图像。
- DataStore 在独立 IO scope 中串行事务写入；不使用 Activity context，避免后台工作持有页面。

## 保持的行为边界

- 所选应用进入前台后最多 15 秒；同 App 内跳转不延长。点击前再读取验证；每会话最多两次点击。
- OCR 逐应用授权、最多每秒一次、连续两次匹配；截图仅内存处理。不能确认则不点。
- 暂时失焦/控件树不可用只暂停；分屏、PiP、输入、滚动等按当前安全边界处理。不为了覆盖率去掉包名、窗口、禁用状态与过期结果校验。
- 最新 100 条操作记录不包含页面文字/截图；“已发送点击”“目标消失”不等于成功跳过。
- 无障碍绑定由系统控制；常驻通知不能保证永不被杀，也不能替用户重新授予权限。
