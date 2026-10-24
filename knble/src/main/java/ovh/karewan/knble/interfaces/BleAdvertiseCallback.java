package ovh.karewan.knble.interfaces;

/**
 * Advertising of the phone (KnBle.startAdvertising). Only onAdvertisingStarted() is mandatory: a lambda works. Nothing
 * comes after stopAdvertising, nor after another startAdvertising (except CANCELLED).
 */
public interface BleAdvertiseCallback {
	/**
	 * Advertising (again after Bluetooth came back: KnBle starts it again by itself)
	 */
	void onAdvertisingStarted();

	/**
	 * @param error BleError: DATA_TOO_LARGE, NOT_SUPPORTED (no peripheral role on this phone), BUSY (no advertising set
	 *              left), BLUETOOTH_DISABLED (also turned off before the advertising started), PERMISSION_DENIED
	 *              (BLUETOOTH_ADVERTISE on Android 12+), TIMEOUT (no answer of the system in 5 s), CANCELLED (another
	 *              startAdvertising before the result of this one), INTERNAL
	 */
	default void onAdvertisingFailed(int error) {}

	/**
	 * End of the timeout of BleAdvertiseData, or Bluetooth turned off (not after stopAdvertising)
	 */
	default void onAdvertisingStopped() {}
}
