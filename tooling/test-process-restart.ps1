param(
    [string]$Adb = "adb",
    [string]$Serial = "",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = if ($env:OS -eq "Windows_NT") { "gradlew.bat" } else { "gradlew" }
$adbArgs = @()
if ($Serial) { $adbArgs = @("-s", $Serial) }
Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $projectRoot $gradleWrapper) :core:database:assembleDebug :core:database:assembleDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Falló compilación de pruebas" }
    }
    $target = "ec.finanzas.database.test"
    & $Adb @adbArgs install -r core/database/build/outputs/apk/androidTest/debug/database-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw "Falló instalación de pruebas" }
    foreach ($phase in @("write", "read")) {
        & $Adb @adbArgs shell am force-stop $target
        if ($LASTEXITCODE -ne 0) { throw "Falló cierre del proceso" }
        $output = & $Adb @adbArgs shell am instrument -w -r -e class ec.finanzas.database.ProcessRestartProbe -e restartPhase $phase "$target/androidx.test.runner.AndroidJUnitRunner"
        $output | Write-Output
        if ($LASTEXITCODE -ne 0 -or ($output -join "\n") -notmatch "OK \(1 test\)") {
            throw "Falló prueba entre procesos: $phase"
        }
    }
} finally { Pop-Location }

