# KnBle

[![](https://jitpack.io/v/Karewan/KnBle.svg)](https://jitpack.io/#Karewan/KnBle)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)
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
		sourceCompatibility JavaVersion.VERSION_11
		targetCompatibility JavaVersion.VERSION_11
	}
}

dependencies {
	implementation 'com.github.Karewan:KnBle:2.3.2'
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

Then initialize
```java
boolean success = KnBle.gi().init(getApplicationContext());
```

Verify is init correctly, return false if device is not BLE compatible
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
	public void onScanFinished(@NonNull HashMap<String, BleDevice> scanResult) {

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

#### Get all scanned devices (string is the mac address)
```java
HashMap<String, BleDevice> devices = KnBle.gi().getScannedDevices();
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
BleDevice device = KnBle.gi().getBleDeviceFromMac("FF:FF:FF:FF:FF:FF");
```

#### Get list of connected devices
```java
List<BleDevice> devices = KnBle.gi().getConnectedDevices();
```

#### Check if device is connected
```java
boolean connected = KnBle.gi().isConnected(device);
```

#### Connect to a device
```java
KnBle.gi().connect(device, new BleGattCallback() {
	@Override
	public void onConnecting() {

	}

	@Override
	public void onConnectFailed() {

	}

	@Override
	public void onConnectSuccess(List<BluetoothGattService> services) {

	}

	@Override
	public void onDisconnected() {

	}
});
```

#### Check if device has a gatt service
```java
KnBle.gi().hasService(device, "service uuid",  new BleCheckCallback() {
	@Override
	public void onResponse(boolean res) {

	}
});
```

#### Check if device has a gatt characteristic
```java
KnBle.gi().hasCharacteristic(device, "service uuid", "characteristic uuid",  new BleCheckCallback() {
	@Override
	public void onResponse(boolean res) {

	}
});
```

#### Write data in gatt characteristic
```java
KnBle.gi().write(device, "service uuid", "characteristic uuid", data, new BleWriteCallback() {
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

// true=split data
// 20=split into packet of
// true=if true send when android set last packet sent as success else send immediately
// 25=interval between two packet
KnBle.gi().write(device, "service uuid", "characteristic uuid", data, true, 20, true, 25, new BleWriteCallback() {
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

#### Read gatt characteristic data
```java
KnBle.gi().read(device, "service uuid", "characteristic uuid", new BleReadCallback() {
	@Override
	public void onReadSuccess(byte[] data) {

	}

	@Override
	public void onReadFailed() {

	}
});
```

#### Request connection priority
```java
KnBle.gi().requestConnectionPriority(device, connectionPriority);
```

#### Request MTU change
```java
KnBle.gi().requestMtu(device, mtu);
```

#### Get current MTU
```java
int mtu = KnBle.gi().getMtu(device);
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

#### Get device connection state
```java
int state = KnBle.gi().getDeviceConnState(device);

// BleGattCallback.DISCONNECTED
// BleGattCallback.CONNECTING
// BleGattCallback.CONNECTED
```

#### Get last gatt status code of a device
```java
int status = KnBle.gi().getLastGattStatusOfDevice(device);
```

#### Destroy all devices instances
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
BluetoothAdapter adapter = KnBle.gi().getBluetoothAdapter();
```

#### Get the bluetooth manager service
```java
BluetoothManager btManager = KnBle.gi().getBluetoothManager();
```

#### Get KnBle context
```java
Context ctx = KnBle.gi().getContext();
```

#### Toggle DEBUG
```java
KnBle.DEBUG = false;
```

## License
```
The MIT License (MIT)

Copyright (c) 2019-2023 Florent VIALATTE

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
