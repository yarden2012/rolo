<#
    Installs pkgfind as a Start-menu app on Windows.

    Creates a Start-menu shortcut (and optionally a desktop one) that launches
    the GUI windowed — no console window — using the pkgfind icon. Nothing is
    copied; the shortcut points back at this folder, so `git pull` updates the
    installed app. Run from this folder:

        powershell -ExecutionPolicy Bypass -File .\install-windows.ps1
        powershell -ExecutionPolicy Bypass -File .\install-windows.ps1 -Desktop
#>
param(
    [switch]$Desktop  # also drop a shortcut on the Desktop
)

$ErrorActionPreference = "Stop"
$here   = $PSScriptRoot
$script = Join-Path $here "pkgfind.py"
$icon   = Join-Path $here "windows\pkgfind.ico"

if (-not (Test-Path $script)) {
    throw "pkgfind.py not found next to this script ($here). Run it from the pkgfind folder."
}

# pythonw.exe / pyw.exe run without opening a console window.
$pythonw = $null
foreach ($name in @("pythonw", "pyw")) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { $pythonw = $cmd.Source; break }
}
if (-not $pythonw) {
    $py = Get-Command python -ErrorAction SilentlyContinue
    if ($py) {
        $cand = Join-Path (Split-Path $py.Source) "pythonw.exe"
        if (Test-Path $cand) { $pythonw = $cand }
    }
}
if (-not $pythonw) {
    throw "Couldn't find pythonw.exe or pyw.exe. Install Python 3 first: winget install Python.Python.3.12"
}

function New-PkgfindShortcut($path) {
    $shell = New-Object -ComObject WScript.Shell
    $sc = $shell.CreateShortcut($path)
    $sc.TargetPath       = $pythonw
    $sc.Arguments        = '"' + $script + '"'
    $sc.WorkingDirectory = $here
    $sc.Description       = "Search every package source at once"
    if (Test-Path $icon) { $sc.IconLocation = $icon }
    $sc.Save()
}

$programs = [Environment]::GetFolderPath("Programs")
$startLnk = Join-Path $programs "pkgfind.lnk"
New-PkgfindShortcut $startLnk
Write-Host "Installed pkgfind to the Start menu:"
Write-Host "  $startLnk"

if ($Desktop) {
    $desktopLnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "pkgfind.lnk"
    New-PkgfindShortcut $desktopLnk
    Write-Host "  $desktopLnk"
}

Write-Host ""
Write-Host "Press the Windows key and type 'pkgfind' to launch it."
Write-Host "Using Python: $pythonw"
