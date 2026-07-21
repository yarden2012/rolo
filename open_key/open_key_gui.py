#!/usr/bin/env python3
"""open_key GUI: a small window for managing keyboard-shortcut -> program
bindings, plus a tray icon so it can keep listening in the background."""
import os
import sys
from pathlib import Path

_VENV_DIR = Path(__file__).parent / ".venv"
_VENV_PY = _VENV_DIR / "bin" / "python"
if _VENV_PY.exists() and Path(sys.prefix) != _VENV_DIR.resolve():
    os.execv(str(_VENV_PY), [str(_VENV_PY), str(Path(__file__).resolve())] + sys.argv[1:])

from pynput import keyboard
from PySide6.QtCore import Qt, QThread, Signal, QSize
from PySide6.QtGui import QAction, QIcon
from PySide6.QtWidgets import (
    QApplication, QDialog, QDialogButtonBox, QFileDialog, QHBoxLayout,
    QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow, QMenu,
    QMessageBox, QPushButton, QStatusBar, QSystemTrayIcon, QTableWidget,
    QTableWidgetItem, QTabWidget, QVBoxLayout, QWidget,
)

import open_key_core as core

AUTOSTART_DIR = Path.home() / ".config" / "autostart"
AUTOSTART_FILE = AUTOSTART_DIR / "open-key.desktop"
APP_ICON_NAME = "input-keyboard"


def app_icon():
    icon = QIcon.fromTheme(APP_ICON_NAME)
    if icon.isNull():
        icon = QApplication.style().standardIcon(QApplication.style().SP_ComputerIcon)
    return icon


def icon_for(name):
    if not name:
        return QIcon()
    if os.path.isabs(name) and os.path.exists(name):
        return QIcon(name)
    icon = QIcon.fromTheme(name)
    return icon


class CaptureThread(QThread):
    captured = Signal(set)

    def run(self):
        combo = core.capture_combo_blocking()
        self.captured.emit(combo)


