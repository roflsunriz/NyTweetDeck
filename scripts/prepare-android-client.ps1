param(
    [string]$JadxRoot = "x-apks\jadx-12.19.1",
    [string]$Destination = ".local\android-client.properties"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $repositoryRoot $JadxRoot)).Path
$credentialSource = Join-Path $sourceRoot 'sources\com\twitter\network\oauth\t.java'
if (-not (Test-Path -LiteralPath $credentialSource -PathType Leaf)) {
    throw "Android OAuth鍵プロバイダーが見つかりません: $credentialSource"
}

$destinationPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $Destination))
$localRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.local'))
if (-not $destinationPath.StartsWith($localRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw '資格情報の出力先はリポジトリの.local配下に限定されます。'
}

$source = Get-Content -Raw -LiteralPath $credentialSource
$arrayMatches = [regex]::Matches($source, 'byte\[\]\s+\w+\s*=\s*\{(?<values>[^}]+)\}')
if ($arrayMatches.Count -lt 2) {
    throw 'Android OAuth鍵プロバイダーから2つのbyte配列を検出できません。'
}

function Resolve-ByteToken([string]$Token) {
    $trimmed = $Token.Trim()
    $numeric = 0
    if ([int]::TryParse($trimmed, [ref]$numeric)) {
        return $numeric
    }
    if ($trimmed -eq 'PSSSigner.TRAILER_IMPLICIT') {
        $constantSource = Join-Path $sourceRoot 'sources\org\bouncycastle\crypto\signers\PSSSigner.java'
        $constantText = Get-Content -Raw -LiteralPath $constantSource
        $constantMatch = [regex]::Match($constantText, 'TRAILER_IMPLICIT\s*=\s*(?<value>-?\d+)')
        if (-not $constantMatch.Success) {
            throw 'PSSSigner.TRAILER_IMPLICITを解決できません。'
        }
        return [int]$constantMatch.Groups['value'].Value
    }
    throw "未対応のbyte配列要素です: $trimmed"
}

function Decode-ObfuscatedArray([System.Text.RegularExpressions.Match]$Match) {
    $builder = [Text.StringBuilder]::new()
    foreach ($token in $Match.Groups['values'].Value.Split(',')) {
        $value = Resolve-ByteToken $token
        [void]$builder.Append([char](22 - $value))
    }
    return $builder.ToString()
}

$consumerKey = Decode-ObfuscatedArray $arrayMatches[0]
$consumerSecret = Decode-ObfuscatedArray $arrayMatches[1]
if ($consumerKey.Length -ne 20 -or $consumerSecret.Length -ne 43) {
    throw '復元したAndroidクライアント資格情報の長さが解析対象版と一致しません。'
}
if ($consumerKey -notmatch '^[A-Za-z0-9_-]+$' -or $consumerSecret -notmatch '^[A-Za-z0-9_-]+$') {
    throw '復元したAndroidクライアント資格情報の文字種が不正です。'
}

$parent = Split-Path -Parent $destinationPath
[IO.Directory]::CreateDirectory($parent) | Out-Null
$temporaryPath = "$destinationPath.tmp"
$content = "consumerKey=$consumerKey`nconsumerSecret=$consumerSecret`n"
[IO.File]::WriteAllText($temporaryPath, $content, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $temporaryPath -Destination $destinationPath -Force

if ($IsWindows) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = Get-Acl -LiteralPath $destinationPath
    $acl.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $identity,
        [Security.AccessControl.FileSystemRights]::Read -bor [Security.AccessControl.FileSystemRights]::Write,
        [Security.AccessControl.AccessControlType]::Allow
    )
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $destinationPath -AclObject $acl
}

[pscustomobject]@{
    destination = [IO.Path]::GetRelativePath($repositoryRoot, $destinationPath)
    consumerKeyLength = $consumerKey.Length
    consumerSecretLength = $consumerSecret.Length
    valuesPrinted = $false
}
