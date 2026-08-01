"""pkgfind GUI — one search box over every app source on the system."""

from __future__ import annotations

import concurrent.futures
import shlex
import subprocess
import threading

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gdk, Gio, GLib, GObject, Gtk, Pango  # noqa: E402

import backends as be  # noqa: E402

APP_ID = "dev.rolo.pkgfind"
MAX_ROWS = 300

CSS = """
.source-badge {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: 7px;
  letter-spacing: 0.03em;
  /* Keeps every badge the same width so the package names line up. */
  min-width: 62px;
}
.badge-flatpak { background: alpha(@accent_bg_color, 0.18); color: @accent_color; }
.badge-rpm     { background: alpha(@warning_bg_color, 0.20); color: @warning_color; }
.badge-brew    { background: alpha(@success_bg_color, 0.20); color: @success_color; }
.badge-box     { background: alpha(@purple_3, 0.20); color: @purple_2; }
.installed-tag {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: 7px;
  background: alpha(@success_bg_color, 0.20);
  color: @success_color;
}
.console {
  font-family: monospace;
  font-size: 0.86rem;
  padding: 10px;
}
.filter-bar { padding: 8px 12px; }
"""

BADGE_CLASS = {"flatpak": "badge-flatpak", "rpm": "badge-rpm", "brew": "badge-brew"}


def badge_class(source: str) -> str:
    return BADGE_CLASS.get(source, "badge-box")


def copy_to_clipboard(widget: Gtk.Widget, text: str) -> None:
    provider = Gdk.ContentProvider.new_for_value(GObject.Value(str, text))
    widget.get_clipboard().set_content(provider)


class CommandDialog(Adw.Dialog):
    """Runs one install/remove command and streams its output live."""

    def __init__(self, title: str, cmd: list[str], env: dict[str, str], caveat: str = ""):
        super().__init__()
        self.set_title(title)
        self.set_content_width(720)
        self.set_content_height(500)
        self._cmd = cmd
        self._env = env
        self._proc: subprocess.Popen | None = None
        self.succeeded = False
        self.on_finished = None

        self._buffer = Gtk.TextBuffer()
        view = Gtk.TextView(buffer=self._buffer, editable=False, monospace=True)
        view.set_cursor_visible(False)
        view.add_css_class("console")
        self._view = view

        scroller = Gtk.ScrolledWindow(vexpand=True)
        scroller.set_child(view)

        self._status = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.END)
        self._status.add_css_class("dim-label")
        self._status.set_text(" ".join(shlex.quote(c) for c in cmd))

        self._button = Gtk.Button(label="Cancel")
        self._button.add_css_class("destructive-action")
        self._button.connect("clicked", self._on_button)

        bottom = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        bottom.set_margin_top(10)
        bottom.set_margin_bottom(10)
        bottom.set_margin_start(14)
        bottom.set_margin_end(14)
        self._status.set_hexpand(True)
        bottom.append(self._status)
        bottom.append(self._button)

        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(Adw.HeaderBar())
        toolbar.set_content(scroller)
        toolbar.add_bottom_bar(bottom)
        self.set_child(toolbar)

        if caveat:
            self._append(f"note: {caveat}\n\n")
        self._append(f"$ {' '.join(shlex.quote(c) for c in cmd)}\n\n")

    def start(self) -> None:
        threading.Thread(target=self._worker, daemon=True).start()

    def _worker(self) -> None:
        try:
            self._proc = be.popen(self._cmd, env=self._env)
        except OSError as exc:
            GLib.idle_add(self._append, f"\nfailed to start: {exc}\n")
            GLib.idle_add(self._finish, 1)
            return
        assert self._proc.stdout is not None
        for line in self._proc.stdout:
            GLib.idle_add(self._append, line)
        code = self._proc.wait()
        GLib.idle_add(self._finish, code)

    def _append(self, text: str) -> bool:
        self._buffer.insert(self._buffer.get_end_iter(), text)
        mark = self._buffer.create_mark(None, self._buffer.get_end_iter(), False)
        self._view.scroll_to_mark(mark, 0.0, False, 0.0, 0.0)
        self._buffer.delete_mark(mark)
        return False

    def _finish(self, code: int) -> bool:
        self.succeeded = code == 0
        self._status.set_text("Done." if self.succeeded else f"Exited with status {code}.")
        self._button.set_label("Close")
        self._button.remove_css_class("destructive-action")
        self._button.add_css_class("suggested-action")
        if self.succeeded:
            self._append("\n✓ Finished successfully.\n")
        if self.on_finished:
            self.on_finished(self.succeeded)
        return False

    def _on_button(self, _button: Gtk.Button) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
            self._append("\n✗ Cancelled.\n")
        self.close()


