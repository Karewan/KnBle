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
package ovh.karewan.knble.ble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ovh.karewan.knble.interfaces.BleCheckCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

public class DevicesManager {
	// Devices OP container
	private final HashMap<String, DeviceOp> mDevicesOp = new HashMap<>();

	/**
	 * Add a device
	 * @param device The device
	 */
	public synchronized void addDevice(@NonNull BleDevice device) {
		if(!containDevice(device)) mDevicesOp.put(device.getMac(), new DeviceOp(device));
	}

	/**
	 * Remove a device
	 * @param device The device
	 */
	@SuppressWarnings("ConstantConditions")
	public synchronized void removeDevice(@NonNull BleDevice device) {
		if(containDevice(device)) {
			getDeviceOp(device).disconnect();
			mDevicesOp.remove(device.getMac());
		}
	}

	/**
	 * Check if contain the device
	 * @param device The device
	 * @return boolean
	 */
	public boolean containDevice(@NonNull BleDevice device) {
		return mDevicesOp.containsKey(device.getMac());
	}

	/**
	 * Get a device OP
	 * @param device The device
	 * @return BleDeviceOp
	 */
	@Nullable
	public DeviceOp getDeviceOp(@NonNull BleDevice device) {
		return mDevicesOp.get(device.getMac());
	}

	/**
	 * Return all OP devices
	 * @return mDevicesOp
	 */
	@NonNull
	public HashMap<String, DeviceOp> getDevicesOpList() {
		return mDevicesOp;
	}

	/**
	 * Return all devices
	 * @return Devices
	 */
	@NonNull
	public List<BleDevice> getDevicesList() {
		List<BleDevice> devicesList = new ArrayList<>();
		for(Map.Entry<String, DeviceOp> entry : mDevicesOp.entrySet()) devicesList.add(entry.getValue().getDevice());
		return devicesList;
	}

	/**
	 * Check if a device is connected
	 * @param device The device
	 * @return boolean
	 */
	@SuppressWarnings("ConstantConditions")
	public boolean isConnected(@NonNull BleDevice device) {
		if(!containDevice(device)) return false;
		return getDeviceOp(device).isConnected();
	}

	/**
	 * Get the current device state
	 * @param device The device
	 * @return The state
	 */
	@SuppressWarnings("ConstantConditions")
	public int getDeviceState(@NonNull BleDevice device) {
		if(!containDevice(device)) return BleGattCallback.DISCONNECTED;
		return getDeviceOp(device).getState();
	}

	/**
	 * Get list of connected devices
	 * @return Connected devices
	 */
	@NonNull
	public List<BleDevice> getConnectedDevices() {
		List<BleDevice> connectedDevices = new ArrayList<>();
		for(Map.Entry<String, DeviceOp> entry : mDevicesOp.entrySet()) {
			if(entry.getValue().isConnected()) connectedDevices.add(entry.getValue().getDevice());
		}
		return connectedDevices;
	}

	/**
	 * Get the last gatt status of a device
	 * @param device The device
	 * @return The last gatt status
	 */
	@SuppressWarnings("ConstantConditions")
	public int getLastGattStatusOfDevice(@NonNull BleDevice device) {
		if(!containDevice(device)) return 0;
		return getDeviceOp(device).getLastGattStatus();
	}

	/**
	 * Request connection priority
	 * @param device The device
	 * @param connectionPriority The connection priority
	 */
	public void requestConnectionPriority(@NonNull BleDevice device, int connectionPriority) {
		if(!containDevice(device)) return;
		getDeviceOp(device).requestConnectionPriority(connectionPriority);
	}

	/**
	 * Connect to a device
	 * @param device The device
	 * @param callback The callback
	 */
	@SuppressWarnings("ConstantConditions")
	public void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		addDevice(device);
		getDeviceOp(device).connect(callback);
	}

	/**
	 * Change BleGattCallback of a device
	 * @param device The device
	 * @param callback The callback
	 */
	@SuppressWarnings("ConstantConditions")
	public void setGattCallback(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		if(!containDevice(device)) return;
		getDeviceOp(device).setGattCallback(callback);
	}

	/**
	 * Check if a service exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param callback The callback
	 */
	@SuppressWarnings("ConstantConditions")
	public void hasService(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull BleCheckCallback callback) {
		if(!containDevice(device)) return;
		getDeviceOp(device).hasService(serviceUUID, callback);
	}

	/**
	 * Check if a characteristic exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	@SuppressWarnings("ConstantConditions")
	public void hasCharacteristic(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleCheckCallback callback) {
		if(!containDevice(device)) return;
		getDeviceOp(device).hasCharacteristic(serviceUUID, characteristicUUID, callback);
	}

	/**
	 * Write data into a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param split Split data if data > 20 bytes
	 * @param spliSize packet split size
	 * @param sendNextWhenLastSuccess If split send next package when last sucess
	 * @param intervalBetweenTwoPackage Interval between two package
	 * @param callback The callback
	 */
	@SuppressWarnings("ConstantConditions")
	public void write(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] data, boolean split, int spliSize, boolean sendNextWhenLastSuccess, long intervalBetweenTwoPackage, @NonNull BleWriteCallback callback) {
		if(!containDevice(device)) {
			callback.onWriteFailed();
			return;
		}

		getDeviceOp(device).write(serviceUUID, characteristicUUID, data, split, spliSize, sendNextWhenLastSuccess, intervalBetweenTwoPackage, callback);
	}

	/**
	 * Read data from a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The call back
	 */
	@SuppressWarnings("ConstantConditions")
	public void read(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		if(!containDevice(device)) {
			callback.onReadFailed();
			return;
		}

		getDeviceOp(device).read(serviceUUID, characteristicUUID, callback);
	}

	/**
	 * Disconnect a device
	 * @param device The device
	 */
	@SuppressWarnings("ConstantConditions")
	public void disconnect(@NonNull BleDevice device) {
		if(containDevice(device)) getDeviceOp(device).disconnect();
	}

	/**
	 * Disconnect all devices
	 */
	public void disconnectAll() {
		for(Map.Entry<String, DeviceOp> entry : mDevicesOp.entrySet()) entry.getValue().disconnect();
	}

	/**
	 * Destroy all devices instances
	 */
	public synchronized void destroy() {
		for(Map.Entry<String, DeviceOp> entry : mDevicesOp.entrySet()) entry.getValue().disconnect();
		mDevicesOp.clear();
	}
}
