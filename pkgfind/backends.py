"""Search backends for pkgfind.

One aggregated search across every app source available on the machine.

On an atomic Fedora system (Bazzite / Silverblue / Kinoite) that means Flatpak
remotes, the Fedora RPM repos reachable through rpm-ostree, Homebrew, and any
distrobox containers. On other Linux distros it picks up the native package
manager instead — apt, dnf, pacman, zypper, apk — plus snap and Homebrew. On
Windows it covers winget, Scoop and Chocolatey. Each backend self-selects: only
the ones whose tool is actually present ever run.

Nothing in here imports GTK, so the same code drives the GUI and the CLI.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass, field

IS_WINDOWS = sys.platform == "win32"

# Windows-only tweaks to every child process, empty elsewhere so Linux/macOS
# behavior is unchanged:
#   - encoding/errors: package tools emit UTF-8, but the default decode is cp1252,
#     which mangles winget's … truncation into "â€¦".
#   - creationflags: the GUI runs windowed, so without CREATE_NO_WINDOW each
#     child (winget, etc.) flashes its own empty console window on every search.
if IS_WINDOWS:
    _TEXT_KW: dict = {
        "encoding": "utf-8",
        "errors": "replace",
        "creationflags": subprocess.CREATE_NO_WINDOW,
    }
else:
    _TEXT_KW = {}

# When pkgfind itself runs inside a Flatpak sandbox, every package tool lives on
# the host, so commands get routed back out through the portal.
_IN_SANDBOX = os.path.exists("/.flatpak-info") and shutil.which("flatpak-spawn")

BREW_CANDIDATES = (
    "/home/linuxbrew/.linuxbrew/bin/brew",
    "/opt/homebrew/bin/brew",
    os.path.expanduser("~/.linuxbrew/bin/brew"),
)

BREW_ENV = {
    "HOMEBREW_NO_AUTO_UPDATE": "1",
    "HOMEBREW_NO_ENV_HINTS": "1",
    "HOMEBREW_NO_ANALYTICS": "1",
}


@dataclass
class Result:
    """One package, from one source."""

    source: str  # backend id: "flatpak", "rpm", "brew", "box:<name>"
    source_label: str  # human label for the badge, e.g. "Flatpak"
    name: str  # display name
    ident: str  # what you actually install
    summary: str = ""
    version: str = ""
    origin: str = ""  # remote / repo / container it came from
    installed: bool = False
    rank: int = 50  # lower sorts first
    newest: str = ""  # version available to upgrade to (updates only)

    @property
    def key(self) -> str:
        return f"{self.source}:{self.ident}"


def _wrap(cmd: list[str], env: dict[str, str] | None = None) -> list[str]:
    """Build the real argv, hopping to the host when we're sandboxed."""
    if not _IN_SANDBOX:
        return cmd
    prefix = ["flatpak-spawn", "--host"]
    for key, value in (env or {}).items():
        prefix.append(f"--env={key}={value}")
    return prefix + cmd


def run(
    cmd: list[str], timeout: int = 120, env: dict[str, str] | None = None
) -> tuple[int, str, str]:
    """Run a command and return (returncode, stdout, stderr). Never raises."""
    child_env = None
    if env and not _IN_SANDBOX:
        child_env = {**os.environ, **env}
    try:
        proc = subprocess.run(
            _wrap(cmd, env),
            capture_output=True,
            text=True,
            timeout=timeout,
            env=child_env,
            **_TEXT_KW,
        )
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError:
        return 127, "", f"{cmd[0]}: not found"
    except subprocess.TimeoutExpired:
        return 124, "", f"{cmd[0]}: timed out after {timeout}s"
    except OSError as exc:
        return 1, "", str(exc)


def popen(cmd: list[str], env: dict[str, str] | None = None) -> subprocess.Popen:
    """Start a command with stdout+stderr merged, for streaming into the UI."""
    child_env = None
    if env and not _IN_SANDBOX:
        child_env = {**os.environ, **env}
    return subprocess.Popen(
        _wrap(cmd, env),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=child_env,
        **_TEXT_KW,
    )


_have_cache: dict[str, bool] = {}


def have(cmd: str) -> bool:
    """Is this executable available where the package tools actually live?"""
    if cmd not in _have_cache:
        if _IN_SANDBOX:
            rc, _, _ = run(["sh", "-c", f"command -v {shlex.quote(cmd)}"], timeout=15)
            _have_cache[cmd] = rc == 0
        else:
            _have_cache[cmd] = shutil.which(cmd) is not None
    return _have_cache[cmd]


RPM_ARCHES = frozenset(
    ("x86_64", "noarch", "i686", "aarch64", "armv7hl", "ppc64le", "s390x", "src")
)


def strip_arch(name: str) -> str:
    """`ripgrep.x86_64` -> `ripgrep`, but leave `python3.12` alone."""
    base, dot, arch = name.rpartition(".")
    return base if dot and arch in RPM_ARCHES else name


def rank_of(term: str, name: str, ident: str, summary: str) -> int:
    """Score a match so the obvious answer floats to the top of a mixed list."""
    t = term.lower().strip()
    name_l, ident_l, summary_l = name.lower(), ident.lower(), (summary or "").lower()
    # Flatpak ids are reverse-DNS; the last segment is the part people type.
    tail = ident_l.rsplit(".", 1)[-1]
    if t in (name_l, ident_l, tail):
        return 0
    if name_l.startswith(t) or tail.startswith(t) or ident_l.startswith(t):
        return 10
    if t in name_l or t in tail:
        return 20
    if t in ident_l:
        return 30
    if t in summary_l:
        return 40
    return 50


