package ovh.karewan.knble.interfaces;

/**
 * Bluetooth turned on or off (KnBle.addBluetoothStateListener), on the main thread (or the Looper of KnBleSettings)
 */
public interface BleStateListener {
	/**
	 * @param enabled true when Bluetooth is on, false when it is turning off or off
	 */
	void onBluetoothStateChanged(boolean enabled);
}
