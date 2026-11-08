$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    throw "Create the companion virtual environment and install requirements-build.txt first."
}

Push-Location $projectRoot
try {
    & $python -m PyInstaller `
        --noconfirm `
        --clean `
        --windowed `
        --onedir `
        --name VibePcMonitor `
        --distpath (Join-Path $projectRoot "dist") `
        --workpath (Join-Path $projectRoot "build\pyinstaller") `
        --specpath (Join-Path $projectRoot "build\pyinstaller") `
        --collect-all websockets `
        --collect-all winsdk `
        --collect-all pyaudiowpatch `
        --collect-all numpy `
        --collect-all psutil `
        --collect-all mss `
        --collect-all PIL `
        --collect-all qrcode `
        --hidden-import winsdk.windows.media.control `
        --hidden-import websockets.asyncio.server `
        --hidden-import websockets.exceptions `
        desktop_app.py
    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

$exe = Join-Path $projectRoot "dist\VibePcMonitor\VibePcMonitor.exe"
if (-not (Test-Path $exe)) {
    throw "Build finished without producing $exe."
}
Write-Output "Runnable companion: $exe"
