# 多指按键回归

`PointerMotionInstrumentation` 是标准 `InstrumentationTestCase` 测试类（不是 runner），
与 `CandidatePagingDeviceTest` 共用 `android.test.InstrumentationTestRunner`。
每例在主线程创建真实 `KeyboardView`，先 EXACTLY measure 1080×720，再 layout，
随后直接发送多指 `MotionEvent`；不启动 Activity，不向系统全局注入触摸。
长按与删除连发均手动调用已注册的 Runnable 模拟到期，**不验证真实计时**、
硬件触摸采样、系统手势拦截、真实录音或 IME 输入连接。

仅编译/打包，不连接或安装设备：

```powershell
./gradlew.bat :app:compileFullDebugAndroidTestKotlin :app:assembleFullDebugAndroidTest
```

以下设备命令仅供获得设备操作授权的执行者使用。务必加 class 过滤，以免意外运行
需要真实 Rime 环境的候选分页测试。正式 full APK 使用 `com.moqi.im`，安装会覆盖同包应用；
不要在用户日常使用设备上未经确认安装。可在独立构建副本更改 applicationId 做隔离验证，
不可将隔离包名提交到正式配置。

```powershell
./gradlew.bat :app:connectedFullDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.moqi.im.keyboard.PointerMotionInstrumentation
# 或者，应用和测试 APK 已安装后（隔离包须替换测试包名）：
adb shell am instrument -w -e class com.moqi.im.keyboard.PointerMotionInstrumentation com.moqi.im.test/android.test.InstrumentationTestRunner
```

标准 runner 应报告 `OK (8 tests)`；失败应检查 `FAILURES!!!` 和异常堆栈，不能只看
adb 进程退出码。编译成功不代表这八例已在设备上执行。

覆盖：旧指先抬、新指先抬、三指交叠、取消未完成按键、MOVE 使用正确指针坐标、
删除连发后的指针交接、长按空格松手、长按空格取消。

主机回归另有 `PointerRolloverTest`（9 例纯状态机）及 `KeyboardPointerMotionTest`
（11 例真实 View，包括 measure 前提、弹窗、滑动、侧栏独占和计时器交接）。
Robolectric 同样手动触发 Runnable，不等价于硬件计时实测。

```powershell
./gradlew.bat :app:testLiteDebugUnitTest :app:testFullDebugUnitTest
```