# -- output parsers, one per package manager --------------------------------
#
# Each turns a search tool's stdout into (name, summary) pairs. They are shared:
# the same parser serves a host-level backend and the distrobox backend that
# runs the same tool inside a container.


def parse_dnf(out: str) -> list[tuple[str, str]]:
    """dnf5 prints `name.arch<TAB>summary` under `Matched fields:` headers;
    older dnf prints `name.arch : summary` under `====` headers."""
    items = []
    for line in out.splitlines():
        if not line.strip() or line.startswith(("Matched fields:", "=")):
            continue
        if "\t" in line:
            name, _, summary = line.partition("\t")
        elif " : " in line:
            name, _, summary = line.partition(" : ")
        else:
            continue
        name = name.strip()
        if not name or " " in name:
            continue
        items.append((strip_arch(name), summary.strip()))
    return items


def parse_apt(out: str) -> list[tuple[str, str]]:
    items = []
    for line in out.splitlines():
        name, sep, summary = line.partition(" - ")
        if sep and name.strip():
            items.append((name.strip(), summary.strip()))
    return items


def parse_pacman(out: str) -> list[tuple[str, str]]:
    items = []
    pending = None
    for line in out.splitlines():
        if line.startswith((" ", "\t")):
            if pending:
                items.append((pending, line.strip()))
                pending = None
        elif "/" in line:
            pending = line.split("/", 1)[1].split()[0]
    return items


def parse_apk(out: str) -> list[tuple[str, str]]:
    items = []
    for line in out.splitlines():
        name, sep, summary = line.partition(" - ")
        if sep:
            items.append((name.strip().rsplit("-", 2)[0], summary.strip()))
    return items


def parse_zypper(out: str) -> list[tuple[str, str]]:
    items = []
    for line in out.splitlines():
        parts = [p.strip() for p in line.split("|")]
        if len(parts) >= 3 and parts[1] and parts[0] not in ("S", "-"):
            items.append((parts[1], parts[2]))
    return items


def parse_snap(out: str) -> list[tuple[str, str]]:
    """`snap find` prints a table: Name  Version  Publisher  Notes  Summary."""
    items = []
    for line in out.splitlines():
        if not line.strip() or line.startswith("Name "):
            continue
        # Five columns, but the summary (last) can contain spaces.
        parts = line.split(None, 4)
        if len(parts) < 5:
            continue
        items.append((parts[0], parts[4].strip()))
    return items


def _columns(out: str, first_header: str) -> tuple[list[list[str]], list[str]]:
    """Split a whitespace-aligned CLI table into rows of cells.

    winget and newer Scoop print columns padded apart by runs of two or more
    spaces. Splitting on that keeps a value that overflows its column intact
    (fixed-offset slicing would chop it), while still surviving single spaces
    inside a Name. Returns (rows, lowercased column headers).
    """
    lines = [ln.rstrip() for ln in out.replace("\r", "\n").splitlines()]
    header_lc = first_header.lower()
    header_idx = next(
        (i for i, ln in enumerate(lines) if ln.strip().lower().startswith(header_lc)),
        None,
    )
    if header_idx is None:
        return [], []
    cols = [c.lower() for c in re.split(r"\s{2,}", lines[header_idx].strip())]
    rows = []
    for ln in lines[header_idx + 1:]:
        stripped = ln.strip()
        if not stripped or set(stripped) <= {"-", " "}:  # blank or dashed rule
            continue
        rows.append(re.split(r"\s{2,}", stripped))
    return rows, cols


def parse_winget(out: str) -> list[tuple[str, str, str]]:
    """`winget search` prints a Name / Id / Version / Source table.

    Returns (name, id, version); the id is what you install with `-e --id`.
    """
    rows, cols = _columns(out, "Name")
    if not rows:
        return []
    try:
        i_name, i_id = cols.index("name"), cols.index("id")
    except ValueError:
        return []
    i_ver = cols.index("version") if "version" in cols else None
    items = []
    for cells in rows:
        if len(cells) <= i_id or not cells[i_id]:
            continue
        version = cells[i_ver] if i_ver is not None and len(cells) > i_ver else ""
        items.append((cells[i_name], cells[i_id], version))
    return items


def parse_scoop(out: str) -> list[tuple[str, str]]:
    """Scoop 0.3+ prints a Name/Version/Source/Binaries table; older builds
    print `name (version)` grouped under `'bucket' bucket:` headers."""
    rows, cols = _columns(out, "Name")
    if rows and "name" in cols:
        i_name = cols.index("name")
        i_ver = cols.index("version") if "version" in cols else None
        out_items = []
        for cells in rows:
            if len(cells) <= i_name or not cells[i_name]:
                continue
            ver = cells[i_ver] if i_ver is not None and len(cells) > i_ver else ""
            out_items.append((cells[i_name], f"version {ver}" if ver else ""))
        if out_items:
            return out_items
    # Legacy grouped format: `name (version)` entries indented under a
    # `'bucket' bucket:` header. Require the version paren so status lines like
    # "No matches found." are not mistaken for packages.
    items = []
    for line in out.splitlines():
        name, sep, rest = line.strip().partition(" (")
        if sep and name.strip():
            items.append((name.strip(), rest.rstrip(")").strip()))
    return items