class DetailDialog(Adw.Dialog):
    """Everything known about one result, plus the exact command to install it."""

    def __init__(self, window: "PkgfindWindow", result: be.Result):
        super().__init__()
        self.set_title(result.name)
        self.set_content_width(560)

        group = Adw.PreferencesGroup()
        rows = [
            ("Identifier", result.ident),
            ("Source", result.source_label),
            ("Version", result.version),
            ("From", result.origin),
            ("Status", "Installed" if result.installed else "Not installed"),
        ]
        for label, value in rows:
            if not value:
                continue
            row = Adw.ActionRow(title=label, subtitle=value)
            row.set_subtitle_selectable(True)
            group.add(row)

        backend = window.backend_for(result)
        cmd = backend.remove_cmd(result) if result.installed else backend.install_cmd(result)
        cmd_text = " ".join(shlex.quote(c) for c in cmd)

        cmd_row = Adw.ActionRow(title="Command", subtitle=cmd_text)
        cmd_row.set_subtitle_selectable(True)
        copy = Gtk.Button(icon_name="edit-copy-symbolic", valign=Gtk.Align.CENTER)
        copy.add_css_class("flat")
        copy.set_tooltip_text("Copy command")
        copy.connect("clicked", lambda _b: (copy_to_clipboard(self, cmd_text),
                                            window.toast("Command copied")))
        cmd_row.add_suffix(copy)
        group.add(cmd_row)

        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        body.set_margin_top(14)
        body.set_margin_bottom(18)
        body.set_margin_start(18)
        body.set_margin_end(18)

        if result.summary:
            summary = Gtk.Label(label=result.summary, xalign=0, wrap=True, selectable=True)
            body.append(summary)
        body.append(group)

        if backend.caveat:
            caveat = Gtk.Label(label=backend.caveat, xalign=0, wrap=True)
            caveat.add_css_class("dim-label")
            caveat.add_css_class("caption")
            body.append(caveat)

        action = Gtk.Button(label="Remove" if result.installed else "Install")
        action.add_css_class("destructive-action" if result.installed else "suggested-action")
        action.add_css_class("pill")
        action.set_halign(Gtk.Align.CENTER)
        action.connect("clicked", lambda _b: (self.close(), window.run_action(result)))
        body.append(action)

        scroller = Gtk.ScrolledWindow(propagate_natural_height=True)
        scroller.set_child(body)

        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(Adw.HeaderBar())
        toolbar.set_content(scroller)
        self.set_child(toolbar)


class ResultRow(Adw.ActionRow):
    """One result. Subclassed so the row can carry its Result back to us."""

    __gtype_name__ = "PkgfindResultRow"

    def __init__(self, result: be.Result, window: "PkgfindWindow"):
        super().__init__(title=GLib.markup_escape_text(result.name))
        self.result = result
        if result.summary:
            self.set_subtitle(GLib.markup_escape_text(result.summary))
        self.set_activatable(True)

        badge = Gtk.Label(label=result.source_label, valign=Gtk.Align.CENTER)
        badge.add_css_class("source-badge")
        badge.add_css_class(badge_class(result.source.split(":")[0]))
        self.add_prefix(badge)

        if result.version:
            version = Gtk.Label(label=result.version, valign=Gtk.Align.CENTER)
            version.add_css_class("dim-label")
            version.add_css_class("caption")
            self.add_suffix(version)

        if result.installed:
            tag = Gtk.Label(label="Installed", valign=Gtk.Align.CENTER)
            tag.add_css_class("installed-tag")
            self.add_suffix(tag)

        button = Gtk.Button(
            label="Remove" if result.installed else "Install", valign=Gtk.Align.CENTER
        )
        button.add_css_class("flat" if result.installed else "suggested-action")
        button.connect("clicked", lambda _b: window.run_action(result))
        self.add_suffix(button)


class PkgfindWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application):
        super().__init__(application=app, title="pkgfind")
        self.set_default_size(940, 700)

        self._generation = 0
        self._results: list[be.Result] = []
        self._active_sources: set[str] = set()
        self._pool = concurrent.futures.ThreadPoolExecutor(max_workers=8)
        self._search_containers = False
        self._backends: dict[str, be.Backend] = {}
        self._reload_backends()

        # -- header ---------------------------------------------------------
        self._entry = Gtk.SearchEntry(placeholder_text="Search every app source…")
        self._entry.set_size_request(400, -1)
        self._entry.connect("activate", lambda _e: self.start_search())
        self._entry.connect("stop-search", lambda _e: self._entry.set_text(""))

        header = Adw.HeaderBar()
        header.set_title_widget(self._entry)

        self._spinner = Adw.Spinner()
        self._spinner.set_visible(False)
        header.pack_start(self._spinner)

        menu = Gio.Menu()
        menu.append("Search distrobox containers", "win.toggle-containers")
        menu.append("About pkgfind", "win.about")
        menu_button = Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu)
        header.pack_end(menu_button)

        refresh = Gtk.Button(icon_name="view-refresh-symbolic")
        refresh.set_tooltip_text("Search again")
        refresh.connect("clicked", lambda _b: self.start_search(force=True))
        header.pack_end(refresh)

        # -- filter bar -----------------------------------------------------
        self._filters = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._filters.add_css_class("filter-bar")
        self._filter_buttons: dict[str, Gtk.ToggleButton] = {}

        self._count = Gtk.Label(xalign=1)
        self._count.add_css_class("dim-label")
        self._count.add_css_class("caption")
        self._count.set_hexpand(True)
        self._filters.append(self._count)
        self._filters.set_visible(False)

        # -- results --------------------------------------------------------
        self._list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self._list.add_css_class("boxed-list")
        self._list.set_valign(Gtk.Align.START)
        self._list.connect("row-activated", self._on_row_activated)

        list_holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        list_holder.set_margin_top(6)
        list_holder.set_margin_bottom(18)
        list_holder.set_margin_start(18)
        list_holder.set_margin_end(18)
        list_holder.append(self._list)

        self._scroller = Gtk.ScrolledWindow(vexpand=True)
        self._scroller.set_child(list_holder)

        self._placeholder = Adw.StatusPage(
            icon_name="system-search-symbolic",
            title="Search every app source at once",
            description=self._sources_line(),
        )

        self._stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE)
        self._stack.add_named(self._placeholder, "placeholder")
        self._stack.add_named(self._scroller, "results")
        self._stack.set_visible_child_name("placeholder")

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        content.append(self._filters)
        content.append(self._stack)

        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(header)
        toolbar.set_content(content)

        self._toasts = Adw.ToastOverlay()
        self._toasts.set_child(toolbar)
        self.set_content(self._toasts)

        self._install_actions()
        self._entry.grab_focus()

    # -- setup helpers ------------------------------------------------------

    def _reload_backends(self) -> None:
        found = be.default_backends(include_containers=self._search_containers)
        self._backends = {b.id: b for b in found}

    def _sources_line(self) -> str:
        labels = [b.label for b in self._backends.values()]
        if not labels:
            return "No package managers were found on this system."
        return "Searching " + ", ".join(labels) + "."

    def _install_actions(self) -> None:
        containers = Gio.SimpleAction.new_stateful(
            "toggle-containers", None, GLib.Variant.new_boolean(False)
        )
        containers.connect("activate", self._on_toggle_containers)
        self.add_action(containers)

        about = Gio.SimpleAction.new("about", None)
        about.connect("activate", lambda *_: self._show_about())
        self.add_action(about)

        focus = Gio.SimpleAction.new("focus-search", None)
        focus.connect("activate", lambda *_: self._entry.grab_focus())
        self.add_action(focus)

    def _on_toggle_containers(self, action: Gio.SimpleAction, _param) -> None:
        self._search_containers = not action.get_state().get_boolean()
        action.set_state(GLib.Variant.new_boolean(self._search_containers))
        self._reload_backends()
        self._placeholder.set_description(self._sources_line())
        if self._search_containers:
            self.toast("Containers included — searches will be slower")
        if self._entry.get_text().strip():
            self.start_search(force=True)

    def _show_about(self) -> None:
        about = Adw.AboutDialog(
            application_name="pkgfind",
            application_icon="system-search-symbolic",
            developer_name="Built for Bazzite",
            version="1.0",
            comments="Search Flatpak, Fedora RPM, Homebrew and distrobox "
            "containers from one box, then install straight from the results.",
        )
        about.present(self)

    # -- public helpers -----------------------------------------------------

    def toast(self, text: str) -> None:
        self._toasts.add_toast(Adw.Toast(title=text))

    def backend_for(self, result: be.Result) -> be.Backend:
        return self._backends[result.source]

    # -- searching ----------------------------------------------------------

    def start_search(self, force: bool = False) -> None:
        term = self._entry.get_text().strip()
        if len(term) < 2:
            if term:
                self.toast("Type at least two characters")
            return
        if not self._backends:
            self.toast("No package managers available")
            return

        self._generation += 1
        generation = self._generation
        self._results = []
        self._pending = set(self._backends)
        self._spinner.set_visible(True)
        self._list.remove_all()
        self._filters.set_visible(False)
        self._placeholder.set_icon_name("system-search-symbolic")
        self._placeholder.set_title(f"Searching for “{term}”…")
        self._placeholder.set_description(self._sources_line())
        self._stack.set_visible_child_name("placeholder")

        for backend in self._backends.values():
            self._pool.submit(self._search_one, generation, backend, term)

    def _search_one(self, generation: int, backend: be.Backend, term: str) -> None:
        try:
            results = backend.search(term)
            error = None
        except Exception as exc:  # a broken backend must not kill the search
            results, error = [], str(exc)
        GLib.idle_add(self._collect, generation, backend, results, error)

    def _collect(
        self, generation: int, backend: be.Backend, results: list, error: str | None
    ) -> bool:
        if generation != self._generation:
            return False  # a newer search already started
        if error:
            self.toast(f"{backend.label}: {error}")
        self._results.extend(results)
        self._pending.discard(backend.id)
        if not self._pending:
            self._spinner.set_visible(False)
            self._render()
        return False

    # -- rendering ----------------------------------------------------------

    def _render(self) -> None:
        term = self._entry.get_text().strip()
        if not self._results:
            self._placeholder.set_icon_name("edit-find-symbolic")
            self._placeholder.set_title(f"Nothing found for “{term}”")
            self._placeholder.set_description(
                "Try a shorter or more general word — sources match on "
                "package names and summaries."
            )
            self._stack.set_visible_child_name("placeholder")
            return

        self._results.sort(key=lambda r: (r.rank, r.source_label.lower(), r.name.lower()))
        self._rebuild_filters()
        self._fill_list()
        self._filters.set_visible(True)
        self._stack.set_visible_child_name("results")

    def _rebuild_filters(self) -> None:
        for button in self._filter_buttons.values():
            self._filters.remove(button)
        self._filter_buttons.clear()

        counts: dict[str, int] = {}
        labels: dict[str, str] = {}
        for result in self._results:
            counts[result.source] = counts.get(result.source, 0) + 1
            labels[result.source] = result.source_label

        keep = {s for s in self._active_sources if s in counts}
        self._active_sources = keep or set(counts)

        for index, source in enumerate(sorted(counts, key=lambda s: -counts[s])):
            button = Gtk.ToggleButton(label=f"{labels[source]}  {counts[source]}")
            button.set_active(source in self._active_sources)
            button.add_css_class("pill")
            button.connect("toggled", self._on_filter_toggled, source)
            self._filters.insert_child_after(
                button, None if index == 0 else list(self._filter_buttons.values())[-1]
            )
            self._filter_buttons[source] = button

    def _on_filter_toggled(self, button: Gtk.ToggleButton, source: str) -> None:
        if button.get_active():
            self._active_sources.add(source)
        else:
            self._active_sources.discard(source)
        if not self._active_sources:  # never leave the user with an empty list
            self._active_sources.add(source)
            button.set_active(True)
            return
        self._fill_list()

    def _fill_list(self) -> None:
        self._list.remove_all()
        shown = [r for r in self._results if r.source in self._active_sources]
        for result in shown[:MAX_ROWS]:
            self._list.append(ResultRow(result, self))

        total = len(shown)
        text = f"{total} result{'s' if total != 1 else ''}"
        if total > MAX_ROWS:
            text = f"showing {MAX_ROWS} of {total} results"
        self._count.set_text(text)

    def _on_row_activated(self, _list: Gtk.ListBox, row: Gtk.ListBoxRow) -> None:
        result = getattr(row, "result", None)
        if result is not None:
            DetailDialog(self, result).present(self)

    # -- install / remove ---------------------------------------------------

    def run_action(self, result: be.Result) -> None:
        backend = self.backend_for(result)
        removing = result.installed
        cmd = backend.remove_cmd(result) if removing else backend.install_cmd(result)
        verb = "Remove" if removing else "Install"

        alert = Adw.AlertDialog(
            heading=f"{verb} {result.name}?",
            body=f"{' '.join(shlex.quote(c) for c in cmd)}"
            + (f"\n\n{backend.caveat}" if backend.caveat else ""),
        )
        alert.add_response("cancel", "Cancel")
        alert.add_response("go", verb)
        alert.set_response_appearance(
            "go",
            Adw.ResponseAppearance.DESTRUCTIVE if removing else Adw.ResponseAppearance.SUGGESTED,
        )
        alert.set_default_response("go")
        alert.set_close_response("cancel")
        alert.connect("response", self._on_confirm, result, backend, cmd, verb)
        alert.present(self)

    def _on_confirm(
        self,
        _alert: Adw.AlertDialog,
        response: str,
        result: be.Result,
        backend: be.Backend,
        cmd: list[str],
        verb: str,
    ) -> None:
        if response != "go":
            return
        dialog = CommandDialog(
            f"{verb} {result.name}", cmd, backend.env(), backend.caveat
        )
        dialog.on_finished = lambda ok: self._after_action(ok, result, verb)
        dialog.present(self)
        dialog.start()

    def _after_action(self, ok: bool, result: be.Result, verb: str) -> None:
        if not ok:
            self.toast(f"{verb} failed for {result.name}")
            return
        self.toast(f"{result.name} — {verb.lower()} complete")
        result.installed = not result.installed
        self._fill_list()


class PkgfindApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self._initial_term = ""
        self.connect("command-line", self._on_command_line)

    def _on_command_line(self, _app, command_line: Gio.ApplicationCommandLine) -> int:
        args = command_line.get_arguments()[1:]
        terms = [a for a in args if not a.startswith("-")]
        self._initial_term = " ".join(terms)
        self.activate()
        return 0

    def do_activate(self) -> None:
        window = self.get_active_window()
        if not window:
            provider = Gtk.CssProvider()
            provider.load_from_string(CSS)
            Gtk.StyleContext.add_provider_for_display(
                Gdk.Display.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
            )
            self.set_accels_for_action("win.focus-search", ["<Control>f", "<Control>l"])
            self.set_accels_for_action("app.quit", ["<Control>q", "<Control>w"])

            quit_action = Gio.SimpleAction.new("quit", None)
            quit_action.connect("activate", lambda *_: self.quit())
            self.add_action(quit_action)

            window = PkgfindWindow(self)
            if self._initial_term:
                window._entry.set_text(self._initial_term)
                GLib.idle_add(window.start_search)
        window.present()


def main(argv: list[str]) -> int:
    return PkgfindApp().run(argv)
