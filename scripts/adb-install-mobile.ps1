<#
.SYNOPSIS
    列出 ADB 已连接设备并将 APK 安装到手机。

.DESCRIPTION
    依赖 PATH 中的 adb（Android SDK platform-tools）。
    未指定 -ApkPath 时，默认使用 full debug 输出（需先执行 assembleFullDebug 或 scripts/build-apk.ps1）。

.PARAMETER ApkPath
    要安装的 APK 绝对或相对路径。留空则使用默认 full/lite 输出路径。

.PARAMETER Flavor
    未传 -ApkPath 时，选择 full 或 lite 对应 APK（默认 full）。

.PARAMETER Serial
    未传 -ApkPath 时，用 debug 或 release 对应默认输出文件名与目录。
#>
param(
    [string]$ApkPath = "",
    [string]$Serial = "",
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",
    [ValidateSet("full", "lite")]
    [string]$Flavor = "full"
)

$ErrorActionPreference = "Stop"

function Get-AdbPath {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "未在 PATH 中找到 adb。请安装 Android SDK platform-tools 并加入 PATH。"
    }
    return $cmd.Source
}

function Get-CanonicalAdbSerial([string]$deviceId) {
    # 无线调试 transport id 形如 adb-<序列号>-<随机>._adb-tls-connect._tcp，与 USB 下列出的序列号为同一台机
    if ($deviceId -match '^adb-(.+)-[A-Za-z0-9]+\._adb-tls-connect\._tcp$') {
        return $Matches[1]
    }
    return $deviceId
}

function Deduplicate-AdbDeviceIds([string[]]$ids) {
    if ($null -eq $ids -or $ids.Count -eq 0) {
        return @()
    }
    if ($ids.Count -eq 1) {
        return ,$ids
    }
    $byCanon = [ordered]@{}
    foreach ($id in $ids) {
        $c = Get-CanonicalAdbSerial $id
        if (-not $byCanon.Contains($c)) {
            $byCanon[$c] = New-Object System.Collections.Generic.List[string]
        }
        [void]$byCanon[$c].Add($id)
    }
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($canon in $byCanon.Keys) {
        $group = $byCanon[$canon]
        if ($group.Count -eq 1) {
            $out.Add($group[0])
            continue
        }
        $usb = $group | Where-Object { $_ -eq $canon } | Select-Object -First 1
        if ($usb) {
            $out.Add($usb)
            continue
        }
        $nonTcp = $group | Where-Object { $_ -notmatch '\._adb-tls-connect\._tcp' } | Select-Object -First 1
        if ($nonTcp) {
            $out.Add($nonTcp)
            continue
        }
        $out.Add($group[0])
    }
    return ,$out.ToArray()
}

function Get-AdbDevices {
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $AdbExe devices 2>&1)
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($exit -ne 0) {
        throw "adb devices 失败: $output"
    }
    $serials = New-Object System.Collections.Generic.List[string]
    foreach ($line in $output) {
        if ($line -match "^\s*(\S+)\s+device\s*$") {
            $serials.Add($Matches[1])
        }
    }
    $deduped = Deduplicate-AdbDeviceIds ($serials.ToArray())
    if ($serials.Count -ne $deduped.Count) {
        Write-Host "USB 与无线调试同时在线（一般为同一台手机），已去重为 $($deduped.Count) 台，安装时优先使用 USB 序列号。" -ForegroundColor DarkGray
    }
    return ,$deduped
}

$AdbExe = Get-AdbPath
$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $apkKind = if ($Variant -eq "release") { "release" } else { "debug" }
    $ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\$Flavor\$apkKind\app-$Flavor-$apkKind.apk"
}

$ApkPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ApkPath)
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "找不到 APK: $ApkPath。请先构建（例如 .\gradlew.bat assembleFullDebug 或 .\scripts\build-apk.ps1），或使用 -ApkPath 指定文件。"
}

$devices = Get-AdbDevices
if ($devices.Count -eq 0) {
    throw "没有处于 device 状态的设备。请连接手机、打开 USB 调试，并执行 adb devices 确认已授权。"
}

$targetSerial = $Serial
if ([string]::IsNullOrWhiteSpace($targetSerial)) {
    if ($devices.Count -gt 1) {
        Write-Host "检测到多台设备，将使用第一台。可用 -Serial 指定：" -ForegroundColor Yellow
        foreach ($d in $devices) { Write-Host "  $d" }
        $targetSerial = $devices[0]
    }
    else {
        $targetSerial = $devices[0]
    }
}
elseif ($devices -notcontains $targetSerial) {
    throw "指定的序列号不在当前已连接设备列表中: $targetSerial"
}

Write-Host "设备: $targetSerial" -ForegroundColor Cyan
Write-Host "安装: $ApkPath" -ForegroundColor Cyan
Write-Host "若手机弹出安装或权限确认，请解锁屏幕并完成操作。" -ForegroundColor DarkGray

# 不要对 adb 输出管道，否则会丢失 $LASTEXITCODE。
# adb 35+ 的 install 不接受 GNU 式「--」；写「install -r -- 路径」会把「--」误当成长选项前缀，报 Unable to open file: -S。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $installOut = @(& $AdbExe "-s" $targetSerial "install" "-r" $ApkPath 2>&1)
    $installExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prevEap
}
foreach ($line in $installOut) {
    Write-Host $line
}

if ($installExit -ne 0) {
    $blob = ($installOut | ForEach-Object { "$_" }) -join "`n"
    $hint = ""
    if ($blob -match "INSTALL_FAILED_ABORTED|User rejected permissions") {
        $hint = "`n`n提示：请在手机上确认「安装」/权限弹窗（需解锁屏幕）；拒绝会导致本错误。"
    }
    elseif ($blob -match "INSTALL_FAILED_VERSION_DOWNGRADE") {
        $hint = "`n`n提示：新版本号低于已安装应用，可先卸载手机上的旧包再装，或使用带 -d 的安装命令。"
    }
    elseif ($blob -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        $hint = "`n`n提示：签名与已安装版本不一致，需先卸载旧应用再安装。"
    }
    throw "adb install 失败 (退出码 $installExit)。`n$blob$hint"
}
Write-Host "安装完成。" -ForegroundColor Green
