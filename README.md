# KnBle

[![](https://jitpack.io/v/Karewan/KnBle.svg)](https://jitpack.io/#Karewan/KnBle)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Bluetooth Low Energy for Android 7 to 17 (API 24 to 37), central and peripheral, in one static API.

- **Central**: scan (foreground and background), connection with auto-reconnection, GATT operations, bonds, companion
  device association, Silicon Labs and TI OTA.
- **Peripheral**: advertising and GATT server.
- **Bluetooth classic**: discovery, serial devices over RFCOMM (Serial Port Profile), and a classic server for the
  devices that connect to the phone.
- **Non-blocking**: no method blocks or makes a binder call on the calling thread (except `getAdapterInfo`, a few binder
  calls). Callbacks run on the main thread (or a Looper of your choice).
- **Robust**: one thread and one operation queue per device, a deadline on every operation, retries for the known stack
  failures (133, 62, 147, busy stack, empty service table, lost requests), automatic pairing replay.

Upgrading from 3.x: [MIGRATION.md](MIGRATION.md). Changes: [CHANGELOG.md](CHANGELOG.md).

## Installation

```groovy
// settings.gradle
dependencyResolutionManagement {
	repositories {
		maven { url 'https://jitpack.io' }
	}
}

// build.gradle of the app (Java 21)
dependencies {
	implementation 'com.github.Karewan:KnBle:4.0.0'
}
```

Permissions (declare what you use, request the runtime ones):

| Permission | Needed for |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` | Everything, Android 11 and below |
| `ACCESS_FINE_LOCATION` with `maxSdkVersion="30"` | Scan and classic discovery, Android 6 to 11 |
| `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"` | Scan and classic discovery, Android 12+ |
| `BLUETOOTH_CONNECT` | Connection (LE and classic), GATT server, bonds, Android 12+ |
| `BLUETOOTH_ADVERTISE` | Advertising, Android 12+ |

An app that derives a location from scan results on Android 12+ keeps `ACCESS_FINE_LOCATION` without `maxSdkVersion`,
removes `neverForLocation` and calls `ScanSettings.Builder.setNeverForLocation(false)`.

## Quick start

```java
// Once, in Application.onCreate(): false when the phone has no Bluetooth LE
KnBle.init(context);

KnBle.setScanFilter(new ScanFilters.Builder().addServiceUuid(SERVICE).build());
KnBle.startScan(device -> {
	KnBle.stopScan();
	KnBle.connect(device, services -> {
		KnBle.enableNotify(device, SERVICE, NOTIFY_CHR, data -> handle(data));
		KnBle.write(device, SERVICE, WRITE_CHR, command, false, () -> {});
	});
});
```

## Conventions

- **Callbacks**: one mandatory method per interface (lambdas work), the others have empty defaults. Every callback is
  called exactly once per operation (success or failure), on the main thread or `KnBleSettings.setCallbackLooper`.
- **Errors**: an `int`, negative for a `BleError` (`NOT_CONNECTED`, `TIMEOUT`, `BUSY`, `PERMISSION_DENIED`...), positive
  for a GATT/HCI status of the device or the stack (5 insufficient authentication, 8 supervision timeout, 133...).
  `BleError.name(error)` gives a readable name.
- **UUIDs**: every GATT method accepts `String` UUIDs, `UUID`s, or the `BluetoothGattService`/`BluetoothGattCharacteristic`
  objects.
- **Queue**: the operations of a device run one at a time, in call order. An operation called before `onConnectSuccess`
  waits for it.

## Setup and Bluetooth state

```java
KnBle.init(context, new KnBleSettings.Builder()
		.setConnectTimeout(30000)       // ms, all attempts of a connection
		.setConnectAttempts(3)          // attempts on 133, 62, 147...
		.setOperationTimeout(10000)     // ms per answer of the device
		.setDiscoveryDelay(300)         // ms between connection and service discovery (1600 when bonded)
		.setRefreshOnDisconnect(true)   // service cache cleared at each disconnection
		.setCallbackLooper(looper)      // default: main thread
		.setLogger((priority, message, error) -> {})
		.setDebug(BuildConfig.DEBUG)    // logs, tag KnBle
		.setPairingDialogInForeground(true) // the pairing dialog of the system over the app, not a notification
		.build());
```

| Method | |
|---|---|
| `isInit()` | `init` succeeded |
| `isBluetoothEnabled()` | Cached state, no binder call |
| `addBluetoothStateListener(l)`, `removeBluetoothStateListener(l)` | `onBluetoothStateChanged(enabled)`, once per change |
| `enableBluetooth(enable)` | Android 12 and below |
| `getBleDeviceFromMac(mac)` | A `BleDevice` from a MAC address, `null` when invalid |
| `getContext()`, `getBluetoothManager()`, `getBluetoothAdapter()` | System objects |
| `getAdapterInfo(context)` | What the Bluetooth of the phone supports, as the "Device information" of nRF Connect (works before `init`) |

`BleAdapterInfo` gives PHY LE 2M and Coded, extended and periodic advertising, the peripheral role (multiple
advertisement), offloaded filtering and scan batching, the longest advertising data, LE Audio (unicast, broadcast source
and assistant, Android 13+), High Data Throughput PHY (Android 17+), Channel Sounding (Android 16+), Bluetooth classic,
the companion device manager, the phone name and model, and `canAnswerPairing()` (false on Android 17+ for an app
targeting API 37+). `toString()` gives a report for the logs. Android only gives the LE features while Bluetooth is on:
KnBle keeps the last ones it read for the times it is off, and `isKnown()` is false when Bluetooth has not been on since
KnBle started. It makes a few binder calls, so call it off the main thread if you call it often.

## Scan

```java
KnBle.setScanFilter(new ScanFilters.Builder()
		.addDeviceNameStartsWith("SENSOR")
		.addManufacturerId(0x1234)
		.setMinRssi(-90)
		.build());
KnBle.setScanSettings(new ScanSettings.Builder().setScanTimeout(30000).build());

KnBle.startScan(new BleScanCallback() {
	@Override public void onScanResult(@NonNull BleDevice device) {}      // first report, or first after a loss
	@Override public void onDeviceUpdated(@NonNull BleDevice device) {}   // new RSSI or advertising data
	@Override public void onDeviceLost(@NonNull BleDevice device) {}      // silent for 5 of its intervals (10 s to 2 min; 30 s when heard once; longer on phones filtering duplicates or with batched results)
	@Override public void onScanFailed(int error) {}                      // BleScanCallback.BT_DISABLED, PERMISSION_DENIED...
	@Override public void onScanFinished(@NonNull List<BleDevice> devices) {}
});
KnBle.stopScan();
```

**Filters** (any filter matches): `addDeviceName`, `addDeviceNameStartsWith`, `addDeviceNameEndsWith`, `addMacAddress`,
`addMacAddressStartsWith`, `addManufacturerId`, `addBeaconUUID`, `addServiceUuid`, `addServiceData(uuid, prefix)`.
`setMinRssi` applies to all. Exact names, MACs, manufacturer IDs, beacon and service UUIDs and service data are given to
the controller (needed to scan with the screen off), unless a prefix or suffix filter is set: KnBle then checks every
filter itself. MAC addresses and prefixes are accepted in any case. An invalid MAC or MAC prefix, a manufacturer ID
outside 0..0xFFFF, or null throws in the Builder. Built `ScanFilters` are immutable.

**Settings**: `setScanMode` (default `SCAN_MODE_LOW_LATENCY`), `setScanTimeout` (0: until `stopScan`; ignored when `setAutoRestartScanAfter` > 0, as in 3.x), `setLegacy`
(default `false`: extended advertising included on 1M), `setPhy`, `setCallbackType`, `setMatchMode`, `setNbMatch`,
`setReportDelay`, `setNeverForLocation` (default `true`), `setAutoRestartScanAfter` (0: automatic).

A scan lasts until `stopScan()` or its timeout. KnBle restarts the system scan transparently when the phone needs it:
duplicate filtering, silence, the time limit of Android (30 min, 10 min from Android 14), a failure, Bluetooth turned
back on. It never exceeds 5 starts in 30 s, the limit beyond which Android silently delivers nothing.

`startScan` checks what Android needs to deliver results, and fails with `PERMISSION_DENIED` or `LOCATION_DISABLED`
instead of scanning silently:
- Android 12+: `BLUETOOTH_SCAN`. Also `ACCESS_FINE_LOCATION` and the location services when `BLUETOOTH_SCAN` lacks
  `usesPermissionFlags="neverForLocation"`, or with `setNeverForLocation(false)`.
- Before 12: the location permission (fine on 10 and 11, coarse or fine on 7 to 9) and the location services.

With `CALLBACK_TYPE_FIRST_MATCH` or `MATCH_LOST`, the losses come from the controller only. `isScanning()` changes at
once with `startScan()` and `stopScan()`. `getScannedDevices()` and `onScanFinished` drop the devices lost for more than
5 min. `getName()` falls back to the system name only with `BLUETOOTH_CONNECT` on Android 12+.

**`BleDevice`** (immutable, one per report): `getMac()`, `getName()` (advertised name, the last one seen), `getRssi()`,
`getTimestamp()`, `getScanRecord()` (flags, TX power, service UUIDs and data, all manufacturer data, raw bytes),
`isConnectable()`, `getPrimaryPhy()`, `getSecondaryPhy()`, `getTxPower()`. A report without scan response keeps the
fields seen before.

Also: `isScanning()`, `getLastScanError()`, `getScannedDevices()`, `clearScannedDevices()`,
`resetScan(resetSettings, resetFilters)`.

### Background scan

Results delivered to a `PendingIntent` (a `BroadcastReceiver`, `FLAG_MUTABLE`) even when the app is not running,
Android 8+. The hardware filters (exact names, MACs, manufacturer IDs, beacon and service UUIDs, service data) go to
the system even alongside name prefix or suffix and MAC prefix filters. Those then only narrow down the results in
`parseBackgroundScan` (AND, unlike the OR of a foreground scan). Without any hardware filter (or with
`setUseAndroid6Filters(false)`), results only come with the screen on.

```java
KnBle.startBackgroundScan(pendingIntent, filters, settings, () -> {});   // callback may be null
KnBle.stopBackgroundScan(pendingIntent);

// In the receiver
List<BleDevice> devices = KnBle.parseBackgroundScan(intent, filters);
int error = KnBle.getBackgroundScanError(intent);                        // BleScanCallback.NO_ERROR when none
```
KnBle restarts it when Bluetooth comes back while the process lives; start it again at each app launch.

## Connection

```java
KnBle.connect(device, new BleGattCallback() {
	@Override public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {}
	@Override public void onConnecting() {}
	@Override public void onDisconnected(boolean connectFailed, int status) {}  // 0 when asked by the app
	@Override public void onServicesChanged(@NonNull List<BluetoothGattService> services) {}
	@Override public void onConnectionParametersChanged(@NonNull BleConnectionParameters parameters) {}
});

KnBle.connect(device, true, callback);   // auto-reconnection
KnBle.disconnect(device);
```

**Auto-reconnection**: after a lost link (or a failed first connection) KnBle reconnects until `disconnect()`: two
direct connections, 1 and 2 s after the loss, then a background connection without timeout (low power, the controller
connects when the device is back, also while the phone sleeps), and again when Bluetooth turns back on. The app sees
`onDisconnected`, one `onConnecting`, then `onConnectSuccess`, or `onDisconnected(true, CANCELLED)` when it calls
`disconnect()` meanwhile. Bluetooth turning off meanwhile gives `onDisconnected(true, BLUETOOTH_DISABLED)`, then
`onConnecting` again once it is back (as for Bluetooth classic). Operations requested while reconnecting fail with `NOT_CONNECTED`. A disconnection ends the
subscriptions: subscribe again in `onConnectSuccess`.

**Services changed**: a Service Changed indication triggers a new discovery and `onServicesChanged`. The subscriptions
are lost (`onNotifyDisabled`): subscribe again.

| Method | |
|---|---|
| `isConnected(device)`, `getDeviceConnState(device)` | `BleGattCallback.DISCONNECTED`, `CONNECTING`, `CONNECTED` |
| `getConnectedDevices()` | |
| `getServices(device)`, `getService`, `getCharacteristic`, `getDescriptor` | Synchronous, `null`/empty when absent |
| `getConnectionParameters(device)` | Interval, latency, supervision timeout (Android 8+), `null` before the first update |
| `setGattCallback(device, callback)` | Replace the callback of a connection |
| `disconnectAll()` | |
| `destroyDevice(device)`, `destroyAllDevices()` | Disconnect and end the thread of the device at once |
| `cancel(device)` | End the operation in progress and the queued ones with `CANCELLED` |
| `getBluetoothGatt(device)` | The `BluetoothGatt`; calling it directly bypasses the queue |

## GATT operations

```java
KnBle.read(device, SERVICE, CHR, data -> {});
KnBle.write(device, SERVICE, CHR, data, noResponse, () -> {});
KnBle.readDesc(device, SERVICE, CHR, DESC, data -> {});
KnBle.writeDesc(device, SERVICE, CHR, DESC, data, () -> {});

// Notifications, or indications when the characteristic only indicates. Never dropped nor merged.
KnBle.enableNotify(device, SERVICE, CHR, data -> {});
KnBle.disableNotify(device, SERVICE, CHR);

// Write, then the next notification of RESPONSE_CHR is the answer (subscribes if needed, TIMEOUT if none)
KnBle.request(device, SERVICE, COMMAND_CHR, RESPONSE_CHR, command, response -> {});

// Prepared writes, applied together by the device or not at all (each value MTU - 5 bytes at most)
KnBle.reliableWrite(device, new BleReliableWrite().add(SERVICE, CHR_A, a).add(SERVICE, CHR_B, b), () -> {});
```

**Large data** in packets, each one sent when the stack accepted the previous one:
```java
KnBle.splittedWrite(device, SERVICE, CHR, data, 0 /* MTU - 3 */, true /* no response */, 0 /* ms between packets */,
		new BleSplittedWriteCallback() {
			@Override public void onWriteSuccess() {}
			@Override public void onWriteProgress(@NonNull BleProgress p) {}   // at most every 100 ms: getPercent(), getRate()
			@Override public void onWriteFailed(int error) {}
		});
```
Android accepts up to about 200 KB of writes without response ahead of the radio on some phones: the progress tracks the
data handed to Android, and the device receives the last bytes a few seconds later.

**Pairing**: an operation on an encrypted characteristic makes the stack pair; KnBle gives it the time of the pairing
(30 s), then replays it once bonded. See [Pairing](#pairing).

## Link

| Method | |
|---|---|
| `requestFastTransfer(device, (mtu, tx, rx) -> {})` | Priority HIGH, PHY 2M when accepted, MTU 517; always calls back with the values in use |
| `requestConnectionPriority(device, priority)` | `BluetoothGatt.CONNECTION_PRIORITY_*` |
| `requestMtu(device, mtu, mtu -> {})`, `getMtu(device)` | Callback always called, with the MTU in use |
| `setPreferredPhy(device, tx, rx, options, (tx, rx) -> {})`, `readPhy(device, cb)` | Android 8+, 1M otherwise |
| `readRssi(device, rssi -> {})` | |

## Pairing

KnBle runs the pairings of its devices by itself and shows no UI: the app chooses who gives the code, and shows its own
dialog when it gives it.

| Case | What happens |
|---|---|
| Nothing done by the app | An operation on an encrypted characteristic (or a secure classic connection) makes Android pair: its dialog asks the user, the operation waits (30 s, 60 s for a classic connection) and goes on once bonded. |
| Code known in advance | `createBond(device, "123456", cb)` or `ClassicOptions.Builder().setPin("1234")`: KnBle gives it when Android asks, no dialog. |
| Code typed in the app | `onPairingRequest(request)` returns `true`, then `request.setPin(code)` or `request.cancel()`, now or later from the dialog of the app, before `request.getDeadline()` (30 s, `getTimeLeftMs()` for a countdown): no dialog of the system. |
| Number to confirm, just works | Always the dialog of the system (an app cannot confirm). `onPairingRequest` is told (`canAnswer()` false, `getPairingKey()` the number), the app may show a hint. |

```java
KnBle.connect(device, new BleGattCallback() {
	@Override public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
		KnBle.createBond(device, () -> readSecureData());       // pair first: one clear step for the user
	}

	@Override public boolean onPairingRequest(@NonNull BlePairingRequest request) {
		if (!request.canAnswer()) {
			showHint("Confirm the pairing in the Android window");  // its dialog shows over the app
			return false;
		}
		showPinDialog(request.isPasskey(),                            // passkey: 6 digits, numeric keyboard
				request.getTimeLeftMs(),                              // closes itself when the time is over
				pin -> request.setPin(pin),                           // IllegalArgumentException when not a valid code
				() -> request.cancel());                              // the operation fails at once with CANCELLED
		return true;                                                  // no dialog of the system
	}
});
```

The same `onPairingRequest` is in `BleBondCallback` (`createBond`) and `BleClassicCallback` (`connectClassic`). It is
only called for the devices KnBle works with: the pairings of the other devices (headphones paired in the settings...)
are never touched.

**The app never waits for nothing.** Android shows its pairing dialog over the app only in some cases (a classic
discovery less than 60 s ago, the app that called `createBond` in the foreground...), else as a notification many
users never see. While the app is in the foreground, KnBle starts a classic discovery of a few hundred ms just before
Android decides, so the dialog shows (`setPairingDialogInForeground(false)` to leave it to Android; it needs the scan
permission). Every pairing ends: bonded, refused (`BOND_FAILED`), cancelled (`CANCELLED`) or `TIMEOUT`.

**Android 17+ and an app targeting API 37+**: Android gives the pairing codes and `removeBond` to the system apps only
(`BLUETOOTH_PRIVILEGED`). KnBle detects it: `canAnswer()` is false, the codes given in advance are not used, the dialog
of the system asks the user. `removeBond` only works there for a device associated with `associate()`. An app
targeting API 36 or below keeps them.

**Lost bond** (Android 16+): a bonded device that was reset, or removed the bond on its side, cannot give its keys
(`ACTION_KEY_MISSING`). KnBle tells `onBondLost(reason)` (`BleGattCallback`, `BleClassicCallback`), stops the
auto-reconnection, which would fail again or ask the user again, fails the operation waiting for the bond with
`BleError.BOND_LOST`, and tells the end of the connection with `BOND_LOST`. The app asks the user to pair again:
`removeBond` then `createBond`, or, on Android 17+, the re-pairing the system offers (`BlePairingRequest.isRepairing()`:
accepting replaces the old bond). A `connect` of the app, or a new bond, lets KnBle try again.

**Cancel**: `request.cancel()` fails at once the operation that needed the pairing, and KnBle does not start the
pairing again by itself for 30 s. Android ends the pairing at once on Android 7 to 11, for a `createBond` of the app on
Android 17+, and for a classic `createBond` of the app on Android 12 to 16; the other pairings end by themselves within
30 s.

| Method | |
|---|---|
| `createBond(device, cb)`, `createBond(device, pin, cb)` | `onBonded`, `onBondFailed(BOND_FAILED, CANCELLED, TIMEOUT...)`, `onPairingRequest`; BLE or classic |
| `removeBond(device)` | Phone side only (Android 17 + API 37: associated devices only) |
| `isBonded(device)`, `getBondState(device)`, `getBondedDevices()` | Cached (loaded at init, with the names of the system), no binder call |

## OTA

Run inside KnBle on the thread of the device, packet after packet.

```java
KnBle.requestFastTransfer(device, (mtu, tx, rx) -> {
	// Silicon Labs AppLoader (device in DFU mode): control 0x00, image, control 0x03
	KnBle.otaSilabs(device, image, new BleOtaCallback() {
		@Override public void onOtaSuccess() {}                            // the device received the whole image
		@Override public void onOtaProgress(@NonNull BleProgress p) {}
		@Override public void onOtaFailed(int error) {}
		@Override public void onOtaRetry(int attempt) {}
	});
});

// TI Enhanced OAD (CC13xx, CC26xx), MTU 25+: the device requests each block
KnBle.otaTi(device, oadImage, callback);
```

`OtaOptions` for bootloaders that lose packets: `setPacketSize` (0: MTU - 3), `setPacketInterval(ms)`,
`setRetries(n)` (restart from the beginning), `setRetryIntervalStep(ms)` (interval added per retry, default 5),
`setSyncEvery(n)` (one packet with response every n: real flow control).

## Peripheral

```java
KnBle.startServer(new BleServerCallback() {
	@Override public void onServerStarted() {}
	@Override public void onCentralConnected(@NonNull BleDevice central) {}
	@Override public void onWrite(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID chr, @NonNull byte[] value) {}
	@Override public void onSubscriptionChanged(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID chr, boolean subscribed) {}
}, new BleServerService(SERVICE)
		.addCharacteristic(STATE_CHR, BleServerService.READ | BleServerService.NOTIFY, initialValue)
		.addCharacteristic(COMMAND_CHR, BleServerService.WRITE | BleServerService.WRITE_NO_RESPONSE));

KnBle.startAdvertising(new BleAdvertiseData.Builder()
		.addServiceUuid(SERVICE)
		.setIncludeName(true)                          // name of the phone, in the scan response
		.setMode(BleAdvertiseData.MODE_LOW_LATENCY)
		.build(), () -> {});

KnBle.notifyCentrals(SERVICE, STATE_CHR, value, null);   // every subscribed central, value also served to reads
```

KnBle serves reads from the stored values (long reads included), stores writes (long writes assembled on execute),
manages subscriptions (CCCD) and sends notifications one at a time, each after the stack confirmed the previous one.
Indications are used for centrals that subscribed to indications only. `ENCRYPTED` makes a characteristic require
pairing. The server and the advertising restart by themselves when Bluetooth comes back.

Android tells a GATT server about every LE link of the phone (a watch, a keyboard, the GATT client of any app). A link
becomes a central of this server at its first request (read, write, subscription), or at once for a bonded central that
comes back with its subscriptions: the Bluetooth spec keeps the subscriptions of a bonded client between connections,
so KnBle keeps them (32 bonded centrals at most). `disconnectCentral` really ends the link, unless another app still
uses it. Values are 512 bytes at most. Long writes are capped at 4096 bytes and 256 parts per central
(`PREPARE_QUEUE_FULL`), and a part past the end of the value fails the whole write (`INVALID_OFFSET`). `startServer`
copies the services: changing them afterwards changes nothing. No callback comes after `stopServer` or
`stopAdvertising`. A start replaced before its result gets `CANCELLED`. Advertising with no answer from the system
fails with `TIMEOUT` after 5 s, and Bluetooth turned off before a start finished gives `BLUETOOTH_DISABLED`.

| Method | |
|---|---|
| `startServer(callback, services...)`, `stopServer()`, `isServerStarted()` | A start replaced before its result: `CANCELLED` |
| `setServerValue(service, chr, value)` | Value served to the next reads; `IllegalArgumentException` above 512 bytes |
| `notifyCentrals(service, chr, value, cb)`, `notifyCentral(central, service, chr, value, cb)` | `NOT_CONNECTED` when no central is subscribed, `DATA_TOO_LARGE` above MTU - 3 or 512 bytes |
| `getConnectedCentrals()`, `disconnectCentral(central)` | Centrals that sent a request, and bonded centrals back with their subscriptions |
| `startAdvertising(data, cb)`, `stopAdvertising()`, `isAdvertising()` | `TIMEOUT` with no answer in 5 s |

`BleAdvertiseData.Builder`: `addServiceUuid`, `addServiceData`, `addManufacturerData`, `setIncludeName`,
`setIncludeTxPower`, `setConnectable` (default `true`), `setMode`, `setTxPower`, `setTimeout(ms)`, `setExtended`
(extended advertising up to 251 bytes when the phone supports it, legacy 31 bytes otherwise). `setMode` and
`setTxPower` throw `IllegalArgumentException` for an unknown value, and `addCharacteristic` throws it for a value
above 512 bytes.

## Companion device

Android 8+. The system scans and lists the matching devices (no location permission needed for it), the user picks one.
`onAssociationFailed(NOT_SUPPORTED)` on a phone without the companion device service, `REFUSED` when Android does not
show the dialog (an app in the background, Android 10+). The app gets the privileges of a companion app for that device (`REQUEST_COMPANION_RUN_IN_BACKGROUND`...). KnBle hosts
the dialog: no `onActivityResult` needed.

```java
KnBle.associate(activity, filters, true /* single device */, device -> {});
KnBle.getAssociatedDevices(devices -> {});
KnBle.disassociate(device);
```
Filters: names (exact, prefix, suffix), MACs, manufacturer IDs, service UUIDs and data. Declare
`<uses-feature android:name="android.software.companion_device_setup" android:required="false"/>`.

## Bluetooth classic

Serial devices over RFCOMM (Serial Port Profile by default): commands written, a stream of bytes read.

```java
KnBle.startClassicDiscovery(device -> {});              // inquiry, about 12 s, name and RSSI of each device
KnBle.stopClassicDiscovery();

KnBle.connectClassic(device, true /* auto-reconnection */, new BleClassicCallback() {
	@Override public void onData(@NonNull byte[] data) {}                     // in order, never dropped
	@Override public void onConnected() {}
	@Override public void onDisconnected(boolean connectFailed, int status) {} // CONNECTION_LOST, TIMEOUT, REFUSED...
});
KnBle.writeClassic(device, command);                     // called while connecting: sent once connected
KnBle.writeClassic(device, command, () -> {});           // given to the stream
KnBle.disconnectClassic(device);                         // onDisconnected after the last bytes read
```

**Stream**: RFCOMM has no packets. `onData` gives the bytes in order; the chunks read while the main thread is busy
come merged (at most 256 KB per `onData`), never dropped. Splitting the messages (lines, frames) is the job of the app.

**Threads**: each connection has a thread that connects then writes the queue in order (the RFCOMM flow control may
hold it when the device is slow), and a thread that reads all the time, up to 1 MB ahead of the app. Past that it
waits for `onData` to catch up, and the RFCOMM flow control slows the device down (nothing dropped, bounded memory).
These are the only blocking calls of KnBle: they never block the app nor the rest of KnBle.

**Connection**: a failed attempt is made again on channel 1 (the fix of "read failed, socket might closed" on many
phones), then after 1 s, `setConnectAttempts` times within `setConnectTimeout`. A running discovery is stopped first
(it makes connections fail). Auto-reconnection: 1, 2, 5 then every 10 s until `disconnectClassic`, also when Bluetooth
comes back. The app sees `onDisconnected`, one `onConnecting`, then `onConnected` (failed rounds are silent).
`disconnectClassic` meanwhile gives `onDisconnected(true, CANCELLED)`. Bluetooth turning off gives
`onDisconnected(true, BLUETOOTH_DISABLED)`, then `onConnecting` again once it is back. Every `onConnecting` is followed
by one `onConnected` or `onDisconnected`. `connectClassic` while connecting: the connection goes on for the new callback
(a replaced callback gets `onDisconnected(true, CANCELLED)`). Right after `disconnectClassic` or a loss: a new
connection once the end of the previous one is told. `startClassicDiscovery` while one runs ends it
(`onDiscoveryFinished`); the new one starts once Android ended the inquiry (2 s at most).

`ClassicOptions.Builder`: `setUuid` (default `ClassicOptions.SPP`), `setSecure` (default `true`: the system pairs first
when needed; `false` for the devices that accept a connection without pairing), `setChannel` (an RFCOMM channel instead
of the UUID), `setChannelFallback` (default `true`), `setPin` (the PIN of a device that pairs the legacy way, given
without dialog). `createBond` pairs a classic-only device over BR/EDR.

**Pairing**: a secure connection to a device not bonded pairs first. Its time does not count in the connection timeout
(60 s for the user), an attempt that fails while the user is still typing waits for the end of the pairing.
`BleClassicCallback.onPairingRequest` lets the app type the PIN (see [Pairing](#pairing)). A refused or cancelled
pairing ends the connection (`BOND_FAILED`, `CANCELLED`) and its auto-reconnection, which would ask the user again.

| Method | |
|---|---|
| `isClassicConnected(device)`, `getClassicConnState(device)` | `BleGattCallback.DISCONNECTED`, `CONNECTING`, `CONNECTED` |
| `getClassicConnectedDevices()`, `disconnectAllClassic()` | |
| `isClassicDiscovering()` | |

The radio is shared with Bluetooth LE: a classic discovery slows down the BLE transfers in progress (an OTA).

### Classic server

The phone publishes an RFCOMM service (Serial Port Profile by default) and accepts the devices that connect to it. Each
client is then a classic connection like the ones of `connectClassic`: same stream (in order, never dropped, 1 MB of
backpressure), same writes (in call order, with their results), driven with `writeClassic` and `disconnectClassic`.

```java
KnBle.startClassicServer(new ClassicServerOptions.Builder()
		.setName("My service")          // name in the SDP record
		.setSecure(true)                // default: a client that is not bonded pairs first
		.setMaxClients(3)               // 1 to 7, default 7
		.build(), new BleClassicServerCallback() {
	@Override public BleClassicCallback onClientConnected(@NonNull BleDevice client) {
		return data -> handle(client, data);  // the callback of this connection; null refuses the client
	}
	@Override public void onServerStarted() {}
	@Override public void onServerFailed(int error) {}       // BLUETOOTH_DISABLED, PERMISSION_DENIED, REFUSED, CANCELLED...
});
KnBle.writeClassic(client, reply);
KnBle.stopClassicServer();                                 // its clients are disconnected, no callback after it
```

The callback returned by `onClientConnected` gets `onConnected` at once, then `onData` (the reading starts once it is
given: nothing the client sent is lost), then one `onDisconnected(false, status)`. A client over `setMaxClients`, or a
device KnBle already connects to, is refused. The server listens again by itself when Bluetooth comes back (no second
`onServerStarted`), and `onServerFailed(INTERNAL)` comes if Android closed it and it cannot listen again. A device that
never paired with the phone has to find it first: make the phone discoverable (`ACTION_REQUEST_DISCOVERABLE`). While a
secure server listens, the pairings asked by devices KnBle does not otherwise work with are given to
`BleClassicServerCallback.onPairingRequest`, and `ClassicServerOptions.setPin` answers a legacy PIN without dialog.

| Method | |
|---|---|
| `startClassicServer(callback)`, `startClassicServer(options, callback)` | A start replaced before its result: `CANCELLED` |
| `stopClassicServer()`, `isClassicServerStarted()` | |

## Test bench

`sample/` runs benchmarks, stress and pairing scenarios against the benches of `testbench/`: an ESP32-C3 for BLE
(`testbench/esp32-c3`), an ESP32 for Bluetooth classic (`testbench/esp32-classic`), or a second phone
(`testbench/benchhost`, BLE and classic). See [testbench/README.md](testbench/README.md).

## License

```
The MIT License (MIT)

Copyright (c) 2019-2026 Florent VIALATTE

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
