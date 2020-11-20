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

@SuppressWarnings({"MissingPermission", "unused"})
public class KnBle {
	private WeakReference<Context> mContext;
	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	public static volatile boolean DEBUG = false;

	private KnBle() {}

	private static class Loader {
		static final KnBle INSTANCE = new KnBle();
	}

	@NonNull
	public static KnBle getInstance() {
		return Loader.INSTANCE;
	}

	/**
	 * Init KnBle
	 * @param context The application context
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	public void init(@NonNull Context context) {
		// Check if device support BLE
		if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return;

		// Get the bluetooth manager service
		mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if(mBluetoothManager == null) return;

		// Get the bluetooth adapter
		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if(mBluetoothAdapter == null) return;

		// Init the scanner
		Scanner.getInstance();

		// Init the devices manager
		DevicesManager.getInstance();

		// Store context into weakref to avoid memory leaks
		mContext = new WeakReference<>(context);
	}

	/**
	 * Check if KnBle has been successfully initialized
	 * @return boolean
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean isInit() {
		return mContext != null && mContext.get() != null;
	}

	/**
	 * Return context
	 * @return Context
	 */
	@Nullable
	public Context getContext() {
		if(mContext == null) return null;
		return mContext.get();
	}

	/**
	 * Return bluetooth manager service
	 * @return BluetoothManager
	 */
	@Nullable
	public BluetoothManager getBluetoothManager() {
		return mBluetoothManager;
	}

	/**
	 * Return bluetooth adapter
	 * @return BluetoothAdapter
	 */
	@Nullable
	public BluetoothAdapter getBluetoothAdapter() {
		return mBluetoothAdapter;
	}

	/**
	 * Check if bluetooth adapter is enabled
	 * @return boolean
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean isBluetoothEnabled() {
		if(mBluetoothAdapter == null) return false;
		return mBluetoothAdapter.isEnabled();
	}

	/**
	 * Enable/Disable bluetooth
	 * @param enable Enable or disable
	 */
	public void enableBluetooth(boolean enable) {
		if(mBluetoothAdapter == null) return;

		if(enable) mBluetoothAdapter.enable();
		else mBluetoothAdapter.disable();
	}

