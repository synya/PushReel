param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDir = Join-Path $env:LOCALAPPDATA 'PushReel\signing'
$keyPath = Join-Path $signingDir 'pushreel-release.p12'
$secretPath = Join-Path $signingDir 'release-password.dpapi'

if (-not (Test-Path -LiteralPath $keyPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $secretPath -PathType Leaf)) {
    throw 'The local PushReel release signing key or protected password is missing.'
}

$securePassword = Get-Content -LiteralPath $secretPath -Raw | ConvertTo-SecureString
$plainPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
$env:PUSHREEL_RELEASE_STORE_FILE = $keyPath
$env:PUSHREEL_RELEASE_KEY_ALIAS = 'pushreel'
$env:PUSHREEL_RELEASE_STORE_PASSWORD = $plainPassword
$env:PUSHREEL_RELEASE_KEY_PASSWORD = $plainPassword

try {
    $gradleArgs = @(':app:assembleStableRelease', '--no-configuration-cache')
    if ($Offline) {
        $gradleArgs += '--offline'
    }
    Push-Location $projectRoot
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Release build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:PUSHREEL_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:PUSHREEL_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:PUSHREEL_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PUSHREEL_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    $plainPassword = $null
}

$apkDir = Join-Path $projectRoot 'app\build\outputs\apk\stable\release'
$metadata = Get-Content -LiteralPath (Join-Path $apkDir 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'com.pushreel.app' -or
    $metadata.variantName -ne 'stableRelease' -or
    $metadata.elements.Count -ne 1) {
    throw 'Unexpected release APK metadata.'
}

$element = $metadata.elements[0]
$version = [string]$element.versionName
if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
    throw "Unexpected release version name: $version"
}

$sourceApk = Join-Path $apkDir ([string]$element.outputFile)
if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf) -or
    $sourceApk.EndsWith('-unsigned.apk', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signed release APK was not produced.'
}

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) {
    throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required to verify the release APK.'
}
$buildToolsDir = Join-Path $sdkRoot 'build-tools'
$apksigner = Get-ChildItem -LiteralPath $buildToolsDir -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1
if (-not $apksigner) {
    throw 'Android apksigner was not found.'
}
$signature = & $apksigner verify --print-certs $sourceApk 2>&1
$expectedSigner = 'd4a19fddf1001c9d26a3669301ac62d69f2b9d99fd0aa9d7b2bde5c10aaeae5f'
if ($LASTEXITCODE -ne 0 -or
    ($signature -join "`n") -notmatch [regex]::Escape($expectedSigner)) {
    throw 'Release APK signature does not match the PushReel release key.'
}

$namedApk = Join-Path $apkDir "push-reel-stable-release-v$version.apk"
Copy-Item -LiteralPath $sourceApk -Destination $namedApk -Force
Write-Output $namedApk
