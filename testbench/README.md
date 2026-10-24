# KnBle test bench

The `sample` app drives benches to benchmark and stress KnBle on real phones, and to run its pairings as a real app does.
Nothing in them belongs to a product: the Silicon Labs AppLoader OTA and TI OAD services use the public UUIDs of their
SDKs.

| Bench | Folder | Role | Found by the sample |
|---|---|---|---|
| ESP32-C3 (Seeed XIAO, Bluetooth 5) | `esp32-c3` | BLE: the whole GATT table and protocol below, pairing modes, advertising modes, DFU reboot | its name `KnBleBench` |
| ESP32 (D1 Mini, BR/EDR) | `esp32-classic` | Bluetooth classic: SPP server, the classic protocol below, every pairing mode of a classic device | its name `KnBleSpp` (discovery) |
| A second phone (Android 8+) | `benchhost` | BLE (same table and protocol, what a phone can do) and a classic SPP server: the bench of a phone the ESP32 cannot reach | its bench service UUID (`--es bench pc`); classic: `--es classic <its MAC>` |

## Firmwares

PlatformIO (`pip install --user platformio`), Arduino core 3 and NimBLE-Arduino 2 for the C3, the SPP API of the IDF
for the classic ESP32:

```bash
cd testbench/esp32-c3 && pio run -t upload && pio device monitor        # BLE bench
cd testbench/esp32-classic && pio run -t upload && pio device monitor   # classic bench
```

The C3 needs its antenna on the U.FL connector (without it, keep the phones right next to it). It advertises as
`KnBleBench` (`KnBleBench-DFU` in DFU mode), manufacturer data `0xFFFF` + counter + advertising mode, and never
advertises while connected. The serial port prints the pairings (passkey, numeric comparison, bonds deleted).

## Phone bench (Android)

`benchhost` turns a phone into the bench with the plain Android API (no KnBle in it: the neutral reference):
`./gradlew :benchhost:installRelease`, then open the app (screen kept on). It serves the same GATT table and protocol as
the C3 except what a phone cannot do (a DFU reboot with the same MAC, the pairing IO capabilities, connection parameters
asked by the device), and an insecure classic SPP server with the classic protocol. Phone to phone, the plain classic
socket itself is slow (20 to 35 KB/s): for the functions, not the throughput.

## Running the sample

Each scenario is a button, run one at a time; the `RESULT` lines go to Logcat (tag `KnBleSample`) and to
`/sdcard/Android/data/ovh.karewan.knble.sample/files/results.txt`. From adb, a scenario starts by the beginning of its
label (else a part of it), quoted for the shell of the phone:

```bash
adb shell "am start -n ovh.karewan.knble.sample/.MainActivity --es run 'Run all'"
adb shell "am start -n ovh.karewan.knble.sample/.MainActivity --es run 'Classic: run all'"
adb shell "am start -n ovh.karewan.knble.sample/.MainActivity --es bench pc --es run 'Write 256 KB'"   # the phone bench
```

Extras: `--es bench pc` (the phone bench instead of the C3), `--es classic <MAC>` (a classic device instead of the
discovery of `KnBleSpp`), `--ez classicsecure true`, `--es classicuuid <UUID>`, `--es classicpin 1234` (the PIN given in
advance to the classic `createBond`), `--es pin 123456` (the answer of the dialog of the app, `cancel` refuses, `none`
shows it again), `--ez debug true` (logs of KnBle).

## Bluetooth classic bench

The sample talks to a Serial Port Profile server with a text protocol (commands ending with `\n`, answers as lines):

| Command | Answer | Effect |
|---|---|---|
| `PING n` | `PONG n` | Latency |
| `INFO` | `INFO <name> v<version> pair=<mode>` | |
| `STREAM n` | `n` bytes, then `END n ms failures` | Byte `i` is `(i * 31 + (i >> 8)) & 0xFF`, sent as fast as the link goes |
| `SINK n` | `SINK READY`, then `SINK n <crc32 hex> ms` | The next `n` bytes are counted, CRC-32 of zlib |
| `RATE bytesPerS frameSize` | `$KNB,<seq>,<payload>*<xor>\r\n` frames, `STOP seq ms` | The radio receiver: frames at a steady rate until `STOP`, answers in between |
| `STOP` | | Ends `RATE` |
| `DISC ms` | `DISC ms` | The server closes the connection after `ms` (link loss) |
| `PAIR m` | `PAIR m` | ESP32 only: its bonds removed, reboot in pairing mode `m` (below) |
| `UNBOND` | `UNBOND n` | ESP32 only: the bench forgets its bonds now, the phone keeps its keys (a lost bond) |
| `STATS` | `STATS rx= tx= cong= failures=` | |

Pairing modes of the ESP32 (kept across its reboots, back to `0` on power off): `0` open (a secure socket pairs with
just works, an insecure one connects without pairing), `1` forced, passkey shown on its serial port for the user to type
on the phone (`tools/type_passkey.py` plays the user), `2` forced, numeric comparison (the bench accepts), `3` forced,
legacy PIN `1234` (Secure Simple Pairing off: Android asks the PIN, the app may give it itself).

