param(
    [string]$MoqiImeRoot = (Resolve-Path "$PSScriptRoot\..\..\moqi-ime").Path,
    [string]$OutputAar = (Resolve-Path "$PSScriptRoot\..").Path + "\app\libs\moqi-ime.aar",
    [string]$Target = "android/arm64",
    [int]$AndroidApi = 24
)

$ErrorActionPreference = "Stop"

function Assert-Command($Name, $InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $InstallHint"
    }
}

Assert-Command "go" "Install Go first."

function Sync-RimeFrostData {
    param(
        [string]$MoqiImeRoot
    )

    $sourceDir = Join-Path $MoqiImeRoot "rime-frost"
    $targetDir = Join-Path $MoqiImeRoot "input_methods\rime\data"
    if (-not (Test-Path $sourceDir)) {
        throw "Missing rime-frost data directory: $sourceDir"
    }

    Write-Host "Syncing Rime Frost data for Android embed..."
    Remove-Item -Recurse -Force $targetDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $targetDir | Out-Null
    Copy-Item -Path (Join-Path $sourceDir "*") -Destination $targetDir -Recurse -Force

    foreach ($name in @(".git", ".github", ".gitignore", "README.md", "LICENSE", "others")) {
        Remove-Item -Recurse -Force (Join-Path $targetDir $name) -ErrorAction SilentlyContinue
    }

    $version = Get-RimeEmbedVersion -TargetDir $targetDir
    Set-Content -Path (Join-Path $targetDir ".moqi_embed_version") -Value $version -Encoding ASCII
    Write-Host "Rime Frost embed version: $version"
}

function Get-RimeEmbedVersion {
    param(
        [string]$TargetDir
    )

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $targetFull = ([System.IO.Path]::GetFullPath($TargetDir) -replace '[\\/]+$', '')
        $builder = New-Object System.Text.StringBuilder
        $files = @(Get-ChildItem -Path $TargetDir -Recurse -File | Sort-Object FullName)
        foreach ($file in $files) {
            if ($file.Name -eq ".moqi_embed_version") {
                continue
            }
            $relative = ($file.FullName.Substring($targetFull.Length) -replace '^[\\/]+', '' -replace '\\', '/')
            $fileHash = (Get-FileHash -Algorithm SHA256 -Path $file.FullName).Hash.ToLowerInvariant()
            [void]($builder.Append($relative).Append("`t").Append($fileHash).Append("`n"))
        }
        $manifest = $builder.ToString()
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($manifest)
        return [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Clear-RimeEmbedData {
    param(
        [string]$MoqiImeRoot
    )

    $targetDir = Join-Path $MoqiImeRoot "input_methods\rime\data"
    Remove-Item -Recurse -Force $targetDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $targetDir | Out-Null
}

$MoqiImeRoot = (Resolve-Path $MoqiImeRoot).Path
Sync-RimeFrostData -MoqiImeRoot $MoqiImeRoot

try {
$abi = switch ($Target) {
    "android/arm64" { "arm64-v8a" }
    "android/arm" { "armeabi-v7a" }
    "android/amd64" { "x86_64" }
    "android/386" { "x86" }
    default { "" }
}

if ($abi -eq "arm64-v8a") {
    $librimeBuildScript = Join-Path $MoqiImeRoot "scripts\build-librime-android.ps1"
    if (Test-Path $librimeBuildScript) {
        Write-Host "Building Android librime native libraries..."
        & $librimeBuildScript -Abi $abi -AndroidPlatform "android-$AndroidApi"
        if ($LASTEXITCODE -ne 0) {
            throw "build-librime-android.ps1 failed with exit code $LASTEXITCODE"
        }
    }
}

$gomobile = Get-Command "gomobile" -ErrorAction SilentlyContinue
if (-not $gomobile) {
    $gomobilePath = Join-Path (go env GOPATH) "bin\gomobile.exe"
    if (-not (Test-Path $gomobilePath)) {
        Write-Host "Installing gomobile..."
        go install golang.org/x/mobile/cmd/gomobile@latest
    }
    $gomobilePath = (Get-Item $gomobilePath).FullName
} else {
    $gomobilePath = $gomobile.Source
}

Write-Host "Initializing gomobile..."
& $gomobilePath init
if ($LASTEXITCODE -ne 0) {
    throw "gomobile init failed with exit code $LASTEXITCODE"
}

$outputDir = Split-Path -Parent $OutputAar
New-Item -ItemType Directory -Force $outputDir | Out-Null

Write-Host "Building moqi-ime Android AAR..."
Push-Location $MoqiImeRoot
try {
    # 与 go build release 一致：去掉符号表/调试信息并裁剪构建路径，减小 libgojni.so
    $gomobileArgs = @(
        "bind",
        "-target=$Target",
        "-androidapi",
        "$AndroidApi",
        "-ldflags=-s -w",
        "-trimpath",
        "-o",
        "$OutputAar",
        "github.com/gaboolic/moqi-ime/mobilebridge"
    )
    & $gomobilePath @gomobileArgs
    if ($LASTEXITCODE -ne 0) {
        throw "gomobile bind failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if ($abi -ne "") {
    $rimeLibDir = Join-Path $MoqiImeRoot "input_methods\rime\android\$abi"
    $librimeSo = Join-Path $rimeLibDir "librime.so"
    $cxxShared = Join-Path $rimeLibDir "libc++_shared.so"
    if ((Test-Path $librimeSo) -and (Test-Path $cxxShared)) {
        $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("moqi-ime-aar-" + [System.Guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force $tempDir | Out-Null
        try {
            Push-Location $tempDir
            try {
                & jar xf $OutputAar
                if ($LASTEXITCODE -ne 0) {
                    throw "jar xf failed with exit code $LASTEXITCODE"
                }
            } finally {
                Pop-Location
            }
            $jniDir = Join-Path $tempDir "jni\$abi"
            New-Item -ItemType Directory -Force $jniDir | Out-Null
            Copy-Item -Force $librimeSo (Join-Path $jniDir "librime.so")
            Copy-Item -Force $cxxShared (Join-Path $jniDir "libc++_shared.so")
            Remove-Item -Force $OutputAar
            & jar cf $OutputAar -C $tempDir .
            if ($LASTEXITCODE -ne 0) {
                throw "jar cf failed with exit code $LASTEXITCODE"
            }
        } finally {
            Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
        }
    } else {
        Write-Warning "Rime native libraries were not found under $rimeLibDir; AAR will not include librime."
    }
}

Write-Host "Built $OutputAar"
} finally {
    Clear-RimeEmbedData -MoqiImeRoot $MoqiImeRoot
}
