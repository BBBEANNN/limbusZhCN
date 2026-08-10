param(
    [string]$OutputDirectory = "",
    [string]$SevenZip = ""
)

$ErrorActionPreference = "Stop"
$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ProjectName = Split-Path $SourceRoot -Leaf
$Stamp = Get-Date -Format "yyyyMMdd"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Split-Path $SourceRoot -Parent
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

if ([string]::IsNullOrWhiteSpace($SevenZip)) {
    $Candidates = @(
        (Get-Command 7z.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        "$env:ProgramFiles\7-Zip\7z.exe",
        "${env:ProgramFiles(x86)}\7-Zip\7z.exe"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($Candidates.Count -eq 0) {
        throw "7z.exe not found. Install 7-Zip x64 or pass -SevenZip <path>."
    }
    $SevenZip = $Candidates[0]
}

$StageParent = Join-Path $env:TEMP "limbusZhCN-handoff-$([Guid]::NewGuid().ToString('N'))"
$StageRoot = Join-Path $StageParent $ProjectName
$Archive = Join-Path $OutputDirectory "$ProjectName-windows-codex-$Stamp.7z"
$Checksum = "$Archive.sha256"

try {
    New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null

    $ExcludedDirectories = @(
        (Join-Path $SourceRoot ".git"),
        (Join-Path $SourceRoot ".gradle"),
        (Join-Path $SourceRoot ".idea"),
        (Join-Path $SourceRoot ".kotlin"),
        (Join-Path $SourceRoot ".diagnostics"),
        (Join-Path $SourceRoot ".agents"),
        (Join-Path $SourceRoot ".claude"),
        (Join-Path $SourceRoot ".codex"),
        (Join-Path $SourceRoot "analysis_artifacts"),
        (Join-Path $SourceRoot "logs"),
        (Join-Path $SourceRoot "signing"),
        (Join-Path $SourceRoot "build"),
        (Join-Path $SourceRoot "app\build"),
        (Join-Path $SourceRoot "third_party\virtualapp-upstream\lib\build"),
        (Join-Path $SourceRoot ".externalNativeBuild"),
        (Join-Path $SourceRoot ".cxx"),
        (Join-Path $SourceRoot "baseline"),
        (Join-Path $SourceRoot "out"),
        (Join-Path $SourceRoot "dist")
    )
    $ExcludedFiles = @(
        "local.properties",
        "*.iml",
        "*.jks",
        "*.keystore",
        "keystore.properties",
        "*.log",
        "limbus_*.png",
        "*.tmp.json",
        "*.7z",
        "*.sha256"
    )

    $RoboArgs = @(
        $SourceRoot,
        $StageRoot,
        "/E",
        "/COPY:DAT",
        "/DCOPY:DAT",
        "/R:1",
        "/W:1",
        "/NFL",
        "/NDL",
        "/NJH",
        "/NJS",
        "/NP",
        "/XD"
    ) + $ExcludedDirectories + @("/XF") + $ExcludedFiles

    & robocopy.exe @RoboArgs | Out-Host
    if ($LASTEXITCODE -gt 7) {
        throw "robocopy failed with exit code $LASTEXITCODE"
    }

    @"
Limbus Company 汉化器 Windows/Codex 源码交接包
Generated: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz")
Source baseline: 5008a5359bf75aa52157aa9caf0b419c66e42855
Build guide: docs/windows-codex-handoff.md

This snapshot intentionally excludes Git history, local SDK paths, signing keys,
build caches, diagnostics, reverse-engineering artifacts, game files and account data.
"@ | Set-Content -Path (Join-Path $StageRoot "SOURCE_SNAPSHOT.txt") -Encoding UTF8

    if (Test-Path $Archive) {
        Remove-Item -Force $Archive
    }
    Push-Location $StageParent
    try {
        & $SevenZip a -t7z -mx=9 -m0=lzma2 -mmt=on $Archive $ProjectName
        if ($LASTEXITCODE -ne 0) {
            throw "7-Zip failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    $Hash = (Get-FileHash -Algorithm SHA256 -Path $Archive).Hash.ToLowerInvariant()
    "$Hash  $(Split-Path $Archive -Leaf)" |
        Set-Content -Path $Checksum -Encoding ASCII

    Write-Host "Archive:  $Archive"
    Write-Host "SHA-256: $Hash"
    Write-Host "Manifest: $Checksum"
} finally {
    if (Test-Path $StageParent) {
        Remove-Item -Recurse -Force $StageParent
    }
}
