# open_key

Bind a key (or key combo) to instantly launch an app or program. Has both
a GUI and a terminal tool.

## Setup / rebuilding the venv

```sh
./install.sh
```

Creates `.venv` and installs dependencies. Rebuild the same way any time
`.venv` gets deleted or corrupted (e.g. it was built with a different
Python version than your system's).

Note: don't `pip install -r requirements.txt` directly — pynput's plain
install pulls in `evdev`, which needs to compile a C extension against
headers that atomic distros like Bazzite don't ship. `install.sh` skips
it (it's only needed for a Wayland/headless input-synthesis backend we
don't use under X11).

## GUI

```sh
./open_key_gui.sh
```

A window with a table of your shortcuts, **Add**/**Remove** buttons, and
a **Start/Stop Listening** toggle. Closing the window minimizes it to a
tray icon instead of quitting, so shortcuts keep working in the
background. Settings menu has "Launch at login" to autostart it.

Adding a shortcut: click **Record Shortcut** and press your key combo,
then either pick an app from the searchable list (built from your
installed `.desktop` files) or type/browse a custom command.

To add it to your KDE app menu:

```sh
cp open-key.desktop ~/.local/share/applications/
```

## CLI

```sh
./open_key.sh add       # record a new shortcut
./open_key.sh list       # see current bindings
./open_key.sh remove     # delete a binding
./open_key.sh listen     # start listening (runs until Ctrl+C)
```

Recording a shortcut asks you to press the key combo you want (e.g. hold
`Ctrl+Alt+O`), then release it, then asks what command to run, e.g.:

- `firefox`
- `code`
- `/usr/bin/thunderbird`
- `flatpak run org.gnome.Calculator`

Single plain letter/number keys are rejected unless combined with a
modifier (Ctrl/Alt/Shift/Cmd) or unless it's a dedicated key like F13+,
Pause, or a media key — otherwise the shortcut would break normal typing
system-wide.

### Run automatically on login (optional, CLI-only alternative)

The GUI's "Launch at login" setting is the easiest way. There's also a
systemd user service for the CLI listener:

```sh
mkdir -p ~/.config/systemd/user
cp openkey.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now openkey.service
```

Check status/logs with `systemctl --user status openkey` and
`journalctl --user -u openkey -f`.

## Notes

- Requires an X11 session (this machine's current session is X11, which
  is what `pynput`'s global listener needs). It will not detect key
  presses under a pure Wayland session.
- Bindings are stored in `config.json` in this folder.
- Both `open_key.py` and `open_key_gui.py` auto re-exec themselves into
  `.venv`'s Python if run with a different interpreter (e.g. plain
  `python3 open_key.py`), so the `.sh` wrappers aren't strictly required
  — but using them is simplest.
