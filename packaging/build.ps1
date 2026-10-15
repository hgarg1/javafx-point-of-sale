[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Version,
    [ValidateSet('app','demo','both')][string]$Variant = 'both',
    [string]$OutputDirectory = 'target/installer',
    [string]$JavaFxJmods = $env:JAVAFX_JMODS,
    [switch]$ImageOnly,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to Eclipse Temurin JDK 26.0.2.1.' }
$taskJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $taskJava)) { throw "Java not found: $taskJava" }
Push-Location $taskRepo
try {
    $taskArgs = @('packaging/Package.java', '--version', $Version, '--variant', $Variant,
        '--output', $OutputDirectory, '--javafx-jmods', $JavaFxJmods)
    if ($ImageOnly) { $taskArgs += '--image-only' }
    if ($Check) { $taskArgs += '--check' }
    & $taskJava @taskArgs
    if ($LASTEXITCODE -ne 0) { throw "Packaging failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
