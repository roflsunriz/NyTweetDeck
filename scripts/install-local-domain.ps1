param(
    [switch]$DryRun,
    [switch]$Elevated
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows -and -not $DryRun) {
    throw '現在のローカルドメイン自動インストーラーはWindows用です。'
}
$domain = 'ny.tweetdeck.com'
if ($DryRun -and $PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) {
    $hostsPath = 'C:\Windows\System32\drivers\etc\hosts'
    $dataRoot = 'C:\Users\User\AppData\Local\NyTweetDeck'
} else {
    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $dataRoot = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'NyTweetDeck'
}
$httpsRoot = Join-Path $dataRoot 'https'
$keyStorePath = Join-Path $httpsRoot 'ny.tweetdeck.com.p12'
$certificatePath = Join-Path $httpsRoot 'ny.tweetdeck.com.cer'
$configPath = Join-Path $dataRoot 'local-domain.json'
$plan = [ordered]@{
    host = $domain
    address = '127.0.0.1'
    httpsPort = 443
    httpPort = 18080
    hostsPath = $hostsPath
    keyStorePath = $keyStorePath
    certificateStore = 'Cert:\CurrentUser\Root'
    configPath = $configPath
}
if ($DryRun) {
    $plan | ConvertTo-Json -Depth 3
    return
}
$isAdministrator = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdministrator -and -not $Elevated) {
    $elevatedProcess = Start-Process `
        -FilePath 'powershell.exe' `
        -ArgumentList @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', ('"{0}"' -f $PSCommandPath),
            '-Elevated'
        ) `
        -Verb RunAs `
        -Wait `
        -PassThru
    if ($elevatedProcess.ExitCode -ne 0) {
        $errorLog = Join-Path $dataRoot 'local-domain-install-error.log'
        $detail = if (Test-Path -LiteralPath $errorLog) {
            (Get-Content -Raw -LiteralPath $errorLog).Trim()
        } else {
            '詳細ログはありません。'
        }
        throw "ローカルドメイン設定の管理者処理に失敗しました。$detail"
    }
    return
}
if ($Elevated) {
    trap {
        New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
        ($_ | Out-String) | Set-Content `
            -LiteralPath (Join-Path $dataRoot 'local-domain-install-error.log') `
            -Encoding utf8
        exit 1
    }
}

New-Item -ItemType Directory -Path $httpsRoot -Force | Out-Null
if (Test-Path -LiteralPath $configPath) {
    $previous = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
    if (-not [string]::IsNullOrWhiteSpace([string]$previous.thumbprint)) {
        Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($previous.thumbprint)" -Force -ErrorAction SilentlyContinue
        & certutil -user -delstore Root ([string]$previous.thumbprint) *> $null
    }
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
$certificate = New-SelfSignedCertificate `
    -Subject "CN=$domain" `
    -DnsName $domain `
    -CertStoreLocation 'Cert:\CurrentUser\My' `
    -KeyAlgorithm RSA `
    -KeyLength 2048 `
    -HashAlgorithm SHA256 `
    -KeyExportPolicy Exportable `
    -NotAfter (Get-Date).AddYears(5) `
    -TextExtension @('2.5.29.19={critical}{text}ca=false', '2.5.29.37={text}1.3.6.1.5.5.7.3.1')
Export-PfxCertificate -Cert $certificate -FilePath $keyStorePath -Password $password -Force | Out-Null
Export-Certificate -Cert $certificate -FilePath $certificatePath -Type CERT -Force | Out-Null
& certutil -user -addstore Root $certificatePath *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'CurrentUserの信頼済みルートへ証明書を登録できませんでした。'
}

$beginMarker = '# BEGIN NyTweetDeck local domain'
$endMarker = '# END NyTweetDeck local domain'
$hostsContent = Get-Content -Raw -LiteralPath $hostsPath
$managedPattern = '(?ms)^' + [regex]::Escape($beginMarker) + '.*?^' + [regex]::Escape($endMarker) + '\r?\n?'
$hostsContent = [regex]::Replace($hostsContent, $managedPattern, '').TrimEnd("`r", "`n")
$updatedHosts = $hostsContent + "`r`n$beginMarker`r`n127.0.0.1 $domain`r`n$endMarker`r`n"
try {
    $hostsBackup = Join-Path $dataRoot 'hosts.before-local-domain'
    if (-not (Test-Path -LiteralPath $hostsBackup)) {
        Copy-Item -LiteralPath $hostsPath -Destination $hostsBackup
    }
    Set-Content -LiteralPath $hostsPath -Value $updatedHosts -Encoding ascii
}
catch {
    Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($certificate.Thumbprint)" -Force -ErrorAction SilentlyContinue
    & certutil -user -delstore Root ([string]$certificate.Thumbprint) *> $null
    Remove-Item -LiteralPath $keyStorePath,$certificatePath -Force -ErrorAction SilentlyContinue
    throw 'hostsファイルを更新できませんでした。管理者としてこのスクリプトを再実行してください。'
}
$config = [ordered]@{
    schemaVersion = 1
    host = $domain
    httpsPort = 443
    httpPort = 18080
    keyStorePath = $keyStorePath
    keyStorePassword = $passwordText
    thumbprint = $certificate.Thumbprint
}
$temporaryConfig = "$configPath.tmp"
$config | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $temporaryConfig -Encoding utf8
Move-Item -LiteralPath $temporaryConfig -Destination $configPath -Force
Remove-Item -LiteralPath (Join-Path $dataRoot 'local-domain-install-error.log') `
    -Force `
    -ErrorAction SilentlyContinue
Write-Host "ローカルHTTPSを設定しました: https://$domain"
