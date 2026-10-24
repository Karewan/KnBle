package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import java.util.List;

import ovh.karewan.knble.struct.BleDevice;

/**
 * Discovery of the Bluetooth classic devices (KnBle.startClassicDiscovery). Only onDeviceFound() is mandatory: a lambda
 * works.
 */
public interface BleClassicDiscoveryCallback {
	/**
	 * A device found, with the name and the RSSI given by the inquiry. Once per device, again when its name comes later.
	 */
	void onDeviceFound(@NonNull BleDevice device);

	/**
	 * End of the discovery (about 12 s, stopClassicDiscovery, or a connection that needed the radio)
	 */
	default void onDiscoveryFinished(@NonNull List<BleDevice> devices) {}

	/**
	 * @param error BleError: BLUETOOTH_DISABLED, PERMISSION_DENIED, REFUSED (the stack did not start it: location off on
	 *              Android 6 to 11...)
	 */
	default void onDiscoveryFailed(int error) {}
}
