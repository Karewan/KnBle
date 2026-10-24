KnBle Change Log
==========

Version 4.0.0 *(2026-10-24)*
----------------------------
* **Breaking changes** (how to migrate: [MIGRATION.md](MIGRATION.md))
	* `KnBle` is static: `KnBle.gi().xxx()` becomes `KnBle.xxx()`, same methods and arguments (step 2)
	* The internal classes of 3.x are gone and `BleDevice` is immutable and final: use the `KnBle` methods (step 2)
	* Every callback comes on the main thread (or the Looper of `KnBleSettings`), in order: heavy work in a callback must move to a thread of the app (step 3)
	* A scan lasts until `stopScan()`: the restarts no longer clear the devices nor call `onScanStarted` / `onScanFinished` again, a device already reported comes in `onDeviceUpdated`, new `onDeviceLost`, Bluetooth off pauses the scan (step 4)
* **Added**
	* `KnBle.init(context, KnBleSettings)`: connect timeout and attempts, operation timeout, discovery delay, refresh on disconnect, callback Looper, logger, debug
	* Callbacks with a single mandatory method (lambdas work), the others with empty defaults
	* Error codes in the failure callbacks: `onReadFailed(int)`, `onWriteFailed(int)`, `onNotifyFailed(int)`, `onRssiFailed(int)`, `onDisconnected(boolean, int)` (`BleError` or GATT status); the 3.x forms are still called
	* Auto-reconnection: `connect(device, true, callback)` keeps the device connected until `disconnect()`, also while the phone sleeps and after Bluetooth comes back
	* `request()`: a write and the notification that answers it, in one operation; `reliableWrite()` with `BleReliableWrite`
	* `readRssi()`, `cancel()`, `requestFastTransfer()` (priority high, PHY 2M, MTU 517), `getServices()`, synchronous `getService` / `getCharacteristic` / `getDescriptor`
	* `BleProgress` in `onWriteProgress`: bytes, packets, percent, rate
	* OTA run inside KnBle: `otaSilabs` (Silicon Labs AppLoader, `OtaOptions`: packet size and interval, retries, flow control) and `otaTi` (TI Enhanced OAD)
	* An operation on an encrypted characteristic waits for the pairing started by Android, then runs again once bonded
	* Bonds: `createBond()` with its result (BLE or Bluetooth classic), `createBond(device, pin, callback)` with the code given in advance, `removeBond()`, `isBonded()` / `getBondState()` / `getBondedDevices()` without binder call
	* Pairing codes typed in the app: `onPairingRequest(BlePairingRequest)` in `BleGattCallback`, `BleBondCallback` and `BleClassicCallback` (`setPin()`, `cancel()`, `canAnswer()`, `getPairingKey()`, `getDeadline()` / `getTimeLeftMs()`: the time to answer), the app shows its own UI, only for the devices KnBle works with
	* The pairing dialog of the system shown over the app instead of a notification the user may not see (`KnBleSettings.setPairingDialogInForeground`, default true)
	* Android 17 pairing hardening detected (an app targeting API 37 may no longer give pairing codes nor remove bonds): `canAnswer()` false, the codes go to the dialog of the system
	* Lost bond, Android 16+: `onBondLost(reason)` in `BleGattCallback` and `BleClassicCallback`, `BleError.BOND_LOST`, auto-reconnection stopped; `BlePairingRequest.isRepairing()` for the re-pairing offered by Android 17+
	* `onServicesChanged` after a Service Changed indication (services discovered again, subscriptions ended with `onNotifyDisabled`)
	* Connection parameters, Android 8+: `getConnectionParameters()`, `onConnectionParametersChanged`
	* Bluetooth state listeners: `addBluetoothStateListener()`, `removeBluetoothStateListener()`
	* `getAdapterInfo(context)`: what the Bluetooth of the phone supports (PHY 2M and Coded, extended advertising, peripheral role, offloaded filtering, LE Audio, Channel Sounding, classic...)
	* Background scan to a `PendingIntent`, Android 8+: `startBackgroundScan()`, `stopBackgroundScan()`, `parseBackgroundScan()`, `getBackgroundScanError()`
	* Scan filters by service UUID, service data and minimum RSSI (`addServiceUuid`, `addServiceData`, `setMinRssi`); `BleScanCallback.PERMISSION_DENIED`
	* `ScanRecord`: service UUIDs, service data, every manufacturer data, TX power level, flags, name; `BleDevice`: `isConnectable()`, `getPrimaryPhy()`, `getSecondaryPhy()`, `getTxPower()`
	* Peripheral mode: advertising (`startAdvertising`, `BleAdvertiseData`, legacy or extended) and GATT server (`startServer`, `BleServerService`, `setServerValue`, `notifyCentrals`), restarted when Bluetooth comes back
	* Companion device association, Android 8+: `associate()`, `getAssociatedDevices()`, `disassociate()`, without `onActivityResult`
	* Bluetooth classic (RFCOMM, Serial Port Profile by default): discovery, `connectClassic()` with retries and auto-reconnection, writes in order, a read stream never dropped, `ClassicOptions` (UUID or channel, secure or insecure, legacy PIN)
	* Bluetooth classic server: `startClassicServer()`, `ClassicServerOptions`, `BleClassicServerCallback`, up to 7 clients
