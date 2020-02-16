# KnBle

[![](https://jitpack.io/v/Karewan/KnBle.svg)](https://jitpack.io/#Karewan/KnBle)
[![API](https://img.shields.io/badge/API-19%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=19)

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
		sourceCompatibility JavaVersion.VERSION_1_8
		targetCompatibility JavaVersion.VERSION_1_8
	}
}

dependencies {
	implementation 'com.github.Karewan:KnBle:2.0.0'
}
```

Do not forget to add internet permissions in manifest
```xml
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<!-- Android 6+ -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
```

Then initialize
```java
KnBle.getInstance().init(getApplicationContext());
```

Verify is init correctly, return false if device is not BLE compatible
```java
boolean isInit = KnBle.getInstance().isInit();
```

## Scanning operations

#### Start scan
```java
bleScanCallback = new BleScanCallback() {
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
	public void onScanFinished(@NonNull HashMap<String, BleDevice> scanResult) {

	}
};

KnBle.getInstance().startScan(bleScanCallback);
```

#### Stop scan
```java
KnBle.getInstance().stopScan();
```

#### Check if currently scanning
```java
boolean isScanning = KnBle.getInstance().isScanning();
```

#### Get last scan error
```java
int error = KnBle.getInstance().getLastError();
```

#### Get current scan settings
```java
ScanSettings settings = KnBle.getInstance().getScanSettings();
```

#### Get current scan filters
```java
ScanFilters filters = KnBle.getInstance().getScanFilters();
```

#### Get all scanned devices (string is the mac address)
```java
HashMap<String, BleDevice> devices = KnBle.getInstance().getScannedDevices();
```

#### Clear scanned devices
```java
KnBle.getInstance().clearScannedDevices();
```

#### Stop and reset scan completely (boolean resetSettings, boolean resetFilters)
```java
KnBle.getInstance().resetScan(true, true);
```

## Device operations

#### Get device from MAC address
```java
BleDevice device = KnBle.getInstance().getBleDeviceFromMac("FF:FF:FF:FF:FF:FF");
```

## Others operations

#### Check if bluetooth adapter is enabled
```java
boolean enabled = KnBle.getInstance().isBluetoothEnabled();
```

#### Enable/Disable bluetooth adapter
```java
// Enable
KnBle.getInstance().enableBluetooth(true);
// Disable
KnBle.getInstance().enableBluetooth(false);
```

#### Get the bluetooth adapter
```java
BluetoothAdapter adapter = KnBle.getInstance().getBluetoothAdapter();
```

#### Get the bluetooth manager service
```java
BluetoothManager btManager = KnBle.getInstance().getBluetoothManager();
```

#### Get KnBle context
```java
Context ctx = KnBle.getInstance().getContext();
```

## License
```
The MIT License (MIT)

Copyright (c) 2019-2020 Florent VIALATTE

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
