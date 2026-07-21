#!/usr/bin/env python3
"""open_key: bind keyboard shortcuts to launch apps/programs.

Commands:
    add       Interactively record a key combo and bind it to a command
    list      Show current bindings
    remove    Delete a binding
    listen    Run the background listener (watches for combos, launches apps)
"""
import argparse
import os
import sys
from pathlib import Path

# If invoked with the system Python (e.g. `python3 open_key.py`) instead of
# via open_key.sh, re-exec ourselves under this project's venv, which is
# where pynput/PySide6 are actually installed. (The venv's python is a
# symlink back to the system interpreter, so we can't detect it by
# resolving paths -- check sys.prefix instead, which venvs override.)
_VENV_DIR = Path(__file__).parent / ".venv"
_VENV_PY = _VENV_DIR / "bin" / "python"
if _VENV_PY.exists() and Path(sys.prefix) != _VENV_DIR.resolve():
    os.execv(str(_VENV_PY), [str(_VENV_PY), str(Path(__file__).resolve())] + sys.argv[1:])

from pynput import keyboard

import open_key_core as core


def capture_combo():
    """CLI wrapper around core.capture_combo_blocking with retry-on-unsafe
    prompting and printed instructions."""
    print("Press the key combination you want to bind (hold them together, then release)...")
    combo = core.capture_combo_blocking()

    if not core.is_combo_safe(combo):
        print(
            "\nWarning: that combo has no modifier key (Ctrl/Alt/Shift/Cmd) and isn't a "
            "dedicated function key. Binding it globally would interfere with normal typing."
        )
        retry = input("Try again with a safer combo? [Y/n] ").strip().lower()
        if retry in ("", "y", "yes"):
            return capture_combo()

    return core.format_combo(combo)


def capture_commands():
    """Prompt for one or more commands to run, in order, when the shortcut
    fires. Blank line finishes the list."""
    print("Command(s) to run, one per line (e.g. firefox, or a setting-change "
          "command). Blank line when done:")
    commands = []
    while True:
        program = input(f"  action {len(commands) + 1}: ").strip()
        if not program:
            break
        commands.append(program)
    return commands


def cmd_add(args):
    cfg = core.load_config()
    combo = capture_combo()
    if combo in cfg:
        print(f"'{combo}' is already bound to: {'; '.join(cfg[combo])}")
        overwrite = input("Overwrite? [y/N] ").strip().lower()
        if overwrite not in ("y", "yes"):
            print("Cancelled.")
            return
    commands = capture_commands()
    if not commands:
        print("No commands entered, cancelled.")
        return
    cfg[combo] = commands
    core.save_config(cfg)
    print(f"Bound {combo}  ->  {'; '.join(commands)}")


def cmd_list(args):
    cfg = core.load_config()
    if not cfg:
        print("No bindings yet. Use 'open_key.py add' to create one.")
        return
    print(f"{'Key combo':<30} Command(s)")
    print("-" * 60)
    for combo, commands in cfg.items():
        print(f"{combo:<30} {'; '.join(commands)}")


def cmd_remove(args):
    cfg = core.load_config()
    if not cfg:
        print("No bindings to remove.")
        return
    items = list(cfg.items())
    for i, (combo, commands) in enumerate(items, 1):
        print(f"{i}. {combo}  ->  {'; '.join(commands)}")
    choice = input("Number to remove (or blank to cancel): ").strip()
    if not choice:
        print("Cancelled.")
        return
    try:
        idx = int(choice) - 1
        combo = items[idx][0]
    except (ValueError, IndexError):
        print("Invalid choice.")
        return
    del cfg[combo]
    core.save_config(cfg)
    print(f"Removed {combo}")


def cmd_listen(args):
    cfg = core.load_config()
    if not cfg:
        print("No bindings configured. Use 'open_key.py add' first.")
        return

    def make_handler(commands):
        return lambda: core.launch_all(commands)

    hotkeys = {combo: make_handler(commands) for combo, commands in cfg.items()}

    print("Listening for shortcuts (Ctrl+C to stop):")
    for combo, commands in cfg.items():
        print(f"  {combo:<25} -> {'; '.join(commands)}")

    with keyboard.GlobalHotKeys(hotkeys) as listener:
        try:
            listener.join()
        except KeyboardInterrupt:
            print("\nStopped.")


def cmd_gui(args):
    import open_key_gui
    open_key_gui.main()


def main():
    parser = argparse.ArgumentParser(description="Bind keyboard shortcuts to launch apps.")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("add", help="record a new key combo and bind it to a command").set_defaults(func=cmd_add)
    sub.add_parser("list", help="show current bindings").set_defaults(func=cmd_list)
    sub.add_parser("remove", help="delete a binding").set_defaults(func=cmd_remove)
    sub.add_parser("listen", help="run the background listener").set_defaults(func=cmd_listen)
    sub.add_parser("gui", help="launch the graphical app").set_defaults(func=cmd_gui)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