* **Changed**
	* Threading: each device keeps its own thread and queue, now with a deadline on every operation (`TIMEOUT`); no method blocks or makes a binder call on the calling thread (except `getAdapterInfo`), `isBluetoothEnabled()` is cached
	* Connection: one timeout for all its attempts and the discovery (30 s), new attempts on status 133, 62 and 147 (3 by default), 500 ms between a close() and the next connection when the phone needs it
	* Service discovery 1600 ms after the connection of a bonded device (300 ms otherwise, as in 3.x)
	* An operation asked right after `connect()` waits for `onConnectSuccess` (3.x failed it)
	* `connect()` on a connected device calls `onConnectSuccess` again (3.x did nothing)
	* A disconnected device keeps its thread 10 s for a reconnection (3.x kept it until `destroyDevice()`)
	* Scan: KnBle restarts the system scan silently when the phone needs it (duplicate filtering, silence, the time limit of Android, Bluetooth back on), never more than 5 starts in 30 s; `setAutoRestartScanAfter()` is no longer needed
	* Scan: `startScan()` fails at once with `PERMISSION_DENIED` or `LOCATION_DISABLED` instead of delivering nothing (MIGRATION.md, step 4)
	* Scan: a failed system scan is started again, an option the phone does not support (report delay, callback type, extended advertising) is dropped instead of failing the scan
	* Scan: the default `ScanSettings` also receive the extended advertising (`setLegacy(true)` for the 3.x behaviour); `getScannedDevices()` drops the devices lost for more than 5 min
	* `BleDevice`: a new instance per report, equal to the others of the same MAC address; `getName()` is the advertised name (else the one of the system, read once off the main thread), never a binder call
	* `ScanRecord` keeps the last value of each field: a report without scan response no longer erases the name or the manufacturer data
	* `requestMtu`, `setPreferredPhy` and `readPhy` always call back with the values in use (also when refused), on every Android version (1M on Android 7)
	* `splittedWrite`: `splitSize` 0 for MTU - 3, packets without response never larger than MTU - 3 (Android cut them), `onWriteProgress` at most every 100 ms
	* `getBleDeviceFromMac()` and `ScanFilters.Builder` accept lower-case MACs; the Builder throws on invalid values, built filters are immutable
	* Android 17: the connection makes no automatic MTU exchange, the device only sees the one asked by the app (`requestMtu`, `requestFastTransfer`)
	* Compiled against Android 17 (API 37), Gradle 9, bumped deps; Java 21 and minSdk 24 as in 3.x
* **Fixed** (bugs of 3.0.5)
	* Notification and read values overwritten by the next notification before reaching the app (values lost or duplicated under load)
	* A slow callback of the app held up the operations of its device (the callbacks ran on the thread of the device)
	* `disableNotify` of a characteristic not subscribed, or an operation on a missing descriptor, blocked the queue of the device until the disconnection
	* A request Android never answered (MTU, PHY...) blocked the queue of the device until the disconnection, without callback
	* Operations queued at a disconnection, or asked for a device never connected, never got their callback
	* `connect()` called right after `disconnect()` was lost
	* `enableNotify` on an indicate-only characteristic never enabled the indications
	* Same characteristic UUID in two services: the characteristic of the first service was used for both
	* A failed or empty service discovery called `onConnectSuccess` without services
	* Scan: name prefix or suffix and MAC prefix filters never matched when exact filters were set too
	* Scan: `setReportDelay()` above 0 delivered nothing (batched results ignored)
	* Scan: a long scan degraded by Android after 30 min (10 min on Android 14+), or silenced by more than 5 restarts in 30 s
	* Crashes: `splitSize` 0, a lower-case MAC in the scan filters, a missing Bluetooth permission on Android 12+ (connect, scan, `getName()`)
