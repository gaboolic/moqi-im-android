# Android Rime 数据包

精简版和完整版都使用 [`moqi-ime.aar`](../app/libs/moqi-ime.aar)。Rime 数据由 Go 嵌入 `jni/arm64-v8a/libgojni.so`。引擎初始化时按 `.moqi_embed_version` 更新共享数据，Rime 再根据配置修改时间重新部署。不覆盖用户自定义配置或词典；若自定义配置完整替换了 `schema_list`，需自行加入 `rime_frost_stroke_t9`。

Android 直接使用 [白霜上游提交 `2c08e25`](https://github.com/gaboolic/rime-frost/commit/2c08e2521b356bd013c4bd287f2ff028e9df4345) 生成的发布 ZIP（包含 [rime-frost #427](https://github.com/gaboolic/rime-frost/pull/427)）。Android 仓库不再维护单独的笔画方案；配置、Lua 模块和词典都来自同一个上游 ZIP。

## 重建随应用发布的 AAR

需要 Python 3、Go 1.26.4、JDK 与 Android SDK/NDK 26.1.10909125。脚本在临时目录以 Go 1.24.6 构建兼容的 `gomobile` 和 `gobind`（`golang.org/x/mobile` 版本 `v0.0.0-20231127183840-76ac6878050a`），不会覆盖全局工具。

从 Android 仓库根目录运行，使用可以丢弃的后端检出目录：

```powershell
$frost = '..\rime-frost-android-data'
$backend = '..\moqi-ime-android-bundle'
$librime = '..\librime-android-headers'
git clone -c core.autocrlf=false https://github.com/gaboolic/rime-frost.git $frost
git -C $frost checkout --detach 2c08e2521b356bd013c4bd287f2ff028e9df4345
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

本次嵌入数据标记：`a5ae518a88e880c73a01031dc893c1569a711826859934adfdf664447f1bdd2b`。随应用提交的 AAR SHA-256：`00599533d021f5365551881d99b6479fd8ce7491249e24eb4961598d44c4d218`。ZIP/AAR 的归档时间戳可能不同，跨机器重建以嵌入数据标记校验内容。
