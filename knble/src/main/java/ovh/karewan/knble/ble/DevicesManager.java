package ovh.karewan.knble.ble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.struct.BleDevice;

public class DevicesManager {
	private final ConcurrentHashMap<Long, DeviceOperation> mDevicesOp = new ConcurrentHashMap<>();

	/**
	 * Add a device
	 * @param device The device
	 * @return DeviceOperation
	 */
	@NonNull
	public DeviceOperation addDevice(@NonNull BleDevice device) {
		return mDevicesOp.computeIfAbsent(device.getMacLong(), ml -> new DeviceOperation(device));
	}

	/**
	 * Remove a device
	 * @param device The device
	 */
	public void removeDevice(@NonNull BleDevice device) {
		DeviceOperation deviceOp = mDevicesOp.remove(device.getMacLong());
		if (deviceOp != null) deviceOp.disconnect(true);
	}

	/**
	 * Get a device OP
	 * @param device The device
	 * @return DeviceOperation
	 */
	@Nullable
	public DeviceOperation getDeviceOp(@NonNull BleDevice device) {
		return mDevicesOp.get(device.getMacLong());
	}

	/**
	 * Get list of connected devices
	 * @return Connected devices
	 */
	@NonNull
	public List<BleDevice> getConnectedDevices() {
		List<BleDevice> connectedDevices = new ArrayList<>();

		for (DeviceOperation deviceOp : mDevicesOp.values()) {
			if(deviceOp.isConnected()) connectedDevices.add(deviceOp.getDevice());
		}

		return connectedDevices;
	}

	/**
	 * Disconnect all devices
	 */
	public void disconnectAll() {
		for (DeviceOperation deviceOp : mDevicesOp.values()) deviceOp.disconnect(false);
	}

	/**
	 * Destroy all devices instances
	 */
	public void destroyAll() {
		for (DeviceOperation deviceOp : mDevicesOp.values()) deviceOp.disconnect(true);
		mDevicesOp.clear();
	}
}
