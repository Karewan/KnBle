package ovh.karewan.knble.interfaces;

public interface BleReadRssiCallback {
	void onRssi(int rssi);

	/**
	 * @param error BleError
	 */
	default void onRssiFailed(int error) {}
}
