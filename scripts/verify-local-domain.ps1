param([string]$JarPath = "")

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $version = (& mvn -q help:evaluate '-Dexpression=project.version' '-DforceStdout').Trim()
    $JarPath = Join-Path $repositoryRoot "target\nytweetdeck-$version.jar"
}
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$plan = & (Join-Path $PSScriptRoot 'install-local-domain.ps1') -DryRun | ConvertFrom-Json
if ($plan.host -ne 'ny.tweetdeck.com' `
        -or $plan.address -ne '127.0.0.1' `
        -or $plan.httpsPort -ne 443 `
        -or $plan.httpPort -ne 18080 `
        -or $plan.schemaVersion -ne 2 `
        -or $plan.rootCertificatePath -notlike '*nytweetdeck-local-ca.cer') {
    throw 'ローカルドメインの導入計画が不正です。'
}
$shell = Get-Command sh -ErrorAction SilentlyContinue
if ($null -eq $shell) { $shell = Get-Command bash -ErrorAction SilentlyContinue }
if ($null -eq $shell) { throw 'macOS/Linux証明書スクリプトの検証にshが必要です。' }
$installShell = Join-Path $PSScriptRoot 'install-local-domain.sh'
$uninstallShell = Join-Path $PSScriptRoot 'uninstall-local-domain.sh'
& $shell.Source -n $installShell
if ($LASTEXITCODE -ne 0) { throw 'install-local-domain.shの構文検証に失敗しました。' }
& $shell.Source -n $uninstallShell
if ($LASTEXITCODE -ne 0) { throw 'uninstall-local-domain.shの構文検証に失敗しました。' }
$linuxPlan = (& $shell.Source $installShell --platform linux --dry-run) -join "`n"
$macPlan = (& $shell.Source $installShell --platform macos --dry-run) -join "`n"
if ($linuxPlan -notlike '*httpsPort=443*' `
        -or $linuxPlan -notlike '*ny.tweetdeck.com.p12*' `
        -or $linuxPlan -notlike '*nytweetdeck-local-ca.cer*') {
    throw 'Linux証明書導入計画が不正です。'
}
if ($macPlan -notlike '*httpsPort=18443*' `
        -or $macPlan -notlike '*ny.tweetdeck.com.p12*' `
        -or $macPlan -notlike '*nytweetdeck-local-ca.cer*') {
    throw 'macOS証明書導入計画が不正です。'
}
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("nytweetdeck-https-test-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
$caKeyStore = Join-Path $temporaryDirectory 'ca.p12'
$keyStore = Join-Path $temporaryDirectory 'server.p12'
$rootCertificate = Join-Path $temporaryDirectory 'nytweetdeck-local-ca.cer'
$certificateRequest = Join-Path $temporaryDirectory 'ny.tweetdeck.com.csr'
$serverCertificate = Join-Path $temporaryDirectory 'ny.tweetdeck.com.cer'
$password = 'NyTweetDeck-test-only'
$process = $null
try {
    & keytool -genkeypair -alias nytweetdeck-local-ca -keyalg RSA -keysize 2048 `
        -storetype PKCS12 -keystore $caKeyStore -storepass $password -keypass $password `
        -dname 'CN=NyTweetDeck Local Root CA' -ext 'BC=ca:true,pathlen:0' `
        -ext 'KU=keyCertSign,cRLSign' -validity 2 -noprompt *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用ルートCAを生成できませんでした。' }
    & keytool -exportcert -rfc -alias nytweetdeck-local-ca -keystore $caKeyStore `
        -storepass $password -file $rootCertificate *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用ルートCAを出力できませんでした。' }
    & keytool -genkeypair -alias nytweetdeck -keyalg RSA -keysize 2048 `
        -storetype PKCS12 -keystore $keyStore -storepass $password -keypass $password `
        -dname 'CN=ny.tweetdeck.com' -ext 'SAN=dns:ny.tweetdeck.com,ip:127.0.0.1' `
        -ext 'EKU=serverAuth' -ext 'BC=ca:false' -validity 1 -noprompt *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用サーバー鍵を生成できませんでした。' }
    & keytool -certreq -alias nytweetdeck -keystore $keyStore -storepass $password `
        -file $certificateRequest -ext 'SAN=dns:ny.tweetdeck.com,ip:127.0.0.1' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用署名要求を生成できませんでした。' }
    & keytool -gencert -rfc -alias nytweetdeck-local-ca -keystore $caKeyStore `
        -storepass $password -infile $certificateRequest -outfile $serverCertificate `
        -ext 'SAN=dns:ny.tweetdeck.com,ip:127.0.0.1' -ext 'EKU=serverAuth' `
        -ext 'KU=digitalSignature,keyEncipherment' -ext 'BC=ca:false' -validity 1 *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用サーバー証明書へ署名できませんでした。' }
    & keytool -importcert -alias nytweetdeck-local-ca -keystore $keyStore `
        -storepass $password -file $rootCertificate -noprompt *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用ルートCAをキーストアへ登録できませんでした。' }
    & keytool -importcert -alias nytweetdeck -keystore $keyStore -storepass $password `
        -file $serverCertificate -noprompt *> $null
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用証明書チェーンを構成できませんでした。' }
    $chainPem = (& keytool -list -rfc -alias nytweetdeck -keystore $keyStore `
            -storepass $password 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 `
            -or ([regex]::Matches($chainPem, '-----BEGIN CERTIFICATE-----')).Count -ne 2) {
        throw 'HTTPS検証用キーストアにルートCAとサーバー証明書のチェーンがありません。'
    }
    $arguments = @(
        '-jar', ('"{0}"' -f $resolvedJar),
        '--server.address=127.0.0.1',
        '--server.port=18443',
        '--server.ssl.enabled=true',
        ('--server.ssl.key-store="{0}"' -f $keyStore),
        "--server.ssl.key-store-password=$password",
        '--server.ssl.key-store-type=PKCS12',
        '--nytweetdeck.http.port=18081',
        '--nytweetdeck.x-api.auto-refresh=false'
    ) -join ' '
    $processOptions = @{
        FilePath = 'java'
        ArgumentList = $arguments
        WorkingDirectory = $repositoryRoot
        RedirectStandardOutput = Join-Path $temporaryDirectory 'server.log'
        RedirectStandardError = Join-Path $temporaryDirectory 'server-error.log'
        PassThru = $true
    }
    if ($PSVersionTable.PSEdition -ne 'Core' -or $IsWindows) {
        $processOptions.WindowStyle = 'Hidden'
    }
    $process = Start-Process @processOptions
    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt += 1) {
        if ($process.HasExited) { throw 'HTTPS検証サーバーが起動前に終了しました。' }
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/v1/system/status' -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        }
        catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) { throw 'HTTP互換コネクタが20秒以内に起動しませんでした。' }
    $opensslCommands = @(
        Get-Command openssl -CommandType Application -ErrorAction SilentlyContinue
    )
    $opensslPath = if ($opensslCommands.Count -eq 0) {
        $null
    } else {
        $opensslCommands[0].Source
    }
    if ($PSVersionTable.PSEdition -ne 'Core' -or $IsWindows) {
        $gitOpenSslCandidates = @(
            (Join-Path $env:ProgramFiles 'Git\mingw64\bin\openssl.exe')
        )
        foreach ($gitCommand in @(Get-Command git.exe -CommandType Application -All `
                    -ErrorAction SilentlyContinue)) {
            $gitRoot = Split-Path -Parent (Split-Path -Parent $gitCommand.Source)
            $gitOpenSslCandidates += Join-Path $gitRoot 'mingw64\bin\openssl.exe'
        }
        foreach ($gitOpenSsl in $gitOpenSslCandidates | Select-Object -Unique) {
            $resolvedGitOpenSsl = Resolve-Path `
                -LiteralPath $gitOpenSsl `
                -ErrorAction SilentlyContinue
            if ($null -ne $resolvedGitOpenSsl) {
                $opensslPath = $resolvedGitOpenSsl.Path
                break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($opensslPath)) {
        throw '証明書チェーン検証にOpenSSLが必要です。'
    }
    $httpRequest = "GET /api/v1/system/status HTTP/1.1`r`n" +
        "Host: ny.tweetdeck.com:18443`r`nConnection: close`r`n`r`n"
    $opensslOutput = $httpRequest | & $opensslPath s_client `
        -connect '127.0.0.1:18443' `
        -servername 'ny.tweetdeck.com' `
        -verify_hostname 'ny.tweetdeck.com' `
        -CAfile $rootCertificate `
        -verify_return_error 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "専用CAを使ったローカルHTTPSの証明書検証に失敗しました: $opensslOutput"
    }
    $httpsResponse = $opensslOutput -join [Environment]::NewLine
    if ($httpsResponse -notmatch 'Verify return code: 0 \(ok\)' `
            -or $httpsResponse -notmatch 'HTTP/1\.1 200') {
        throw '専用CAの証明書チェーン、ホスト名、またはHTTPS応答が不正です。'
    }
    Write-Host '専用CAで署名したny.tweetdeck.com用HTTPSとHTTP互換コネクタを検証しました。'
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
