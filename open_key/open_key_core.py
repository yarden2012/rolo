"""Shared logic for open_key's CLI and GUI front ends: config storage,
key-combo capture/validation, discovering installed apps, and launching
programs."""
import json
import os
import re
import shlex
import subprocess
from pathlib import Path

from pynput import keyboard

_FIELD_CODE_RE = re.compile(r"%[fFuUdDnNickvm]")

CONFIG_PATH = Path(__file__).parent / "config.json"

MODIFIER_TOKENS = {
    keyboard.Key.ctrl_l: "<ctrl>",
    keyboard.Key.ctrl_r: "<ctrl>",
    keyboard.Key.ctrl: "<ctrl>",
    keyboard.Key.alt_l: "<alt>",
    keyboard.Key.alt_r: "<alt>",
    keyboard.Key.alt: "<alt>",
    keyboard.Key.alt_gr: "<alt_gr>",
    keyboard.Key.shift_l: "<shift>",
    keyboard.Key.shift_r: "<shift>",
    keyboard.Key.shift: "<shift>",
    keyboard.Key.cmd_l: "<cmd>",
    keyboard.Key.cmd_r: "<cmd>",
    keyboard.Key.cmd: "<cmd>",
}

# Special keys that are safe to bind on their own (won't interfere with typing).
SAFE_STANDALONE = {
    "f13", "f14", "f15", "f16", "f17", "f18", "f19", "f20", "f21", "f22", "f23", "f24",
    "pause", "scroll_lock", "insert", "menu",
    "media_play_pause", "media_next", "media_previous", "media_volume_up",
    "media_volume_down", "media_volume_mute",
}


def load_config():
    """Returns {combo: [command, ...]}. Older configs stored a single
    command string per combo; those are transparently upgraded to a
    one-item list."""
    if not CONFIG_PATH.exists():
        return {}
    cfg = json.loads(CONFIG_PATH.read_text())
    return {combo: (commands if isinstance(commands, list) else [commands])
            for combo, commands in cfg.items()}


def save_config(cfg):
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))


def key_token(key):
    """Convert a pynput key event into a hotkey-string token."""
    if key in MODIFIER_TOKENS:
        return MODIFIER_TOKENS[key]
    if isinstance(key, keyboard.KeyCode):
        if key.char:
            return key.char.lower()
        return f"<{key.vk}>"
    # Named special key, e.g. Key.f13, Key.esc
    return f"<{key.name}>"


def is_combo_safe(combo):
    """A combo is safe to bind globally if it has a modifier key, or is a
    single dedicated key (function key F13+, media key, etc)."""
    has_modifier = any(t in MODIFIER_TOKENS.values() for t in combo)
    is_safe_standalone = len(combo) == 1 and next(iter(combo)).strip("<>") in SAFE_STANDALONE
    return has_modifier or is_safe_standalone


def format_combo(combo):
    return "+".join(sorted(combo))


def capture_combo_blocking():
    """Block until the user presses and releases a key combo. Returns the
    raw set of hotkey tokens, e.g. {'<ctrl>', '<alt>', 'o'}."""
    pressed = set()
    result = {}

    def on_press(key):
        pressed.add(key_token(key))
        result["combo"] = set(pressed)

    def on_release(key):
        pressed.discard(key_token(key))
        if not pressed and result.get("combo"):
            return False

    with keyboard.Listener(on_press=on_press, on_release=on_release) as listener:
        listener.join()

    return result["combo"]


def _application_dirs():
    """XDG data dirs to scan for .desktop files, in precedence order
    (user's own dir first, so it can override system entries)."""
    dirs = []
    data_home = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    dirs.append(Path(data_home) / "applications")
    data_dirs = os.environ.get("XDG_DATA_DIRS", "/usr/local/share:/usr/share")
    for d in data_dirs.split(":"):
        if d:
            dirs.append(Path(d) / "applications")
    # Explicit fallbacks for Flatpak app exports, in case the session's
    # XDG_DATA_DIRS doesn't already include them.
    dirs.append(Path(data_home) / "flatpak" / "exports" / "share" / "applications")
    dirs.append(Path("/var/lib/flatpak/exports/share/applications"))
    seen = []
    for d in dirs:
        if d not in seen:
            seen.append(d)
    return seen


def _parse_desktop_file(path):
    """Read the [Desktop Entry] section of a .desktop file into a dict."""
    try:
        text = path.read_text(errors="ignore")
    except OSError:
        return {}
    entry = {}
    in_main_section = False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("["):
            in_main_section = line == "[Desktop Entry]"
            continue
        if not in_main_section or not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if key not in entry:  # first occurrence wins (skip localized Name[xx] dupes)
            entry[key] = value.strip()
    return entry


