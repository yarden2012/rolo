@echo off
rem pkgfind launcher for Windows. Prefers the `py` launcher, falls back to
rem whatever `python` is on PATH.
setlocal
where py >nul 2>nul && (
    py -3 "%~dp0pkgfind.py" %*
    exit /b %errorlevel%
)
python "%~dp0pkgfind.py" %*
exit /b %errorlevel%
