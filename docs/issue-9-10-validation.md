# Issues #9 / #10：已有修复覆盖与本次验证

核对基线：`671a89c`（已包含 PR #13、#14、#15、#16）。本 PR 不合并新功能请求，不自动关闭下列 issues。

## 已合并 PR 覆盖了什么

| 报告内容 | 既有 PR 是否覆盖 | 本次确认 |
| --- | --- | --- |
| [#9](https://github.com/gaboolic/moqi-im-android/issues/9)：快速跨键输入 `cai` 丢中间字母 | [#14](https://github.com/gaboolic/moqi-im-android/pull/14) 修复多指交叠漏键；[#16](https://github.com/gaboolic/moqi-im-android/pull/16) 修复异步回调及首键回退乱序 | 新增真实 KeyboardView 的 QWERTY `cai` 序列测试：两种抬指顺序、三指交叠和不交叠点击。#14 前源码负对照为 3 项断言失败、1 项通过，其中一个实际输出为 `ci`；当前代码 4 项全过。这确认了多指路径，并不证明鸿蒙设备所有可能的吞字原因都已排除。 |
| #9：已授权但语音失败；[#10](https://github.com/gaboolic/moqi-im-android/issues/10)：Release JNI 找不到 `decodingMethod` 字段 | **没有覆盖 R8 根因**。#16 修的是语音返回后的布局，不是识别器初始化 | 当前 main 的真实 Full Release R8 输出仍把 `OnlineRecognizerConfig.decodingMethod` 重命名为 `g`；本次新增保留规则与产物检查。#9 没有同类日志，不能断言其所有语音失败均由 R8 引起。 |
| #9：设置页/键盘半屏菜单配色不一致、夜间模式文字接近背景 | **没有覆盖** | 原代码把 Rime highlight 当设置底色，Preference 文本仍跟随系统 DayNight；菜单浅灰按钮也可能用白字。本次修复设置/菜单专属颜色角色，不修改键盘/候选皮肤。 |

[#13](https://github.com/gaboolic/moqi-im-android/pull/13) 是选区退格修复；[#15](https://github.com/gaboolic/moqi-im-android/pull/15) 是英文九键多击首字符/计时清理修复，不能直接等同于 #9 报告的中文 26 键场景。

## 本次：Release 语音 JNI 保留

[应用混淆规则](../app/proguard-rules.pro) 原来没有 Sherpa 保留规则；所带 [Sherpa AAR](../app/libs/sherpa-onnx-1.13.0.aar) 的消费规则为空。JNI 按原名字访问 Java 配置字段，仅保留 native 方法不足以保护这些字段。

本次保留 `com.k2fsa.sherpa.onnx.**` 全部类和成员。实际 R8 构建前后的检查如下：

- 修复前：Full Release minify 构建成功，但真实 mapping 中 `decodingMethod -> g`，与 #10 的初始化失败一致。
- 修复后：Full Release 与 Lite Release minify 构建成功；核对实际 AAR class 定义和匹配的 R8 mapping/DEX，122 个 Sherpa 类、380 个字段和 117 个 native 方法名均保留。
- Lite 不依赖 Sherpa；其 mapping/APK 未包含 Sherpa/ONNX 条目。
- [检查工具](../scripts/check_sherpa_jni_mapping.py) 不只检查规则字符串：读取真实 AAR、mapping 和 DEX，防止 R8 省略 identity mapping 时将缺失字段误判为通过。它检查名称，不是完整 JNI 调用或语音识别端到端验证。
- [工具回归测试](../scripts/tests/test_check_sherpa_jni_mapping.py) 覆盖保留、重命名、移除和二进制解析等 11 项用例。

```powershell
./gradlew.bat :app:minifyFullReleaseWithR8 :app:minifyLiteReleaseWithR8
python -B -m unittest discover -s scripts/tests -v
python -B scripts/check_sherpa_jni_mapping.py
```

[PR 构建流水线](../.github/workflows/build-apk.yml) 新增工具测试；签名 Release 构建后还会检查实际 R8 输出，再上传 APK，避免“编译通过但 JNI 名称损坏”的回归。

## 本次：设置与菜单对比度

新增 [ThemeSurfaceColors](../app/src/main/java/com/moqi/im/theme/ThemeSurfaceColors.kt)：设置和菜单共享所选主题的 candidate surface，独立计算 surface/button 的文字颜色；保留原主题前景色达到 4.5:1 时的颜色，否则选对比度更高的黑/白色。

- [设置 Activity](../app/src/main/java/com/moqi/im/settings/SettingsActivity.kt) 与 [Preference 行](../app/src/main/java/com/moqi/im/settings/SettingsFragment.kt) 应用同一 surface/前景色，覆盖标题、summary、分类和 disabled 文本，并在主题变化/返回页面时刷新。
- [半屏菜单](../app/src/main/java/com/moqi/im/keyboard/KeyboardMenuView.kt) 分开使用 surface 与 button 前景色，刷新保留的 header、URL/提示和新建按钮。
- 不修改原 [ThemePalette](../app/src/main/java/com/moqi/im/theme/ThemePalette.kt) 皮肤常量或键盘/候选绘制。部分设置/菜单背景从 highlight 改为 candidate 色；例如 Pepsi 采用蓝色 surface/白字，浅灰按钮使用深色字。
- [真实 UI 回归](../app/src/test/java/com/moqi/im/theme/ThemeSurfaceUiTest.kt)：11 个主题 × 日/夜 × 4 场景，共 88 项。baseline 有 85 项断言失败、0 个环境错误；修复后全通过。测试仅隔离主机缺失的 AndroidKeyStore 密码查询，不替换被测 Activity/Fragment/Preference/View。

## 合并分支本地验证

在上述两类修复与 `cai` 回归组合后：Lite / Full 各 **206 项单元测试通过**（0 failure/error/skip）；两个 Debug APK、Full 设备测试 APK、Full/Lite Release R8 构建均成功；Python 检查工具 11 项测试通过，实际 Full R8 mapping/DEX 契约检查通过。Lite/Full 重复运行同一组用例，不应算作 412 个不同场景。

## 验证范围与仍需确认

- [输入序列回归](../app/src/test/java/com/moqi/im/keyboard/KeyboardPointerMotionTest.kt) 验证真实 View 的模拟 MotionEvent，不是硬件触摸采样、鸿蒙事件拦截或实际输入引擎压力测试。
- 本轮无连接的真机，未执行实际模型加载、麦克风识别、HarmonyOS 截图或 OEM 最终像素验证。
- JNI 名称保留修复针对 #10 的明确堆栈；权限、模型缺失、音频设备、其他 JNI 版本等不同语音失败仍需各自日志。
- 对比度测试覆盖默认/disabled 文本与主题刷新，不承诺系统对话框、OEM 样式或瞬时按下 ripple 的全部状态。
- 因此 PR 使用关联说明而非自动关闭 #9/#10，等待维护者和报告者验证。