* **Removed**
	* `KnBle.gi()`: `KnBle` has no instance any more
	* The internal classes of 3.x: `ble.DeviceOperation`, `ble.DevicesManager`, `scan.Scanner`, `cache.*`, `tasks.*`
	* `BleDevice.updateDevice()` (`BleDevice` is immutable and final)
	* Deprecated: the `BleDevice(BluetoothDevice, int, ScanRecord, long)` constructor, `Utils.log`, `Utils.splitBytesArrayAndFillQueue`, the callback forms of `getService` / `getCharacteristic` / `getDescriptor` (`BleGetService`...: to be removed in 5.0)
* **Test bench**: `sample` app with benchmark and stress scenarios, against an ESP32-C3 BLE bench, an ESP32 Bluetooth classic bench or a second phone (`testbench/benchhost`); Robolectric unit tests

Version 3.0.5 *(2025-10-16)*
----------------------------
* Location is not longer required on Android 12+ for scanning
* Added a "neverForLocation" setting in the ScanSettings class
* Bumped dep

Version 3.0.4 *(2025-10-16)*
----------------------------
* Fixed a possible crash when the user disabled the Bluetooth when a BLE scan was running
* Bumped dep

Version 3.0.3 *(2025-08-18)*
----------------------------
* **BREAKING CHANGE**: Removed sendNextWhenLastSuccess parameters from splittedWrite => now pkg are always sent when last success (better performance and fiability)
* Bumped deps
* Various changes and improvements

Version 3.0.2 *(2025-08-18)*
----------------------------
* Fixed: new onCharacteristicRead and onCharacteristicChanged callbacks are only called from Android 13+ devices

Version 3.0.1 *(2025-08-18)*
----------------------------
* Fixed: The thread is sometimes destroyed too quickly

Version 3.0.0 *(2025-08-16)*
----------------------------
* Gatt Queue (to never execute two operations per device at the same time)
* Added ability to listen multiple characteristics for a same device (enableNotify method)
* Added ability to enableNotify on characteristic with the indicate flag
* Added read and write descriptor methods
* Added a readPhy method
* Added a callback for the requestMtu method (optional)
* Added a callback for the setPreferredPhy method (optional)
* Added a destroyDevice method (that disconnect and destroy the associated thread)
* Added a getDescriptor method
* Added more possibility to interact with device for operations like write, read, notify... by using UUID or service, characteristic, descriptor directly... (in addition to the existing “string UUID” method)
* Removed duplicate methods between KnBle and DevicesManager classes
* When calling the connect method, the DeviceOp instance is now reused for a same mac address (if existent)
* Scanner and DevicesManager classes are no longer Singleton
* Improved Scanner performance (by using temp variables and HandlerThread)
* Improved debug logs
* Bumped compile and target SDK to 36
* Bumped JDK to 21
* Bumped deps
* Various changes and improvements
* **BREAKING CHANGES (from v2.4.7)**
	* Bumped min SDK to 24
	* The disableNotify method now require serviceUUID and characteristicUUID parameters
	* Replaced hasService method by a getService method
	* Replaced hasCharacteristic method by a getCharacteristic method
	* Replaced Hashmap by List for getScannedDevices method and onScanFinished callback
	* Replaced HashMap by ConcurrentHashMap in the DevicesManager class
	* Replaced HashMap by ConcurrentHashMap in the Scanner class
	* Removed onConnectFailed method in the BleGattCallback, replaced by a parameter in the onDisconnected method
	* Removed getLastGattStatusOfDevice method
	* Added a dedicated method to seperate splitted writes and "standard" writes

