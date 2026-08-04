; Inno Setup script — wraps dist\pkgfind.exe into pkgfind-setup.exe.
; Compile from the pkgfind/ folder:
;   "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\pkgfind.iss

#define MyAppName "pkgfind"
#define MyAppVersion "0.6.0"
#define MyAppPublisher "yarden2012"
#define MyAppURL "https://github.com/yarden2012/rolo"
#define MyAppExeName "pkgfind.exe"

[Setup]
; A stable AppId keeps upgrades/uninstall tied to the same entry.
AppId={{E462E67F-04BC-4B47-8D6F-CA7E58E1434A}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\pkgfind
DefaultGroupName=pkgfind
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#MyAppExeName}
OutputDir={#SourcePath}\..\dist
OutputBaseFilename=pkgfind-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
; A per-user install needs no admin rights.
PrivilegesRequired=lowest

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
Source: "{#SourcePath}\..\dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\pkgfind"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall pkgfind"; Filename: "{uninstallexe}"
Name: "{autodesktop}\pkgfind"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch pkgfind now"; Flags: nowait postinstall skipifsilent
