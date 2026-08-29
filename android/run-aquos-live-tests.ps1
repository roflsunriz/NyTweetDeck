param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$MainApkPath = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk",
    [string]$TestApkPath = "$PSScriptRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
    [string]$ReleaseApkPath = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk",
    [switch]$MutationAuthorized,
    [string[]]$TestClasses = @(
        "dev.nytweetdeck.android.LiveTimelineSmokeTest",
        "dev.nytweetdeck.android.LiveReadOnlyParitySmokeTest",
        "dev.nytweetdeck.android.LiveTargetColumnsSmokeTest",
        "dev.nytweetdeck.android.LiveSecondaryColumnsSmokeTest",
        "dev.nytweetdeck.android.LiveRefreshSmokeTest",
        "dev.nytweetdeck.android.CachedTimelineSmokeTest"
    )
)

$mainApk = Resolve-Path -LiteralPath $MainApkPath -ErrorAction Stop
$testApk = Resolve-Path -LiteralPath $TestApkPath -ErrorAction Stop
$releaseApk = Resolve-Path -LiteralPath $ReleaseApkPath -ErrorAction Stop

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = @(& $AdbPath devices | Select-Object -Skip 1 | Where-Object {
        $_ -match "\sdevice$"
    } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devices.Count -ne 1) {
        throw "接続済みAndroid端末を1台にするか、-Serialを指定してください。"
    }
    $Serial = $devices[0]
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb実行に失敗しました: $($Arguments -join ' ')"
    }
}

$testExitCode = 0
try {
    # AQUOS Android 16では本体への -t がデータ消去を起こすため、絶対に付けない。
    Invoke-Adb install -r $mainApk.Path
    Invoke-Adb shell run-as dev.nytweetdeck.android ls no_backup/accounts/accounts.json
    Invoke-Adb install -r -t $testApk.Path
    foreach ($testClass in $TestClasses) {
        Invoke-Adb shell input keyevent WAKEUP
        Invoke-Adb shell wm dismiss-keyguard
        $authorizationArguments = if ($MutationAuthorized) {
            @("-e", "mutationAuthorized", "true")
        } else {
            @()
        }
        $instrumentOutput = @(& $AdbPath -s $Serial shell am instrument -w -r -e class $testClass @authorizationArguments dev.nytweetdeck.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
        $instrumentExitCode = $LASTEXITCODE
        $instrumentOutput | Write-Output
        if (
            $instrumentExitCode -ne 0 -or
            -not ($instrumentOutput -match "^OK \(") -or
            $instrumentOutput -match "INSTRUMENTATION_STATUS_CODE: -4"
        ) {
            throw "instrumentationテストが成功しませんでした: $testClass"
        }
    }
} catch {
    $testExitCode = 1
    Write-Error $_
} finally {
    # 検証後は同じ署名の非debuggable releaseへ戻し、認証データは保持する。
    try {
        Invoke-Adb install -r $releaseApk.Path
    } catch {
        $testExitCode = 1
        Write-Error "release APKへ戻せませんでした。アンインストールは行っていません。"
    }
}

if ($testExitCode -ne 0) {
    throw "AQUOS実X読み取り検証に失敗しました。"
}
