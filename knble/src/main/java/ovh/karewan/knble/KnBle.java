/*
	KnBle

	Released under the MIT License (MIT)

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
 */
package ovh.karewan.knble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ovh.karewan.knble.ble.DevicesManager;
import ovh.karewan.knble.interfaces.BleCheckCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.scan.Scanner;
import ovh.karewan.knble.struct.BleDevice;

@SuppressWarnings("MissingPermission")
public class KnBle {
	private static boolean mInitCorrectly = false;
	private static WeakReference<Context> mContext;
	private static BluetoothManager mBluetoothManager;
	private static BluetoothAdapter mBluetoothAdapter;
	private static Scanner mScanner;
	private static DevicesManager mDevicesManager;

	/**
	 * Private class constructor to prevent instantiation of this class
	 */
	private KnBle() {}

	/**
	 * Initiliaze KwBle
	 * @param context The context
	 */
	public static void initialize(@NonNull Context context) {
		// Destroy previous instance
		destroy();

		// Store context into weakref to avoid memory leaks
		mContext = new WeakReference<>(context);

		// Check if device support BLE
		if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			mInitCorrectly = false;
			return;
		}

		// Get the bluetooth manager service
		mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if(mBluetoothManager == null) {
			mInitCorrectly = false;
			return;
		}

		// Get the bluetooth adapter
		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if(mBluetoothAdapter == null) {
			mInitCorrectly = false;
			return;
		}

		// Init the scanner
		mScanner = new Scanner();

		// Init the devices manager
		mDevicesManager = new DevicesManager();

