param([switch]$Release)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectPath
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath "$projectPath/.tools/jdk/jdk-17.0.12")) {
    $env:JAVA_HOME = "$projectPath/.tools/jdk/jdk-17.0.12"
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "$projectPath/.tools/gradle-home" }
python "$PSScriptRoot/check_dependencies.py"
if ($LASTEXITCODE -ne 0) { throw 'Dependency verification failed' }
$assembleTask = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
& "$projectPath/gradlew.bat" $assembleTask ':app:testDebugUnitTest' ':app:lintDebug' '--console' 'plain'
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed' }
