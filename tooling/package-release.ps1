param([string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
foreach ($name in @('FINANZAS_RELEASE_STORE_FILE','FINANZAS_RELEASE_STORE_PASSWORD','FINANZAS_RELEASE_KEY_ALIAS','FINANZAS_RELEASE_KEY_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "Falta $name. No se generó una firma de reemplazo." }
}
if (-not (Test-Path -LiteralPath $env:FINANZAS_RELEASE_STORE_FILE -PathType Leaf)) { throw 'Keystore no encontrado.' }
if ([string]::IsNullOrWhiteSpace($Sdk)) { throw 'Configura ANDROID_HOME o indica -Sdk.' }
$signer = Join-Path $Sdk 'build-tools/36.0.0/apksigner.bat'
if (-not (Test-Path -LiteralPath $signer)) { throw 'Se requiere Android Build Tools 36.0.0.' }
Push-Location $root
try {
    & .\gradlew.bat :app:assembleRelease --no-daemon --no-parallel --max-workers=1 --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación release.' }
    & python tooling/verify-manifest.py release
    if ($LASTEXITCODE -ne 0) { throw 'Falló la auditoría del manifest release.' }
    $apk = Join-Path $root 'app/build/outputs/apk/release/app-release.apk'
    $verification = & $signer verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'La firma release no es válida.' }
    if (($verification -join "`n") -match '4c25c60c1f166aa9048df078172f89164251d5b6a15f59b9cdc1f4d23f25f656|CN=Android Debug') {
        throw 'No se distribuye como producción un APK con el certificado debug conocido.'
    }
    $destination = Join-Path $root 'artifacts'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $target = Join-Path $destination 'finanzas-locales-f5-release.apk'
    Copy-Item -LiteralPath $apk -Destination $target -Force
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$target.sha256", "$hash  finanzas-locales-f5-release.apk`n")
    $verification | Set-Content -LiteralPath (Join-Path $destination 'finanzas-locales-f5-release-signature.txt')
    Write-Output 'APK release firmado y checksum guardados en artifacts. Instalar y validar antes de distribuir.'
} finally { Pop-Location }
