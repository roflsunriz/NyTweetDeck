[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [string]$ChangelogPath = (Join-Path $PSScriptRoot '..' 'CHANGELOG.md'),

    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Version)) {
    $changelog = Get-Content -Raw -LiteralPath $ChangelogPath
    $latestRelease = [Regex]::Match(
        $changelog,
        '(?m)^## \[(?<version>[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?)\]'
    )
    if (-not $latestRelease.Success) {
        throw 'CHANGELOG.md に確定バージョンのセクションがありません。'
    }
    $Version = $latestRelease.Groups['version'].Value
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot '..' 'target' "release-notes-$Version.md"
}

& (Join-Path $PSScriptRoot 'extract-changelog-section.ps1') `
    -Version $Version `
    -ChangelogPath $ChangelogPath `
    -OutputPath $OutputPath

$notes = Get-Content -Raw -LiteralPath $OutputPath
$expectedHeading = "## [$Version]"
if (-not $notes.StartsWith($expectedHeading, [StringComparison]::Ordinal)) {
    throw "リリース本文が $expectedHeading で始まっていません。"
}

$versionHeadings = [Regex]::Matches($notes, '(?m)^## \[')
if ($versionHeadings.Count -ne 1) {
    throw 'リリース本文に別バージョンのCHANGELOGセクションが混入しています。'
}

if ($notes.Contains('## [Unreleased]', [StringComparison]::Ordinal)) {
    throw 'リリース本文にUnreleasedセクションが混入しています。'
}

Write-Output "バージョン $Version のリリース本文を検証しました。"
