$script:NyTweetDeckTaskName = 'NyTweetDeck'
$script:NyTweetDeckDomain = 'ny.tweetdeck.com'

function Get-NyTweetDeckDataRoot {
    return Join-Path `
        ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) `
        'NyTweetDeck'
}

function Get-NyTweetDeckDomainConfig {
    param([switch]$Required)

    $configPath = Join-Path (Get-NyTweetDeckDataRoot) 'local-domain.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        if ($Required) {
            throw 'ローカルHTTPS設定がありません。install-nytweetdeck.ps1を再実行してください。'
        }
        return $null
    }
    try {
        $config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
    }
    catch {
        throw "ローカルHTTPS設定を読み取れません: $configPath"
    }
    if ($config.schemaVersion -ne 2 `
            -or $config.host -ne $script:NyTweetDeckDomain `
            -or $config.httpsPort -ne 443 `
            -or $config.httpPort -ne 18080 `
            -or [string]::IsNullOrWhiteSpace([string]$config.keyStorePath) `
            -or [string]::IsNullOrWhiteSpace([string]$config.keyStorePassword) `
            -or [string]::IsNullOrWhiteSpace([string]$config.rootCertificatePath) `
            -or [string]::IsNullOrWhiteSpace([string]$config.rootThumbprint) `
            -or -not (Test-Path -LiteralPath ([string]$config.keyStorePath)) `
            -or -not (Test-Path -LiteralPath ([string]$config.rootCertificatePath))) {
        throw "ローカルHTTPS設定が不正です。install-nytweetdeck.ps1を再実行してください: $configPath"
    }
    return $config
}

function Test-NyTweetDeckRootCertificate {
    param([Parameter(Mandatory)]$Config)

    return Test-Path -LiteralPath (
        'Cert:\CurrentUser\Root\' + [string]$Config.rootThumbprint)
}

function Test-NyTweetDeckHostsMapping {
    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    if (-not (Test-Path -LiteralPath $hostsPath)) {
        return $false
    }
    $mapping = Select-String `
        -LiteralPath $hostsPath `
        -Pattern '^\s*127\.0\.0\.1\s+ny\.tweetdeck\.com\s*$'
    return $null -ne $mapping
}

function Assert-NyTweetDeckRootCertificateFile {
    param([Parameter(Mandatory)]$Config)

    $certificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2(
        [string]$Config.rootCertificatePath)
    try {
        if ($certificate.Thumbprint -ne [string]$Config.rootThumbprint `
                -or $certificate.Subject -ne 'CN=NyTweetDeck Local Root CA' `
                -or $certificate.Issuer -ne $certificate.Subject) {
            throw '専用CAファイルとローカルHTTPS設定が一致しません。'
        }
        $basicConstraintsExtension = $certificate.Extensions | Where-Object {
            $_.Oid.Value -eq '2.5.29.19'
        } | Select-Object -First 1
        if ($null -eq $basicConstraintsExtension) {
            throw '専用CAファイルにBasic Constraintsがありません。'
        }
        $basicConstraints = New-Object `
            Security.Cryptography.X509Certificates.X509BasicConstraintsExtension(
                $basicConstraintsExtension,
                $basicConstraintsExtension.Critical)
        if (-not $basicConstraints.CertificateAuthority) {
            throw '専用CAファイルがCA証明書ではありません。'
        }
        return $certificate
    }
    catch {
        $certificate.Dispose()
        throw
    }
}

