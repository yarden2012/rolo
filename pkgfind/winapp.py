"""A minimal Tkinter interface for pkgfind.

The main GUI is GTK4 + libadwaita, which does not run on Windows. Tkinter ships
inside CPython on Windows with nothing extra to install, so this gives Windows a
real window over the exact same backends. It is deliberately small: a search
box, a results table, and a details pane that installs or removes the selection
while streaming the command's output.

Nothing here is Windows-specific — it runs anywhere Tkinter does — but it is the
frontend pkgfind picks on Windows.
"""

from __future__ import annotations

import concurrent.futures
import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import ttk

import backends as be

MAX_ROWS = 300

# When frozen by PyInstaller the icon is unpacked into the bundle dir (_MEIPASS);
# otherwise it sits next to this file under windows/.
if getattr(sys, "frozen", False):
    _ICON_BASE = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
else:
    _ICON_BASE = os.path.dirname(os.path.abspath(__file__))
ICON_PATH = os.path.join(_ICON_BASE, "windows", "pkgfind.ico")


def _apply_windows_chrome(root: tk.Tk) -> None:
    """Give the window and taskbar the pkgfind icon on Windows.

    The AppUserModelID makes the taskbar group under our own icon instead of
    Python's generic one. Both steps are best-effort and no-ops off Windows."""
    if sys.platform != "win32":
        return
    try:
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("dev.rolo.pkgfind")
    except Exception:
        pass
    if os.path.exists(ICON_PATH):
        try:
            root.iconbitmap(ICON_PATH)
        except tk.TclError:
            pass


def _search_all(term: str, backends: list[be.Backend]):
    """Hit every backend in parallel; one failure never sinks the rest."""
    results: list[be.Result] = []
    errors: list[str] = []

    def one(backend: be.Backend):
        try:
            return backend, backend.search(term), None
        except Exception as exc:  # a backend must never crash the search
            return backend, [], str(exc)

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(len(backends), 1)) as pool:
        for backend, found, error in pool.map(one, backends):
            results.extend(found)
            if error:
                errors.append(f"{backend.label}: {error}")

    results.sort(key=lambda r: (r.rank, r.source_label.lower(), r.name.lower()))
    return results, errors


