param(
    [string]$Assets,
    [string]$Python = $env:HBS_PYTHON,
    [switch]$SkipImport,
    [switch]$ImportOnly
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Assets) { $Assets = Join-Path (Split-Path $project) 'BTA_ASSETS' }
$cache = Join-Path $project '.work\hbs-cache'
$packages = Join-Path $project '.work\hbs-python'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}

Push-Location $project
try {
    if (-not $SkipImport) {
        if (-not (Test-Path -LiteralPath $Assets -PathType Container)) {
            throw "HBS asset folder not found: $Assets. Pass -Assets with your CAB directory."
        }
        if (-not $Python) {
            $candidates = @('python', 'py', (Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'))
            foreach ($candidate in $candidates) {
                try {
                    $found = & $candidate -c 'import sys; print(sys.executable)' 2>$null
                    if ($LASTEXITCODE -eq 0 -and $found) { $Python = $found.Trim(); break }
                } catch { }
            }
        }
        if (-not $Python) { throw 'Python 3.10+ is required for the first import. Pass -Python with its executable path.' }
        $oldPythonPath = $env:PYTHONPATH
        try {
            $env:PYTHONPATH = $packages
            $dependenciesPresent = $false
            try {
                & $Python -c 'import sys, UnityPy, numpy; assert UnityPy.__version__ == sys.argv[1]' '1.24.2' 2>$null
                $dependenciesPresent = $LASTEXITCODE -eq 0
            } catch { }
            if (-not $dependenciesPresent) {
                Invoke-Checked $Python @('-m', 'pip', 'install', '--disable-pip-version-check', '--no-warn-script-location',
                    '--target', $packages, '--upgrade', '-r', (Join-Path $PSScriptRoot 'requirements.txt'))
            }
            Invoke-Checked $Python @((Join-Path $PSScriptRoot 'import_assets.py'), '--source', $Assets, '--output', $cache)
        } finally { $env:PYTHONPATH = $oldPythonPath }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $cache 'catalog.json'))) {
        throw 'No HBS cache exists. Run once without -SkipImport.'
    }
    if (-not $ImportOnly) {
        Invoke-Checked (Join-Path $project 'gradlew.bat') @(':megamek:runHbs', '--console=plain')
    }
} finally { Pop-Location }