The KnBle classic server of the sample ("Classic server: KnBle bench server") serves the same protocol (without `PAIR`
and `UNBOND`): a phone becomes the classic bench of another one.

## Pairing scenarios

The pairing requests go to `PairingUi`, what a real app does: its own dialog for the code when `canAnswer()`, else a
hint while the dialog of the system asks the user (shown over the app by KnBle).

| Scenario | Bench | What it checks |
|---|---|---|
| Pairing: passkey of the bench typed by the app | C3 mode 1 | `onPairingRequest` with a passkey, `setPin("123456")`, no dialog of the system (Android 17 + API 37: the dialog asks it) |
| Pairing: numeric comparison | C3 mode 2 | the dialog of the system over the app, the number told to the app (confirm on the phone) |
| Pairing: lost bond | C3 (`0x0C`), phone bench | Android 16+: `onBondLost`, `BOND_LOST`, no auto-reconnection; before: what the stack does |
| Pairing: encrypted characteristic, createBond, remove the bond | any | the pairing started by the stack, `createBond`, `removeBond` |
| Classic pairing: legacy PIN typed by the app, given in advance | ESP32 mode 3 | `setPin("1234")` from the app, `ClassicOptions.setPin` without request |
| Classic pairing: numeric comparison, pairing with a code | ESP32 modes 2, 1 | the dialog of the system, the time of the pairing out of the connection timeout |
| Classic pairing: lost bond | ESP32 `UNBOND` | Android 16+: `onBondLost`, `BOND_LOST`, no auto-reconnection |

"Run all" buttons run what needs no user: "Pairing: run all BLE", "Classic pairing: run all". On Android 17 with an app
targeting API 37, Android types no code for the app: `tools/pairing_user.py <adb serial>` plays the user (types `1234`
or `123456` in the dialog of the system, confirms a numeric comparison or a consent), and `tools/type_passkey.py` types
the random passkey the classic ESP32 prints in its passkey mode. Such an app cannot remove its bonds either: forget the
benches in the Bluetooth settings of the phone between the pairing runs.

## Measurement tools

`tools/`: interleaved A/B of sample builds and analysis of the HCI snoop log, to tell whether the phone (app, KnBle,
Android stack) or the radio limits a transfer. Python 3.10+.

**A/B run.** Build each version of the sample as `sample-<version>.apk` in a folder (`./gradlew :sample:assembleRelease`
for the current one; the 3.0.5 reference is a sample built on KnBle 3.0.5, kept outside the repository), then:

```bash
DEVICE=<adb serial> NAME=pixel_c3 APK_DIR=/path/to/apks VERSION_LIST="3.0.5 4.0" BENCH=pc \
SCENARIOS="Write 256 KB without response;Write 64 KB with response;OTA Silicon Labs 256 KB" \
ONLY_40="KnBle OTA: Silicon Labs 256 KB" testbench/tools/ab.sh 6
python -I testbench/tools/summary.py ab_pixel_c3.txt
```

The order of the builds turns every round: the radio drifts during a session (the same build can lose 30 % between
the first and the last round), so a build that always runs first or last wins or loses for nothing. Six rounds at
least before concluding. Wireless adb sometimes drops during a run: the script then waits for a scenario that already
ended on the phone (its results stay in `files/results.txt` of the sample). After stopping a run, check that no
`ab.sh` is left (`ps -ef | grep ab.sh`): it would go on reinstalling builds.

**HCI snoop log.** Developer options → "Enable Bluetooth HCI snoop log" → Enabled (full, not filtered), then Bluetooth
off and on. After the run, `adb bugreport report.zip` holds the log: `FS/data/misc/bluetooth/logs/btsnoop_hci.log` and
`.log.last` on a Pixel (UTC timestamps, two files of about 9 MB: take the bugreport after 9 blocks or so),
`FS/data/log/bt/btsnoop_hci.log` on a Samsung (local time). Logging every packet costs the phone a little: compare
builds with the log on for all of them.

```bash
python -I testbench/tools/snoop.py btsnoop_hci.log.last btsnoop_hci.log --tz=2 > report.txt
python -I testbench/tools/join.py ab_pixel_c3.txt report.txt
```

`snoop.py` prints the link setup of each connection (create and update commands, PHY, data length, MTU exchanges,
L2CAP channels, scan and advertising state) and, for each burst of Write Commands, what decides its duration:

- *controller full*: share of the time the controller holds as many packets as it takes. Near 100 %: the radio side
  limits (link parameters, interference, the bench), the phone always has the next packet ready.
- *host refill*: time from a completed packet to the next packet given to the controller. This is the cost of the
  phone side (app + KnBle + stack, or only the stack when Android buffers the writes, as on a Pixel).
- *never below*: fewest packets left in the controller. 0 means the radio waited for the phone.
- *radio stalls*: no packet completed for 20 ms or more while some wait (retransmissions, Wi-Fi coexistence, a scan
  of another app, the bench not acknowledging).

`join.py` labels the bursts with the builds of the A/B run and gives the medians per scenario and build: two builds
with the same link setup and the same host refill differ only by the radio.

