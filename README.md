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
- **隐私安全**：输入、候选在本地处理；语音在设备本地识别（Sherpa），不上传音频。云剪贴板**默认关闭**，开启后仅将纯文本同步到您自建的 WebDAV（HTTPS + 基本认证）。
- **深色模式**：候选区、编辑区和键盘支持跟随系统深色模式。

## 使用方式

1. 安装 APK。
2. 在系统设置中启用「墨奇输入法」。
3. 在输入法选择中切换到「墨奇输入法」。
4. 点击键盘底栏 `...` 可打开菜单，切换方案集或当前输入方案。
5. 长按空格键进行语音输入，松开后上屏当前识别结果。

## 输入效果展示

### 26 键输入

26 键布局会展示下滑副字符，字母键可直接输入拼音，底栏集成符号、数字、中英切换、回车和长按空格语音入口。

![26 键输入界面](docs/moqi-qwerty-26.png)

### 9 键输入

在白霜方案集中选择九键拼音方案后，键盘自动变为中文九键布局；左侧侧栏显示可选拼音，候选栏同步展示 Rime 候选。

选择「九宫格笔画」可切换到笔画键盘。按书写顺序点按 `一`（横）、`丨`（竖）、`丿`（撇）、`丶`（点或捺）、`𠃋`（折或钩），再点候选上屏。例如，`一丨丿丶` 可以输入「木」。轻点 `6` 是「通配」，每字可代替一笔不确定的笔画；轻点 `7` 是「分词」，确认当前候选并继续输入下一字，不立即上屏。下滑仍输入数字 `6`、`7`。

![9 键输入界面](docs/moqi-t9-keyboard.png)

![9 键候选与拼音侧栏](docs/moqi-t9-candidates.png)

### 14 键全拼

选择「白霜十四键」。同一键上的两个字母轻点时都参与全拼匹配，不需要连按切字母。例如，输入「你好」依次点 `BN UI GH AS OP`。候选注释显示实际拼音；需要明确音节边界时下滑逗号键。字母键下滑可输入键帽上方的数字或符号；切换到英文仍使用原来的英文布局。

### 数字与符号

底栏 `123` 和 `符` 可快速进入数字键盘和符号键盘。符号键盘按常用、英文、中文、网络分组，适合移动端快速输入标点和网址片段。

![数字键盘](docs/moqi-number-keyboard.png)

![符号键盘](docs/moqi-symbol-keyboard.png)

### 键盘内菜单与方案切换

点击候选栏左侧 `...` 打开墨奇菜单，可切换输入状态、方案集、当前输入方案，也可以进入系统输入法选择和设置页。

![键盘内菜单](docs/moqi-keyboard-menu.png)

![方案集与输入方案切换](docs/moqi-keyboard-menu-schema.png)

### 设置、主题与自定义布局

设置页提供 Rime 共享配置目录、主题、键盘高度、按键音效、振动等选项。主题列表可一键切换键盘配色。

![设置页](docs/moqi-settings.png)

![键盘设置](docs/moqi-keyboard-settings.png)

![主题切换](docs/moqi-theme-picker.png)

最后一行布局支持拖动调整，26 键、123 数字键盘和符号键盘都可以分别配置，只影响每个键盘的最后一行。

![最后一行布局调整](docs/moqi-bottom-row-layout.png)

### 语音入口

支持长按空格触发本地语音输入；full 版本会在空格键显示麦克风标识，提示当前构建已启用语音能力。

![语音输入入口](docs/moqi-voice-input.png)

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

日常 Android 构建使用仓库自带的 AAR。修改 `moqi-ime`、mobile bridge 或 Rime 方案数据时，按 [Android Rime 数据包](docs/rime-data-bundle.md) 的步骤重新生成 AAR。

构建 Android debug APK：

```powershell
.\gradlew.bat assembleDebug
```

安装到已连接的 Android 设备：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## 隐私与云剪贴板

- **语音输入**：使用设备本地 Sherpa 模型识别，音频不会上传到第三方服务器。
- **云剪贴板（可选，默认关闭）**：在设置中配置 WebDAV 地址、用户名与密码（密码加密保存在本机）。开启后，仅在输入法窗口可见时，将您复制的**纯文本**按最小间隔上传至 WebDAV 上的 `{设置目录}/clip/`（设置目录默认 `moqi-input-method/`，词库同步将使用同级的 `dict/`）。系统标记为敏感的内容、掩码密码样式等会尽力跳过，避免与下载上屏形成循环上传。
- **多端协同**：多端添加同一 WebDAV，即可与手机互相同步剪贴板文件（复制/粘贴文件内容即可）。

## 友情链接

白霜拼音 <https://github.com/gaboolic/rime-frost>

墨奇音形 <https://github.com/gaboolic/rime-shuangpin-fuzhuma>

墨奇五笔整句 <https://github.com/gaboolic/rime-wubi-sentence>
