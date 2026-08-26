$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dependencyListPath = Join-Path $repositoryRoot 'target\maven-dependencies.txt'
$reportPath = Join-Path $repositoryRoot 'target\osv-audit.json'

Push-Location $repositoryRoot
try {
    & mvn --batch-mode --no-transfer-progress dependency:list "-DoutputFile=$dependencyListPath" '-DappendOutput=false'
    if ($LASTEXITCODE -ne 0) {
        throw "Maven依存関係一覧の生成に失敗しました。終了コード: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$packages = @(
    Get-Content -LiteralPath $dependencyListPath | ForEach-Object {
        if ($_ -match '^\s*([^:\s]+):([^:\s]+):([^:\s]+):([^:\s]+):([^:\s]+)') {
            [pscustomobject]@{
                Name = "$($Matches[1]):$($Matches[2])"
                Version = $Matches[4]
            }
        }
    }
)

if ($packages.Count -eq 0) {
    throw '検査対象のMaven依存関係を取得できませんでした。'
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
$response = Invoke-RestMethod -Method Post -Uri 'https://api.osv.dev/v1/querybatch' -ContentType 'application/json' -Body $requestBody
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
    throw "$($findings.Count)件の未撤回脆弱性がMaven依存関係で見つかりました。"
}

Write-Host "OSV監査完了: $($packages.Count)件のMaven依存関係に既知の未撤回脆弱性はありません。"
