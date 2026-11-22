param(
    [string]$MoqiImeRoot = "$PSScriptRoot\..\..\moqi-ime",
    [string]$OutputAar = "$PSScriptRoot\..\app\libs\moqi-ime.aar",
    [string]$Target = "android/arm64",
    [int]$AndroidApi = 24,
    [switch]$UsePrebuiltLibrime
)

$ErrorActionPreference = "Stop"

function Assert-Command($Name, $InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $InstallHint"
    }
}

Assert-Command "go" "Install Go first."
Assert-Command "jar" "Install a JDK first."

function Add-FrostStrokeSchema {
    param([string]$TargetDir)

    foreach ($name in @("default.yaml", "stroke.dict.yaml", "essay.txt")) {
        if (-not (Test-Path -LiteralPath (Join-Path $TargetDir $name) -PathType Leaf)) {
            throw "The Rime Frost submodule is missing $name; initialize it at the backend's pinned revision."
        }
    }

    $schemaName = "frost_stroke.schema.yaml"
    $schemaSource = Join-Path $PSScriptRoot "..\app\rime-data\$schemaName"
    Copy-Item -LiteralPath $schemaSource -Destination (Join-Path $TargetDir $schemaName) -Force

    # Stage the Android schema without changing either upstream Frost or user overrides.
    $defaultPath = Join-Path $TargetDir "default.yaml"
    $default = [System.IO.File]::ReadAllText($defaultPath)
    $schemaList = [regex]::Match($default, '(?m)^schema_list:[ \t]*(?<eol>\r?\n)(?<items>(?:[ \t]+[^\r\n]*\r?\n|\r?\n)*)')
    $items = [regex]::Matches($schemaList.Groups['items'].Value, '(?m)^(?<indent>[ \t]+)- schema:[ \t]+(?<id>[\w-]+)[^\r\n]*')
    if ($items.Count -eq 0) {
        throw "Expected a block schema_list in the pinned Rime Frost default.yaml."
    }
    foreach ($item in $items) {
        if ($item.Groups['id'].Value -eq 'frost_stroke') {
            return
        }
    }
    $last = $items[$items.Count - 1]
    $end = $schemaList.Groups['items'].Index + $last.Index + $last.Length
    $entry = $schemaList.Groups['eol'].Value + $last.Groups['indent'].Value + '- schema: frost_stroke'
    [System.IO.File]::WriteAllText($defaultPath, $default.Insert($end, $entry))
}

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
    New-Item -ItemType Directory -Force $targetDir | Out-Null
    Copy-Item -Path (Join-Path $sourceDir "*") -Destination $targetDir -Recurse -Force

    foreach ($name in @(".git", ".github", ".gitignore", "README.md", "LICENSE", "others")) {
        Remove-Item -Recurse -Force (Join-Path $targetDir $name) -ErrorAction SilentlyContinue
    }

    Add-FrostStrokeSchema -TargetDir $targetDir
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

$MoqiImeRoot = (Resolve-Path -LiteralPath $MoqiImeRoot).Path
$OutputAar = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputAar)
$embedDir = Join-Path $MoqiImeRoot "input_methods\rime\data"
if ((Test-Path -LiteralPath $embedDir) -and @(Get-ChildItem -LiteralPath $embedDir -Force).Count -ne 0) {
    throw "Refusing to overwrite $embedDir. Use a disposable backend checkout with an empty generated data directory."
}

$toolsDir = Join-Path ([System.IO.Path]::GetTempPath()) ('moqi-ime-tools-' + [guid]::NewGuid().ToString('N'))
$oldGoBin = $env:GOBIN
$oldPath = $env:PATH

try {
Sync-RimeFrostData -MoqiImeRoot $MoqiImeRoot
$abi = switch ($Target) {
    "android/arm64" { "arm64-v8a" }
    "android/arm" { "armeabi-v7a" }
    "android/amd64" { "x86_64" }
    "android/386" { "x86" }
    default { "" }
}

if ($abi -eq "arm64-v8a" -and -not $UsePrebuiltLibrime) {
    $librimeBuildScript = Join-Path $MoqiImeRoot "scripts\build-librime-android.ps1"
    if (Test-Path $librimeBuildScript) {
        Write-Host "Building Android librime native libraries..."
        & $librimeBuildScript -Abi $abi -AndroidPlatform "android-$AndroidApi"
        if ($LASTEXITCODE -ne 0) {
            throw "build-librime-android.ps1 failed with exit code $LASTEXITCODE"
        }
    }
}

# Android bind uses the installed NDK. `gomobile init` is unnecessary here and
# would install gobind@latest, which is incompatible with the pinned gomobile.
$xMobileVersion = 'v0.0.0-20231127183840-76ac6878050a'
$env:GOBIN = $toolsDir
$env:PATH = "$toolsDir$([IO.Path]::PathSeparator)$oldPath"
$oldGoToolchain = $env:GOTOOLCHAIN
try {
    # The pinned x/tools no longer compiles with Go 1.26. Use 1.24 for the
    # helper binaries only; bind uses the caller's newer Go for the backend.
    $env:GOTOOLCHAIN = 'go1.24.6'
    foreach ($tool in @('gomobile', 'gobind')) {
        go install "golang.org/x/mobile/cmd/$tool@$xMobileVersion"
        if ($LASTEXITCODE -ne 0) {
            throw "$tool install failed with exit code $LASTEXITCODE"
        }
    }
} finally {
    $env:GOTOOLCHAIN = $oldGoToolchain
}
$gomobilePath = Join-Path $toolsDir 'gomobile.exe'

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
        throw "Required Rime native libraries were not found under $rimeLibDir."
    }
}

Write-Host "Built $OutputAar"
} finally {
    $env:GOBIN = $oldGoBin
    $env:PATH = $oldPath
    if (Test-Path -LiteralPath $toolsDir) {
        Remove-Item -LiteralPath $toolsDir -Recurse -Force
    }
    Clear-RimeEmbedData -MoqiImeRoot $MoqiImeRoot
}
