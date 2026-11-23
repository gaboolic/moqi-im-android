# Android Rime 数据包

精简版和完整版都使用 [`moqi-ime.aar`](../app/libs/moqi-ime.aar)。Rime 数据由 Go 嵌入 `jni/arm64-v8a/libgojni.so`。引擎初始化时按 `.moqi_embed_version` 更新共享数据，Rime 再根据配置修改时间重新部署。不覆盖用户自定义配置或词典；若自定义配置完整替换了 `schema_list`，需自行加入 `rime_frost_stroke_t9`。

Android 直接使用白霜的发布打包脚本生成 ZIP。当前数据包取自 [白霜十四键 PR #432](https://github.com/gaboolic/rime-frost/pull/432) 的提交 `2e52d7705a24034c5cb7b62e81316f55e88f8ae9`。配置、Lua 模块和词典都来自同一个 ZIP，不在 Android 仓库另存一份。

## 重建随应用发布的 AAR

需要 Python 3、Go 1.26.4、JDK 与 Android SDK/NDK 26.1.10909125。脚本在临时目录以 Go 1.24.6 构建兼容的 `gomobile` 和 `gobind`（`golang.org/x/mobile` 版本 `v0.0.0-20231127183840-76ac6878050a`），不会覆盖全局工具。

从 Android 仓库根目录运行，使用可以丢弃的后端检出目录：

```powershell
$frost = '..\rime-frost-android-data'
$backend = '..\moqi-ime-android-bundle'
$librime = '..\librime-android-headers'
git clone -c core.autocrlf=false https://github.com/gaboolic/rime-frost.git $frost
git -C $frost fetch origin refs/pull/432/head
git -C $frost checkout --detach 2e52d7705a24034c5cb7b62e81316f55e88f8ae9
Push-Location $frost
try {
    python others/program/release/generate_rime_all_schema_zip.py
    if ($LASTEXITCODE -ne 0) { throw '白霜打包失败' }
} finally { Pop-Location }
git clone -c core.autocrlf=false https://github.com/gaboolic/moqi-ime.git $backend
git -C $backend checkout --detach 9b1dbdf92cc9253ea7c9351509fc35a14455f9ef
git clone https://github.com/gaboolic/librime.git $librime
git -C $librime checkout --detach a49a57f7142c39021e4290d1a63c16e69649290d
$env:CGO_CFLAGS = '"-I' + (Resolve-Path "$librime\src").Path.Replace('\', '/') + '"'
git -C $backend apply (Join-Path $PWD 'patches/moqi-ime-real-candidate-page-change.patch')
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\26.1.10909125'
.\scripts\build-moqi-ime-aar.ps1 -MoqiImeRoot $backend -RimeFrostZip "$frost\rime-frost-schemas.zip" -UsePrebuiltLibrime
```

`-UsePrebuiltLibrime` 继续使用后端提交自带的 `librime.so` 和 `libc++_shared.so`，只重建 Go 桥和嵌入数据（Android API 24，arm64-v8a）。脚本仅向后端临时 `input_methods/rime/data` 目录解包，拒绝覆盖其中的已有文件；无需初始化后端的白霜数据子模块。

本次嵌入数据标记：`b735ec1df22efe93357cb54c7bf2c5412b6e1b551e67119122f78adfe2fb3fae`。当前 AAR SHA-256：`cc5d7814cb321dc7c1adc843a548b5b877e1d2cf965278fadf16bee700c8f2ee`。ZIP/AAR 的归档时间戳可能不同，跨机器重建以嵌入数据标记校验内容。
