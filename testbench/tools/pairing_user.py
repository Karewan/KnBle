# Plays the user of the pairing scenarios of the sample on one phone: when the sample logs a pairing request left to
# the dialog of the system (Android 17+ with an app targeting API 37 types no code itself), types the code in it, or
# confirms a numeric comparison or a consent. Runs until stopped.
#
#   python3 -I pairing_user.py <adb serial> [--pin 1234] [--passkey 123456] [--adb adb]
#
# The codes are the ones of the benches: PIN 1234 (classic ESP32, legacy mode), passkey 123456 (ESP32-C3, passkey mode).
import re
import subprocess
import sys
import time

args = sys.argv[1:]
if not args:
    sys.exit(__doc__ or 'usage: pairing_user.py <adb serial> [--pin 1234] [--passkey 123456] [--adb adb]')
device = args[0]
opts = dict(zip(args[1::2], args[2::2]))
pin = opts.get('--pin', '1234')
passkey = opts.get('--passkey', '123456')
adb = opts.get('--adb', 'adb')


def shell(*cmd):
    return subprocess.run([adb, '-s', device, 'shell', *cmd], capture_output=True, text=True, check=False).stdout


def tap_positive_button():
    """Taps the positive button of the pairing dialog, found in the UI dump: android:id/button1 (AlertDialog), or
    positive_button (the dialog of Settings on Android 17)"""
    for _ in range(10):
        dump = shell('uiautomator', 'dump', '/sdcard/pairing_ui.xml') and shell('cat', '/sdcard/pairing_ui.xml')
        for node in re.findall(r'<node[^>]*>', dump):
            if not re.search(r'resource-id="[^"]*(?::id/button1|:id/positive_button)"', node):
                continue
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        time.sleep(0.5)
    return False


# Lines of PairingUi: "Pairing request of <MAC>: variant <n>, passkey|PIN, key <k>, answered in the dialog of the system"
request = re.compile(r'Pairing request of \S+: variant (\d+), (passkey|PIN), key (-?\d+), answered in the dialog of the system')
log = subprocess.Popen([adb, '-s', device, 'logcat', '-T', '1', '-s', 'KnBleSample:I'], stdout=subprocess.PIPE, text=True)
print(f'pairing user on {device}: PIN {pin}, passkey {passkey}', flush=True)
for line in log.stdout:
    m = request.search(line)
    if not m:
        continue
    variant, kind = int(m.group(1)), m.group(2)
    # The dialog of the system shows a moment later
    time.sleep(2)
    if variant in (0, 7):
        code = passkey if kind == 'passkey' else pin
        shell('input', 'text', code)
        time.sleep(0.5)
        done = tap_positive_button()
        print(f'variant {variant}: typed {code}, confirmed {done}', flush=True)
    elif variant in (2, 3):
        print(f'variant {variant}: confirmed {tap_positive_button()}', flush=True)
    else:
        print(f'variant {variant}: left to the user', flush=True)
