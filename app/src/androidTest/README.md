# 多指按键回归

`PointerMotionInstrumentation` 使用 Android 自带的 `Instrumentation`，不增加依赖。
它在主线程创建并布局真实 `KeyboardView`，直接发送多指 `MotionEvent`；不启动宿主
Activity，不向系统全局注入触摸。长按与删除连发通过调用已注册的回调模拟定时器到期，
因此不验证实际时间间隔、硬件触摸采样或系统手势拦截。

仅编译测试，不连接或安装设备：

```powershell
./gradlew.bat :app:compileFullDebugAndroidTestKotlin
```

需要执行时，在测试设备上安装完整版本及测试 APK，并启动已配置的 runner：

```powershell
./gradlew.bat :app:connectedFullDebugAndroidTest
```

也可以在 APK 已安装后直接运行：

```powershell
adb shell am instrument -w com.moqi.im.test/com.moqi.im.keyboard.PointerMotionInstrumentation
```

成功结果为 `result=PASS: 8 cases` 和 `INSTRUMENTATION_CODE: -1`；失败结果包含异常堆栈，
结果码为 `0`。这不是 AndroidJUnitRunner；手动执行时应检查结果字段，不能只看 adb 进程退出码。

覆盖：旧指先抬、新指先抬、三指交叠、取消未完成按键、MOVE 使用正确指针坐标、
删除连发后的指针交接、长按空格松手、长按空格取消。另有 `PointerRolloverTest`
覆盖纯状态机，可用 `./gradlew.bat :app:testFullDebugUnitTest` 运行。
