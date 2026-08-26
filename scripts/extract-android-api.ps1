param(
    [string]$JadxRoot = 'x-apks\jadx-12.19.1',
    [string]$ExtractedRoot = 'x-apks\extracted-12.19.1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resolvedJadxRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $JadxRoot))
$resolvedExtractedRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $ExtractedRoot))
$generatedRoot = Join-Path $repositoryRoot 'docs\generated'
$sourceRoot = Join-Path $resolvedJadxRoot 'sources'
$registrarRoot = Join-Path $sourceRoot 'com\twitter\api\graphql'

if (-not (Test-Path -LiteralPath $sourceRoot)) {
    throw "JADX解析結果が見つかりません: $sourceRoot"
}

New-Item -ItemType Directory -Path $generatedRoot -Force | Out-Null

$operations = @()
foreach ($registrarName in @('d.java', 'e.java')) {
    $registrarPath = Join-Path $registrarRoot $registrarName
    if (-not (Test-Path -LiteralPath $registrarPath)) {
        throw "GraphQLレジストラーが見つかりません: $registrarPath"
    }

    $typeByVariable = @{}
    foreach ($line in Get-Content -LiteralPath $registrarPath) {
        if ($line -match '^\s*j (?<variable>jVar\d*) = j\.(?<type>QUERY|MUTATION);') {
            $typeByVariable[$Matches.variable] = $Matches.type
            continue
        }

        $operationPattern = '(?:aVar\.a\(|[a-z]\.a\(aVar,\s*)"(?<key>[^"]+)",\s*new i\("(?<id>[^"]+)",\s*"(?<name>[^"]+)",\s*(?<typeVariable>jVar\d*)'
        if ($line -notmatch $operationPattern) {
            continue
        }

        $typeVariable = $Matches.typeVariable
        if (-not $typeByVariable.ContainsKey($typeVariable)) {
            throw "GraphQL操作種別を解決できません: $registrarName / $line"
        }
        $operations += [pscustomobject][ordered]@{
            key = $Matches.key
            operationId = $Matches.id
            operationName = $Matches.name
            type = $typeByVariable[$typeVariable]
        }
    }
}

$operations = @($operations | Sort-Object key -Unique)
if ($operations.Count -lt 100) {
    throw "GraphQL操作の抽出件数が少なすぎます: $($operations.Count)"
}

$operationDocument = [ordered]@{
    source = [ordered]@{
        packageName = 'com.twitter.android'
        versionName = '12.19.1-release.0'
        versionCode = 312191000
    }
    count = $operations.Count
    operations = $operations
}
$operationPath = Join-Path $generatedRoot 'android-graphql-operations.json'
$operationDocument | ConvertTo-Json -Depth 8 -Compress | Set-Content -LiteralPath $operationPath -Encoding utf8

$endpointMatches = @(
    & rg --no-filename --only-matching '"/(?:1\.1|2)/[^" ]+' $sourceRoot --glob '*.java'
)
if ($LASTEXITCODE -notin @(0, 1)) {
    throw "RESTエンドポイント抽出に失敗しました。終了コード: $LASTEXITCODE"
}
$endpoints = @(
    $endpointMatches |
        ForEach-Object { $_.TrimStart('"') } |
        Where-Object { $_ -notmatch '[{}+]' } |
        Sort-Object -Unique
)
$endpointDocument = [ordered]@{
    source = [ordered]@{
        packageName = 'com.twitter.android'
        versionName = '12.19.1-release.0'
        versionCode = 312191000
    }
    count = $endpoints.Count
    endpoints = $endpoints
}
$endpointPath = Join-Path $generatedRoot 'android-rest-endpoints.json'
$endpointDocument | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $endpointPath -Encoding utf8

$archive = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'x-apks') -Filter '*.apkm' -File | Select-Object -First 1
$baseApkPath = Join-Path $resolvedExtractedRoot 'base.apk'
$infoPath = Join-Path $resolvedExtractedRoot 'info.json'
if ($null -eq $archive -or -not (Test-Path -LiteralPath $baseApkPath) -or -not (Test-Path -LiteralPath $infoPath)) {
    throw 'APKM、base.apk、またはinfo.jsonが不足しています。'
}
$info = Get-Content -Raw -LiteralPath $infoPath | ConvertFrom-Json
$metadata = [ordered]@{
    packageName = $info.pname
    versionName = $info.release_version
    versionCode = [long]$info.versioncode
    minApi = [int]$info.min_api
    apkmSha256 = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    baseApkSha256 = (Get-FileHash -LiteralPath $baseApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    jadxVersion = '1.5.6'
    jadxCompletedClasses = 106782
    jadxTotalClasses = 106783
    jadxReportedErrors = 1005
}
$metadataPath = Join-Path $generatedRoot 'android-apk-metadata.json'
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8

$featureManifestPath = Join-Path $resolvedJadxRoot 'resources\res\raw\feature_switch_manifest'
if (-not (Test-Path -LiteralPath $featureManifestPath)) {
    throw "Feature Switch manifestが見つかりません: $featureManifestPath"
}
$featureManifest = Get-Content -Raw -LiteralPath $featureManifestPath | ConvertFrom-Json -AsHashtable
$booleanDefaults = [ordered]@{}
foreach ($featureName in @($featureManifest.default.config.Keys | Sort-Object)) {
    $value = $featureManifest.default.config[$featureName].value
    if ($value -is [bool]) {
        $booleanDefaults[$featureName] = $value
    }
}
$featureDocument = [ordered]@{
    source = [ordered]@{
        packageName = 'com.twitter.android'
        versionName = '12.19.1-release.0'
        versionCode = 312191000
        featureSetToken = $featureManifest.default.feature_set_token
    }
    count = $booleanDefaults.Count
    defaults = $booleanDefaults
}
$featureJson = $featureDocument | ConvertTo-Json -Depth 6 -Compress
$featurePath = Join-Path $generatedRoot 'android-boolean-feature-defaults.json'
$featureJson | Set-Content -LiteralPath $featurePath -Encoding utf8
$resourceRoot = Join-Path $repositoryRoot 'src\main\resources\x-api'
New-Item -ItemType Directory -Path $resourceRoot -Force | Out-Null
$featureJson | Set-Content -LiteralPath (Join-Path $resourceRoot 'android-boolean-feature-defaults.json') -Encoding utf8

Write-Host "Android API抽出完了: GraphQL $($operations.Count)件 / REST $($endpoints.Count)件 / Boolean Feature $($booleanDefaults.Count)件"
Write-Host "生成先: $generatedRoot"
