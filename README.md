# KnBle

[![](https://jitpack.io/v/Karewan/KnBle.svg)](https://jitpack.io/#Karewan/KnBle)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

A simple BLE Android client

## Installation

```groovy
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}
```

```groovy
android {
	compileOptions {
		sourceCompatibility JavaVersion.VERSION_21
		targetCompatibility JavaVersion.VERSION_21
	}
}

dependencies {
	implementation 'com.github.Karewan:KnBle:3.0.2'
}
```

Do not forget to add internet permissions in manifest
```xml
<!-- For all Android versions -->
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<!-- Android 6+: Needed for BLE scan -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<!-- Android 10+: For background BLE scan (Optional) -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<!-- Android 12+: BLE scan -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<!-- Android 12+: BLE connect to already paired device -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Then initialize, return false if device is not BLE compatible
```java
boolean success = KnBle.gi().init(getApplicationContext());
```

At any time you can check if the initialization is correct, return false if the device is not BLE compatible
```java
boolean isInit = KnBle.gi().isInit();
```

## Scanning operations

#### Start scan
```java
KnBle.gi().startScan(new BleScanCallback() {
	@Override
	public void onScanStarted() {

	}

	@Override
	public void onScanFailed(int error) {
		// BleScanCallback.BT_DISABLED
		// BleScanCallback.LOCATION_DISABLED
		// BleScanCallback.SCANNER_UNAVAILABLE
		// BleScanCallback.UNKNOWN_ERROR
		// BleScanCallback.SCAN_FEATURE_UNSUPPORTED
	}

	@Override
	public void onScanResult(@NonNull BleDevice bleDevice) {

	}

	@Override
	public void onDeviceUpdated(@NonNull BleDevice bleDevice) {

	}

	@Override
	public void onScanFinished(@NonNull List<BleDevice> scanResult) {

	}
});
```

#### Stop scan
```java
KnBle.gi().stopScan();
```

#### Set scan settings (before start scan)
```java
// Check ScanSettings class to see all settings
ScanSettings settings = new ScanSettings.Builder().build();

KnBle.gi().setScanSettings(settings);
```

#### Set scan filters (before start scan)
```java
// Check ScanFilters class to see all filters
ScanFilters filters = new ScanFilters.Builder().build();

KnBle.gi().setScanFilter(filters);
```

#### Check if currently scanning
```java
boolean isScanning = KnBle.gi().isScanning();
```

#### Get last scan error
```java
int error = KnBle.gi().getLastError();
```

#### Get current scan settings
```java
ScanSettings settings = KnBle.gi().getScanSettings();
```

#### Get current scan filters
```java
ScanFilters filters = KnBle.gi().getScanFilters();
```

#### Get all scanned devices
```java
@NonNull
List<BleDevice> devices = KnBle.gi().getScannedDevices();
```

#### Clear scanned devices
```java
KnBle.gi().clearScannedDevices();
```

#### Stop and reset scan completely (boolean resetSettings, boolean resetFilters)
```java
KnBle.gi().resetScan(true, true);
```

## Device operations

#### Get device from MAC address
```java
@Nullable
BleDevice device = KnBle.gi().getBleDeviceFromMac("FF:FF:FF:FF:FF:FF");
```

#### Get list of connected devices
```java
@NonNull
List<BleDevice> devices = KnBle.gi().getConnectedDevices();
```

#### Check if device is connected
```java
boolean connected = KnBle.gi().isConnected(device);
```

#### Get device connection state
```java
int state = KnBle.gi().getDeviceConnState(device);

// BleGattCallback.DISCONNECTED
// BleGattCallback.CONNECTING
// BleGattCallback.CONNECTED
```

#### Connect to a device
```java
KnBle.gi().connect(device, new BleGattCallback() {
	@Override
	public void onConnecting() {

	}

	@Override
	public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {

	}

	@Override
	public void onDisconnected(boolean connectFailed) {

	}
});
```

#### Get a gatt service of a device
```java
KnBle.gi().getService(device, "service uuid",  new BleGetService() {
	@Override
	public void onSuccess(@NonNull BluetoothGattService service) {

	}

	@Override
	public void onFailed() {

	}
});

// OR

KnBle.gi().getService(device, serviceUUID,  new BleGetService() {
	@Override
	public void onSuccess(@NonNull BluetoothGattService service) {

	}

	@Override
	public void onFailed() {

	}
});
```

#### Get a gatt characteristic of a device
```java
KnBle.gi().getCharacteristic(device, "service uuid", "characteristic uuid",  new BleGetCharacteristic() {
	@Override
	public void onSuccess(@NonNull BluetoothGattCharacteristic characteristic) {

	}

	@Override
	public void onFailed() {

	}
});

// OR

KnBle.gi().getCharacteristic(device, serviceUUID, characteristicUUID,  new BleGetCharacteristic() {
	@Override
	public void onSuccess(@NonNull BluetoothGattCharacteristic characteristic) {

	}

	@Override
	public void onFailed() {

	}
});
```

#### Get a gatt descriptor of a device
```java
KnBle.gi().getDescriptor(device, "service uuid", "characteristic uuid", "descriptor uuid", new BleGetDescriptor() {
	@Override
	public void onSuccess(@NonNull BluetoothGattDescriptor descriptor) {

	}

	@Override
	public void onFailed() {

	}
});

// OR

KnBle.gi().getDescriptor(device, serviceUUID, characteristicUUID, descriptorUUID, new BleGetDescriptor() {
	@Override
	public void onSuccess(@NonNull BluetoothGattDescriptor descriptor) {

	}

	@Override
	public void onFailed() {

	}
});
```

#### Read gatt characteristic data
```java
KnBle.gi().read(device, "service uuid", "characteristic uuid", new BleReadCallback() {
	@Override
	public void onReadSuccess(@NonNull byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});

// OR

KnBle.gi().read(device, serviceUUID, characteristicUUID, new BleReadCallback() {
	@Override
	public void onReadSuccess(@NonNull byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});

// OR

KnBle.gi().read(device, service, characteristic, new BleReadCallback() {
	@Override
	public void onReadSuccess(@NonNull byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});
```

#### Write data in gatt characteristic
```java
KnBle.gi().write(device, "service uuid", "characteristic uuid", data, noResponse, new BleWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteSuccess() {

	}
});

// OR

KnBle.gi().write(device, serviceUUID, characteristicUUID, data, noResponse, new BleWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteSuccess() {

	}
});

// OR

KnBle.gi().write(device, service, characteristic, data, noResponse, new BleWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteSuccess() {

	}
});
```

#### Splitted write data in gatt characteristic
```java
KnBle.gi().splittedWrite(device, "service uuid", "characteristic uuid", data, splitSize, noResponse, sendNextWhenLastSuccess, intervalBetweenTwoPackage, new BleSplittedWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteProgress(int current, int total) {

	}

	@Override
	public void onWriteSuccess() {

	}
});

// OR

KnBle.gi().splittedWrite(device, serviceUUID, characteristicUUID, data, splitSize, noResponse, sendNextWhenLastSuccess, intervalBetweenTwoPackage, new BleSplittedWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteProgress(int current, int total) {

	}

	@Override
	public void onWriteSuccess() {

	}
});

