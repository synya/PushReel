[CmdletBinding()]
param(
    [Parameter()]
    [string]$ApkPath = "app/build/outputs/apk/stable/debug/app-stable-debug.apk",

    [Parameter()]
    [string]$Serial
)

$ErrorActionPreference = "Stop"

function Stop-WithError {
    param([string]$Message)

    Write-Error $Message
    exit 1
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedApkPath = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repositoryRoot $ApkPath
}

if (-not (Test-Path -LiteralPath $resolvedApkPath -PathType Leaf)) {
    Stop-WithError "PushReel APK was not found at '$resolvedApkPath'. Build it first with '.\gradlew.bat :app:assembleStableDebug'."
}

$adbCommand = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
$adbPath = if ($adbCommand) { $adbCommand.Path } else { $null }
if (-not $adbPath) {
    $androidSdkRoots = @(
        $env:ANDROID_HOME
        $env:ANDROID_SDK_ROOT
        if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android/Sdk" }
    ) | Where-Object { $_ } | Select-Object -Unique

    foreach ($androidSdkRoot in $androidSdkRoots) {
        $adbCandidate = Join-Path $androidSdkRoot "platform-tools/adb.exe"
        if (Test-Path -LiteralPath $adbCandidate -PathType Leaf) {
            $adbPath = $adbCandidate
            break
        }
    }
}

if (-not $adbPath) {
    Stop-WithError "adb was not found. Add Android SDK platform-tools to PATH, set ANDROID_HOME, or install the SDK in the standard LocalAppData location."
}

$deviceLines = & $adbPath devices 2>&1
if ($LASTEXITCODE -ne 0) {
    Stop-WithError "adb could not list devices: $($deviceLines -join [Environment]::NewLine)"
}

$connectedDevices = @(
    $deviceLines |
        Select-Object -Skip 1 |
        ForEach-Object {
            if ($_ -match '^([^\s]+)\s+device(?:\s|$)') {
                $Matches[1]
            }
        }
)

if ($Serial) {
    if ($Serial -notin $connectedDevices) {
        Stop-WithError "Android device '$Serial' is not connected and authorized. Connected devices: $($connectedDevices -join ', ')."
    }
    $selectedSerial = $Serial
} else {
    if ($connectedDevices.Count -eq 0) {
        Stop-WithError "No connected and authorized Android device was found. Connect the phone, enable USB debugging, and accept its authorization prompt."
    }
    if ($connectedDevices.Count -gt 1) {
        Stop-WithError "Multiple Android devices are connected: $($connectedDevices -join ', '). Pass the target serial with -Serial."
    }
    $selectedSerial = $connectedDevices[0]
}

$packageName = "com.pushreel.app"

function Get-InstalledUserIds {
    $packageState = & $adbPath -s $selectedSerial shell dumpsys package $packageName 2>&1
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "The per-user package state for PushReel could not be read: $($packageState -join [Environment]::NewLine)"
    }

    @(
        $packageState |
            ForEach-Object {
                if ($_ -match '^\s*User\s+(\d+):.*\binstalled=true\b') {
                    [int]$Matches[1]
                }
            }
    )
}

$installedUserIdsBeforeInstall = @(Get-InstalledUserIds)
$additionalUserIdsBeforeInstall = @($installedUserIdsBeforeInstall | Where-Object { $_ -ne 0 })
if ($additionalUserIdsBeforeInstall.Count -gt 0) {
    Stop-WithError "PushReel is already installed for additional Android userId values: $($additionalUserIdsBeforeInstall -join ', '). Installation was not started because replacing the shared APK could affect those profiles. To remove an existing profile installation, use 'adb -s $selectedSerial shell pm uninstall --user <userId> $packageName'. This command removes PushReel and its app data for that Android user; run it only if that is intended."
}

Write-Host "Installing PushReel on device '$selectedSerial' for Android userId 0..."
$installOutput = & $adbPath -s $selectedSerial install --user 0 -r $resolvedApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    Stop-WithError "PushReel installation failed: $($installOutput -join [Environment]::NewLine)"
}

$installOutput | ForEach-Object { Write-Host $_ }

$installedUserIds = @(Get-InstalledUserIds)

if (0 -notin $installedUserIds) {
    Stop-WithError "PushReel installation completed, but the package is not marked installed for Android userId 0."
}

$additionalUserIds = @($installedUserIds | Where-Object { $_ -ne 0 })
if ($additionalUserIds.Count -gt 0) {
    Stop-WithError "PushReel is installed for additional Android userId values after installation: $($additionalUserIds -join ', '). To remove an existing profile installation, use 'adb -s $selectedSerial shell pm uninstall --user <userId> $packageName'. This command removes PushReel and its app data for that Android user; run it only if that is intended."
}

Write-Host "Verified: PushReel is installed only for Android userId 0."
