"""Verify the settings picker and stored locale on an English Android emulator."""

import re
import subprocess
import time
import xml.etree.ElementTree as ET


def shell(*args):
    return subprocess.check_output(["adb", "shell", *args], text=True, encoding="utf-8").strip()


def find(label):
    for attempt in range(5):
        shell("uiautomator", "dump", "/sdcard/uniear-ui.xml")
        tree = ET.fromstring(shell("cat", "/sdcard/uniear-ui.xml"))
        for node in tree.iter("node"):
            if node.get("text", "") == label or node.get("text", "").startswith(label + " · "):
                return tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        time.sleep(0.5)
    raise AssertionError(f"Label {label!r} was not visible")


def tap(label):
    left, top, right, bottom = find(label)
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))


tap("Settings")
tap("Language")
tap("Español")
find("Ajustes")
shell("am", "force-stop", "dev.uniear.app")
shell("am", "start", "-W", "-n", "dev.uniear.app/uniear.android.MainActivity")
tap("Ajustes")
tap("Idioma")
tap("Seguir al sistema")
find("Settings")
print("Language picker switched to Spanish, persisted after restart, and restored the system language.")
