<#
    Removes the pkgfind Start-menu (and desktop) shortcuts created by
    install-windows.ps1. Does not touch the cloned repo.
#>
$ErrorActionPreference = "Stop"

$targets = @(
    (Join-Path ([Environment]::GetFolderPath("Programs")) "pkgfind.lnk"),
    (Join-Path ([Environment]::GetFolderPath("Desktop"))  "pkgfind.lnk")
)

$removed = $false
foreach ($lnk in $targets) {
    if (Test-Path $lnk) {
        Remove-Item $lnk
        Write-Host "Removed $lnk"
        $removed = $true
    }
}
if (-not $removed) { Write-Host "No pkgfind shortcuts found." }
