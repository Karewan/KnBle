# Migrating from KnBle 3.x to 4.0

Most apps only need the steps 1 to 4. The others concern apps relying on a 3.x behaviour that changed.

## 1. Update the dependency

```groovy
implementation 'com.github.Karewan:KnBle:4.0.0'
```

Java 21 and minSdk 24, like 3.x.

## 2. Static calls (BREAKING)

`KnBle` has no instance any more: replace `KnBle.gi().` by `KnBle.` everywhere. Same method names, same arguments.

The internal classes of 3.x are gone (`ble.DeviceOperation`, `ble.DevicesManager`, `scan.Scanner`, `cache.*`,
`tasks.*`): use the `KnBle` methods. `BleDevice` is immutable and final: `updateDevice()` is gone, the 3.x
constructor from a scan of the app and `Utils.log` / `Utils.splitBytesArrayAndFillQueue` are deprecated.

```java
// 3.x
KnBle.gi().init(context);
KnBle.gi().connect(device, callback);

// 4.0
KnBle.init(context);
KnBle.connect(device, callback);
```

## 3. Callbacks on the main thread (BREAKING for the apps that did heavy work in them)

In 3.x the callbacks came on threads of KnBle (or of Android). In 4.0 they all come on the main thread, in order, or on
the Looper given by `KnBleSettings.Builder.setCallbackLooper()`.

- The `runOnUiThread()` / `mainHandler.post()` around the callbacks are no longer needed.
- Heavy work (file writes, parsing, database) must move to a thread of the app: a callback that blocks the main thread
  freezes the UI. It never slows KnBle down: transfers and OTAs go on on the thread of the device.
- A protocol where the app answers each notification (request/response, custom OTA) now goes through the main thread
  at each step. Use `KnBle.otaSilabs` / `KnBle.otaTi` for the OTAs: their loops run inside KnBle.

## 4. Scan updates (BREAKING for the apps that ignore onDeviceUpdated)

3.x restarted the scan every `setAutoRestartScanAfter()` ms by clearing the list of devices: each device came back as
`onScanResult`, then `onScanStarted` / `onScanFinished` were called again at each restart.

4.0 restarts the scan of the system silently when the phone needs it (duplicate filtering, silence, the time limit
of Android (30 min, 10 min on Android 14+), Bluetooth off and on). The scan of the app lasts until `stopScan()`:

- `onScanResult`: first report of a device during the scan, or again after `onDeviceLost`.
- `onDeviceUpdated`: every later report (RSSI, advertising data, name, time). **An app that refreshed its list in
  `onScanResult` only must handle `onDeviceUpdated` too.**
- `onDeviceLost` (new): the device is no longer heard.
- `onScanStarted` once per `startScan()`, `onScanFinished` once at the end. Bluetooth off does not end the scan: it
  goes on when Bluetooth is back.

`setAutoRestartScanAfter()` still works (silent restarts at that interval) but is no longer needed: remove it. As in
3.x, a scan with `setAutoRestartScanAfter() > 0` ignores `setScanTimeout()`; removing it makes the timeout apply.

A scan missing its permission now fails at once with `onScanFailed(PERMISSION_DENIED)` (or `LOCATION_DISABLED`) instead
of delivering nothing. On Android 12+, an app whose `BLUETOOTH_SCAN` lacks `usesPermissionFlags="neverForLocation"`
needs `ACCESS_FINE_LOCATION` and the location services. `getScannedDevices()` drops the devices lost for more than 5 min.

The default `ScanSettings` now also receive the extended advertising when the phone supports it, on the 1M PHY
(`setLegacy(true)` for the 3.x behaviour).

## 5. Errors in the callbacks

The failure callbacks get the cause: `onReadFailed(int error)`, `onWriteFailed(int error)`, `onNotifyFailed(int error)`,
`onDisconnected(boolean connectFailed, int status)`, `onRssiFailed(int error)`. The error is a `BleError` code (negative)
or the GATT status of the device (positive). The 3.x forms without argument are still called by default: existing code
compiles and works, move to the new forms when you need the cause.

`onNotifyFailed(int)` is called when a subscription fails (3.x called `onNotifyDisabled()`, still the default).

## 6. Synchronous getters

`getService`, `getCharacteristic` and `getDescriptor` return the object at once (null when not connected or absent).
The callback versions (`BleGetService`...) are deprecated and will go in 5.0.

