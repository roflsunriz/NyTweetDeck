[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [string]$ChangelogPath = (Join-Path $PSScriptRoot '..' 'CHANGELOG.md'),

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

$changelog = Get-Content -Raw -LiteralPath $ChangelogPath
$escapedVersion = [Regex]::Escape($Version)
$pattern = "(?ms)^(?<section>## \[$escapedVersion\][^\r\n]*\r?\n.*?)(?=^## \[|\z)"
$match = [Regex]::Match($changelog, $pattern)

if (-not $match.Success) {
    throw "CHANGELOG.md にバージョン $Version のセクションがありません。"
}

$section = $match.Groups['section'].Value.TrimEnd()
if ($section -notmatch '(?m)^###\s+\S+' -or $section -notmatch '(?m)^-\s+\S+') {
    throw "CHANGELOG.md のバージョン $Version に公開可能な変更内容がありません。"
}

$absoluteOutputPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($absoluteOutputPath)
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}

$utf8WithoutBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($absoluteOutputPath, "$section`n", $utf8WithoutBom)
Write-Output "CHANGELOG.md の $Version セクションを $absoluteOutputPath へ出力しました。"
