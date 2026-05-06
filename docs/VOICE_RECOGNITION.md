# 墨奇输入法 - 语音识别功能

## 概述

墨奇输入法支持语音输入功能，当前实现基于 Android 原生 SpeechRecognizer（需要设备支持）。

同时预留了 **Sherpa-onnx** 本地离线语音识别接口，支持完全离线的语音转文字，无需联网。

## 当前状态

### ✅ 已实现
- 语音模式键盘界面（返回键盘/切换模式/退出语音）
- Android SpeechRecognizer 集成（需要设备有 Google App 或其他语音识别引擎）
- 麦克风权限检查
- 语音识别结果自动提交到输入框

### 📝 待完成（Sherpa-onnx 离线识别）
- 模型文件集成（约 50-100MB）
- Sherpa-onnx 引擎初始化
- 本地音频录制与解码

## 模型下载

### 推荐模型（中文+英文）

| 模型名称 | 大小 | 语言 | 下载链接 |
|---------|------|------|---------|
| sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16 | ~50MB | 中文、英文 | [下载](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2) |
| sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23 | ~14MB | 中文 | [下载](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2) |
| sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 | ~100MB | 多语种 | [下载](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2) |

### 更多模型

完整模型列表请访问：
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models

### 模型格式

Sherpa-onnx 使用以下文件：
```
model/
├── encoder.onnx      # 编码器模型
├── decoder.onnx      # 解码器模型
├── joiner.onnx       # Joiner 模型（Transducer）
└── tokens.txt        # 词表文件
```

## 手动集成步骤

### 1. 下载模型

```bash
# 下载推荐的中文模型
curl -L -o model.tar.bz2 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2"

# 解压
tar -xjf model.tar.bz2
```

### 2. 放置模型文件

将解压后的模型文件放入应用 assets 目录：

```
app/src/main/assets/
└── models/
    └── sherpa/
        ├── encoder.onnx
        ├── decoder.onnx
        ├── joiner.onnx
        └── tokens.txt
```

### 3. 修改 build.gradle

在 `app/build.gradle.kts` 中启用 Sherpa-onnx 依赖：

```kotlin
dependencies {
    // ... 其他依赖
    implementation("com.github.k2-fsa:sherpa-onnx:v1.12.1")
}
```

在 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

### 4. 修改 SherpaVoiceEngine

取消注释 `startSherpaOnnxRecognition()` 方法中的代码，完成 Sherpa-onnx 集成。

## 代码结构

```
app/src/main/java/com/moqi/im/
├── engine/
│   ├── SherpaVoiceEngine.kt      # 语音引擎（当前使用 SpeechRecognizer）
│   └── VoiceEngine.kt             # 旧版语音引擎（已废弃）
├── voice/
│   └── ModelManager.kt            # 模型管理器（预留）
└── core/
    └── MoqiInputMethodService.kt  # IME 服务（语音模式入口）
```

## 语音模式使用

1. 在键盘上点击 **🎤** 按钮进入语音模式
2. 点击 **🎤 语音输入** 开始录音
3. 说话后自动识别并提交文字
4. 点击 **返回键盘** 退出语音模式

## 注意事项

- **权限**：首次使用需要授予麦克风权限
- **离线识别**：Sherpa-onnx 完全本地运行，不上传任何数据
- **模型大小**：集成模型后 APK 会增加 50-100MB
- **性能**：建议使用 14M 以上的模型以获得较好识别效果

## 参考链接

- Sherpa-onnx 官方文档：https://k2-fsa.github.io/sherpa/onnx/
- GitHub 仓库：https://github.com/k2-fsa/sherpa-onnx
- Android API 示例：https://github.com/k2-fsa/sherpa-onnx/tree/master/android