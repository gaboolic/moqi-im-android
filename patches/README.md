# Rime candidate paging contract fix

## Why the native engine must change

The bundled librime `RimeChangePage` highlights `selected_index +/- page_size`.
`Context::Highlight` clamps an out-of-range index to the last candidate and returns
whether the **highlight** changed, not whether the **page** changed. A final page
can therefore be returned twice with `handled=true`. The Android pager then caches
an extra page and later selects the same local index on the wrong native page.

The concrete regression is `nir`: selecting `𠉎` from the expanded candidates can
commit `你`. The original Kotlin fake engine incorrectly assumed every successful
page request changed pages, so the earlier tests missed this native behavior.

The [backend patch](moqi-ime-real-candidate-page-change.patch) makes the Go bridge
compare real `rimeState.PageNo` before/after a page request. Native cloud clipboard
paging likewise compares its actual page index. It does **not** compare candidate
text or guess from the visible cursor: adjacent pages may have identical text,
and the backend can clip the visible cursor independently of the native page size.

## Reproduce the bundled artifact

The patch is based on `gaboolic/moqi-ime` commit
`9b1dbdf92cc9253ea7c9351509fc35a14455f9ef`.
Apply it to a **clean, disposable** backend checkout, not a checkout containing
unrelated local modifications:

```powershell
git -C <backend-checkout> apply <android-checkout>/patches/moqi-ime-real-candidate-page-change.patch
# Run from the backend checkout:
go test ./input_methods/rime -run 'Test(ChangePageRequestUsesActualPageNumber|CloudClipboardChangePageRequestUsesActualPage|ChangePageRequestDelegatesToBackend)$' -count=1 -v
```

Rebuild with the existing [AAR build script](../scripts/build-moqi-ime-aar.ps1),
passing the clean patched backend as `-MoqiImeRoot`. That script replaces/clears the
backend's temporary embedded `input_methods/rime/data` directory; do not point it
at a checkout where that directory contains data you need to preserve.
The backend's Rime Frost assets and Android native libraries must be available.

The original paging-fix AAR was built from a separate
clean backend checkout plus this patch, using Go 1.26.4, Android API 24, arm64,
NDK 26.1.10909125, and the existing bundled librime/C++ shared libraries. No unrelated
local backend changes were included. The public Java bindings are unchanged.

SHA-256 of the bundled AAR:

```text
341df4b6cf5c863fc3f29a95ab8a1aef215eda9e9416d60d9ce763788a5d5dbd
```

## Validation recorded for this revision

- Android Lite and Full: 60 unit tests each passed; both Debug APKs and the
  Full device-test APK built successfully.
- Backend paging/select/highlight/bridge-focused tests passed, including 13 new
  paging boundary cases. The complete `mobilebridge` package passed as well.
- The entire Go Rime suite is **not** green: existing real-AI configuration and
  appearance assertions fail, and an AI hotkey test waits until the suite timeout.
  These unrelated failures were not changed or hidden.
- On 2026-11-18 at 12:40 (Asia/Shanghai), the connected physical device passed
  the native instrumentation regression: `OK (1 test)`. With input `nir`, the
  expanded candidate at page 1 / local index 1 was `𠉎` and the native commit was
  exactly `𠉎`. Prefetch stopped on page request 3 with `handled=false` despite a
  changed highlight on the same final page. Installation used `adb install -r`;
  existing app data was retained. This is real native-engine verification, not
  an automated touch-coordinate/UI screenshot assertion.

## Tests

[Kotlin contract tests](../app/src/test/java/com/moqi/im/engine/RimeCandidatePagingTest.kt)
model the real global-highlight clamping behavior, including the reported wrong
character, partial/full final pages, identical adjacent candidate pages, and
visible-cursor clipping. The patch also contains Go regression tests that exercise
the real bridge request handler against explicit before/after native page states.

The [device regression](../app/src/androidTest/java/com/moqi/im/engine/CandidatePagingDeviceTest.kt)
uses the **actual bundled native bridge and the device's deployed schema**, not a
fake engine. The active Rime schema must expose `𠉎` for `nir`, as in the report.
Use a debug build with the same package/signing identity as the app on the device;
do not uninstall the user's app or clear its data to run this test.

```powershell
./gradlew.bat :app:assembleFullDebug :app:assembleFullDebugAndroidTest
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
adb install -r app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb shell am instrument -w -e class com.moqi.im.engine.CandidatePagingDeviceTest com.moqi.im.test/android.test.InstrumentationTestRunner
```
