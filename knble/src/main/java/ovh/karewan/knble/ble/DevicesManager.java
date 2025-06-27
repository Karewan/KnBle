package ovh.karewan.knble.ble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.struct.BleDevice;

@SuppressWarnings({"ConstantConditions", "unused"})
public class DevicesManager {
	private static volatile DevicesManager sInstance;

	// Devices OP container
	private final ConcurrentHashMap<String, DeviceOp> mDevicesOp = new ConcurrentHashMap<>();

	private DevicesManager() {}

	/**
	 * Get instance
	 * @return DevicesManager
	 */
	@NonNull
	public static DevicesManager gi() {
		if(sInstance == null) {
			synchronized(DevicesManager.class) {
				if(sInstance == null) sInstance = new DevicesManager();
			}
		}

		return sInstance;
	}

	/**
	 * Add a device
	 * @param device The device
	 * @return DeviceOp
	 */
	public DeviceOp addDevice(@NonNull BleDevice device) {
		DeviceOp deviceOp = new DeviceOp(device);
		mDevicesOp.put(device.getMac(), deviceOp);
		return deviceOp;
	}

	/**
	 * Remove a device
	 * @param device The device
	 */
	public void removeDevice(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesOp.remove(device.getMac());
		if(deviceOp != null) deviceOp.disconnect();
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
	public ConcurrentHashMap<String, DeviceOp> getDevicesOpList() {
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
	 * Disconnect all devices
	 */
	public void disconnectAll() {
		for(Map.Entry<String, DeviceOp> entry : mDevicesOp.entrySet()) entry.getValue().disconnect();
	}

	/**
	 * Destroy all devices instances
	 */
	public void destroy() {
		for(Map.Entry<String, DeviceOp> entry : mDevicesOp.entrySet()) entry.getValue().disconnect();
		mDevicesOp.clear();
	}
}