def parse_choco(out: str) -> list[tuple[str, str]]:
    """Run with `--limit-output`: one `name|version` per line, no chrome."""
    items = []
    for line in out.splitlines():
        if "|" not in line:
            continue
        name, _, version = line.partition("|")
        if name.strip():
            items.append((name.strip(), f"version {version.strip()}" if version.strip() else ""))
    return items


class Backend:
    """A place apps can come from."""

    id = ""
    label = ""
    # Shown in the UI when the user is about to install from here.
    caveat = ""

    def available(self) -> bool:
        raise NotImplementedError

    def search(self, term: str) -> list[Result]:
        raise NotImplementedError

    def install_cmd(self, result: Result) -> list[str]:
        raise NotImplementedError

    def remove_cmd(self, result: Result) -> list[str]:
        raise NotImplementedError

    def env(self) -> dict[str, str]:
        return {}

    # -- updates: backends that support it override these ---------------------

    def outdated(self) -> list[Result]:
        """Installed packages from this source that have a newer version."""
        return []

    def upgrade_cmd(self, result: Result) -> list[str]:
        """Upgrade one package to its newest version."""
        return self.install_cmd(result)

    def upgrade_all_cmd(self) -> list[str]:
        """Upgrade everything from this source at once ([] if unsupported)."""
        return []

    def _update(self, name: str, ident: str, current: str, newest: str, origin: str = "") -> Result:
        """Build a Result describing one available update."""
        return Result(
            source=self.id, source_label=self.label, name=name, ident=ident,
            version=current, newest=newest, origin=origin or self.label,
            installed=True, rank=0,
        )


