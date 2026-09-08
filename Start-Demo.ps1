[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java was not found. Install a JDK (11 or later) and add its bin directory to PATH.'
}

if (-not $SkipBuild -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven was not found. Install Maven 3.6.3 or later and add its bin directory to PATH.'
}

Push-Location $PSScriptRoot
try {
    if (-not $SkipBuild) {
        Write-Host 'Building the legacy refund demo...'
        & mvn -q clean verify
        if ($LASTEXITCODE -ne 0) {
            throw "The Maven build failed with exit code $LASTEXITCODE. The demo was not started."
        }
    }

    $jars = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'target') -Filter '*.jar' -File)
    if ($jars.Count -ne 1) {
        throw 'Expected one application JAR in target. Run .\Start-Demo.ps1 without -SkipBuild.'
    }

    $generatedDahnesh = [string]::IsNullOrWhiteSpace($env:REFUNDS_DAHNESH_PASSWORD)
    $generatedShweta = [string]::IsNullOrWhiteSpace($env:REFUNDS_SHWETA_PASSWORD)
    if ($generatedDahnesh) {
        $env:REFUNDS_DAHNESH_PASSWORD = 'demo-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
    }
    if ($generatedShweta) {
        $env:REFUNDS_SHWETA_PASSWORD = 'demo-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
    }

    Write-Host ''
    Write-Host 'RefundOps | Legacy baseline | Local mock payments only'
    Write-Host "Open http://127.0.0.1:$Port"
    Write-Host ''
    Write-Host 'Local demo sign-in (passwords are not stored in source):'
    if ($generatedDahnesh) {
        Write-Host "  dahnesh  /  $($env:REFUNDS_DAHNESH_PASSWORD)  [Requestor + Approver]"
    } else {
        Write-Host '  dahnesh  /  password supplied by REFUNDS_DAHNESH_PASSWORD'
    }
    if ($generatedShweta) {
        Write-Host "  shweta   /  $($env:REFUNDS_SHWETA_PASSWORD)  [Requestor]"
    } else {
        Write-Host '  shweta   /  password supplied by REFUNDS_SHWETA_PASSWORD'
    }
    Write-Host ''
    Write-Host 'Stopping/restarting clears the in-memory demo data. Press Ctrl+C to stop.'
    Write-Host 'This unsupported framework baseline is for isolated demonstrations, not production.'
    Write-Host ''

    & java '-Dfile.encoding=UTF-8' '-jar' $jars[0].FullName "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) {
        throw "The application exited with code $LASTEXITCODE."
    }
} finally {
    if (Get-Variable generatedDahnesh -ErrorAction SilentlyContinue) {
        if ($generatedDahnesh) { Remove-Item Env:\REFUNDS_DAHNESH_PASSWORD -ErrorAction SilentlyContinue }
    }
    if (Get-Variable generatedShweta -ErrorAction SilentlyContinue) {
        if ($generatedShweta) { Remove-Item Env:\REFUNDS_SHWETA_PASSWORD -ErrorAction SilentlyContinue }
    }
    Pop-Location
}
