# Builds the faster llama.cpp engine for recent ARM phones and places it at
# app/src/main/jniLibs/arm64-v8a/libjllama_i8mm.so, where the app build picks it up.
#
# It compiles java-llama.cpp at the same version as the net.ladenthin:llama-android dependency, with the
# dot-product and int8 matrix-multiply instructions switched on. The standard build in that dependency
# leaves them off, so it runs on every arm64 phone but reads prompts several times slower.
# The app chooses this build at run time only on processors that report both instructions (NativeEngine.kt).
#
# Needs the Android SDK with NDK 29.0.14206865 and CMake 3.31.6 (install both with sdkmanager), and Git.
# The source and build folders live outside the project, by default in %USERPROFILE%\Android\src.
param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$WorkDir = (Join-Path $env:USERPROFILE 'Android\src'),
    [string]$Version = 'v5.1.0',
    [string]$NdkVersion = '29.0.14206865',
    [string]$Arch = 'armv8.2-a+dotprod+fp16+i8mm'
)
$ErrorActionPreference = 'Continue'
function CheckExit([string]$operation) { if ($LASTEXITCODE -ne 0) { throw "$operation failed with exit code $LASTEXITCODE" } }
if (-not $AndroidSdk) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ndk = Join-Path $AndroidSdk "ndk\$NdkVersion"
$cmakeBin = Join-Path $AndroidSdk 'cmake\3.31.6\bin'
foreach ($p in @($ndk, $cmakeBin)) { if (-not (Test-Path -LiteralPath $p)) { throw "Missing $p. Install it with sdkmanager." } }
$cmake = Join-Path $cmakeBin 'cmake.exe'
$ninja = Join-Path $cmakeBin 'ninja.exe'

New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
$source = Join-Path $WorkDir 'java-llama.cpp'
if (-not (Test-Path -LiteralPath $source)) {
    & git clone --depth 1 --branch $Version https://github.com/bernardladenthin/java-llama.cpp.git $source
    CheckExit 'git clone'
}
$tag = (& git -C $source describe --tags --exact-match 2>$null)
if ($tag -ne $Version) { throw "$source is at '$tag', not $Version. Delete it and run again." }

$build = Join-Path $WorkDir 'build-i8mm'
& $cmake -S (Join-Path $source 'llama') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
    -DCMAKE_BUILD_TYPE=Release -DOS_NAME=Linux-Android -DOS_ARCH=aarch64 "-DGGML_CPU_ARM_ARCH=$Arch"
CheckExit 'CMake configure'
& $cmake --build $build --target jllama -j $env:NUMBER_OF_PROCESSORS
CheckExit 'CMake build'

# The java-llama.cpp build installs its library into its own resources folder.
$built = Get-Item -LiteralPath (Join-Path $source 'llama/src/main/resources/net/ladenthin/llama/Linux-Android/aarch64/libjllama.so') -ErrorAction SilentlyContinue
if (-not $built) { throw 'The build produced no libjllama.so.' }
$target = Join-Path $project 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$out = Join-Path $target 'libjllama_i8mm.so'
$strip = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
& $strip --strip-unneeded -o $out $built.FullName
CheckExit 'llvm-strip'

# Android 15 and later need every LOAD segment aligned to 16 KB.
$readelf = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
$aligns = & $readelf -lW $out | Select-String '^\s+LOAD' | ForEach-Object { ($_.Line -split '\s+')[-1] }
if ($aligns | Where-Object { [Convert]::ToInt64($_, 16) -lt 0x4000 }) { throw "A LOAD segment is aligned below 16 KB: $aligns" }
$hash = (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "ENGINE=$out"
Write-Output "BYTES=$((Get-Item -LiteralPath $out).Length)"
Write-Output "SHA256=$hash"
