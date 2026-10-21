# 按当前或指定版本打 annotated tag，说明写入标签，再推送。
# 推送 v* 标签后，GitHub Action 会打包 APK 并创建 Release，正文使用这里的说明。
#
# 使用当前 versionName：
#   .\scripts\release.ps1 -Notes "修复地图选点"
# 从文件读取说明：
#   .\scripts\release.ps1 -NotesFile .\notes.txt
# 改版本后再发布。versionCode 自动加 1，并先提交 gradle 改动：
#   .\scripts\release.ps1 -Version 0.1.1 -Notes "第一版可安装包"

param(
    [string]$Notes = "",
    [string]$NotesFile = "",
    [string]$Version = "",
    [string]$Remote = "origin"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

function Read-AppVersion {
    $text = [System.IO.File]::ReadAllText((Resolve-Path "app/build.gradle.kts"))
    if ($text -notmatch 'versionCode = (\d+)') { throw "app/build.gradle.kts 里没有 versionCode" }
    $code = [int]$Matches[1]
    if ($text -notmatch 'versionName = "([^"]+)"') { throw "app/build.gradle.kts 里没有 versionName" }
    return @{ Name = $Matches[1]; Code = $code; Text = $text }
}

if ($NotesFile) {
    if (-not (Test-Path $NotesFile)) { throw "找不到说明文件：$NotesFile" }
    $Notes = [System.IO.File]::ReadAllText((Resolve-Path $NotesFile))
}
$Notes = $Notes.Trim()
if (-not $Notes) { throw "请用 -Notes 或 -NotesFile 写上这次 Release 的说明" }

$current = Read-AppVersion
if (-not $Version) { $Version = $current.Name }
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "版本号需要是 0.1.0 这种形式，当前是 $Version" }

$dirty = git status --porcelain
if ($dirty) { throw "工作区还有未提交的改动，先提交或暂存后再发布" }

if ($Version -ne $current.Name) {
    $nextCode = $current.Code + 1
    $updated = [regex]::Replace($current.Text, 'versionCode = \d+', "versionCode = $nextCode", 1)
    $updated = [regex]::Replace($updated, 'versionName = "[^"]+"', "versionName = `"$Version`"", 1)
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) "app/build.gradle.kts"), $updated, $utf8)
    git add -- "app/build.gradle.kts"
    git commit -m "发布 v$Version"
}

$tag = "v$Version"
$ErrorActionPreference = "Continue"
$existing = git rev-parse --verify --quiet "refs/tags/$tag"
if ($LASTEXITCODE -eq 0 -and $existing) { throw "本地已经有标签 $tag" }
$ErrorActionPreference = "Stop"
$remoteTag = git ls-remote --tags $Remote "refs/tags/$tag"
if ($remoteTag) { throw "远程已经有标签 $tag" }

$noteFile = Join-Path ([System.IO.Path]::GetTempPath()) "uestc-nightsign-release-notes.txt"
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($noteFile, $Notes + "`n", $utf8)
try {
    git tag -a $tag -F $noteFile
} finally {
    Remove-Item $noteFile -ErrorAction SilentlyContinue
}

git push $Remote HEAD
git push $Remote $tag
Write-Host "已推送 $tag。Action 打包完成后，Release 说明就是上面这段文字。"
