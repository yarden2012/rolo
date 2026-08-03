# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec: bundle the Tkinter GUI into a single windowed pkgfind.exe.
# Run from the pkgfind/ folder:  pyinstaller --noconfirm installer/pkgfind.spec
import os

root = os.path.abspath(os.path.join(SPECPATH, os.pardir))

a = Analysis(
    [os.path.join(root, "winapp.py")],
    pathex=[root],
    binaries=[],
    # Ship the icon inside the exe so the running window can load it too.
    datas=[(os.path.join(root, "windows", "pkgfind.ico"), "windows")],
    hiddenimports=["backends"],
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
