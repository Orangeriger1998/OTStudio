# Builds the OT Studio LoRA Trainer as one standalone Windows program: outputs/trainer/OT-Studio-LoRA-Trainer.exe
#
# The trainer is scripts/lora_trainer_app.py on top of scripts/train_lora.py. Python 3.11 or later is needed only to
# build it; the finished .exe runs on a PC without Python. A private virtual environment holds PyInstaller and Pillow,
# by default in %USERPROFILE%\Android\src\trainer-venv, so the system Python stays untouched.
param(
    [string]$Python = 'python',
    [string]$Venv = (Join-Path $env:USERPROFILE 'Android\src\trainer-venv')
)
$ErrorActionPreference = 'Continue'
function CheckExit([string]$operation) { if ($LASTEXITCODE -ne 0) { throw "$operation failed with exit code $LASTEXITCODE" } }
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$venvPython = Join-Path $Venv 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    & $Python -m venv $Venv
    CheckExit 'Creating the virtual environment'
}
& $venvPython -m pip install --quiet --disable-pip-version-check pyinstaller pillow pillow-heif
CheckExit 'Installing PyInstaller, Pillow, and pillow-heif'

Push-Location (Join-Path $project 'scripts')
try {
    & $venvPython -m unittest test_train_lora test_lora_trainer_app
    CheckExit 'Trainer tests'
} finally { Pop-Location }

$work = Join-Path $env:TEMP 'otstudio-trainer-build'
$dist = Join-Path $project 'outputs\trainer'
& $venvPython -m PyInstaller --noconfirm --clean --onefile --windowed `
    --name 'OT-Studio-LoRA-Trainer' `
    --icon (Join-Path $project 'branding\trainer.ico') `
    --add-data ((Join-Path $project 'branding\trainer-icon.png') + ';.') `
    --paths (Join-Path $project 'scripts') `
    --collect-all pillow_heif `
    --distpath $dist --workpath $work --specpath $work `
    (Join-Path $project 'scripts\lora_trainer_app.py')
CheckExit 'PyInstaller'

$exe = Join-Path $dist 'OT-Studio-LoRA-Trainer.exe'
$hash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "TRAINER=$exe"
Write-Output "BYTES=$((Get-Item -LiteralPath $exe).Length)"
Write-Output "SHA256=$hash"
