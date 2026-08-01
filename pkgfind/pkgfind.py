#!/usr/bin/env python3
"""pkgfind — search every app source on an atomic Fedora system at once.

    pkgfind                 open the app
    pkgfind inkscape        open the app with a search already run
    pkgfind -c inkscape     print results in the terminal instead
"""

from __future__ import annotations

import argparse
import concurrent.futures
import os
import shlex
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import backends as be  # noqa: E402

COLORS = {
    "flatpak": "\033[38;5;75m",
    "rpm": "\033[38;5;215m",
    "brew": "\033[38;5;114m",
    "box": "\033[38;5;177m",
}
RESET = "\033[0m"
DIM = "\033[2m"
BOLD = "\033[1m"
GREEN = "\033[38;5;114m"


def search_all(term: str, backends: list[be.Backend]) -> tuple[list[be.Result], list[str]]:
    """Hit every backend in parallel; a failure in one never sinks the rest."""
    results: list[be.Result] = []
    errors: list[str] = []

    def one(backend: be.Backend):
        try:
            return backend, backend.search(term), None
        except Exception as exc:
            return backend, [], str(exc)

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(len(backends), 1)) as pool:
        for backend, found, error in pool.map(one, backends):
            results.extend(found)
            if error:
                errors.append(f"{backend.label}: {error}")

    results.sort(key=lambda r: (r.rank, r.source_label.lower(), r.name.lower()))
    return results, errors


def run_cli(args: argparse.Namespace) -> int:
    term = " ".join(args.terms).strip()
    if len(term) < 2:
        print("pkgfind: give me at least two characters to search for", file=sys.stderr)
        return 2

    backends = be.default_backends(include_containers=args.containers)
    if not backends:
        print("pkgfind: no package managers found on this system", file=sys.stderr)
        return 1

    color = sys.stdout.isatty() and not args.no_color
    tint = (lambda text, key: f"{COLORS.get(key, '')}{text}{RESET}") if color else (
        lambda text, key: text
    )
    dim = (lambda text: f"{DIM}{text}{RESET}") if color else (lambda text: text)
    bold = (lambda text: f"{BOLD}{text}{RESET}") if color else (lambda text: text)

    results, errors = search_all(term, backends)
    for error in errors:
        print(f"warning: {error}", file=sys.stderr)

    if not results:
        print(f"No results for “{term}”.")
        return 1

    if args.limit:
        results = results[: args.limit]

    width = max(len(r.source_label) for r in results)
    for result in results:
        badge = tint(result.source_label.ljust(width), result.source.split(":")[0])
        mark = f" {GREEN}✓{RESET}" if (color and result.installed) else (
            " ✓" if result.installed else "  "
        )
        version = dim(f" {result.version}") if result.version else ""
        print(f"{badge}{mark} {bold(result.ident)}{version}")
        if result.summary:
            print(f"{' ' * (width + 3)}{dim(result.summary)}")

    installed = sum(1 for r in results if r.installed)
    print()
    print(dim(f"{len(results)} results  ·  {installed} already installed"))

    if args.install:
        target = next((r for r in results if r.ident == args.install), None)
        if target is None:
            print(f"pkgfind: {args.install!r} is not in these results", file=sys.stderr)
            return 1
        backend = next(b for b in backends if b.id == target.source)
        cmd = backend.install_cmd(target)
        print(f"\n$ {' '.join(shlex.quote(c) for c in cmd)}\n")
        if backend.caveat:
            print(f"note: {backend.caveat}\n")
        proc = be.popen(cmd, env=backend.env())
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
        return proc.wait()

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="pkgfind",
        description="Search Flatpak, Fedora RPM, Homebrew and distrobox containers at once.",
    )
    parser.add_argument("terms", nargs="*", help="what to search for")
    parser.add_argument("-c", "--cli", action="store_true", help="print results in the terminal")
    parser.add_argument(
        "-b", "--containers", action="store_true",
        help="also search inside distrobox containers (slow — has to start them)",
    )
    parser.add_argument("-n", "--limit", type=int, default=0, help="show at most N results")
    parser.add_argument("--no-color", action="store_true", help="disable coloured output")
    parser.add_argument(
        "-i", "--install", metavar="ID",
        help="install this identifier from the results (implies --cli)",
    )
    args = parser.parse_args()

    if args.install:
        args.cli = True

    if args.cli:
        return run_cli(args)

    try:
        import app  # imported late so the CLI never needs GTK
    except ImportError as exc:
        print(
            f"pkgfind: can't load the graphical interface ({exc}).\n"
            f"  Running under: {sys.executable}\n"
            f"  The GUI needs the system Python with PyGObject and libadwaita.\n"
            f"  Homebrew's python3 does not have them, and it wins on PATH.\n"
            f"  Terminal output works either way: pkgfind -c {' '.join(args.terms)}",
            file=sys.stderr,
        )
        return 1

    return app.main([sys.argv[0]] + args.terms)


if __name__ == "__main__":
    sys.exit(main())
