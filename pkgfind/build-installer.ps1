<#
    Builds pkgfind-setup.exe on a Windows machine.

    Bundles the app into a standalone exe with PyInstaller, then wraps it in an
    installer with Inno Setup. The output is dist\pkgfind-setup.exe — a normal
    Windows installer that needs no Python on the target machine.

        powershell -ExecutionPolicy Bypass -File .\build-installer.ps1

    Requires Python 3. Inno Setup is installed automatically via winget if it
    isn't already present. Pass -SkipInno to stop after building the bare exe.
#>
param([switch]$SkipInno)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "==> Installing PyInstaller"
python -m pip install --upgrade pyinstaller

Write-Host "==> Building pkgfind.exe"
python -m PyInstaller --noconfirm installer\pkgfind.spec

if ($SkipInno) {
    Write-Host "Built dist\pkgfind.exe (skipped installer)."
    return
}

$iscc = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles}\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $iscc) {
    Write-Host "==> Inno Setup not found; installing via winget"
    winget install --id JRSoftware.InnoSetup -e --accept-source-agreements --accept-package-agreements
    $iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
}

Write-Host "==> Building installer"
& $iscc installer\pkgfind.iss

Write-Host ""
Write-Host "Done: dist\pkgfind-setup.exe"
