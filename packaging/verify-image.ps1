[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ImageDirectory,
    [Parameter(Mandatory=$true)][string]$TestHome,
    [ValidateSet('app','demo')][string]$Variant = 'app',
    [ValidateSet('ui','seed','check')][string]$Action = 'ui'
)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskImage = (Resolve-Path -LiteralPath $ImageDirectory).Path
$taskHome = [System.IO.Path]::GetFullPath($TestHome)
$taskAllowed = Join-Path $taskRepo 'target\installer\'
if (-not $taskHome.StartsWith($taskAllowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'TestHome must be under target/installer.' }
New-Item -ItemType Directory -Force -Path $taskHome | Out-Null
Set-Content -LiteralPath (Join-Path $taskHome '.packaging-test-home') -Value 'Isolated installer acceptance data'
$taskClasses = Join-Path $taskRepo 'target/installer/verification-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskApp = Join-Path $taskImage 'app'
$taskJava = Join-Path $taskImage 'runtime/bin/java.exe'
$taskRuntime = Join-Path $taskImage 'runtime'
& "$env:JAVA_HOME/bin/javac.exe" --system $taskRuntime --add-modules javafx.controls -cp "$taskApp/*" -d $taskClasses (Join-Path $PSScriptRoot 'VerifyPackage.java')
if ($LASTEXITCODE -ne 0) { throw 'Acceptance helper compilation failed.' }
$taskEncodedHome = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($taskHome))
& $taskJava '--add-modules=javafx.controls' '--enable-native-access=javafx.graphics' -cp "$taskApp/*;$taskClasses" com.example.app.ui.VerifyPackage $Action $Variant $taskEncodedHome
if ($LASTEXITCODE -ne 0) { throw 'Packaged acceptance failed.' }
