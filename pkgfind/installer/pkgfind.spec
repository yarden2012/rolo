# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec: bundle the Tkinter GUI into a single windowed pkgfind.exe.
# Run from the pkgfind/ folder:  pyinstaller --noconfirm installer/pkgfind.spec
import os
from PyInstaller.utils.hooks import collect_data_files, collect_submodules

root = os.path.abspath(os.path.join(SPECPATH, os.pardir))

# Ship the icon inside the exe so the running window can load it too.
datas = [(os.path.join(root, "windows", "pkgfind.ico"), "windows")]
hiddenimports = ["backends"]

# Bundle the Sun Valley theme (Windows 11 look) if it's installed in the build
# env; the app falls back to the native theme if it isn't present.
try:
    datas += collect_data_files("sv_ttk")
    hiddenimports += collect_submodules("sv_ttk")
except Exception:
    pass

a = Analysis(
    [os.path.join(root, "winapp.py")],
    pathex=[root],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["app"],  # the GTK frontend is Linux/macOS only — never bundle it
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="pkgfind",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    runtime_tmpdir=None,
    console=False,  # windowed app — no console flashes on launch
    icon=os.path.join(root, "windows", "pkgfind.ico"),
)
