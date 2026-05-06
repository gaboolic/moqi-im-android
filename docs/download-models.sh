#!/bin/bash
# 下载 Sherpa-onnx 语音模型脚本
# 用法: ./download-models.sh

set -e

MODELS_DIR="../app/src/main/assets/models/sherpa"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2"
MODEL_FILE="model.tar.bz2"

echo "=== 墨奇输入法 - 语音模型下载脚本 ==="
echo ""

# 创建目录
mkdir -p "$MODELS_DIR"

# 检查是否已下载
if [ -f "$MODELS_DIR/encoder.onnx" ]; then
    echo "✓ 模型文件已存在，跳过下载"
    echo "  位置: $MODELS_DIR"
    exit 0
fi

echo "正在下载语音模型（约 50MB）..."
echo "来源: $MODEL_URL"
echo ""

# 下载模型
curl -L -o "$MODEL_FILE" "$MODEL_URL" --progress-bar

# 解压
echo ""
echo "正在解压模型..."
tar -xjf "$MODEL_FILE" -C "$MODELS_DIR" --strip-components=1

# 清理
rm "$MODEL_FILE"

echo ""
echo "✓ 模型下载完成"
echo "  位置: $MODELS_DIR"
echo "  文件:"
ls -lh "$MODELS_DIR"

echo ""
echo "提示: 模型已放入 assets 目录，构建 APK 时会自动集成"
echo "      下次构建: ./gradlew assembleDebug"