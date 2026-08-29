$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidRoot = Join-Path $repositoryRoot 'android'
$reportPath = Join-Path $repositoryRoot 'target\android-osv-audit.json'
$gradle = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
    Join-Path $androidRoot 'gradlew.bat'
}
else {
    Join-Path $androidRoot 'gradlew'
}

Push-Location $androidRoot
try {
    $dependencyOutput = @(
        & $gradle --no-daemon --console=plain `
            :app:dependencies --configuration releaseRuntimeClasspath 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle依存関係一覧の生成に失敗しました。終了コード: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$coordinates = [Collections.Generic.Dictionary[string, object]]::new(
    [StringComparer]::Ordinal
)
foreach ($lineValue in $dependencyOutput) {
    $line = $lineValue.ToString()
    if ($line -notmatch '^[|+\\\s]*---\s+([^:\s]+):([^:\s]+):([^\s]+)(?:\s+->\s+([^\s]+))?') {
        continue
    }
    $version = if ([string]::IsNullOrWhiteSpace($Matches[4])) {
        $Matches[3]
    }
    else {
        $Matches[4]
    }
    $version = $version.TrimEnd('(', '*', ')')
    if ($version -notmatch '^[0-9][0-9A-Za-z.+_-]*$') {
        continue
    }
    $name = "$($Matches[1]):$($Matches[2])"
    $coordinates["$name@$version"] = [pscustomobject]@{
        Name = $name
        Version = $version
    }
}

$packages = @($coordinates.Values)
if ($packages.Count -eq 0) {
    throw '検査対象のGradle依存関係を取得できませんでした。'
}

$queries = @(
    $packages | ForEach-Object {
        @{
            package = @{
                ecosystem = 'Maven'
                name = $_.Name
            }
            version = $_.Version
        }
    }
)
$requestBody = @{ queries = $queries } | ConvertTo-Json -Depth 6 -Compress
$response = Invoke-RestMethod `
    -Method Post `
    -Uri 'https://api.osv.dev/v1/querybatch' `
    -ContentType 'application/json' `
    -Body $requestBody
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($reportPath)) | Out-Null
$response | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding utf8

$findings = @()
for ($index = 0; $index -lt $packages.Count; $index += 1) {
    $result = @($response.results)[$index]
    if ($null -eq $result -or $null -eq $result.PSObject.Properties['vulns']) {
        continue
    }
    foreach ($vulnerability in @($result.vulns)) {
        $withdrawnProperty = $vulnerability.PSObject.Properties['withdrawn']
        if ($null -eq $withdrawnProperty -or $null -eq $withdrawnProperty.Value) {
            $findings += [pscustomobject]@{
                Package = "$($packages[$index].Name)@$($packages[$index].Version)"
                Vulnerability = $vulnerability.id
            }
        }
    }
}

if ($findings.Count -gt 0) {
    $findings | Format-Table -AutoSize
    throw "$($findings.Count)件の未撤回脆弱性がGradle依存関係で見つかりました。"
}

Write-Host "OSV監査完了: $($packages.Count)件のGradle依存関係に既知の未撤回脆弱性はありません。"