class AddBindingDialog(QDialog):
    def __init__(self, parent=None, existing_combo=None, existing_commands=None):
        super().__init__(parent)
        self.setWindowTitle("Add Shortcut" if not existing_combo else "Edit Shortcut")
        self.resize(440, 620)
        self.combo = existing_combo
        self.combo_str = core.format_combo(existing_combo) if existing_combo else ""

        layout = QVBoxLayout(self)

        self.status_label = QLabel(
            self.combo_str if self.combo_str else "Click 'Record' and press your key combination."
        )
        self.status_label.setAlignment(Qt.AlignCenter)
        self.status_label.setStyleSheet("font-size: 14pt; padding: 12px;")
        layout.addWidget(self.status_label)

        self.record_button = QPushButton("Record Shortcut")
        self.record_button.clicked.connect(self.start_capture)
        layout.addWidget(self.record_button)

        layout.addWidget(QLabel(
            "Actions (all run, in order, when the shortcut is pressed — "
            "e.g. open an app and change a setting):"
        ))
        self.actions_list = QListWidget()
        layout.addWidget(self.actions_list, stretch=1)
        for command in (existing_commands or []):
            self.actions_list.addItem(QListWidgetItem(command))

        actions_button_row = QHBoxLayout()
        remove_action_button = QPushButton("Remove Selected Action")
        remove_action_button.clicked.connect(self.remove_action)
        actions_button_row.addStretch()
        actions_button_row.addWidget(remove_action_button)
        layout.addLayout(actions_button_row)

        layout.addWidget(QLabel("Choose an app or a setting to change:"))
        tabs = QTabWidget()
        tabs.addTab(self._build_picker(core.list_installed_apps(), "Search installed apps…"), "Apps")
        tabs.addTab(self._build_picker(core.list_quick_settings(), "Search settings…"), "Settings")
        layout.addWidget(tabs, stretch=1)

        cmd_row = QHBoxLayout()
        self.command_edit = QLineEdit()
        self.command_edit.setPlaceholderText("e.g. firefox, or a setting-change command")
        browse_button = QPushButton("Browse…")
        browse_button.clicked.connect(self.browse)
        cmd_row.addWidget(self.command_edit)
        cmd_row.addWidget(browse_button)
        layout.addWidget(QLabel("...or enter a custom command:"))
        layout.addLayout(cmd_row)

        add_action_button = QPushButton("+ Add Action")
        add_action_button.clicked.connect(self.add_action)
        layout.addWidget(add_action_button)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.try_accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self._thread = None

    def _build_picker(self, items, search_placeholder):
        """Build a search box + list widget for picking an item (app or
        quick setting) whose exec string gets copied into command_edit."""
        list_widget = QListWidget()
        list_widget.setIconSize(QSize(24, 24))

        def populate(entries):
            list_widget.clear()
            for entry in entries:
                list_item = QListWidgetItem(icon_for(entry["icon"]), entry["name"])
                list_item.setData(Qt.UserRole, entry["exec"])
                list_widget.addItem(list_item)

        def on_search(text):
            text = text.strip().lower()
            populate(items if not text else [e for e in items if text in e["name"].lower()])

        search_edit = QLineEdit()
        search_edit.setPlaceholderText(search_placeholder)
        search_edit.textChanged.connect(on_search)
        list_widget.itemClicked.connect(lambda item: self.command_edit.setText(item.data(Qt.UserRole)))
        populate(items)

        container = QWidget()
        v = QVBoxLayout(container)
        v.setContentsMargins(0, 0, 0, 0)
        v.addWidget(search_edit)
        v.addWidget(list_widget)
        return container

    def add_action(self):
        command = self.command_edit.text().strip()
        if not command:
            QMessageBox.warning(self, "No command", "Choose an app or enter a command first.")
            return
        self.actions_list.addItem(QListWidgetItem(command))
        self.command_edit.clear()

    def remove_action(self):
        row = self.actions_list.currentRow()
        if row < 0:
            QMessageBox.information(self, "No selection", "Select an action to remove first.")
            return
        self.actions_list.takeItem(row)

    def start_capture(self):
        self.status_label.setText("Press your key combination now…")
        self.record_button.setEnabled(False)
        self._thread = CaptureThread()
        self._thread.captured.connect(self.on_captured)
        self._thread.start()

    def on_captured(self, combo):
        self.record_button.setEnabled(True)
        if not core.is_combo_safe(combo):
            QMessageBox.warning(
                self,
                "Unsafe shortcut",
                "That combo has no modifier key (Ctrl/Alt/Shift/Cmd) and isn't a dedicated "
                "function key. Binding it globally would interfere with normal typing.\n\n"
                "Please record a different combination.",
            )
            self.status_label.setText("Click 'Record' and press your key combination.")
            return
        self.combo = combo
        self.combo_str = core.format_combo(combo)
        self.status_label.setText(self.combo_str)

    def browse(self):
        path, _ = QFileDialog.getOpenFileName(self, "Select Program", "/usr/bin")
        if path:
            self.command_edit.setText(path)

    def try_accept(self):
        if not self.combo:
            QMessageBox.warning(self, "No shortcut", "Record a key combination first.")
            return
        if self.actions_list.count() == 0:
            QMessageBox.warning(self, "No actions", "Add at least one action (app or command).")
            return
        self.accept()

    def result_data(self):
        commands = [self.actions_list.item(i).text() for i in range(self.actions_list.count())]
        return self.combo_str, commands


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Open Key")
        self.resize(520, 360)
        self.setWindowIcon(app_icon())

        self.listener = None  # active pynput.keyboard.GlobalHotKeys or None

        central = QWidget()
        layout = QVBoxLayout(central)

        self.table = QTableWidget(0, 2)
        self.table.setHorizontalHeaderLabels(["Shortcut", "Command"])
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        layout.addWidget(self.table)

        button_row = QHBoxLayout()
        add_button = QPushButton("Add")
        add_button.clicked.connect(self.add_binding)
        remove_button = QPushButton("Remove")
        remove_button.clicked.connect(self.remove_binding)
        self.listen_button = QPushButton("Start Listening")
        self.listen_button.clicked.connect(self.toggle_listening)
        button_row.addWidget(add_button)
        button_row.addWidget(remove_button)
        button_row.addStretch()
        button_row.addWidget(self.listen_button)
        layout.addLayout(button_row)

        self.setCentralWidget(central)
        self.setStatusBar(QStatusBar())
        self.status_bar_message("Not listening.")

        self._build_menu()
        self._build_tray()
        self.reload_table()

    # ---- menu / tray -----------------------------------------------
    def _build_menu(self):
        settings_menu = self.menuBar().addMenu("Settings")
        self.autostart_action = QAction("Launch at login", self, checkable=True)
        self.autostart_action.setChecked(AUTOSTART_FILE.exists())
        self.autostart_action.toggled.connect(self.set_autostart)
        settings_menu.addAction(self.autostart_action)

    def _build_tray(self):
        self.tray = QSystemTrayIcon(app_icon(), self)
        self.tray.setToolTip("Open Key")
        menu = QMenu()
        show_action = menu.addAction("Show")
        show_action.triggered.connect(self.showNormal)
        self.tray_toggle_action = menu.addAction("Start Listening")
        self.tray_toggle_action.triggered.connect(self.toggle_listening)
        menu.addSeparator()
        quit_action = menu.addAction("Quit")
        quit_action.triggered.connect(QApplication.instance().quit)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(self._on_tray_activated)
        self.tray.show()

    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.Trigger:
            self.showNormal()
            self.raise_()
            self.activateWindow()

    def closeEvent(self, event):
        event.ignore()
        self.hide()
        self.tray.showMessage("Open Key", "Still running in the background.", app_icon(), 2000)

    # ---- table / bindings -------------------------------------------
    def reload_table(self):
        cfg = core.load_config()
        self.table.setRowCount(0)
        for combo, commands in cfg.items():
            row = self.table.rowCount()
            self.table.insertRow(row)
            self.table.setItem(row, 0, QTableWidgetItem(combo))
            self.table.setItem(row, 1, QTableWidgetItem("; ".join(commands)))

    def add_binding(self):
        dialog = AddBindingDialog(self)
        if dialog.exec() == QDialog.Accepted:
            combo_str, commands = dialog.result_data()
            cfg = core.load_config()
            if combo_str in cfg:
                resp = QMessageBox.question(
                    self, "Overwrite?",
                    f"'{combo_str}' is already bound to '{'; '.join(cfg[combo_str])}'. Overwrite?",
                )
                if resp != QMessageBox.Yes:
                    return
            cfg[combo_str] = commands
            core.save_config(cfg)
            self.reload_table()
            self.restart_listener_if_active()

    def remove_binding(self):
        row = self.table.currentRow()
        if row < 0:
            QMessageBox.information(self, "No selection", "Select a shortcut to remove first.")
            return
        combo_str = self.table.item(row, 0).text()
        cfg = core.load_config()
        cfg.pop(combo_str, None)
        core.save_config(cfg)
        self.reload_table()
        self.restart_listener_if_active()

    # ---- listening ----------------------------------------------------
    def toggle_listening(self):
        if self.listener is None:
            self.start_listening()
        else:
            self.stop_listening()

    def start_listening(self):
        cfg = core.load_config()
        if not cfg:
            QMessageBox.information(self, "No shortcuts", "Add a shortcut first.")
            return

        def make_handler(commands):
            return lambda: core.launch_all(commands)

        hotkeys = {combo: make_handler(commands) for combo, commands in cfg.items()}
        self.listener = keyboard.GlobalHotKeys(hotkeys)
        self.listener.start()
        self.listen_button.setText("Stop Listening")
        self.tray_toggle_action.setText("Stop Listening")
        self.status_bar_message(f"Listening for {len(cfg)} shortcut(s).")

    def stop_listening(self):
        if self.listener is not None:
            self.listener.stop()
            self.listener = None
        self.listen_button.setText("Start Listening")
        self.tray_toggle_action.setText("Start Listening")
        self.status_bar_message("Not listening.")

    def restart_listener_if_active(self):
        if self.listener is not None:
            self.stop_listening()
            self.start_listening()

    def status_bar_message(self, text):
        self.statusBar().showMessage(text)

    # ---- autostart ------------------------------------------------
    def set_autostart(self, enabled):
        if enabled:
            AUTOSTART_DIR.mkdir(parents=True, exist_ok=True)
            gui_script = Path(__file__).parent / "open_key_gui.sh"
            AUTOSTART_FILE.write_text(
                "[Desktop Entry]\n"
                "Type=Application\n"
                "Name=Open Key\n"
                f"Exec={gui_script}\n"
                "Icon=input-keyboard\n"
                "X-GNOME-Autostart-enabled=true\n"
            )
        else:
            AUTOSTART_FILE.unlink(missing_ok=True)


def main():
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
