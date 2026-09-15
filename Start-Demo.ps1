[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8081,
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

    $defaultDahnesh = [string]::IsNullOrWhiteSpace($env:REFUNDS_DAHNESH_PASSWORD)
    $defaultShweta = [string]::IsNullOrWhiteSpace($env:REFUNDS_SHWETA_PASSWORD)
    $defaultAuditor = [string]::IsNullOrWhiteSpace($env:REFUNDS_AUDITOR_PASSWORD)
    if ($defaultDahnesh) {
        $env:REFUNDS_DAHNESH_PASSWORD = 'dahnesh'
    }
    if ($defaultShweta) {
        $env:REFUNDS_SHWETA_PASSWORD = 'shweta'
    }
    if ($defaultAuditor) {
        $env:REFUNDS_AUDITOR_PASSWORD = 'auditor'
    }

    Write-Host ''
    Write-Host 'RefundOps | High-value approval candidate | Local mock payments only'
    Write-Host "Open http://127.0.0.1:$Port"
    Write-Host ''
    Write-Host 'Demo-only accounts. Never use these accounts for real data or production.'
    Write-Host 'Public local defaults are documented in README.txt. Passwords are not logged.'
    if ($defaultDahnesh) {
        Write-Host '  dahnesh  [Requestor + Approver + Demo operator]  - public local default'
    } else {
        Write-Host '  dahnesh  [Requestor + Approver + Demo operator]  - REFUNDS_DAHNESH_PASSWORD override'
    }
    if ($defaultShweta) {
        Write-Host '  shweta   [Requestor]  - public local default'
    } else {
        Write-Host '  shweta   [Requestor]  - REFUNDS_SHWETA_PASSWORD override'
    }
    if ($defaultAuditor) {
        Write-Host '  auditor  [Auditor - read-only]  - public local default'
    } else {
        Write-Host '  auditor  [Auditor - read-only]  - REFUNDS_AUDITOR_PASSWORD override'
    }
    Write-Host ''
    Write-Host 'Stopping/restarting clears the in-memory demo data. Press Ctrl+C to stop.'
    Write-Host 'This unsupported framework baseline is for isolated demonstrations, not production.'
    Write-Host ''

    & java '-Dfile.encoding=UTF-8' '-jar' $jars[0].FullName "--server.port=$Port" '--server.address=127.0.0.1'
    if ($LASTEXITCODE -ne 0) {
        throw "The application exited with code $LASTEXITCODE."
    }
} finally {
    if (Get-Variable defaultDahnesh -ErrorAction SilentlyContinue) {
        if ($defaultDahnesh) { Remove-Item Env:\REFUNDS_DAHNESH_PASSWORD -ErrorAction SilentlyContinue }
    }
    if (Get-Variable defaultShweta -ErrorAction SilentlyContinue) {
        if ($defaultShweta) { Remove-Item Env:\REFUNDS_SHWETA_PASSWORD -ErrorAction SilentlyContinue }
    }
    if (Get-Variable defaultAuditor -ErrorAction SilentlyContinue) {
        if ($defaultAuditor) { Remove-Item Env:\REFUNDS_AUDITOR_PASSWORD -ErrorAction SilentlyContinue }
    }
    Pop-Location
}
