# Plays the user of the "Classic: pairing with a code" scenario: reads the passkey the classic ESP32 bench prints on its
# serial port, waits (longer than the connection timeout of KnBle), then types it in the pairing dialog of the phone.
#
#   python3 type_passkey.py /dev/cu.usbserial-XXXX <adb serial> [delay s] [adb path]     (COM5 on Windows)
#
# Needs pyserial (installed with PlatformIO: pip install --user platformio).
import re
import subprocess
import sys
import time

import serial

port, device = sys.argv[1], sys.argv[2]
delay = float(sys.argv[3]) if len(sys.argv) > 3 else 40
adb = sys.argv[4] if len(sys.argv) > 4 else 'adb'

s = serial.Serial(port, 115200, timeout=0.2)
s.dtr = False
s.rts = False
line = b''
deadline = time.time() + 300
while time.time() < deadline:
    line += s.read(256)
    m = re.search(rb'passkey (\d{6})', line)
    if m:
        passkey = m.group(1).decode()
        print(f'passkey {passkey}, typed in {delay:.0f} s', flush=True)
        time.sleep(delay)
        subprocess.run([adb, '-s', device, 'shell', 'input', 'text', passkey], check=False)
        subprocess.run([adb, '-s', device, 'shell', 'input', 'keyevent', '66'], check=False)
        print('typed', flush=True)
        break
    line = line[-64:]
else:
    print('no passkey seen', flush=True)
