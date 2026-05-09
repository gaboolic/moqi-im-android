<#
.SYNOPSIS
    在仓库根目录调用 Gradle 编译 app APK。

.PARAMETER Variant
    debug 对应 *Debug；release 对应 *Release。

.PARAMETER Flavor
    full / lite / both。默认 both，与 CI 一致打出完整包与精简包。

.PARAMETER Clean
    若指定，先执行 clean 再 assemble。
#>
param(
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",
    [ValidateSet("full", "lite", "both")]
    [string]$Flavor = "both",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Gradlew = Join-Path $RepoRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $Gradlew)) {
    throw "找不到 gradlew.bat: $Gradlew"
}

$suffix = if ($Variant -eq "release") { "Release" } else { "Debug" }
$tasks = switch ($Flavor) {
    "full" { @("assembleFull$suffix") }
    "lite" { @("assembleLite$suffix") }
    "both" { @("assembleFull$suffix", "assembleLite$suffix") }
}

Push-Location $RepoRoot
try {
    if ($Clean) {
        Write-Host "执行: gradlew.bat clean" -ForegroundColor Cyan
        & $Gradlew clean --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "gradlew clean 失败，退出码: $LASTEXITCODE" }
    }
    foreach ($task in $tasks) {
        Write-Host "执行: gradlew.bat $task" -ForegroundColor Cyan
        & $Gradlew $task --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "gradlew $task 失败，退出码: $LASTEXITCODE" }
    }
}
finally {
    Pop-Location
}

$apkKind = if ($Variant -eq "release") { "release" } else { "debug" }
Write-Host "构建完成。APK 目录: $(Join-Path $RepoRoot "app\build\outputs\apk")" -ForegroundColor Green
foreach ($f in @("full", "lite")) {
    if ($Flavor -eq "both" -or $Flavor -eq $f) {
        $dir = Join-Path $RepoRoot "app\build\outputs\apk\$f\$apkKind"
        if (Test-Path $dir) {
            Get-ChildItem $dir -Filter "*.apk" | ForEach-Object { Write-Host "  $($_.FullName)" -ForegroundColor Green }
        }
    }
}
