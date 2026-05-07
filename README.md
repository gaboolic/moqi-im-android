# 墨奇输入法 Android版

墨染清风书雅韵，奇思逸趣入诗文。

Android 端的输入法前端，负责把 [`moqi-ime`](https://github.com/gaboolic/moqi-ime) 后端接入 Android `InputMethodService`，并提供自绘键盘、候选栏、编辑区、键盘内菜单、方案切换和语音输入等移动端能力。

当前状态：已接入 **[Rime / 中州韵](https://github.com/rime/librime)**，默认使用 **[白霜拼音](https://github.com/gaboolic/rime-frost)**，支持 26 键 / 9 键方案自动切换。

## 特色功能

- **本地输入引擎**：拼音、五笔等核心输入逻辑由 `moqi-ime` 提供，Android 侧只负责按键、候选和上屏。
- **默认白霜拼音**：内置白霜拼音方案数据，支持 Rime 候选、注释、整句和方案配置。
- **26 键 / 9 键自动布局**：根据当前 Rime schema 自动展示 26 键或中文九键，不需要单独的键盘切换按钮。
- **键盘内菜单**：点击 `...` 展开菜单，可切换输入状态、方案集、当前输入方案、系统输入法，并支持下载新的方案集。
- **下滑输入数字和标点**：QWERTY 字母键显示副字符，下滑可输入数字或常用标点。
- **长按空格键语音输入**：轻点空格仍输入空格；长按空格开始本地语音识别，松开空格时将编辑区里的识别文字上屏并停止聆听。
- **隐私安全**：输入、候选和语音识别均在本地处理，不上传用户输入内容。
- **深色模式**：候选区、编辑区和键盘支持跟随系统深色模式。

## 使用方式

1. 安装 APK。
2. 在系统设置中启用「墨奇输入法」。
3. 在输入法选择中切换到「墨奇输入法」。
4. 点击键盘底栏 `...` 可打开菜单，切换方案集或当前输入方案。
5. 长按空格键进行语音输入，松开后上屏当前识别结果。

## 输入效果展示

![墨奇输入法输入效果展示一](docs/f36d4011b63e2f5abfb2d249c48fb60b.gif)

![墨奇输入法输入效果展示二](docs/4a1d12986443191837a6b97e7d67661e.gif)


## 运行架构

```
┌─────────────────────────────────────────────┐
│              Android 应用 / 输入框          │
├─────────────────────────────────────────────┤
│  MoqiInputMethodService                     │
│  - Android InputMethodService 实现          │
│  - 处理按键、候选选择、上屏、语音入口        │
├─────────────────────────────────────────────┤
│  KeyboardView / CandidateView / ComposeView │
│  - 自绘键盘                                 │
│  - fcitx5 风格候选栏和编辑区                │
│  - 长按空格语音、下滑副字符、键盘内菜单      │
├─────────────────────────────────────────────┤
│  MoqiImeEngineRunner / MoqiImeSession       │
│  - 后台线程串行调用 Go mobile bridge        │
│  - 避免 Rime 处理阻塞 Android UI            │
├─────────────────────────────────────────────┤
│  moqi-ime.aar                               │
│  - gomobile bind 生成的 Go 后端 AAR          │
│  - 集成 librime.so 和 Rime 数据部署逻辑      │
└─────────────────────────────────────────────┘
```

## 与 `moqi-ime` 的关系

本仓库不实现拼音解析、候选生成、词库管理等输入法核心逻辑，这些能力由 `moqi-ime` 提供。

Android 侧的职责主要是：

- 把 Android 软键盘按键转换为 `moqi-ime` 请求。
- 根据后端返回结果更新候选栏、编辑区和上屏文本。
- 暴露方案集、schema 和输入状态菜单。
- 管理本地 Rime 数据目录、语音识别和移动端交互。

## 源码布局

```
app/src/main/java/com/moqi/im/
├── core/
│   └── MoqiInputMethodService.kt      # IME 核心服务
├── engine/
│   ├── MoqiImeBridge.kt               # gomobile bridge Kotlin 包装
│   ├── MoqiImeEngineRunner.kt         # 后台线程调用输入引擎
│   └── SherpaVoiceEngine.kt           # 本地语音识别
├── keyboard/
│   ├── KeyboardView.kt                # 自绘键盘
│   ├── CandidateView.kt               # 候选词栏
│   ├── ComposeView.kt                 # 编辑区 / 组合串显示
│   ├── KeyboardMenuView.kt            # 键盘内菜单
│   ├── KeyDefinition.kt               # 按键定义
│   └── KeyCode.kt                     # 特殊按键编码
├── settings/
│   ├── SettingsActivity.kt
│   └── SettingsFragment.kt
└── MoqiApplication.kt
```

## 构建

如果修改了 `moqi-ime` 或 mobile bridge，需要先重新生成 AAR：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-moqi-ime-aar.ps1
```

构建 Android debug APK：

```powershell
.\gradlew.bat assembleDebug
```

安装到已连接的 Android 设备：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## 友情链接

白霜拼音 <https://github.com/gaboolic/rime-frost>

墨奇音形 <https://github.com/gaboolic/rime-shuangpin-fuzhuma>

墨奇五笔整句 <https://github.com/gaboolic/rime-wubi-sentence>

## 许可证

MIT License