param(
    [switch]$DryRun,
    [switch]$ElevatedHosts
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$isWindowsHost = $PSVersionTable.PSEdition -ne 'Core' -or $IsWindows
if (-not $isWindowsHost -and -not $DryRun) {
    throw '現在のローカルドメイン自動インストーラーはWindows用です。'
}

$domain = 'ny.tweetdeck.com'
$beginMarker = '# BEGIN NyTweetDeck local domain'
$endMarker = '# END NyTweetDeck local domain'

function Set-ManagedHostsEntry {
    param([Parameter(Mandatory)][string]$HostsPath)

    $hostsContent = Get-Content -Raw -LiteralPath $HostsPath
    $managedPattern = '(?ms)^' + [regex]::Escape($beginMarker) + '.*?^' +
        [regex]::Escape($endMarker) + '\r?\n?'
    $hostsContent = [regex]::Replace($hostsContent, $managedPattern, '').TrimEnd("`r", "`n")
    $updatedHosts = $hostsContent +
        "`r`n$beginMarker`r`n127.0.0.1 $domain`r`n$endMarker`r`n"
    Set-Content -LiteralPath $HostsPath -Value $updatedHosts -Encoding ascii
}

function Test-ManagedHostsEntry {
    param([Parameter(Mandatory)][string]$HostsPath)

    $hostsContent = Get-Content -Raw -LiteralPath $HostsPath
    $expectedBlock = '(?ms)^' + [regex]::Escape($beginMarker) + '\r?\n' +
        '127\.0\.0\.1[ \t]+' + [regex]::Escape($domain) + '[ \t]*\r?\n' +
        [regex]::Escape($endMarker) + '[ \t]*\r?$'
    return $hostsContent -match $expectedBlock
}

function Remove-CurrentUserRootCertificate {
    param(
        [Parameter(Mandatory)][string]$Thumbprint,
        [switch]$IgnoreFailure
    )

    if (-not (Test-Path -LiteralPath "Cert:\CurrentUser\Root\$Thumbprint")) {
        return
    }
    & certutil.exe -user -delstore Root $Thumbprint *> $null
    if ($LASTEXITCODE -ne 0 -and -not $IgnoreFailure) {
        throw "現在のユーザーの信頼済みルートから証明書を削除できませんでした: $Thumbprint"
    }
}

if ($DryRun -and -not $isWindowsHost) {
    $hostsPath = '/etc/hosts'
    $dataRoot = '/tmp/NyTweetDeck'
} else {
    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $dataRoot = Join-Path `
        ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) `
        'NyTweetDeck'
}

if ($ElevatedHosts) {
    $isAdministrator = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdministrator) {
        throw 'hostsファイルの更新には管理者権限が必要です。'
    }
    Set-ManagedHostsEntry -HostsPath $hostsPath
    return
}

$httpsRoot = Join-Path $dataRoot 'https'
$keyStorePath = Join-Path $httpsRoot 'ny.tweetdeck.com.p12'
$certificatePath = Join-Path $httpsRoot 'ny.tweetdeck.com.cer'
$rootCertificatePath = Join-Path $httpsRoot 'nytweetdeck-local-ca.cer'
$configPath = Join-Path $dataRoot 'local-domain.json'
$plan = [ordered]@{
    host = $domain
    address = '127.0.0.1'
    httpsPort = 443
    httpPort = 18080
    hostsPath = $hostsPath
    keyStorePath = $keyStorePath
    rootCertificatePath = $rootCertificatePath
    certificateStore = 'Cert:\CurrentUser\Root'
    configPath = $configPath
    schemaVersion = 2
}
if ($DryRun) {
    $plan | ConvertTo-Json -Depth 3
    return
}

New-Item -ItemType Directory -Path $httpsRoot -Force | Out-Null
$stagingRoot = Join-Path $httpsRoot ('.install-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $stagingRoot | Out-Null
$stagedKeyStorePath = Join-Path $stagingRoot 'ny.tweetdeck.com.p12'
$stagedCertificatePath = Join-Path $stagingRoot 'ny.tweetdeck.com.cer'
$stagedRootCertificatePath = Join-Path $stagingRoot 'nytweetdeck-local-ca.cer'

$previousConfig = $null
if (Test-Path -LiteralPath $configPath) {
    $previousConfig = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
}

$passwordBytes = New-Object byte[] 24
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($passwordBytes)
}
finally {
    $random.Dispose()
}
$passwordText = [Convert]::ToBase64String($passwordBytes)
$password = ConvertTo-SecureString -String $passwordText -AsPlainText -Force
$rootCertificate = $null
$serverCertificate = $null
$rootTrusted = $false