	/**
	 * Get a BleDevice object from a mac address
	 * @param mac The mac address
	 * @return BleDevice
	 */
	@Nullable
	public BleDevice getBleDeviceFromMac(@NonNull String mac) {
		if(mBluetoothAdapter == null) return null;

		try {
			return new BleDevice(mBluetoothAdapter.getRemoteDevice(mac));
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Return scanning status
	 * @return boolean
	 */
	public boolean isScanning() {
		return Scanner.getInstance().isScanning();
	}

	/**
	 * Get last scan error
	 * @return int from ScanCallback
	 */
	public int getLastScanError() {
		return Scanner.getInstance().getLastError();
	}

	/**
	 * Set the scan filter
	 * @param scanFilter ScanFilter
	 */
	public void setScanFilter(@NonNull ScanFilters scanFilter) {
		Scanner.getInstance().setScanFilter(scanFilter);
	}

	/**
	 * Return current ScanFilters
	 * @return ScanFilters
	 */
	public ScanFilters getScanFilters() {
		return Scanner.getInstance().getScanFilters();
	}

	/**
	 * Set the scan settings
	 * @param scanSettings ScanSettings
	 */
	public void setScanSettings(@NonNull ScanSettings scanSettings) {
		Scanner.getInstance().setScanSettings(scanSettings);
	}

	/**
	 * Return current ScanSettings
	 * @return ScanSettings
	 */
	public ScanSettings getScanSettings() {
		return Scanner.getInstance().getScanSettings();
	}

	/**
	 * Start devices scan
	 * @param callback BleScanCallback
	 */
	public void startScan(@NonNull BleScanCallback callback) {
		Scanner.getInstance().startScan(callback);
	}

	/**
	 * Stop devices scan
	 */
	public void stopScan() {
		Scanner.getInstance().stopScan();
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public HashMap<String, BleDevice> getScannedDevices() {
		return Scanner.getInstance().getScannedDevices();
	}

	/**
	 * Clear scanned devices list
	 */
	public void clearScannedDevices() {
		Scanner.getInstance().clearScannedDevices();
	}

	/**
	 * Stop and reset devices scan
	 * @param resetSettings Reset settings
	 * @param resetFilters Reset filters
 	 */
	public void resetScan(boolean resetSettings, boolean resetFilters) {
		Scanner.getInstance().reset(resetSettings, resetFilters);
	}

	/**
	 * Get connected devices list
	 * @return Connected devices
	 */
	@NonNull
	public List<BleDevice> getConnectedDevices() {
		return DevicesManager.getInstance().getConnectedDevices();
	}

	/**
	 * Check if a device is connected
	 * @param device The device
	 * @return boolean
	 */
	public boolean isConnected(@NonNull BleDevice device) {
		return DevicesManager.getInstance().isConnected(device);
	}

	/**
	 * Get device connection state
	 * @param device The device
	 * @return The state
	 */
	public int getDeviceConnState(@NonNull BleDevice device) {
		return DevicesManager.getInstance().getDeviceState(device);
	}

	/**
	 * Get the last gatt status of a device
	 * @param device The device
	 * @return The last gatt status
	 */
	public int getLastGattStatusOfDevice(@NonNull BleDevice device) {
		return DevicesManager.getInstance().getLastGattStatusOfDevice(device);
	}

	/**
	 * Connect to a device
	 * @param device The device
	 * @param callback The callback
	 */
	public void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		if(!isInit()) {
			callback.onConnectFailed();
			return;
		}

		DevicesManager.getInstance().connect(device, callback);
	}

	/**
	 * Request connection priority
	 * @param device The device
	 * @param connectionPriority The connection priority
	 */
	public void requestConnectionPriority(@NonNull BleDevice device, int connectionPriority) {
		DevicesManager.getInstance().requestConnectionPriority(device, connectionPriority);
	}

	/**
	 * Request MTU
	 * @param device The device
	 * @param mtu The MTU
	 */
	public void requestMtu(@NonNull BleDevice device, int mtu) {
		DevicesManager.getInstance().requestMtu(device, mtu);
	}

	/**
	 * Get MTU of a device
	 * @param device The device
	 * @return The MTU
	 */
	public int getMtu(@NonNull BleDevice device) {
		return DevicesManager.getInstance().getMtu(device);
	}

	/**
	 * Check if a service exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param callback The callback
	 */
	public void hasService(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull BleCheckCallback callback) {
		DevicesManager.getInstance().hasService(device, serviceUUID, callback);
	}

	/**
	 * Check if a characteristic exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public void hasCharacteristic(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleCheckCallback callback) {
		DevicesManager.getInstance().hasCharacteristic(device, serviceUUID, characteristicUUID, callback);
	}

	/**
	 * Set BleGattCallback of a device
	 * @param device The device
	 * @param callback The callback
	 */
	public void setGattCallback(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		DevicesManager.getInstance().setGattCallback(device, callback);
	}

	/**
	 * Write data into a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param callback The callback
	 */
	public void write(@NonNull BleDevice device,
					  @NonNull String serviceUUID,
					  @NonNull String characteristicUUID,
					  @NonNull byte[] data,
					  @NonNull BleWriteCallback callback) {

		write(device, serviceUUID, characteristicUUID, data, false, 20, true, 0, callback);
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
	public void write(@NonNull BleDevice device,
					  @NonNull String serviceUUID,
					  @NonNull String characteristicUUID,
					  @NonNull byte[] data,
					  boolean split,
					  int spliteSize,
					  boolean sendNextWhenLastSuccess,
					  long intervalBetweenTwoPackage,
					  @NonNull BleWriteCallback callback) {

		DevicesManager.getInstance().write(device, serviceUUID, characteristicUUID, data, split, spliteSize, sendNextWhenLastSuccess, intervalBetweenTwoPackage, callback);
	}

	/**
	 * Read data from a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The call back
	 */
	public void read(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		DevicesManager.getInstance().read(device, serviceUUID, characteristicUUID, callback);
	}

	/**
	 * Disconnect a device
	 * @param device The device
	 */
	public void disconnect(@NonNull BleDevice device) {
		DevicesManager.getInstance().disconnect(device);
	}

	/**
	 * Disconnect all devices
	 */
	public void disconnectAll() {
		DevicesManager.getInstance().disconnectAll();
	}

	/**
	 * Destroy all devices instances
	 */
	public void destroyAllDevices() {
		DevicesManager.getInstance().destroy();
	}
}