class PkgfindApp:
    def __init__(self, root: tk.Tk, initial: str = "") -> None:
        self.root = root
        self.backends = be.default_backends()
        self._by_id = {b.id: b for b in self.backends}
        self._rows: dict[str, be.Result] = {}  # tree item id -> Result
        self._events: queue.Queue = queue.Queue()  # background -> UI messages
        self._busy = False

        root.title("pkgfind")
        root.geometry("900x600")
        root.minsize(640, 420)
        _apply_windows_chrome(root)

        self._build()
        self.root.after(80, self._pump)

        if not self.backends:
            self._set_status("No supported package managers found on this system.")
            self._search_btn.state(["disabled"])
        elif initial:
            self._query.insert(0, initial)
            self._start_search()
        else:
            sources = ", ".join(b.label for b in self.backends)
            self._set_status(f"Ready — searching: {sources}")

    # -- layout ------------------------------------------------------------

    def _apply_theme(self) -> None:
        """Give the window a current look: the Sun Valley (Windows 11) theme if
        it's available, otherwise the native theme with Segoe UI and roomier
        rows so it doesn't look like a 1990s Tk app."""
        import tkinter.font as tkfont

        style = ttk.Style()
        try:
            import sv_ttk

            sv_ttk.set_theme("light")
        except Exception:
            for theme in ("vista", "clam"):
                try:
                    style.theme_use(theme)
                    break
                except tk.TclError:
                    continue

        if sys.platform == "win32":
            for name in ("TkDefaultFont", "TkTextFont", "TkHeadingFont", "TkMenuFont"):
                try:
                    tkfont.nametofont(name).configure(family="Segoe UI", size=10)
                except tk.TclError:
                    pass

        # Comfortable rows and a clear header, regardless of theme.
        style.configure("Treeview", rowheight=30)
        try:
            style.configure("Treeview.Heading", font=("Segoe UI Semibold", 10))
        except tk.TclError:
            pass

    def _build(self) -> None:
        self._apply_theme()

        bar = ttk.Frame(self.root, padding=(10, 10, 10, 6))
        bar.pack(fill="x")
        self._query = ttk.Entry(bar, font=("", 11))
        self._query.pack(side="left", fill="x", expand=True)
        self._query.bind("<Return>", lambda _e: self._start_search())
        self._search_btn = ttk.Button(bar, text="Search", command=self._start_search)
        self._search_btn.pack(side="left", padx=(8, 0))

        columns = ("source", "package", "version", "installed")
        panes = ttk.Panedwindow(self.root, orient="vertical")
        panes.pack(fill="both", expand=True, padx=10, pady=(0, 6))

        table_frame = ttk.Frame(panes)
        self._tree = ttk.Treeview(
            table_frame, columns=columns, show="headings", selectmode="browse"
        )
        for key, text, width, anchor in (
            ("source", "Source", 110, "w"),
            ("package", "Package", 340, "w"),
            ("version", "Version", 120, "w"),
            ("installed", "Installed", 80, "center"),
        ):
            self._tree.heading(key, text=text)
            self._tree.column(key, width=width, anchor=anchor, stretch=(key == "package"))
        vsb = ttk.Scrollbar(table_frame, orient="vertical", command=self._tree.yview)
        self._tree.configure(yscrollcommand=vsb.set)
        self._tree.pack(side="left", fill="both", expand=True)
        vsb.pack(side="right", fill="y")
        self._tree.bind("<<TreeviewSelect>>", lambda _e: self._on_select())
        panes.add(table_frame, weight=3)

        detail = ttk.Frame(panes)
        top = ttk.Frame(detail)
        top.pack(fill="x", pady=(6, 4))
        self._detail = ttk.Label(top, text="", anchor="w", justify="left", wraplength=640)
        self._detail.pack(side="left", fill="x", expand=True)
        self._action = ttk.Button(top, text="Install", command=self._on_action, state="disabled")
        self._action.pack(side="right")
        self._console = tk.Text(detail, height=8, wrap="word", state="disabled",
                                font=("Consolas", 9))
        self._console.pack(fill="both", expand=True)
        panes.add(detail, weight=2)

        self._status = ttk.Label(self.root, text="", anchor="w", padding=(10, 2, 10, 6))
        self._status.pack(fill="x")

    # -- search ------------------------------------------------------------

    def _start_search(self) -> None:
        if self._busy:
            return
        term = self._query.get().strip()
        if len(term) < 2:
            self._set_status("Type at least two characters to search.")
            return
        self._busy = True
        self._search_btn.state(["disabled"])
        self._set_status(f"Searching for “{term}” …")
        self._tree.delete(*self._tree.get_children())
        self._rows.clear()
        self._clear_detail()
        threading.Thread(target=self._search_worker, args=(term,), daemon=True).start()

    def _search_worker(self, term: str) -> None:
        results, errors = _search_all(term, self.backends)
        self._events.put(("results", term, results, errors))

    def _show_results(self, term: str, results, errors) -> None:
        shown = results[:MAX_ROWS]
        for r in shown:
            item = self._tree.insert(
                "", "end",
                values=(r.source_label, r.ident, r.version or "", "✓" if r.installed else ""),
            )
            self._rows[item] = r
        installed = sum(1 for r in shown if r.installed)
        msg = f"{len(shown)} result{'s' if len(shown) != 1 else ''} · {installed} installed"
        if len(results) > MAX_ROWS:
            msg += f" (showing first {MAX_ROWS})"
        if not results:
            msg = f"No results for “{term}”."
        if errors:
            msg += "  —  " + "; ".join(errors)
        self._set_status(msg)
        self._busy = False
        self._search_btn.state(["!disabled"])

    # -- selection & actions ----------------------------------------------

    def _on_select(self) -> None:
        result = self._current()
        if not result:
            return
        backend = self._by_id.get(result.source)
        cmd = " ".join(backend.install_cmd(result)) if backend else ""
        lines = [f"{result.source_label}: {result.ident}"]
        if result.summary:
            lines.append(result.summary)
        if cmd:
            lines.append(f"$ {cmd}")
        if backend and backend.caveat:
            lines.append(f"Note: {backend.caveat}")
        self._detail.configure(text="\n".join(lines))
        if backend:
            self._action.configure(
                text="Remove" if result.installed else "Install", state="normal"
            )
        else:
            self._action.configure(state="disabled")

    def _on_action(self) -> None:
        if self._busy:
            return
        result = self._current()
        backend = self._by_id.get(result.source) if result else None
        if not result or not backend:
            return
        cmd = backend.remove_cmd(result) if result.installed else backend.install_cmd(result)
        self._busy = True
        self._action.state(["disabled"])
        self._search_btn.state(["disabled"])
        self._console_clear()
        self._console_write(f"$ {' '.join(cmd)}\n\n")
        threading.Thread(
            target=self._action_worker, args=(cmd, backend.env()), daemon=True
        ).start()

    def _action_worker(self, cmd, env) -> None:
        try:
            proc = be.popen(cmd, env=env or None)
            assert proc.stdout is not None
            for line in proc.stdout:
                self._events.put(("output", line))
            code = proc.wait()
        except Exception as exc:  # noqa: BLE001
            self._events.put(("output", f"\nerror: {exc}\n"))
            code = 1
        self._events.put(("done", code))

    # -- background -> UI marshalling --------------------------------------

    def _pump(self) -> None:
        """Tkinter is single-threaded; drain worker messages on the UI thread."""
        try:
            while True:
                event = self._events.get_nowait()
                kind = event[0]
                if kind == "results":
                    self._show_results(event[1], event[2], event[3])
                elif kind == "output":
                    self._console_write(event[1])
                elif kind == "done":
                    self._console_write(
                        f"\n{'✓ done' if event[1] == 0 else f'exited with {event[1]}'}\n"
                    )
                    self._busy = False
                    self._search_btn.state(["!disabled"])
                    self._action.state(["!disabled"])
        except queue.Empty:
            pass
        self.root.after(80, self._pump)

    # -- small helpers -----------------------------------------------------

    def _current(self) -> be.Result | None:
        sel = self._tree.selection()
        return self._rows.get(sel[0]) if sel else None

    def _set_status(self, text: str) -> None:
        self._status.configure(text=text)

    def _clear_detail(self) -> None:
        self._detail.configure(text="")
        self._action.configure(state="disabled")
        self._console_clear()

    def _console_clear(self) -> None:
        self._console.configure(state="normal")
        self._console.delete("1.0", "end")
        self._console.configure(state="disabled")

    def _console_write(self, text: str) -> None:
        self._console.configure(state="normal")
        self._console.insert("end", text)
        self._console.see("end")
        self._console.configure(state="disabled")


def main(terms: list[str] | None = None) -> int:
    root = tk.Tk()
    PkgfindApp(root, initial=" ".join(terms or []).strip())
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
