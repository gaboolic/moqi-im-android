# Android Rime 数据包

Android 精简版和完整版都直接使用 [`moqi-ime.aar`](../app/libs/moqi-ime.aar)。Rime 数据由 Go 嵌入 `jni/arm64-v8a/libgojni.so`，不走 APK 的 `assets/`。引擎初始化时会按 `.moqi_embed_version` 把共享数据更新到应用私有目录；Rime 根据配置修改时间重新部署。构建只追加共享 `default.yaml` 的方案列表，不修改用户方案列表、已选方案或用户词典。自定义配置若完整替换了 `schema_list`，需自行将 `frost_stroke` 加入其列表。

[`frost_stroke.schema.yaml`](../app/rime-data/frost_stroke.schema.yaml) 是 Android 构建的方案输入，与白霜提交 `5613ab05a842fa5edb3e56a478a6e7d19b85cae2` 的同名文件逐字节一致。它复用 [固定版本的白霜字典](https://github.com/gaboolic/rime-frost/blob/6b74e54da2737d9b9ba8c502ce697145f94504ba/stroke.dict.yaml)和同一版本的 `essay.txt`、`default.yaml`，不另存一份字典。`stroke.dict.yaml` 的原始头部保留了 CNS11643 和北大中文论坛的来源说明；见 [Rime 笔画项目及许可](https://github.com/rime/rime-stroke)。

## 重建随应用发布的 AAR

需要 Go 1.26.4、JDK 与 Android SDK/NDK 26.1.10909125。脚本在临时目录通过 Go 1.24.6 构建兼容的 `gomobile` 和 `gobind`（`golang.org/x/mobile` 版本 `v0.0.0-20231127183840-76ac6878050a`），不会覆盖全局工具。

从 Android 仓库根目录运行，另建一个可以丢弃的后端检出目录：

```powershell
$backend = '..\moqi-ime-android-bundle'
$librime = '..\librime-android-headers'
git clone -c core.autocrlf=false https://github.com/gaboolic/moqi-ime.git $backend
git -C $backend checkout --detach 9b1dbdf92cc9253ea7c9351509fc35a14455f9ef
git clone https://github.com/gaboolic/librime.git $librime
git -C $librime checkout --detach a49a57f7142c39021e4290d1a63c16e69649290d
$env:CGO_CFLAGS = '"-I' + (Resolve-Path "$librime\src").Path.Replace('\', '/') + '"'
git -C $backend -c core.autocrlf=false submodule update --init rime-frost
git -C $backend apply (Join-Path $PWD 'patches/moqi-ime-real-candidate-page-change.patch')
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\26.1.10909125'
.\scripts\build-moqi-ime-aar.ps1 -MoqiImeRoot $backend -UsePrebuiltLibrime
```

后端的 Git 子模块固定为 `6b74e54da2737d9b9ba8c502ce697145f94504ba`。`-UsePrebuiltLibrime` 采用后端提交自带的 `librime.so` 和 `libc++_shared.so`，只重建 Go 桥和其中嵌入的数据（Android API 24，arm64-v8a）。脚本仅操作后端的临时 `input_methods/rime/data` 目录，并拒绝覆盖该目录中的已有文件。

随应用发布的 AAR SHA-256：`02e8d079cc3dbb2b26e0aa8aeb7ec47a9532438b1e7f7cf5d300c9c59c418726`。嵌入数据标记：`0cedaac6490e2aec76dc7f18c03128d78c5dfb4e377150140682d3aea1cb7867`。重建的 ZIP 时间戳以及 Go 工具链记录的本机检出路径可不同，AAR 整包哈希不作为跨机器重建校验。