// OR

KnBle.gi().splittedWrite(device, service, characteristic, data, splitSize, noResponse, sendNextWhenLastSuccess, intervalBetweenTwoPackage, new BleSplittedWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteProgress(int current, int total) {

	}

	@Override
	public void onWriteSuccess() {

	}
});
```

#### Enable characteristic notification
```java
KnBle.gi().enableNotify(device, "service uuid", "characteristic uuid", new BleNotifyCallback() {
	@Override
	public void onNotifyEnabled() {

	}

	@Override
	public void onNotifyDisabled() {

	}

	@Override
	public void onNotify(@NonNull byte[] data) {

	}
});

// OR

KnBle.gi().enableNotify(device, serviceUUID, characteristicUUID, new BleNotifyCallback() {
	@Override
	public void onNotifyEnabled() {

	}

	@Override
	public void onNotifyDisabled() {

	}

	@Override
	public void onNotify(@NonNull byte[] data) {

	}
});

// OR

KnBle.gi().enableNotify(device, service, characteristic, new BleNotifyCallback() {
	@Override
	public void onNotifyEnabled() {

	}

	@Override
	public void onNotifyDisabled() {

	}

	@Override
	public void onNotify(@NonNull byte[] data) {

	}
});
```

#### Disable characteristic notification
```java
KnBle.gi().disableNotify(device, "service uuid", "characteristic uuid");

