# 结构与验证范围

```text
app/src/main/java/cn/local/mijiabattery/  Hook、温度判断、状态页
app/src/main/AndroidManifest.xml         APK与LSPosed元数据
app/src/main/assets/xposed_init          旧版Xposed API入口
app/src/main/res/values/arrays.xml       推荐作用域
module/source.sh                        原始节点与来源检查
module/service.sh                       校验、采样发布和有效期
module/power-events.c                   AArch64 内核供电事件监听与采样节拍
module/customize.sh                     安装版本检查
scripts/build.py                        跨平台构建
tests/LogicCheck.java                    无Android依赖的判断检查
```

采样脚本通过单个 Global Settings 键原子发布会话编号、时间戳、温度和来源。手机管家的监听器只在首次有效读数、温度变化或规则变化时评估规则。读数超过 45 秒失效；拔电或来源校验失败时发布无效状态。

米家新增条件使用独立实例前缀及大于100的本地编码。手机管家的普通电量分发跳过这些实例，专用处理在首次满足或温度跨越时调用已有场景通道。云端仍使用既有手机电量属性，因此同一规则中温度与电量条件不能并用。

0.2.1-preview 把重新读取的手机改版0.2.0行为合并回可编译源码：仅充电采样、特定伪装兼容分支、首次有效且满足条件时触发。状态页和文档已对齐；临时 APK 的 `/data/local/tmp` 调试追加代码未带入。发布流程不替换手机当前安装。

历史实机曾确认未覆盖的 battery 节点与模块发布值连续一致。最近一次读取时，伪装模块已不在模块列表和原目录，采样走无该模块的交叉检查分支。

源码构建包括温度比较、首次触发、无效输入及编码检查。**尚未完成米家云端保存、真实通知动作、熄屏延迟和完整场景联动验证。** 节点原始性不等于独立硬件测温校准，不能据此声称可靠的温控保护。
