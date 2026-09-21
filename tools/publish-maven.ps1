# Regenerates the `maven` branch (Maven-layout repository served via raw.githubusercontent.com).
#
# Flow: publishToMavenLocal in every branch worktree -> mirror released versions (1.0.0+) from
# ~/.m2 into the maven worktree with checksums + maven-metadata.xml -> commit on `maven`.
# Run from anywhere; push the maven branch together with the release (test-before-push applies).
#
# Worktree layout this repo uses:
#   C:\Users\rcham\draftsmith-mod          <- 26.x version branches (26.2 / 26.3 / ...), see $BaseBranches
#   C:\Users\rcham\draftsmith-mod-26.1.2   <- main
#   C:\Users\rcham\draftsmith-mod-1.21.1   <- 1.21.1
#   C:\Users\rcham\draftsmith-mod-maven    <- the maven branch (create once with:
#                                             git worktree add --orphan -b maven ..\draftsmith-mod-maven)

param(
    # Branches built inside the base worktree (checked out one after another, original restored).
    [string[]]$BaseBranches = @('26.2', '26.3'),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = 'C:\Users\rcham'
$base = Join-Path $root 'draftsmith-mod'
$mavenWt = Join-Path $root 'draftsmith-mod-maven'
$m2 = Join-Path $env:USERPROFILE '.m2\repository\com\draftsmith\draftsmith'
$dest = Join-Path $mavenWt 'com\draftsmith\draftsmith'

function Invoke-Publish([string]$dir) {
    Write-Host "== publishToMavenLocal: $dir" -ForegroundColor Cyan
    Push-Location $dir
    try {
        & .\gradlew.bat publishToMavenLocal -q --console=plain
        if ($LASTEXITCODE -ne 0) { throw "build failed in $dir" }
    } finally { Pop-Location }
}

if (-not $SkipBuild) {
    # Fixed worktrees first.
    Invoke-Publish (Join-Path $root 'draftsmith-mod-26.1.2')
    Invoke-Publish (Join-Path $root 'draftsmith-mod-1.21.1')
    # The base worktree hosts several 26.x branches — build each, then restore the original.
    Push-Location $base
    $original = (git branch --show-current).Trim()
    Pop-Location
    foreach ($b in $BaseBranches) {
        Push-Location $base
        git checkout -q $b
        if ($LASTEXITCODE -ne 0) { Pop-Location; throw "checkout $b failed (dirty tree?)" }
        Pop-Location
        Invoke-Publish $base
    }
    Push-Location $base
    git checkout -q $original
    Pop-Location
}

# Mirror released versions (>= 1.0.0 — pre-release 0.x and stubs never publish).
$versions = Get-ChildItem $m2 -Directory |
    Where-Object { $_.Name -match '^[1-9][0-9]*\.[0-9]+\.[0-9]+\+' } |
    ForEach-Object Name | Sort-Object
if (-not $versions) { throw "no releasable versions found in $m2" }

$utf8 = New-Object System.Text.UTF8Encoding($false)
function Write-Checksums([string]$file) {
    foreach ($algo in 'SHA1', 'MD5', 'SHA256', 'SHA512') {
        $hash = (Get-FileHash -Algorithm $algo $file).Hash.ToLower()
        [IO.File]::WriteAllText("$file.$($algo.ToLower())", $hash, $utf8)
    }
}

New-Item -ItemType Directory -Force $dest | Out-Null
foreach ($v in $versions) {
    $vDest = Join-Path $dest $v
    if (Test-Path $vDest) { Remove-Item -Recurse -Force $vDest }
    New-Item -ItemType Directory $vDest | Out-Null
    Get-ChildItem (Join-Path $m2 $v) -File |
        Where-Object { $_.Name -notlike 'maven-metadata*' } |
        ForEach-Object {
            Copy-Item $_.FullName $vDest
            Write-Checksums (Join-Path $vDest $_.Name)
        }
    Write-Host "mirrored $v"
}

# latest/release = highest mod version, newest 26.x target (26.x sorts above 1.21.x numerically).
$latest = $versions | Sort-Object {
    $mod, $mc = $_.Split('+', 2)
    ($mod.Split('.') + $mc.Split('.') | ForEach-Object { '{0:d5}' -f [int]$_ }) -join '.'
} | Select-Object -Last 1

$versionsXml = ($versions | ForEach-Object { "      <version>$_</version>" }) -join "`n"
$meta = @"
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.draftsmith</groupId>
  <artifactId>draftsmith</artifactId>
  <versioning>
    <latest>$latest</latest>
    <release>$latest</release>
    <versions>
$versionsXml
    </versions>
    <lastUpdated>$(Get-Date -Format yyyyMMddHHmmss)</lastUpdated>
  </versioning>
</metadata>
"@ -replace "`r`n", "`n"
$metaPath = Join-Path $dest 'maven-metadata.xml'
[IO.File]::WriteAllText($metaPath, $meta, $utf8)
Write-Checksums $metaPath

Push-Location $mavenWt
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    git commit -q -m "maven: publish $latest (all targets)"
    Write-Host "maven branch committed: $(git log --oneline -1)" -ForegroundColor Green
    Write-Host "push it with the release: git push origin maven"
} else {
    Write-Host "maven branch already up to date" -ForegroundColor Green
}
Pop-Location