// OR

KnBle.gi().disableNotify(device, serviceUUID, characteristicUUID);

// OR

KnBle.gi().disableNotify(device, service, characteristic);
```

#### Read gatt descriptor data
```java
KnBle.gi().readDesc(device, "service uuid", "characteristic uuid", "descriptor uuid", new BleReadCallback() {
	@Override
	public void onReadSuccess(@NonNull byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});

// OR

KnBle.gi().readDesc(device, serviceUUID, characteristicUUID, descriptorUUID, new BleReadCallback() {
	@Override
	public void onReadSuccess(@NonNull byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});

// OR

KnBle.gi().readDesc(device, service, characteristic, descriptor new BleReadCallback() {
	@Override
	public void onReadSuccess(@NonNull byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});
```

#### Write data in gatt descriptor
```java
KnBle.gi().writeDesc(device, "service uuid", "characteristic uuid", "descriptor uuid", data, new BleWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteSuccess() {

	}
});

// OR

KnBle.gi().writeDesc(device, serviceUUID, characteristicUUID, descriptorUUID, data, new BleWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteSuccess() {

	}
});

// OR

KnBle.gi().writeDesc(device, service, characteristic, descriptor, data, new BleWriteCallback() {
	@Override
	public void onWriteFailed() {

	}

	@Override
	public void onWriteSuccess() {

	}
});
```

#### Request connection priority (BluetoothGatt.CONNECTION_PRIORITY_xxx)
```java
KnBle.gi().requestConnectionPriority(device, connectionPriority);
```

#### Get current MTU
```java
int mtu = KnBle.gi().getMtu(device);
```

#### Request MTU change
```java
KnBle.gi().requestMtu(device, mtu);

// OR

KnBle.gi().requestMtu(device, mtu, new BleMtuChangedCallback() {
	@Override
	public void onMtuChanged(int mtu) {

	}
});
```

#### Read PHY (Android 13+)
```java
KnBle.gi().readPhy(device, new BlePhyValueCallback() {
	@Override
	public void onPhyValue(int txPhy, int rxPhy) {

	}
});
```

#### Set prefered PHY (Android 8+)
```java
KnBle.gi().setPreferredPhy(device, txPhy, rxPhy, phyOptions);

// OR

KnBle.gi().setPreferredPhy(device, txPhy, rxPhy, phyOptions, new BlePhyValueCallback() {
	@Override
	public void onPhyValue(int txPhy, int rxPhy) {

	}
});
```

#### Change BleGattCallback of a device
```java
KnBle.gi().setGattCallback(device, newCallback);
```

#### Disconnect a device
```java
KnBle.gi().disconnect(device);
```

#### Disconnect all devices
```java
KnBle.gi().disconnectAll();
```

#### Get the BluetoothGatt of a device
```java
@Nullable
BluetoothGatt gatt = KnBle.gi().getBluetoothGatt(device);
```

#### Destroy (and disconnect) a device instance
```java
KnBle.gi().destroyDevice(device);
```

#### Destroy (and disconnect) all devices instances
```java
KnBle.gi().destroyAllDevices();
```

## Others operations

#### Check if bluetooth adapter is enabled
```java
boolean enabled = KnBle.gi().isBluetoothEnabled();
```

#### Enable/Disable bluetooth adapter (Deprecated in Android 13+)
```java
// Enable
KnBle.gi().enableBluetooth(true);
// Disable
KnBle.gi().enableBluetooth(false);
```

#### Get the bluetooth adapter
```java
@Nullable
BluetoothAdapter adapter = KnBle.gi().getBluetoothAdapter();
```

#### Get the bluetooth manager service
```java
@Nullable
BluetoothManager btManager = KnBle.gi().getBluetoothManager();
```

#### Get KnBle context
```java
@Nullable
Context ctx = KnBle.gi().getContext();
```

#### Toggle DEBUG
```java
KnBle.DEBUG = false;
```

## License
```
The MIT License (MIT)

Copyright (c) 2019-2025 Florent VIALATTE

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
