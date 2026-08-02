# pkgfind

One search box over every place an app can come from on Bazzite (and any other
atomic Fedora spin — Silverblue, Kinoite, Bluefin).

Instead of checking Discover, then `rpm-ostree search`, then `brew search`, then
remembering which container you put a CLI tool in, you type once and get a single
ranked list that tells you where each hit lives and whether it's already installed.

## Sources

| Badge | Where it looks | Install path |
|---|---|---|
| **Flatpak** | every configured remote (Flathub etc.) | `flatpak install --user` — no password |
| **RPM** | the Fedora repos, via `rpm-ostree search` | `rpm-ostree install` — layered, needs a reboot |
| **Homebrew** | `homebrew/core` | `brew install` — no password |
| **Container** | the package manager inside each distrobox container | `distrobox enter … install` |

Container search is **off by default** — reaching a stopped container has to boot
it first, which takes longer than every other source put together. Turn it on in
the menu (or `-b` on the command line) when you want it.

## Install

```sh
./install.sh
```

Symlinks `pkgfind` into `~/.local/bin` and adds a launcher to the app menu. Nothing
is copied, so editing files in this directory updates the installed app.

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
| `app.py` | the GTK4 / libadwaita interface |
| `pkgfind.py` | entry point and the CLI |
| `pkgfind` | launcher that puts Homebrew on `PATH` first |

Adding a source means subclassing `Backend` in `backends.py` (implement
`available`, `search`, `install_cmd`, `remove_cmd`) and adding it to
`default_backends()`. The GUI picks it up with no changes — give it a badge
colour in `BADGE_CLASS` in `app.py` if you want one.

## Notes

- Runs on the host. It also works from inside a Flatpak sandbox — it detects that
  and routes every package command back out through `flatpak-spawn --host`.
- `rpm-ostree install` triggers a normal polkit password prompt and only takes
  effect after a reboot; the app says so before you commit to it.
- A backend that fails or times out shows a toast and the other sources still
  return — one broken source never sinks the search.

## Install with Homebrew

```sh
brew tap yarden2012/rolo https://github.com/yarden2012/rolo
brew install yarden2012/rolo/pkgfind
```

### macOS

Works via Homebrew: the terminal mode searches Homebrew (the only package
source on a Mac), and the GUI uses the GTK stack that the formula installs
automatically. Requires macOS 12.3+.
