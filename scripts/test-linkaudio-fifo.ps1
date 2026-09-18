param(
    [string]$Compiler = "g++"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$testSource = Join-Path $repositoryRoot "linkaudio/src/test/cpp/pcm_fifo_test.cpp"
$outputDirectory = Join-Path $repositoryRoot "linkaudio/build/host-tests"
$testExecutable = Join-Path $outputDirectory "pcm_fifo_test.exe"

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
& $Compiler -std=c++17 -Wall -Wextra -Werror $testSource -o $testExecutable
if ($LASTEXITCODE -ne 0) {
    throw "Link Audio FIFO host test compilation failed."
}

& $testExecutable
if ($LASTEXITCODE -ne 0) {
    throw "Link Audio FIFO host test failed."
}

Write-Host "Link Audio FIFO host test passed."
