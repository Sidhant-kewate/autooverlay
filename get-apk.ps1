# Usage: powershell -ExecutionPolicy Bypass -File get-apk.ps1 https://github.com/YOU/REPO
# Pushes this project to your repo, lets GitHub Actions build it, downloads AutoTapOverlay.apk here.
# Needs: git (logged in to GitHub) and ideally GitHub CLI `gh` (run `gh auth login` once).
param([string]$Repo = "")
$ErrorActionPreference = "Stop"
$Here = $PSScriptRoot
if (-not $Repo) { $Repo = Read-Host "GitHub repo link" }
$Repo = $Repo.TrimEnd('/'); if ($Repo.EndsWith(".git")) { $Repo = $Repo.Substring(0, $Repo.Length - 4) }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "Install git first" }

$Tmp = Join-Path $env:TEMP ("autotap-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory $Tmp | Out-Null
Write-Host ">> Cloning $Repo ..."
git clone "$Repo.git" "$Tmp\repo"
if ($LASTEXITCODE -ne 0) { throw "Clone failed - check the link and your GitHub login" }
Set-Location "$Tmp\repo"

if (git ls-files) {
  Write-Host "!! This repo already has files. The project will be mixed into its root."
  $a = Read-Host "Continue anyway? [y/N]"
  if ($a -notmatch '^[Yy]') { throw "Cancelled. Use an empty repo." }
}

Write-Host ">> Copying project in ..."
robocopy $Here "$Tmp\repo" /E /XD .git build .gradle /XF local.properties *.apk /NFL /NDL /NJH /NJS | Out-Null

git add -A
git commit -m "Add AutoTap Overlay"
if ($LASTEXITCODE -ne 0) { git commit --allow-empty -m "Rebuild APK" }
Write-Host ">> Pushing ..."
git push -u origin HEAD
if ($LASTEXITCODE -ne 0) { throw "Push failed - check your GitHub login" }

$hasGh = (Get-Command gh -ErrorAction SilentlyContinue) -ne $null
if ($hasGh) { gh auth status 2>$null | Out-Null; $hasGh = ($LASTEXITCODE -eq 0) }
if ($hasGh) {
  Write-Host ">> Waiting for GitHub to build (about 3-5 minutes) ..."
  Start-Sleep 10
  $run = gh run list --workflow "Build APK" --limit 1 --json databaseId -q ".[0].databaseId"
  gh run watch $run --exit-status
  gh run download $run -n AutoTapOverlay-debug-apk -D "$Tmp\out"
  Copy-Item "$Tmp\out\app-debug.apk" "$Here\AutoTapOverlay.apk" -Force
  Write-Host ""; Write-Host "DONE -> $Here\AutoTapOverlay.apk"
} else {
  Write-Host ""
  Write-Host "Pushed. 'gh' is not installed/logged in, so finish in the browser:"
  Write-Host "  1. Open $Repo/actions"
  Write-Host "  2. Click the newest 'Build APK' run, wait for the green tick"
  Write-Host "  3. Download 'AutoTapOverlay-debug-apk' under Artifacts and unzip it"
}