def list_installed_apps():
    """Discover installed applications from .desktop files, the same way
    the desktop's app menu does. Returns a list of
    {id, name, exec, icon} dicts sorted by name."""
    apps = {}
    for app_dir in _application_dirs():
        if not app_dir.is_dir():
            continue
        for desktop_file in app_dir.rglob("*.desktop"):
            app_id = desktop_file.stem
            if app_id in apps:
                continue  # earlier (higher-precedence) dir already provided this app
            entry = _parse_desktop_file(desktop_file)
            if entry.get("Type", "Application") != "Application":
                continue
            if entry.get("NoDisplay", "false").lower() == "true":
                continue
            if entry.get("Hidden", "false").lower() == "true":
                continue
            raw_exec = entry.get("Exec")
            name = entry.get("Name")
            if not raw_exec or not name:
                continue
            command = _FIELD_CODE_RE.sub("", raw_exec).strip()
            apps[app_id] = {
                "id": app_id,
                "name": name,
                "exec": command,
                "icon": entry.get("Icon", ""),
            }
    return sorted(apps.values(), key=lambda a: a["name"].casefold())


# Curated quick-settings actions for KDE Plasma / PipeWire / NetworkManager
# systems. Some rely on tools that may not be installed (e.g. brightnessctl,
# playerctl) -- if a command's binary is missing, launch() just no-ops
# rather than crashing anything.
QUICK_SETTINGS = [
    {"name": "Volume Up", "exec": "pactl set-sink-volume @DEFAULT_SINK@ +5%", "icon": "audio-volume-up"},
    {"name": "Volume Down", "exec": "pactl set-sink-volume @DEFAULT_SINK@ -5%", "icon": "audio-volume-down"},
    {"name": "Mute/Unmute Speaker", "exec": "pactl set-sink-mute @DEFAULT_SINK@ toggle", "icon": "audio-volume-muted"},
    {"name": "Mute/Unmute Microphone", "exec": "pactl set-source-mute @DEFAULT_SOURCE@ toggle", "icon": "microphone-sensitivity-muted"},
    {"name": "Brightness Up", "exec": "brightnessctl set +10%", "icon": "display-brightness"},
    {"name": "Brightness Down", "exec": "brightnessctl set 10%-", "icon": "display-brightness"},
    {"name": "Dark Mode", "exec": "plasma-apply-colorscheme BreezeDark", "icon": "weather-clear-night"},
    {"name": "Light Mode", "exec": "plasma-apply-colorscheme BreezeClassic", "icon": "weather-clear"},
    {"name": "Power Profile: Performance", "exec": "powerprofilesctl set performance", "icon": "power-profile-performance"},
    {"name": "Power Profile: Balanced", "exec": "powerprofilesctl set balanced", "icon": "power-profile-balanced"},
    {"name": "Power Profile: Power Saver", "exec": "powerprofilesctl set power-saver", "icon": "power-profile-power-saver"},
    {"name": "Wi-Fi On", "exec": "nmcli radio wifi on", "icon": "network-wireless"},
    {"name": "Wi-Fi Off", "exec": "nmcli radio wifi off", "icon": "network-wireless-disconnected"},
    {"name": "Bluetooth On", "exec": "rfkill unblock bluetooth", "icon": "preferences-system-bluetooth"},
    {"name": "Bluetooth Off", "exec": "rfkill block bluetooth", "icon": "preferences-system-bluetooth"},
    {"name": "Airplane Mode On", "exec": "rfkill block all", "icon": "airplane-mode"},
    {"name": "Airplane Mode Off", "exec": "rfkill unblock all", "icon": "network-wireless"},
    {"name": "Lock Screen", "exec": "loginctl lock-session", "icon": "system-lock-screen"},
    {"name": "Suspend", "exec": "systemctl suspend", "icon": "system-suspend"},
    {"name": "Take Screenshot", "exec": "spectacle -b -n -f", "icon": "applets-screenshooter"},
    {"name": "Play/Pause Media", "exec": "playerctl play-pause", "icon": "media-playback-start"},
    {"name": "Next Track", "exec": "playerctl next", "icon": "media-skip-forward"},
    {"name": "Previous Track", "exec": "playerctl previous", "icon": "media-skip-backward"},
]


def list_quick_settings():
    """Curated list of common system-setting actions, same shape as
    list_installed_apps() entries ({name, exec, icon})."""
    return QUICK_SETTINGS


def launch(program):
    try:
        subprocess.Popen(shlex.split(program))
    except FileNotFoundError:
        # fall back to shell so things like "code ." or aliases still work
        subprocess.Popen(program, shell=True)


def launch_all(commands):
    for command in commands:
        launch(command)