```java
// 3.x
KnBle.gi().getCharacteristic(device, SERVICE, CHARACTERISTIC, new BleGetCharacteristic() { ... });

// 4.0
BluetoothGattCharacteristic c = KnBle.getCharacteristic(device, SERVICE, CHARACTERISTIC);
```

## 7. Workarounds to remove from the apps

KnBle 4.0 does these itself; keeping them in the app only slows it down:

| App workaround | 4.0 |
|---|---|
| Connection timeout timer, retries of `connect()` after 133 | `KnBleSettings` connect timeout and attempts (30 s, 3 attempts by default) |
| Delay before reconnecting after a disconnection | KnBle waits 500 ms after a `close()` when needed |
| Scan restart every 10 s | Silent restarts by KnBle (step 4) |
| Progress throttling (percent changed + 100 ms) | `onWriteProgress` comes at most every 100 ms |
| `mtu - 3` for the split size | `splitSize = 0` |
| PHY 2M then MTU 517 callback chains | `KnBle.requestFastTransfer(device, callback)` |
| Silicon Labs OTA sequence (0x00, data, 0x03, retries with growing interval) | `KnBle.otaSilabs(device, image, options, callback)` |
| TI OAD state machine on the main thread | `KnBle.otaTi(device, image, callback)` |
| Reconnection loop after a lost link | `KnBle.connect(device, true, callback)` |
| Write, then wait for the notification that answers it | `KnBle.request(device, service, writeChr, notifyChr, data, callback)` |
| `ACTION_STATE_CHANGED` receiver for the Bluetooth state | `KnBle.addBluetoothStateListener(listener)` |
| `createBond()` and an `ACTION_BOND_STATE_CHANGED` receiver | `KnBle.createBond(device, callback)` |
| `ACTION_PAIRING_REQUEST` receiver with `setPin()` and `abortBroadcast()` | `KnBle.createBond(device, pin, callback)`, `ClassicOptions.setPin`, or `onPairingRequest` of the callbacks |

## 8. Other changes

- `BleDevice` is immutable: each report gives a new instance, equal (`equals`/`hashCode`) to the others of the same MAC
  address. `getName()` is the advertised name (else the one known by the system for a bonded or connected device, read
  once off the main thread), never a binder call: null for a device known by its MAC only, until it is connected.
- `connect()` on a connected device calls `onConnectSuccess` again (3.x did nothing): an app calling `connect()` in
  `onResume` gets it at each resume.
- `onNotifyDisabled()` comes after `disableNotify`, a change of the services, or a failed subscription (through the
  default `onNotifyFailed`). A disconnection ends the subscriptions without a call: subscribe again in `onConnectSuccess`.
- `ScanRecord` keeps the last value of each field: a report without the scan response no longer erases the name or the
  manufacturer data. New getters: `getServiceUuids()`, `getServiceData()`, `getManufacturerSpecificData()`,
  `getTxPowerLevel()`, `getAdvertiseFlags()`. `getManufacturerId()` / `getManufacturerData()` keep their 3.x meaning
  (the last manufacturer specific data of the record).
- `requestMtu`, `setPreferredPhy` and `readPhy` always call back with the values in use (also when refused; 1M on
  Android 7). `setPreferredPhy` no longer reports the requested PHY when the device refused it.
- `getBleDeviceFromMac()` accepts lower case. `ScanFilters.Builder` accepts lower-case MACs and throws on invalid
  values; built filters are immutable (unmodifiable lists).
- `KnBle.DEBUG` still exists; `KnBleSettings.setLogger()` sends the logs elsewhere than Logcat.
- A disconnected device keeps its thread 10 s for a reconnection, `destroyDevice()` ends it at once.
- Bluetooth turned off: `onDisconnected` gets `BleError.BLUETOOTH_DISABLED` (3.x gave 0).
- New in 4.0, nothing to migrate: auto-reconnection, `request`, `reliableWrite`, bonds and pairing codes, background
  scan, connection parameters, peripheral mode (advertising and GATT server), companion device association, Bluetooth
  classic (RFCOMM). See the README.
- Android 17 with an app targeting API 37: Android refuses `removeBond` and the pairing codes to the apps (they go to
  the dialog of the system); `removeBond` still works for a device associated with `associate()`.
