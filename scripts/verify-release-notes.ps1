[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [ValidatePattern('^[A-Za-z][A-Za-z0-9-]*$')]
    [string]$SectionPrefix,

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

$extractArguments = @{
    Version = $Version
    ChangelogPath = $ChangelogPath
    OutputPath = $OutputPath
}
if (-not [string]::IsNullOrWhiteSpace($SectionPrefix)) {
    $extractArguments.SectionPrefix = $SectionPrefix
}
& (Join-Path $PSScriptRoot 'extract-changelog-section.ps1') @extractArguments

$notes = Get-Content -Raw -LiteralPath $OutputPath
$sectionVersion = if ([string]::IsNullOrWhiteSpace($SectionPrefix)) {
    $Version
}
else {
    "$SectionPrefix $Version"
}
$expectedHeading = "## [$sectionVersion]"
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

Write-Output "バージョン $sectionVersion のリリース本文を検証しました。"
