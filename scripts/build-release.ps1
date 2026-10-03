# Builds, tests, signs, and verifies an OT Studio release APK with the standard Gradle route.
# The signing key lives outside the project (never in the source ZIP or a synced folder) and is created only once.
# Every later release must use the same key, or Android will refuse to update the installed app.
param(
    [string]$SigningDir = (Join-Path $env:USERPROFILE 'OTStudioSigning'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$SkipChecks
)
# Native tools (keytool, Gradle, apksigner) write progress to stderr, which Windows PowerShell 5.1 would turn into a
# terminating error. Failures are detected by exit code in CheckExit and by explicit throws instead.
$ErrorActionPreference = 'Continue'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function CheckExit([string]$operation) { if ($LASTEXITCODE -ne 0) { throw "$operation failed with exit code $LASTEXITCODE" } }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Set JAVA_HOME (JDK 17) or pass -JavaHome.' }
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'build-tools/36.0.0'))) { throw 'Set ANDROID_HOME (with build-tools 36.0.0) or pass -AndroidSdk.' }
$SigningDir = [IO.Path]::GetFullPath($SigningDir)
if ($SigningDir.StartsWith($project, [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep the signing folder outside the project so it never enters the source ZIP.' }
if ($SigningDir -match '\\OneDrive') { Write-Warning 'The signing folder is inside OneDrive, so the private key will sync to the cloud. Prefer a local folder plus a private offline backup.' }

$java = Join-Path $JavaHome 'bin/java.exe'
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$buildTools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$apksigner = Join-Path $buildTools 'lib/apksigner.jar'
$keystore = Join-Path $SigningDir 'otstudio-release.p12'
$passwordFile = Join-Path $SigningDir 'password.txt'

if (-not (Test-Path -LiteralPath $keystore)) {
    New-Item -ItemType Directory -Path $SigningDir -Force | Out-Null
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $SigningDir /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
    CheckExit 'Signing-folder permissions'
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($bytes), [Text.UTF8Encoding]::new($false))
    # Android recommends a validity of at least 25 years for app signing keys.
    & $keytool -genkeypair -alias otstudio -keyalg RSA -keysize 3072 -validity 10000 -storetype PKCS12 -keystore $keystore -storepass:file $passwordFile -keypass:file $passwordFile -dname 'CN=OT Studio, O=OrangeTiger' -noprompt
    CheckExit 'Release signing key creation'
    Write-Output "NEW SIGNING KEY created in $SigningDir. Back up this whole folder privately (for example to a USB drive). Losing it means future versions cannot update the installed app."
}

# Releases carry the faster engine build for recent phones. Without it they would quietly run slower.
if (-not (Test-Path -LiteralPath (Join-Path $project 'app/src/main/jniLibs/arm64-v8a/libjllama_i8mm.so'))) { throw 'Run scripts\build-engine.ps1 first.' }

Push-Location $project
try {
    $tasks = if ($SkipChecks) { @('clean', ':app:assembleRelease') } else { @('clean', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleRelease') }
    $env:JAVA_HOME = $JavaHome; $env:ANDROID_HOME = $AndroidSdk
    & (Join-Path $project 'gradlew.bat') @tasks --no-daemon --console=plain
    CheckExit 'Gradle build'
} finally { Pop-Location }

$version = (Select-String -LiteralPath (Join-Path $project 'app/build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$unsigned = Join-Path $project 'app/build/outputs/apk/release/app-release-unsigned.apk'
$outDir = Join-Path $project "outputs/$version"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$result = Join-Path $outDir "OT-Studio-$version.apk"
& $java -jar $apksigner sign --ks $keystore --ks-key-alias otstudio --ks-pass "file:$passwordFile" --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $result $unsigned
CheckExit 'APK signing'
& $java -jar $apksigner verify --verbose --print-certs $result
CheckExit 'APK signature verification'
& (Join-Path $buildTools 'zipalign.exe') -c 4 $result
CheckExit 'APK alignment verification'
$hash = (Get-FileHash -LiteralPath $result -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "APK=$result"
Write-Output "BYTES=$((Get-Item -LiteralPath $result).Length)"
Write-Output "SHA256=$hash"