Version 2.5.0 *(2025-07-29)*
----------------------------
* **Maintenance release before v3...**
* Improved Scanner performance (by using temp variables and HandlerThread)
* **Breaking Changes**
	* Replaced HashMap by ConcurrentHashMap in the Scanner class
	* onScanFinished now return a List instead of HashMap
	* getScannedDevices now return a List instead of HashMap
* Bumped deps

Version 2.4.7 *(2023-06-14)*
----------------------------
* Various bug fixes and improvements

Version 2.4.6 *(2023-06-14)*
----------------------------
* Various bug fixes and improvements

Version 2.4.5 *(2023-06-12)*
----------------------------
* Various bug fixes and improvements

Version 2.4.4 *(2023-05-12)*
----------------------------
* Various bug fixes and improvements

Version 2.4.3 *(2023-05-12)*
----------------------------
* Various bug fixes and improvements

Version 2.4.2 *(2023-05-11)*
----------------------------
* Various bug fixes and improvements

Version 2.4.1 *(2023-05-02)*
----------------------------
* Write no response, correctly handle remote gatt busy and retry every 60ms, 80 times
* Various bug fixes and improvements

Version 2.4.0 *(2023-05-02)*
----------------------------
* Add enableNotify method
* Add disableNotify method
* Add getBluetoothGatt method
* DeviceOp: Use main thread only for connect / disconnect, use new thread for the rest
* Various bug fixes and improvements

Version 2.3.2 *(2023-04-25)*
----------------------------
* Add setPreferredPhy function (Android 8+)

Version 2.3.1 *(2023-03-08)*
----------------------------
* Enable/disable adapter is deprecated since Android 13
* Bump target SDK to 33
* Bump gradle plugin to 7.4.2
* Bump gradle to 7.5
* Bump androidx.annotation:annotation to 1.6.0
* Various bug fixes and improvements

Version 2.3.0 *(2022-09-09)*
----------------------------
* New scan filter: beaconUUID
* Beacon service UUID parsing (available in the ScanRecord)
* Removed post SDK 23 code chunks
* Bump Java to 11
* Bump target SDK to 32
* Bump min SDK to 23
* Bump gradle plugin to 7.1.3
* Bump gradle to 7.2
* Bump androidx.annotation:annotation to 1.4.0
* Various bug fixes and improvements

Version 2.2.7 *(2022-01-27)*
----------------------------
* Various bug fixes and improvements

Version 2.2.6 *(2022-01-27)*
----------------------------
* New scan filter: devicesMacsStartsWith

Version 2.2.5 *(2021-11-24)*
----------------------------
* Remove unbond by default on disconnect

Version 2.2.4 *(2021-11-23)*
----------------------------
* Ability to enable/disable Android 6 BLE scan filters

Version 2.2.3 *(2021-11-23)*
----------------------------
* New scan filters: deviceNameStartsWith and deviceNameEndsWith
* Various bug fixes and improvements

Version 2.2.2 *(2021-11-22)*
----------------------------
* Bump gradle plugin to 4.2.2
* Bump gradle to 6.7.1
* Various bug fixes and improvements

Version 2.2.1 *(2021-11-08)*
----------------------------
* Scanner: onUpdatedDevice event added

Version 2.2.0 *(2021-11-08)*
----------------------------
* Breaking change: getInstance() remplaced by gi()

Version 2.0.2 *(2020-11-20)*
----------------------------
* Add requestMtu and getMtu methods
* Add DEBUG flag
* Bump min SDK to 21
* Bump gradle plugin to 4.1.1
* Bump gradle to 6.5

Version 2.0.1 *(2020-06-18)*
----------------------------
* Disable data split by default on write operations
* Bump gradle plugin to 4.0.0
* Bump gradle to 6.1.1

Version 2.0.0 *(2020-02-16)*
----------------------------
* BleDevices are now updated when scanning (recreated before)
* Better instance management
* Better performances
* Bugs fix

Version 1.0.1 *(2020-02-14)*
----------------------------
 * Destroy previous instance before init a new one
 * Remove synchronized flag on few methods (unnecessary)
 * Unbound device after disconnect
 * Bump compile and target SDK to 29
 * Bugs fix

Version 1.0.0 *(2019-09-27)*
----------------------------
 * Initial release.