try {
    $rootCertificate = New-SelfSignedCertificate `
        -Type Custom `
        -Subject 'CN=NyTweetDeck Local Root CA' `
        -FriendlyName 'NyTweetDeck Local Root CA' `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -KeyUsage CertSign, CRLSign, DigitalSignature `
        -NotAfter (Get-Date).AddYears(10) `
        -TextExtension @('2.5.29.19={critical}{text}ca=true&pathlength=0')
    Export-Certificate `
        -Cert $rootCertificate `
        -FilePath $stagedRootCertificatePath `
        -Type CERT `
        -Force | Out-Null
    & certutil.exe -user -f -addstore Root $stagedRootCertificatePath *> $null
    if ($LASTEXITCODE -ne 0) {
        throw '現在のユーザーの信頼済みルートへ専用CAを登録できませんでした。'
    }
    $rootTrusted = Test-Path -LiteralPath "Cert:\CurrentUser\Root\$($rootCertificate.Thumbprint)"
    if (-not $rootTrusted) {
        throw '現在のユーザーの信頼済みルートへ専用CAを登録できませんでした。'
    }

    $serverCertificate = New-SelfSignedCertificate `
        -Type Custom `
        -Subject "CN=$domain" `
        -DnsName $domain `
        -Signer $rootCertificate `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -KeyExportPolicy Exportable `
        -KeyUsage DigitalSignature, KeyEncipherment `
        -NotAfter (Get-Date).AddYears(2) `
        -TextExtension @(
            '2.5.29.19={critical}{text}ca=false',
            '2.5.29.37={text}1.3.6.1.5.5.7.3.1'
        )

    Export-PfxCertificate `
        -Cert $serverCertificate `
        -FilePath $stagedKeyStorePath `
        -Password $password `
        -ChainOption BuildChain `
        -Force | Out-Null
    Export-Certificate `
        -Cert $serverCertificate `
        -FilePath $stagedCertificatePath `
        -Type CERT `
        -Force | Out-Null
    $isAdministrator = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (Test-ManagedHostsEntry -HostsPath $hostsPath) {
        # 既存の管理ブロックが正しければ、証明書の更新だけに管理者権限は不要です。
    }
    elseif ($isAdministrator) {
        Set-ManagedHostsEntry -HostsPath $hostsPath
    } else {
        $elevatedProcess = Start-Process `
            -FilePath 'powershell.exe' `
            -ArgumentList @(
                '-NoProfile',
                '-ExecutionPolicy', 'Bypass',
                '-File', ('"{0}"' -f $PSCommandPath),
                '-ElevatedHosts'
            ) `
            -Verb RunAs `
            -WindowStyle Hidden `
            -Wait `
            -PassThru
        if ($elevatedProcess.ExitCode -ne 0) {
            throw 'hostsファイルを更新できませんでした。管理者確認を承認して再実行してください。'
        }
    }

    Move-Item -LiteralPath $stagedKeyStorePath -Destination $keyStorePath -Force
    Move-Item -LiteralPath $stagedCertificatePath -Destination $certificatePath -Force
    Move-Item `
        -LiteralPath $stagedRootCertificatePath `
        -Destination $rootCertificatePath `
        -Force
    $config = [ordered]@{
        schemaVersion = 2
        host = $domain
        httpsPort = 443
        httpPort = 18080
        keyStorePath = $keyStorePath
        keyStorePassword = $passwordText
        certificatePath = $certificatePath
        rootCertificatePath = $rootCertificatePath
        rootThumbprint = $rootCertificate.Thumbprint
    }
    $temporaryConfig = "$configPath.tmp"
    $config | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $temporaryConfig -Encoding utf8
    Move-Item -LiteralPath $temporaryConfig -Destination $configPath -Force

    if ($null -ne $previousConfig) {
        $oldThumbprints = @()
        if ($null -ne $previousConfig.PSObject.Properties['thumbprint']) {
            $oldThumbprints += [string]$previousConfig.thumbprint
        }
        if ($null -ne $previousConfig.PSObject.Properties['rootThumbprint']) {
            $oldThumbprints += [string]$previousConfig.rootThumbprint
        }
        foreach ($thumbprint in $oldThumbprints | Select-Object -Unique) {
            if (-not [string]::IsNullOrWhiteSpace($thumbprint) `
                    -and $thumbprint -ne $rootCertificate.Thumbprint) {
                Remove-Item `
                    -LiteralPath "Cert:\CurrentUser\My\$thumbprint" `
                    -Force `
                    -ErrorAction SilentlyContinue
                Remove-CurrentUserRootCertificate -Thumbprint $thumbprint
            }
        }
    }
}
catch {
    if ($rootTrusted -and $null -ne $rootCertificate) {
        Remove-CurrentUserRootCertificate `
            -Thumbprint $rootCertificate.Thumbprint `
            -IgnoreFailure
    }
    throw
}
finally {
    if ($null -ne $serverCertificate) {
        Remove-Item `
            -LiteralPath "Cert:\CurrentUser\My\$($serverCertificate.Thumbprint)" `
            -Force `
            -ErrorAction SilentlyContinue
    }
    if ($null -ne $rootCertificate) {
        Remove-Item `
            -LiteralPath "Cert:\CurrentUser\My\$($rootCertificate.Thumbprint)" `
            -Force `
            -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}

Write-Host "ローカルHTTPSを設定しました: https://$domain"
