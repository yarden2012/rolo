"""Search backends for pkgfind.

One aggregated search across every app source available on an atomic Fedora
system (Bazzite / Silverblue / Kinoite): Flatpak remotes, the Fedora RPM repos
reachable through rpm-ostree, Homebrew, and any distrobox containers.

Nothing in here imports GTK, so the same code drives the GUI and the CLI.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
from dataclasses import dataclass, field

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

        parsed = getattr(self, f"_parse_{manager.replace('-', '_')}")(out)
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

    # -- output parsers, one per package manager ---------------------------

    @staticmethod
    def _parse_dnf5(out: str) -> list[tuple[str, str]]:
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

    _parse_dnf = _parse_dnf5

    @staticmethod
    def _parse_apt_get(out: str) -> list[tuple[str, str]]:
        items = []
        for line in out.splitlines():
            name, sep, summary = line.partition(" - ")
            if sep and name.strip():
                items.append((name.strip(), summary.strip()))
        return items

    @staticmethod
    def _parse_pacman(out: str) -> list[tuple[str, str]]:
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

    @staticmethod
    def _parse_apk(out: str) -> list[tuple[str, str]]:
        items = []
        for line in out.splitlines():
            name, sep, summary = line.partition(" - ")
            if sep:
                items.append((name.strip().rsplit("-", 2)[0], summary.strip()))
        return items

    @staticmethod
    def _parse_zypper(out: str) -> list[tuple[str, str]]:
        items = []
        for line in out.splitlines():
            parts = [p.strip() for p in line.split("|")]
            if len(parts) >= 3 and parts[1] and parts[0] not in ("S", "-"):
                items.append((parts[1], parts[2]))
        return items

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


def default_backends(include_containers: bool = False) -> list[Backend]:
    """Every source that actually works on this machine right now."""
    backends: list[Backend] = [FlatpakBackend(), RpmOstreeBackend(), BrewBackend()]
    backends = [b for b in backends if b.available()]
    if include_containers:
        backends += [DistroboxBackend(name) for name in DistroboxBackend.list_containers()]
    return backends