		// Init success
		mInitCorrectly = true;
	}

	/**
	 * Check if library is init correctly
	 * @return mInitCorrectly
	 */
	public static boolean isInitCorrectly() {
		return mInitCorrectly;
	}

	/**
	 * Bluetooth enabled ?
	 * @return boolean
	 */
	public static boolean isBluetoothEnabled() {
		if(mBluetoothAdapter == null) return false;

		return mBluetoothAdapter.isEnabled();
	}

	/**
	 * Enable/Disable bluetooth
	 * @param enable Enable or disable
	 */
	public static void enableBluetooth(boolean enable) {
		if(mBluetoothAdapter == null) return;

		if(enable) mBluetoothAdapter.enable();
		else mBluetoothAdapter.disable();
	}

	/**
	 * Get the bluetooth manager
	 * @return BluetoothManager
	 */
	@Nullable
	public static BluetoothManager getBluetoothManager() {
		return mBluetoothManager;
	}

	/**
	 * Get the bluetooth adapter
	 * @return BluetoothAdapter
	 */
	@Nullable
	public static BluetoothAdapter getBluetoothAdapter() {
		return mBluetoothAdapter;
	}

	/**
	 * Get the context
	 * @return Context
	 */
	@Nullable
	public static Context getContext() {
		if(mContext == null) return null;
		return mContext.get();
	}

	/**
	 * Get a BleDevice object from a mac address
	 * @param mac The mac address
	 * @return BleDevice
	 */
	@Nullable
	public static BleDevice getBleDeviceFromMac(@NonNull String mac) {
		if(mBluetoothAdapter == null) return null;

		try {
			return new BleDevice(mBluetoothAdapter.getRemoteDevice(mac));
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Is scanning ?
	 * @return boolean
	 */
	public static boolean isScanning() {
		if(!mInitCorrectly) return false;
		return mScanner.isScanning();
	}

	/**
	 * Get last scan error
	 * @return int from ScanCallback
	 */
	public static int getLastScanError() {
		if(!mInitCorrectly) return BleScanCallback.NO_ERROR;
		return mScanner.getLastError();
	}

	/**
	 * Set the scan filter
	 * @param scanFilter ScanFilter
	 */
	public static void setScanFilter(@NonNull ScanFilters scanFilter) {
		if(!mInitCorrectly) return;
		mScanner.setScanFilter(scanFilter);
	}

	/**
	 * Set the scan settings
	 * @param scanSettings ScanSettings
	 */
	public static void setScanSettings(@NonNull ScanSettings scanSettings) {
		if(!mInitCorrectly) return;
		mScanner.setScanSettings(scanSettings);
	}

	/**
	 * Start devices scan
	 * @param callback BleScanCallback
	 */
	public static void startScan(@NonNull BleScanCallback callback) {
		if(!mInitCorrectly) {
			callback.onScanFailed(BleScanCallback.BT_DISABLED);
			return;
		}

		mScanner.startScan(callback);
	}

	/**
	 * Stop devices scan
	 */
	public static void stopScan() {
		if(!mInitCorrectly) return;
		mScanner.stopScan();
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public static HashMap<String, BleDevice> getScannedDevices() {
		if(!mInitCorrectly) return new HashMap<>();
		return mScanner.getScannedDevices();
	}

	/**
	 * Get connected devices list
	 * @return Connected devices
	 */
	@NonNull
	public static List<BleDevice> getConnectedDevices() {
		if(!mInitCorrectly) return new ArrayList<>();
		return mDevicesManager.getConnectedDevices();
	}

	/**
	 * Check if a device is connected
	 * @param device The device
	 * @return boolean
	 */
	public static boolean isConnected(@NonNull BleDevice device) {
		if(!mInitCorrectly) return false;
		return mDevicesManager.isConnected(device);
	}

	/**
	 * Get device connection state
	 * @param device The device
	 * @return The state
	 */
	public static int getDeviceConnState(@NonNull BleDevice device) {
		if(!mInitCorrectly) return BleGattCallback.DISCONNECTED;
		return mDevicesManager.getDeviceState(device);
	}

	/**
	 * Get the last gatt status of a device
	 * @param device The device
	 * @return The last gatt status
	 */
	public static int getLastGattStatusOfDevice(@NonNull BleDevice device) {
		if(!mInitCorrectly) return 0;
		return mDevicesManager.getLastGattStatusOfDevice(device);
	}

	/**
	 * Connect to a device
	 * @param device The device
	 * @param callback The callback
	 */
	public static void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		if(!mInitCorrectly) {
			callback.onConnectFailed();
			return;
		}

		mDevicesManager.connect(device, callback);
	}

	/**
	 * Request connection priority
	 * @param device The device
	 * @param connectionPriority The connection priority
	 */
	public static void requestConnectionPriority(@NonNull BleDevice device, int connectionPriority) {
		if(!mInitCorrectly) return;
		mDevicesManager.requestConnectionPriority(device, connectionPriority);
	}

	/**
	 * Check if a service exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param callback The callback
	 */
	public static void hasService(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull BleCheckCallback callback) {
		if(!mInitCorrectly) return;
		mDevicesManager.hasService(device, serviceUUID, callback);
	}

	/**
	 * Check if a characteristic exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public static void hasCharacteristic(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleCheckCallback callback) {
		if(!mInitCorrectly) return;
		mDevicesManager.hasCharacteristic(device, serviceUUID, characteristicUUID, callback);
	}

	/**
	 * Set BleGattCallback of a device
	 * @param device The device
	 * @param callback The callback
	 */
	public static void setGattCallback(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		if(!mInitCorrectly) return;
		mDevicesManager.setGattCallback(device, callback);
	}

	/**
	 * Write data into a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param callback The callback
	 */
	public static void write(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] data, @NonNull BleWriteCallback callback) {
		write(device, serviceUUID, characteristicUUID, data, true, 20, true, 0, callback);
	}

	/**
	 * Write data into a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param split Split data if data > 20 bytes
	 * @param spliteSize Packet split size
	 * @param sendNextWhenLastSuccess If split send next package when last sucess
	 * @param intervalBetweenTwoPackage Interval between two package
	 * @param callback The callback
	 */
	public static void write(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] data, boolean split, int spliteSize, boolean sendNextWhenLastSuccess, long intervalBetweenTwoPackage, @NonNull BleWriteCallback callback) {
		if(!mInitCorrectly) {
			callback.onWriteFailed();
			return;
		}

		mDevicesManager.write(device, serviceUUID, characteristicUUID, data, split, spliteSize, sendNextWhenLastSuccess, intervalBetweenTwoPackage, callback);
	}

	/**
	 * Read data from a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The call back
	 */
	public static void read(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		if(!mInitCorrectly) {
			callback.onReadFailed();
			return;
		}

		mDevicesManager.read(device, serviceUUID, characteristicUUID, callback);
	}

	/**
	 * Disconnect a device
	 * @param device The device
	 */
	public static void disconnect(@NonNull BleDevice device) {
		if(!mInitCorrectly) return;
		mDevicesManager.disconnect(device);
	}

	/**
	 * Disconnect all devices
	 */
	public static void disconnectAll() {
		if(!mInitCorrectly) return;
		mDevicesManager.disconnectAll();
	}

	/**
	 * Destroy and cleanup
	 */
	public static void destroy() {
		// Un-init
		mInitCorrectly = false;

		// Destroy the scanner
		if(mScanner != null) {
			mScanner.destroy();
			mScanner = null;
		}

		// Disconnect all devices and cleanup
		if(mDevicesManager != null) {
			mDevicesManager.destroy();
			mDevicesManager = null;
		}

		// Cleanup
		mBluetoothAdapter = null;
		mBluetoothManager = null;
		mContext = null;
	}
}