class FlatpakBackend(Backend):
    id = "flatpak"
    label = "Flatpak"

    def available(self) -> bool:
        return have("flatpak")

    def _installed(self) -> set[str]:
        rc, out, _ = run(["flatpak", "list", "--columns=application"], timeout=30)
        if rc != 0:
            return set()
        return {line.strip() for line in out.splitlines() if line.strip()}

    def search(self, term: str) -> list[Result]:
        columns = "name,description,application,version,branch,remotes"
        rc, out, err = run(
            ["flatpak", "search", f"--columns={columns}", term], timeout=90
        )
        if rc != 0 and not out:
            raise RuntimeError(err.strip() or "flatpak search failed")

        installed = self._installed()
        results = []
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) < 6:
                continue  # "No matches found" and other prose
            name, desc, app_id, version, _branch, remotes = (p.strip() for p in parts[:6])
            if not app_id:
                continue
            clean = lambda v: "" if v in ("-", "") else v  # noqa: E731
            results.append(
                Result(
                    source=self.id,
                    source_label=self.label,
                    name=clean(name) or app_id,
                    ident=app_id,
                    summary=clean(desc),
                    version=clean(version),
                    origin=clean(remotes),
                    installed=app_id in installed,
                    rank=rank_of(term, name, app_id, desc),
                )
            )
        return results

    def install_cmd(self, result: Result) -> list[str]:
        # --user keeps this off the polkit path entirely.
        remote = (result.origin or "").split(",")[0].strip()
        cmd = ["flatpak", "install", "-y", "--user"]
        if remote:
            cmd.append(remote)
        cmd.append(result.ident)
        return cmd

    def remove_cmd(self, result: Result) -> list[str]:
        return ["flatpak", "uninstall", "-y", result.ident]

    def _versions(self) -> dict[str, str]:
        rc, out, _ = run(["flatpak", "list", "--columns=application,version"], timeout=30)
        current: dict[str, str] = {}
        if rc == 0:
            for line in out.splitlines():
                parts = line.split("\t")
                if len(parts) >= 2 and parts[0].strip():
                    current[parts[0].strip()] = parts[1].strip()
        return current

    def outdated(self) -> list[Result]:
        rc, out, _ = run(
            ["flatpak", "remote-ls", "--updates", "--columns=application,version,name"],
            timeout=120,
        )
        if rc != 0:
            return []
        current = self._versions()
        results = []
        for line in out.splitlines():
            parts = [p.strip() for p in line.split("\t")]
            app = parts[0] if parts else ""
            if not app or "." not in app:
                continue
            newest = parts[1] if len(parts) > 1 and parts[1] != "-" else ""
            name = parts[2] if len(parts) > 2 and parts[2] != "-" else app
            results.append(self._update(name, app, current.get(app, ""), newest))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["flatpak", "update", "-y", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["flatpak", "update", "-y"]


class RpmOstreeBackend(Backend):
    id = "rpm"
    label = "RPM"
    caveat = "Layered onto the image — takes effect after a reboot."

    _SECTION = re.compile(r"^=+.*=+$")

    def available(self) -> bool:
        return have("rpm-ostree")

    def _installed(self) -> set[str]:
        """Everything present: base image packages plus layered ones."""
        names: set[str] = set()
        rc, out, _ = run(["rpm", "-qa", "--qf", "%{NAME}\\n"], timeout=60)
        if rc == 0:
            names |= {line.strip() for line in out.splitlines() if line.strip()}
        rc, out, _ = run(["rpm-ostree", "status", "--json"], timeout=30)
        if rc == 0:
            try:
                data = json.loads(out)
                for deployment in data.get("deployments", []):
                    # Layered entries are plain names, e.g. "plasma-workspace-x11".
                    names |= {str(p) for p in deployment.get("packages", []) or []}
            except (ValueError, AttributeError):
                pass
        return names

    def layered(self) -> set[str]:
        rc, out, _ = run(["rpm-ostree", "status", "--json"], timeout=30)
        if rc != 0:
            return set()
        try:
            data = json.loads(out)
        except ValueError:
            return set()
        deployments = data.get("deployments", [])
        if not deployments:
            return set()
        return set(deployments[0].get("packages", []) or [])

    def search(self, term: str) -> list[Result]:
        rc, out, err = run(["rpm-ostree", "search", term], timeout=300)
        if rc != 0 and not out:
            raise RuntimeError(err.strip() or "rpm-ostree search failed")

        installed = self._installed()
        seen: dict[str, Result] = {}
        for line in out.splitlines():
            line = line.strip()
            if not line or self._SECTION.match(line) or line.startswith("No matches"):
                continue
            name, _, summary = line.partition(" : ")
            name, summary = name.strip(), summary.strip()
            if not name or " " in name:
                continue
            if name in seen:
                continue
            seen[name] = Result(
                source=self.id,
                source_label=self.label,
                name=name,
                ident=name,
                summary=summary,
                origin="Fedora repos",
                installed=name in installed,
                rank=rank_of(term, name, name, summary),
            )
        return list(seen.values())

    def install_cmd(self, result: Result) -> list[str]:
        return ["rpm-ostree", "install", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["rpm-ostree", "uninstall", result.ident]

    def outdated(self) -> list[Result]:
        # On an atomic system the whole image updates as one unit, not per
        # package. `upgrade --check` exits 0 when a newer image is available.
        rc, out, _ = run(["rpm-ostree", "upgrade", "--check"], timeout=180)
        if rc != 0:
            return []
        version = ""
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("Version:"):
                version = line.split(":", 1)[1].strip().split(" ")[0]
                break
        return [self._update("System image", "@system", "", version, "Fedora base image")]

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["rpm-ostree", "upgrade"]

    def upgrade_all_cmd(self) -> list[str]:
        return ["rpm-ostree", "upgrade"]


class BrewBackend(Backend):
    id = "brew"
    label = "Homebrew"

    def __init__(self) -> None:
        self._bin: str | None = None

    def _brew(self) -> str | None:
        if self._bin is None:
            for path in BREW_CANDIDATES:
                if _IN_SANDBOX:
                    rc, _, _ = run(["test", "-x", path], timeout=10)
                    ok = rc == 0
                else:
                    ok = os.access(path, os.X_OK)
                if ok:
                    self._bin = path
                    break
            else:
                self._bin = shutil.which("brew") or ""
        return self._bin or None

    def available(self) -> bool:
        return self._brew() is not None

    def env(self) -> dict[str, str]:
        return dict(BREW_ENV)

    def _installed(self) -> set[str]:
        brew = self._brew()
        if not brew:
            return set()
        rc, out, _ = run([brew, "list", "--formula", "-1"], timeout=60, env=BREW_ENV)
        if rc != 0:
            return set()
        return {line.strip() for line in out.splitlines() if line.strip()}

    @staticmethod
    def _names(out: str) -> list[tuple[str, str]]:
        """Parse `brew search` output into (name, description) pairs.

        Plain search prints bare names; --desc prints `name: description`.
        Both may carry `==> Formulae` / `==> Casks` section headers.
        """
        pairs = []
        for line in out.splitlines():
            line = line.strip()
            if not line or line.startswith("==>"):
                continue
            if line.startswith(("Error:", "Warning:")):
                continue
            name, sep, desc = line.partition(": ")
            pairs.append((name.strip(), desc.strip() if sep else ""))
        return pairs

    def search(self, term: str) -> list[Result]:
        brew = self._brew()
        if not brew:
            return []

        rc, out, err = run([brew, "search", term], timeout=180, env=BREW_ENV)
        # brew exits non-zero on "no formulae found", which is not an error here.
        if rc != 0 and "No formulae or casks found" not in (out + err):
            raise RuntimeError(err.strip() or "brew search failed")
        by_name = dict(self._names(out))

        # A second, cheap pass that also gives us descriptions.
        rc, out, _ = run([brew, "search", "--desc", term], timeout=180, env=BREW_ENV)
        if rc == 0:
            for name, desc in self._names(out):
                if desc or name not in by_name:
                    by_name[name] = desc

        installed = self._installed()
        results = []
        for name, desc in by_name.items():
            if not name:
                continue
            results.append(
                Result(
                    source=self.id,
                    source_label=self.label,
                    name=name,
                    ident=name,
                    summary=desc,
                    origin="homebrew/core",
                    installed=name in installed,
                    rank=rank_of(term, name, name, desc),
                )
            )
        return results

    def install_cmd(self, result: Result) -> list[str]:
        return [self._brew() or "brew", "install", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return [self._brew() or "brew", "uninstall", result.ident]

    def outdated(self) -> list[Result]:
        brew = self._brew()
        if not brew:
            return []
        rc, out, _ = run([brew, "outdated", "--formula", "--verbose"], timeout=120, env=BREW_ENV)
        if rc != 0:
            return []
        results = []
        for line in out.splitlines():
            line = line.strip()
            if not line:
                continue
            # "name (1.0, 1.1) < 2.0"  or  "name (1.0) != 2.0"
            name = line.split(" (", 1)[0].strip()
            newest = ""
            for sep in (" < ", " != ", " <= "):
                if sep in line:
                    newest = line.split(sep, 1)[1].strip()
                    break
            current = ""
            if " (" in line and ")" in line:
                current = line.split(" (", 1)[1].split(")", 1)[0]
            if name:
                results.append(self._update(name, name, current, newest, "homebrew/core"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return [self._brew() or "brew", "upgrade", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return [self._brew() or "brew", "upgrade"]


class DistroboxBackend(Backend):
    """Searches the package manager inside one distrobox container.

    Off by default: reaching a stopped container has to boot it first, which
    takes far longer than every other source combined.
    """

    id = "box"
    label = "Container"
    caveat = "Installs inside the container, not on the host."

    def __init__(self, container: str) -> None:
        self.container = container
        self.id = f"box:{container}"
        self.label = container
        self._pm: str | None = None

    @staticmethod
    def list_containers() -> list[str]:
        if not have("distrobox"):
            return []
        rc, out, _ = run(["distrobox", "list", "--no-color"], timeout=60)
        if rc != 0:
            return []
        names = []
        for line in out.splitlines()[1:]:  # skip header
            parts = [p.strip() for p in line.split("|")]
            if len(parts) >= 2 and parts[1]:
                names.append(parts[1])
        return names

    def available(self) -> bool:
        return have("distrobox")

    def _enter(self, argv: list[str], timeout: int) -> tuple[int, str, str]:
        return run(
            ["distrobox", "enter", "--name", self.container, "--"] + argv,
            timeout=timeout,
        )

    def _manager(self) -> str | None:
        if self._pm is None:
            rc, out, _ = self._enter(
                ["sh", "-c", "command -v dnf5 dnf apt-get pacman apk zypper | head -1"],
                timeout=180,
            )
            self._pm = os.path.basename(out.strip()) if rc == 0 and out.strip() else ""
        return self._pm or None

    def search(self, term: str) -> list[Result]:
        manager = self._manager()
        if not manager:
            return []

        commands = {
            "dnf5": ["dnf5", "search", term],
            "dnf": ["dnf", "search", term],
            "apt-get": ["apt-cache", "search", term],
            "pacman": ["pacman", "-Ss", term],
            "apk": ["apk", "search", "-d", term],
            "zypper": ["zypper", "--non-interactive", "search", term],
        }
        rc, out, err = self._enter(commands[manager], timeout=300)
        if rc != 0 and not out:
            return []

        parsers = {
            "dnf5": parse_dnf, "dnf": parse_dnf, "apt-get": parse_apt,
            "pacman": parse_pacman, "apk": parse_apk, "zypper": parse_zypper,
        }
        parsed = parsers[manager](out)
        results = []
        for name, summary in parsed:
            results.append(
                Result(
                    source=self.id,
                    source_label=self.label,
                    name=name,
                    ident=name,
                    summary=summary,
                    origin=f"{self.container} ({manager})",
                    rank=rank_of(term, name, name, summary),
                )
            )
        return results

    def install_cmd(self, result: Result) -> list[str]:
        manager = self._manager() or "dnf"
        installs = {
            "dnf5": ["sudo", "dnf5", "install", "-y", result.ident],
            "dnf": ["sudo", "dnf", "install", "-y", result.ident],
            "apt-get": ["sudo", "apt-get", "install", "-y", result.ident],
            "pacman": ["sudo", "pacman", "-S", "--noconfirm", result.ident],
            "apk": ["sudo", "apk", "add", result.ident],
            "zypper": ["sudo", "zypper", "--non-interactive", "install", result.ident],
        }
        return ["distrobox", "enter", "--name", self.container, "--"] + installs[manager]

    def remove_cmd(self, result: Result) -> list[str]:
        manager = self._manager() or "dnf"
        removes = {
            "dnf5": ["sudo", "dnf5", "remove", "-y", result.ident],
            "dnf": ["sudo", "dnf", "remove", "-y", result.ident],
            "apt-get": ["sudo", "apt-get", "remove", "-y", result.ident],
            "pacman": ["sudo", "pacman", "-R", "--noconfirm", result.ident],
            "apk": ["sudo", "apk", "del", result.ident],
            "zypper": ["sudo", "zypper", "--non-interactive", "remove", result.ident],
        }
        return ["distrobox", "enter", "--name", self.container, "--"] + removes[manager]


class HostBackend(Backend):
    """A native package manager running directly on the host.

    Subclasses provide the search argv, a parser, an installed-set query, and
    the install/remove commands; this base runs the search and builds Results.
    """

    origin_label = ""
    search_timeout = 120
    tool = ""  # executable that must be present for this backend to apply

    def available(self) -> bool:
        return have(self.tool)

    def _search_cmd(self, term: str) -> list[str]:
        raise NotImplementedError

    def _parse(self, out: str) -> list[tuple[str, str]]:
        raise NotImplementedError

    def _installed(self) -> set[str]:
        return set()

    def search(self, term: str) -> list[Result]:
        rc, out, err = run(self._search_cmd(term), timeout=self.search_timeout)
        if rc != 0 and not out:
            raise RuntimeError(err.strip() or f"{self.label} search failed")
        installed = self._installed()
        results = []
        for name, summary in self._parse(out):
            if not name:
                continue
            results.append(
                Result(
                    source=self.id,
                    source_label=self.label,
                    name=name,
                    ident=name,
                    summary=summary,
                    origin=self.origin_label,
                    installed=name in installed,
                    rank=rank_of(term, name, name, summary),
                )
            )
        return results


def _rpm_installed() -> set[str]:
    rc, out, _ = run(["rpm", "-qa", "--qf", "%{NAME}\\n"], timeout=60)
    return {l.strip() for l in out.splitlines() if l.strip()} if rc == 0 else set()


class AptBackend(HostBackend):
    """Debian, Ubuntu, Mint and friends."""

    id = "apt"
    label = "APT"
    caveat = "Installs system-wide — asks for your password."
    origin_label = "APT repos"
    tool = "apt-get"

    def _search_cmd(self, term: str) -> list[str]:
        return ["apt-cache", "search", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_apt(out)

    def _installed(self) -> set[str]:
        rc, out, _ = run(["dpkg-query", "-W", "-f=${Package}\\n"], timeout=60)
        return {l.strip() for l in out.splitlines() if l.strip()} if rc == 0 else set()

    def install_cmd(self, result: Result) -> list[str]:
        return ["sudo", "apt-get", "install", "-y", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["sudo", "apt-get", "remove", "-y", result.ident]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(["apt", "list", "--upgradable"], timeout=120)
        if rc != 0 and not out:
            return []
        results = []
        for line in out.splitlines():
            if "/" not in line or line.startswith("Listing"):
                continue
            name = line.split("/", 1)[0].strip()
            fields = line.split()
            newest = fields[1] if len(fields) > 1 else ""
            current = ""
            if "upgradable from:" in line:
                current = line.split("upgradable from:", 1)[1].strip().rstrip("]").strip()
            if name:
                results.append(self._update(name, name, current, newest, "APT"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["sudo", "apt-get", "install", "--only-upgrade", "-y", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["sudo", "apt-get", "upgrade", "-y"]


class DnfBackend(HostBackend):
    """Traditional (non-atomic) Fedora, RHEL, CentOS, Rocky, Alma.

    Held off on atomic systems, where rpm-ostree is the real install path and a
    plain `dnf install` would not persist — RpmOstreeBackend covers those.
    """

    id = "dnf"
    label = "DNF"
    caveat = "Installs system-wide — asks for your password."
    origin_label = "Fedora/RHEL repos"
    search_timeout = 180
    tool = "dnf"

    def available(self) -> bool:
        return have("dnf") and not have("rpm-ostree")

    def _search_cmd(self, term: str) -> list[str]:
        return ["dnf", "search", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_dnf(out)

    def _installed(self) -> set[str]:
        return _rpm_installed()

    def install_cmd(self, result: Result) -> list[str]:
        return ["sudo", "dnf", "install", "-y", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["sudo", "dnf", "remove", "-y", result.ident]

    def outdated(self) -> list[Result]:
        # dnf check-update exits 100 when updates exist, 0 when none.
        rc, out, _ = run(["dnf", "check-update"], timeout=180)
        if rc not in (0, 100):
            return []
        results = []
        for line in out.splitlines():
            line = line.rstrip()
            if not line or line[0].isspace() or line.startswith(("Last metadata", "Obsoleting", "Security")):
                continue
            fields = line.split()
            if len(fields) < 3 or "." not in fields[0]:
                continue
            name = strip_arch(fields[0])
            results.append(self._update(name, name, "", fields[1], fields[2]))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["sudo", "dnf", "upgrade", "-y", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["sudo", "dnf", "upgrade", "-y"]


class PacmanBackend(HostBackend):
    """Arch, Manjaro, EndeavourOS."""

    id = "pacman"
    label = "Pacman"
    caveat = "Installs system-wide — asks for your password."
    origin_label = "Arch repos"
    tool = "pacman"

    def _search_cmd(self, term: str) -> list[str]:
        return ["pacman", "-Ss", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_pacman(out)

    def _installed(self) -> set[str]:
        rc, out, _ = run(["pacman", "-Qq"], timeout=60)
        return {l.strip() for l in out.splitlines() if l.strip()} if rc == 0 else set()

    def install_cmd(self, result: Result) -> list[str]:
        return ["sudo", "pacman", "-S", "--noconfirm", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["sudo", "pacman", "-R", "--noconfirm", result.ident]

    def outdated(self) -> list[Result]:
        # checkupdates (pacman-contrib) is safe and needs no root; fall back to -Qu.
        cmd = ["checkupdates"] if have("checkupdates") else ["pacman", "-Qu"]
        rc, out, _ = run(cmd, timeout=120)
        if rc != 0:
            return []
        results = []
        for line in out.splitlines():
            fields = line.split()
            if not fields:
                continue
            name = fields[0]
            if "->" in fields:
                i = fields.index("->")
                current = fields[i - 1] if i >= 2 else ""
                newest = fields[i + 1] if i + 1 < len(fields) else ""
            else:
                current, newest = "", fields[1] if len(fields) > 1 else ""
            results.append(self._update(name, name, current, newest, "Arch"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        # Partial upgrades are unsafe on Arch — refresh the whole system.
        return ["sudo", "pacman", "-Syu", "--noconfirm"]

    def upgrade_all_cmd(self) -> list[str]:
        return ["sudo", "pacman", "-Syu", "--noconfirm"]


class ZypperBackend(HostBackend):
    """openSUSE Leap and Tumbleweed."""

    id = "zypper"
    label = "Zypper"
    caveat = "Installs system-wide — asks for your password."
    origin_label = "openSUSE repos"
    tool = "zypper"

    def _search_cmd(self, term: str) -> list[str]:
        return ["zypper", "--non-interactive", "search", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_zypper(out)

    def _installed(self) -> set[str]:
        return _rpm_installed()

    def install_cmd(self, result: Result) -> list[str]:
        return ["sudo", "zypper", "--non-interactive", "install", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["sudo", "zypper", "--non-interactive", "remove", result.ident]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(["zypper", "--non-interactive", "list-updates"], timeout=120)
        if rc != 0 and not out:
            return []
        results = []
        for line in out.splitlines():
            parts = [p.strip() for p in line.split("|")]
            # S | Repository | Name | Current Version | Available Version | Arch
            if len(parts) >= 6 and parts[0] == "v":
                results.append(self._update(parts[2], parts[2], parts[3], parts[4], "openSUSE"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["sudo", "zypper", "--non-interactive", "update", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["sudo", "zypper", "--non-interactive", "update"]


class ApkBackend(HostBackend):
    """Alpine Linux."""

    id = "apk"
    label = "APK"
    caveat = "Installs system-wide — asks for your password."
    origin_label = "Alpine repos"
    tool = "apk"

    def _search_cmd(self, term: str) -> list[str]:
        return ["apk", "search", "-d", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_apk(out)

    def _installed(self) -> set[str]:
        rc, out, _ = run(["apk", "info"], timeout=60)
        return {l.strip() for l in out.splitlines() if l.strip()} if rc == 0 else set()

    def install_cmd(self, result: Result) -> list[str]:
        return ["sudo", "apk", "add", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["sudo", "apk", "del", result.ident]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(["apk", "version", "-l", "<"], timeout=120)
        if rc != 0:
            return []
        results = []
        for line in out.splitlines():
            line = line.strip()
            if not line or "<" not in line or line.startswith("Installed"):
                continue
            left, _, right = line.partition("<")
            pkg, newest = left.strip(), right.strip()
            name = pkg.rsplit("-", 2)[0] if pkg.count("-") >= 2 else pkg
            results.append(self._update(name, name, "", newest, "Alpine"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["sudo", "apk", "add", "-u", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["sudo", "apk", "upgrade"]


class SnapBackend(HostBackend):
    """Snap: cross-distro, ships its own store."""

    id = "snap"
    label = "Snap"
    origin_label = "Snap Store"
    tool = "snap"

    def _search_cmd(self, term: str) -> list[str]:
        return ["snap", "find", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_snap(out)

    def _installed(self) -> set[str]:
        rc, out, _ = run(["snap", "list"], timeout=30)
        if rc != 0:
            return set()
        names = set()
        for line in out.splitlines()[1:]:  # skip header
            parts = line.split()
            if parts:
                names.add(parts[0])
        return names

    def install_cmd(self, result: Result) -> list[str]:
        return ["sudo", "snap", "install", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["sudo", "snap", "remove", result.ident]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(["snap", "refresh", "--list"], timeout=60)
        if rc != 0 or "up to date" in out.lower():
            return []
        results = []
        for line in out.splitlines():
            if not line.strip() or line.startswith("Name "):
                continue
            fields = line.split()
            if len(fields) >= 4:
                results.append(self._update(fields[0], fields[0], "", fields[1], "Snap Store"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["sudo", "snap", "refresh", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["sudo", "snap", "refresh"]


class WingetBackend(HostBackend):
    """Windows Package Manager (winget)."""

    id = "winget"
    label = "winget"
    origin_label = "winget"
    tool = "winget"

    def available(self) -> bool:
        return IS_WINDOWS and have("winget")

    def _search_cmd(self, term: str) -> list[str]:
        return [
            "winget", "search", "--source", "winget", "-q", term,
            "--accept-source-agreements", "--disable-interactivity",
        ]

    def search(self, term: str) -> list[Result]:
        rc, out, err = run(self._search_cmd(term), timeout=self.search_timeout)
        if rc != 0 and not out:
            raise RuntimeError(err.strip() or "winget search failed")
        installed = self._installed()
        results = []
        for name, ident, version in parse_winget(out):
            results.append(
                Result(
                    source=self.id,
                    source_label=self.label,
                    name=name,
                    ident=ident,  # the winget Id, installed with -e --id
                    summary="",
                    version=version,
                    origin=self.origin_label,
                    installed=ident in installed,
                    rank=rank_of(term, name, ident, ""),
                )
            )
        return results

    def _installed(self) -> set[str]:
        rc, out, _ = run(
            ["winget", "list", "--disable-interactivity"], timeout=60
        )
        if rc != 0:
            return set()
        return {ident for _, ident, _ in parse_winget(out)}

    def install_cmd(self, result: Result) -> list[str]:
        return [
            "winget", "install", "-e", "--id", result.ident,
            "--accept-package-agreements", "--accept-source-agreements",
        ]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["winget", "uninstall", "-e", "--id", result.ident]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(
            ["winget", "upgrade", "--include-unknown", "--disable-interactivity"], timeout=120
        )
        if rc != 0 and not out:
            return []
        rows, cols = _columns(out, "Name")
        if not rows or "id" not in cols:
            return []
        i_name, i_id = cols.index("name"), cols.index("id")
        i_ver = cols.index("version") if "version" in cols else None
        i_av = cols.index("available") if "available" in cols else None
        results = []
        for c in rows:
            if len(c) <= i_id or not c[i_id]:
                continue
            newest = c[i_av] if i_av is not None and len(c) > i_av else ""
            if not newest:
                continue
            current = c[i_ver] if i_ver is not None and len(c) > i_ver else ""
            results.append(self._update(c[i_name], c[i_id], current, newest, "winget"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return [
            "winget", "upgrade", "-e", "--id", result.ident,
            "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity",
        ]

    def upgrade_all_cmd(self) -> list[str]:
        return [
            "winget", "upgrade", "--all",
            "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity",
        ]


class ScoopBackend(HostBackend):
    """Scoop, the Windows command-line installer."""

    id = "scoop"
    label = "Scoop"
    origin_label = "Scoop"
    tool = "scoop"

    def available(self) -> bool:
        return IS_WINDOWS and have("scoop")

    def _search_cmd(self, term: str) -> list[str]:
        return ["scoop", "search", term]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_scoop(out)

    def _installed(self) -> set[str]:
        rc, out, _ = run(["scoop", "list"], timeout=60)
        if rc != 0:
            return set()
        rows, cols = _columns(out, "Name")
        if rows and "name" in cols:
            i = cols.index("name")
            return {r[i] for r in rows if len(r) > i and r[i]}
        return set()

    def install_cmd(self, result: Result) -> list[str]:
        return ["scoop", "install", result.ident]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["scoop", "uninstall", result.ident]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(["scoop", "status"], timeout=120)
        if rc != 0 and not out:
            return []
        rows, cols = _columns(out, "Name")
        if not rows or "name" not in cols:
            return []
        i_name = cols.index("name")
        i_cur = cols.index("installed version") if "installed version" in cols else None
        i_new = cols.index("latest version") if "latest version" in cols else None
        results = []
        for c in rows:
            if len(c) <= i_name or not c[i_name]:
                continue
            current = c[i_cur] if i_cur is not None and len(c) > i_cur else ""
            newest = c[i_new] if i_new is not None and len(c) > i_new else ""
            results.append(self._update(c[i_name], c[i_name], current, newest, "Scoop"))
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["scoop", "update", result.ident]

    def upgrade_all_cmd(self) -> list[str]:
        return ["scoop", "update", "*"]


class ChocolateyBackend(HostBackend):
    """Chocolatey for Windows."""

    id = "choco"
    label = "Chocolatey"
    caveat = "Runs choco, which needs an elevated (admin) shell to install."
    origin_label = "Chocolatey"
    tool = "choco"

    def available(self) -> bool:
        return IS_WINDOWS and have("choco")

    def _search_cmd(self, term: str) -> list[str]:
        return ["choco", "search", term, "--limit-output"]

    def _parse(self, out: str) -> list[tuple[str, str]]:
        return parse_choco(out)

    def _installed(self) -> set[str]:
        rc, out, _ = run(
            ["choco", "list", "--local-only", "--limit-output"], timeout=60
        )
        if rc != 0:
            return set()
        return {name for name, _ in parse_choco(out)}

    def install_cmd(self, result: Result) -> list[str]:
        return ["choco", "install", result.ident, "-y"]

    def remove_cmd(self, result: Result) -> list[str]:
        return ["choco", "uninstall", result.ident, "-y"]

    def outdated(self) -> list[Result]:
        rc, out, _ = run(["choco", "outdated", "--limit-output"], timeout=120)
        if not out:
            return []
        results = []
        for line in out.splitlines():
            # name|current|available|pinned
            parts = line.split("|")
            if len(parts) >= 3 and parts[0].strip():
                results.append(
                    self._update(parts[0].strip(), parts[0].strip(), parts[1].strip(), parts[2].strip(), "Chocolatey")
                )
        return results

    def upgrade_cmd(self, result: Result) -> list[str]:
        return ["choco", "upgrade", result.ident, "-y"]

    def upgrade_all_cmd(self) -> list[str]:
        return ["choco", "upgrade", "all", "-y"]


# Every backend that installs onto the host directly, in display order. Each
# self-selects through available(), so only the ones whose tool exists run —
# a box only ever lights up the handful that match its OS and distro.
_HOST_BACKENDS: list[type[Backend]] = [
    FlatpakBackend,
    RpmOstreeBackend,
    DnfBackend,
    AptBackend,
    PacmanBackend,
    ZypperBackend,
    ApkBackend,
    SnapBackend,
    BrewBackend,
    WingetBackend,
    ScoopBackend,
    ChocolateyBackend,
]


def default_backends(include_containers: bool = False) -> list[Backend]:
    """Every source that actually works on this machine right now."""
    backends = [cls() for cls in _HOST_BACKENDS]
    backends = [b for b in backends if b.available()]
    if include_containers:
        backends += [DistroboxBackend(name) for name in DistroboxBackend.list_containers()]
    return backends
