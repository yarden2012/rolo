# pkgfind

One search box over every place an app can come from. Type once and get a
single ranked list that tells you where each hit lives and whether it's already
installed — instead of checking a store, then a `search` for each package
manager, then remembering which container you put a CLI tool in.

Built first for Bazzite and the atomic Fedora spins (Silverblue, Kinoite,
Bluefin), it now runs on ordinary Linux distros, macOS and Windows too. Every
source self-selects: only the ones whose tool is actually on the machine ever
run, so the same install works everywhere.

## Sources

| Badge | Where it looks | Applies to |
|---|---|---|
| **Flatpak** | every configured remote (Flathub etc.) | any Linux with Flatpak |
| **RPM** | Fedora repos via `rpm-ostree search` (layered, needs a reboot) | atomic Fedora |
| **DNF** | Fedora / RHEL / Rocky / Alma repos | traditional (non-atomic) Fedora & RHEL |
| **APT** | Debian / Ubuntu / Mint repos | Debian-family |
| **Pacman** | Arch repos | Arch / Manjaro / EndeavourOS |
| **Zypper** | openSUSE repos | Leap / Tumbleweed |
| **APK** | Alpine repos | Alpine |
| **Snap** | the Snap Store | any Linux with snapd |
| **Homebrew** | `homebrew/core` and casks | Linux & macOS |
| **winget** | the winget source | Windows |
| **Scoop** | your Scoop buckets | Windows |
| **Chocolatey** | the Chocolatey gallery | Windows |
| **Container** | the package manager inside each distrobox container | atomic / any Linux |

DNF is deliberately held back on atomic systems, where `rpm-ostree` is the real
install path — you never see both for the same package.

Container search is **off by default** — reaching a stopped container has to boot
it first, which takes longer than every other source put together. Turn it on in
the menu (or `-b` on the command line) when you want it.

## Install

## Install

On **Linux**, the quickest path is the local installer:

```sh
./install.sh
```

Symlinks `pkgfind` into `~/.local/bin` and adds a launcher to the app menu. Nothing
is copied, so editing files in this directory updates the installed app. There is
also a Flatpak (`flatpak/`) and a Homebrew formula — see the sections below.

On **macOS** use Homebrew (see [macOS](#macos)). On **Windows** see
[Windows](#windows).

## Use

```sh
pkgfind                 # open the app
pkgfind inkscape        # open it with the search already run
pkgfind -c inkscape     # print results in the terminal instead
pkgfind -c -b ripgrep   # ...including inside distrobox containers
pkgfind -c -i ripgrep --install ripgrep   # install straight from the CLI
```

| Flag | Meaning |
|---|---|
| `-c`, `--cli` | terminal output instead of the GUI |
| `-b`, `--containers` | also search distrobox containers |
| `-n N`, `--limit N` | show at most N results |
| `-i ID`, `--install ID` | install that identifier from the results |
| `--no-color` | plain output |

In the app: **Enter** searches, **Ctrl+F** jumps back to the search box, the chips
filter by source, clicking a row opens details with the exact command (and a copy
button), and **Install** / **Remove** streams the real command output in a window
you can cancel.

## How results are ordered

Everything is merged into one list and sorted by how well it matches, not by
source — an exact name match from any source beats a partial one from a
"preferred" source. Ties break by source name, then package name.

## Layout

| File | What it does |
|---|---|
| `backends.py` | all the search/install logic, no GTK — this is where you'd add a source |
| `app.py` | the GTK4 / libadwaita interface (Linux/macOS) |
| `winapp.py` | the Tkinter interface (Windows) |
| `pkgfind.py` | entry point and the CLI |
| `pkgfind` | Linux/macOS launcher — finds a Python that has PyGObject |
| `pkgfind.cmd` | Windows launcher |

Adding a source means subclassing `Backend` in `backends.py` (implement
`available`, `search`, `install_cmd`, `remove_cmd`) and adding it to the
`_HOST_BACKENDS` list. Host package managers can subclass `HostBackend`, which
only needs the search argv, a parser, and the install/remove commands. The GUI
picks it up with no changes — give it a badge colour in `BADGE_CLASS` in
`app.py` if you want one.

## Notes

- Runs on the host. It also works from inside a Flatpak sandbox — it detects that
  and routes every package command back out through `flatpak-spawn --host`.
- `rpm-ostree install` triggers a normal polkit password prompt and only takes
  effect after a reboot; the app says so before you commit to it.
- A backend that fails or times out shows a toast and the other sources still
  return — one broken source never sinks the search.

## Install with Homebrew

Works on both Linux and macOS:

```sh
brew tap yarden2012/rolo https://github.com/yarden2012/rolo
brew install yarden2012/rolo/pkgfind
```

### macOS

**Requirements:** macOS 12.3 (Monterey) or newer, and Homebrew. If you don't
have Homebrew yet, install it first:

```sh
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Then run the two `brew` commands above. On macOS the install also pulls in the
GTK stack the GUI needs (`gtk4`, `libadwaita`, `pygobject3`), so the first
install can take a while as those build.

```sh
pkgfind firefox        # open the GUI with a search already run
pkgfind -c firefox     # print results in the terminal instead
```

Note that Homebrew is the only package source on a Mac, so pkgfind there is
essentially a nicer `brew search` — the multi-source view is where it earns its
keep on Linux.

### Windows

pkgfind searches **winget**, **Scoop** and **Chocolatey** — whichever of them
you have installed. Clone the repo and run it with Python 3:

```powershell
git clone https://github.com/yarden2012/rolo
cd rolo\pkgfind
python pkgfind.py firefox           # graphical window
python pkgfind.py -c firefox        # terminal search across winget/Scoop/choco
```

Windows gets its own **Tkinter** GUI (`winapp.py`) — a search box, a results
table, and a details pane that installs or removes the selection while streaming
the command's output. Tkinter ships inside CPython, so there is nothing extra to
install; if a stripped-down Python is missing it, pkgfind prints a message and
`-c` still works. (The main GTK4/libadwaita interface is Linux/macOS only, which
is why Windows has a separate, lighter frontend over the same backends.)

`pkgfind.cmd` is a launcher you can drop on your `PATH` so `pkgfind …` works
from anywhere. Installing from the results runs the native tool: `winget
install`, `scoop install`, or `choco install` (Chocolatey needs an
elevated/admin shell).
