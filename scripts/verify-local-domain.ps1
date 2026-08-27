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
        -or $plan.httpPort -ne 18080) {
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
if ($linuxPlan -notlike '*httpsPort=443*' -or $linuxPlan -notlike '*ny.tweetdeck.com.p12*') {
    throw 'Linux証明書導入計画が不正です。'
}
if ($macPlan -notlike '*httpsPort=18443*' -or $macPlan -notlike '*ny.tweetdeck.com.p12*') {
    throw 'macOS証明書導入計画が不正です。'
}
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("nytweetdeck-https-test-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
$keyStore = Join-Path $temporaryDirectory 'test.p12'
$password = 'NyTweetDeck-test-only'
$process = $null
try {
    $keytoolOutput = & keytool `
        -genkeypair `
        -alias nytweetdeck `
        -keyalg RSA `
        -keysize 2048 `
        -storetype PKCS12 `
        -keystore $keyStore `
        -storepass $password `
        -keypass $password `
        -dname 'CN=ny.tweetdeck.com' `
        -ext 'SAN=dns:ny.tweetdeck.com,ip:127.0.0.1' `
        -validity 1 `
        -noprompt 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS検証用証明書を生成できませんでした。' }
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
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.ServerCertificateCustomValidationCallback = `
        [Net.Http.HttpClientHandler]::DangerousAcceptAnyServerCertificateValidator
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $request = [Net.Http.HttpRequestMessage]::new(
            [Net.Http.HttpMethod]::Get,
            'https://127.0.0.1:18443/api/v1/system/status')
        $request.Headers.Host = 'ny.tweetdeck.com:18443'
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200) {
            throw "ローカルHTTPS応答が不正です: $([int]$response.StatusCode)"
        }
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
    Write-Host 'ny.tweetdeck.com用HTTPSと127.0.0.1 HTTP互換コネクタを検証しました。'
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
