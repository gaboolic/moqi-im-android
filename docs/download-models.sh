#!/bin/bash
# 下载 Sherpa-onnx 语音模型脚本
# 用法: ./download-models.sh

set -e

MODELS_DIR="../app/src/full/assets/models/sherpa"
MODEL_NAME="sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL_NAME}.tar.bz2"
MODEL_FILE="model.tar.bz2"
MODEL_MARKER="$MODELS_DIR/.model-name"

detect_system_proxy() {
    # Prefer the Windows system proxy when the script is run from Git Bash/MSYS.
    if command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -Command "\
            \$settings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'; \
            if (\$settings.ProxyEnable -ne 1 -or [string]::IsNullOrWhiteSpace(\$settings.ProxyServer)) { exit 0 }; \
            \$proxy = [string]\$settings.ProxyServer; \
            if (\$proxy -match '(^|;)https=([^;]+)') { \$proxy = \$Matches[2] } \
            elseif (\$proxy -match '(^|;)http=([^;]+)') { \$proxy = \$Matches[2] }; \
            if (\$proxy -notmatch '^[a-zA-Z][a-zA-Z0-9+.-]*://') { \$proxy = 'http://' + \$proxy }; \
            Write-Output \$proxy" | tr -d '\r'
    fi
}

echo "=== 墨奇输入法 - 语音模型下载脚本 ==="
echo ""

# 创建目录
mkdir -p "$MODELS_DIR"

# 检查是否已下载
if [ -f "$MODEL_MARKER" ] &&
    [ "$(cat "$MODEL_MARKER")" = "$MODEL_NAME" ] &&
    compgen -G "$MODELS_DIR/encoder*.onnx" > /dev/null &&
    [ -f "$MODELS_DIR/tokens.txt" ]; then
    echo "✓ 模型文件已存在，跳过下载"
    echo "  位置: $MODELS_DIR"
    exit 0
fi

if [ -n "$(ls -A "$MODELS_DIR" 2>/dev/null)" ]; then
    echo "检测到旧模型或不完整模型，正在清理: $MODELS_DIR"
    shopt -s dotglob nullglob
    rm -rf "$MODELS_DIR"/*
    shopt -u dotglob nullglob
fi

echo "正在下载中文流式语音模型（约 70MB）..."
echo "来源: $MODEL_URL"
echo ""

CURL_PROXY_ARGS=()
SYSTEM_PROXY="$(detect_system_proxy)"
if [ -n "$SYSTEM_PROXY" ]; then
    echo "使用系统代理: $SYSTEM_PROXY"
    CURL_PROXY_ARGS=(--proxy "$SYSTEM_PROXY")
elif [ -n "${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}" ]; then
    echo "使用环境变量代理"
fi
echo ""

# 下载模型
curl "${CURL_PROXY_ARGS[@]}" \
    --http1.1 \
    --retry 5 \
    --retry-all-errors \
    --retry-delay 5 \
    -C - \
    -L -o "$MODEL_FILE" "$MODEL_URL" --progress-bar

# 解压
echo ""
echo "正在解压模型..."
tar -xjf "$MODEL_FILE" -C "$MODELS_DIR" --strip-components=1
printf "%s\n" "$MODEL_NAME" > "$MODEL_MARKER"

# 清理
rm "$MODEL_FILE"

echo ""
echo "✓ 模型下载完成"
echo "  位置: $MODELS_DIR"
echo "  文件:"
ls -lh "$MODELS_DIR"

echo ""
echo "提示: 模型已放入 assets 目录，构建 APK 时会自动集成"
echo "      下次构建 full 包: ./gradlew assembleFullDebug"