function Repair-NyTweetDeckRootCertificate {
    param([Parameter(Mandatory)]$Config)

    if (Test-NyTweetDeckRootCertificate -Config $Config) {
        return
    }
    $certificate = Assert-NyTweetDeckRootCertificateFile -Config $Config
    $store = New-Object Security.Cryptography.X509Certificates.X509Store(
        [Security.Cryptography.X509Certificates.StoreName]::Root,
        [Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
    try {
        $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $existing = @($store.Certificates | Where-Object Thumbprint -eq $certificate.Thumbprint)
        if ($existing.Count -eq 0) {
            $store.Add($certificate)
        }
    }
    finally {
        $store.Close()
        $store.Dispose()
        $certificate.Dispose()
    }
    if (-not (Test-NyTweetDeckRootCertificate -Config $Config)) {
        throw '現在ユーザーの信頼済みルートへNyTweetDeck専用CAを登録できませんでした。'
    }
}

function Remove-NyTweetDeckRootCertificate {
    param(
        [Parameter(Mandatory)][string]$Thumbprint,
        [switch]$IgnoreFailure
    )

    try {
        $store = New-Object Security.Cryptography.X509Certificates.X509Store(
            [Security.Cryptography.X509Certificates.StoreName]::Root,
            [Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)
        try {
            $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
            foreach ($certificate in @(
                    $store.Certificates | Where-Object Thumbprint -eq $Thumbprint
                )) {
                $store.Remove($certificate)
            }
        }
        finally {
            $store.Close()
            $store.Dispose()
        }
    }
    catch {
        if (-not $IgnoreFailure) {
            throw
        }
    }
}

function Write-NyTweetDeckRuntimeState {
    param(
        [Parameter(Mandatory)][ValidateSet('starting', 'ready', 'error')][string]$Status,
        [string]$JarPath = '',
        [bool]$HttpsEnabled = $false,
        [int]$ProcessId = 0,
        [string]$Message = ''
    )

    $dataRoot = Get-NyTweetDeckDataRoot
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    $statePath = Join-Path $dataRoot 'runtime-state.json'
    $temporaryPath = "$statePath.tmp"
    $state = [ordered]@{
        schemaVersion = 1
        status = $Status
        jarPath = $JarPath
        httpsEnabled = $HttpsEnabled
        processId = $ProcessId
        message = $Message
        updatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    $state | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $temporaryPath -Encoding utf8
    Move-Item -LiteralPath $temporaryPath -Destination $statePath -Force
}

function Get-NyTweetDeckTaskJarPath {
    $task = Get-ScheduledTask -TaskName $script:NyTweetDeckTaskName -ErrorAction SilentlyContinue
    if ($null -eq $task -or $task.Actions.Count -eq 0) {
        return $null
    }
    $arguments = [string]$task.Actions[0].Arguments
    $match = [regex]::Match($arguments, '(?i)-JarPath\s+"(?<path>[^"]+)"')
    if (-not $match.Success) {
        return $null
    }
    return [IO.Path]::GetFullPath($match.Groups['path'].Value)
}

function Get-NyTweetDeckTaskLauncherPath {
    $task = Get-ScheduledTask -TaskName $script:NyTweetDeckTaskName -ErrorAction SilentlyContinue
    if ($null -eq $task -or $task.Actions.Count -eq 0) {
        return $null
    }
    $arguments = [string]$task.Actions[0].Arguments
    $match = [regex]::Match($arguments, '(?i)-File\s+"(?<path>[^"]+)"')
    if (-not $match.Success) {
        return $null
    }
    return [IO.Path]::GetFullPath($match.Groups['path'].Value)
}

function Stop-NyTweetDeckRunningInstance {
    param([string[]]$ExpectedJarPaths = @())

    Stop-ScheduledTask -TaskName $script:NyTweetDeckTaskName -ErrorAction SilentlyContinue
    $normalizedExpectedPaths = @($ExpectedJarPaths | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    } | ForEach-Object { [IO.Path]::GetFullPath($_) } | Select-Object -Unique)
    $connections = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
        $_.LocalPort -in @(443, 18080) -and $_.LocalAddress -in @('127.0.0.1', '::1')
    })
    foreach ($processId in @($connections | Select-Object -ExpandProperty OwningProcess -Unique)) {
        $process = Get-Process -Id $processId -ErrorAction Stop
        if ($process.ProcessName -notin @('java', 'javaw')) {
            throw "NyTweetDeckのポートを別プロセスが使用しています: $($process.ProcessName)"
        }
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
        $commandLine = [string]$processInfo.CommandLine
        $matchesExpectedJar = $normalizedExpectedPaths.Count -gt 0 -and @(
            $normalizedExpectedPaths | Where-Object {
                $commandLine.IndexOf($_, [StringComparison]::OrdinalIgnoreCase) -ge 0
            }
        ).Count -gt 0
        if (-not $matchesExpectedJar) {
            throw "18080/443番のJavaプロセスをNyTweetDeckとして確認できません: PID=$processId"
        }
        Stop-Process -Id $processId -Force
    }
}

function Wait-NyTweetDeckReady {
    param(
        [bool]$RequireHttps,
        [int]$TimeoutSeconds = 60
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $httpResponse = Invoke-WebRequest `
                -UseBasicParsing `
                -Uri 'http://127.0.0.1:18080/api/v1/system/status' `
                -TimeoutSec 2
            if ([int]$httpResponse.StatusCode -eq 200) {
                if (-not $RequireHttps) {
                    return
                }
                $httpsResponse = Invoke-WebRequest `
                    -UseBasicParsing `
                    -Uri 'https://ny.tweetdeck.com/api/v1/system/status' `
                    -TimeoutSec 2
                if ([int]$httpsResponse.StatusCode -eq 200) {
                    return
                }
            }
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    }
    $detail = if ([string]::IsNullOrWhiteSpace($lastError)) {
        '応答がありません。'
    } else {
        $lastError
    }
    throw "NyTweetDeckが${TimeoutSeconds}秒以内に準備できませんでした: $detail"
}
