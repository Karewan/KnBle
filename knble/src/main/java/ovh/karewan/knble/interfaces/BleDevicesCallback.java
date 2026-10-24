package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import java.util.List;

import ovh.karewan.knble.struct.BleDevice;

/**
 * A list of devices, read by KnBle off the main thread (KnBle.getAssociatedDevices)
 */
public interface BleDevicesCallback {
	void onDevices(@NonNull List<BleDevice> devices);
}