## GATT table (normal mode)

Base UUID `4b42xxxx-8d28-4316-88b3-6e0111311ac6`.

| Service / characteristic | UUID | Properties | Role |
|---|---|---|---|
| Bench service | `4b420001-…` | | |
| Control | `4b420002-…` | write, notify | Commands below, answers notified as `[cmd, status, payload]` |
| Sink | `4b420003-…` | write, write no response | Data sink: byte count, write count, CRC-32 (zlib) |
| Stream | `4b420004-…` | notify | Sequenced notifications: `[seq u32 LE][pattern]` |
| Indicate | `4b420005-…` | indicate only | Sends `IND-OK` on command `0x08`. A CCCD write of `0x0001` is refused (ATT 0x06) |
| Result | `4b420006-…` | read | Last OTA: `[protocol u8][state u8][bytes u32][crc u32][dropped u32][duration ms u32]` |
| Encrypted | `4b420007-…` | read (encryption) | `SECURE-OK`, triggers pairing |
| Notify | `4b420008-…` | notify | Plain notify characteristic, never written by the bench |
| Long | `4b420009-…` | read, write | 512 bytes, byte `i` = `(i * 31 + (i >> 8)) & 0xFF` at boot: read blob and prepared writes while the MTU is 23 |
| Dup service A | `4b420010-…` | | |
| Dup characteristic | `4b420011-…` | read | `A` |
| Dup service B | `4b420020-…` | | |
| Dup characteristic | `4b420011-…` | read | `B` (same UUID as in service A) |
| Silicon Labs OTA | `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` | | AppLoader emulation |
| OTA control | `f7bf3564-fb6d-4e53-88a4-5e37e0326063` | write | `0x00` start, `0x03` end |
| OTA data | `984227f3-34fc-4045-a5d0-2c581f81a153` | write, write no response | Image, through the sink pipeline |
| TI OAD | `f000ffc0-0451-4000-b000-000000000000` | | Enhanced OAD emulation |
| OAD identify | `f000ffc1-0451-4000-b000-000000000000` | write no response, notify | 22 bytes, answer `[status]` |
| OAD block | `f000ffc2-0451-4000-b000-000000000000` | write no response, notify | `[block u32 LE][data]` |
| OAD control | `f000ffc5-0451-4000-b000-000000000000` | write no response, notify | `0x01` block size, `0x03` start, `0x04` enable image; block requests `[0x12, status, block u32 LE]` |

DFU mode (after command `0x07 0x01`): name `KnBleBench-DFU`, only the Silicon Labs OTA service and a DFU info service
`4b420030-…` holding the Result characteristic `4b420031-…`. The end of an OTA (`0x03`) reboots the bench in normal mode
1.5 s later (time to read the result), and so does 2 minutes without connection.

## Control commands

All integers little endian. Each command is answered on Control with `[cmd, status]` (+ payload), status `0` = done.

| Cmd | Arguments | Answer payload | Effect |
|---|---|---|---|
| `0x01` | | | Reset the sink statistics |
| `0x02` | | `bytes u32, writes u32, dropped u32, crc u32, first-to-last ms u32, longest pause ms u32, pauses of 50 ms or more u32` | Sink statistics |
| `0x03` | `count u32, size u16, interval ms u16` | `sent u32, retries u32` (when done) | Stream `count` notifications of `size` bytes |
| `0x04` | `delay µs u32` | | Processing delay of each sink write (real link-layer flow control) |
| `0x05` | `mode u8, depth u16, drain µs u32` | | `1` = no flow control: writes queued in `depth` slots drained every `drain µs`, overflow dropped (buggy bootloader) |
| `0x06` | `mode u8, param u32` | | Advertising after the disconnection: `0` normal, `1` name only in the scan response, `2` non connectable, `3` interval `param` ms, `4` stopped for `param` ms. Modes 1 to 3 go back to normal after 20 s |
| `0x07` | `mode u8` | | Reboot: `0` normal, `1` DFU mode (same MAC, other name and services) |
| `0x08` | | | Send an indication on Indicate |
| `0x09` | `delay ms u32` | | Disconnect after the delay (link loss seen from the phone) |
| `0x0A` | | `version u8, boot mode u8, mtu u16, pairing mode u8, bonds u8` | Bench information |
| `0x0B` | `mode u8` | | Pairing mode of the next pairings: `0` just works, `1` passkey `123456` shown by the bench (the phone types it), `2` numeric comparison (the bench accepts). Bonding and LE Secure Connections always. ESP32-C3 only |
| `0x0C` | | `bonds u8` | The bench forgets its bonds: a bonded phone keeps keys the bench no longer knows (a lost bond). The phone bench removes its bond with the central |
| `0x0D` | `min u16, max u16, latency u16, timeout u16` | | The bench asks the phone for these connection parameters (interval in 1.25 ms, timeout in 10 ms). ESP32-C3 only |

The phone bench answers status `1` to what a phone cannot do: `0x07` (a reboot with the same MAC), `0x0B`, `0x0D`.
