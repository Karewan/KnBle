package ovh.karewan.knble.interfaces;

/**
 * Start of a background scan (KnBle.startBackgroundScan). Only onBackgroundScanStarted() is mandatory: a lambda works.
 * The system gets the hardware filters (exact names, MACs, manufacturer IDs, beacon and service UUIDs, service data),
 * needed to receive anything with the screen off; the name prefixes and suffixes and MAC prefixes only narrow down the
 * results in parseBackgroundScan. Without any hardware filter the results only come with the screen on.
 */
public interface BleBackgroundScanCallback {
	void onBackgroundScanStarted();

	/**
	 * @param error BleScanCallback error: BT_DISABLED, LOCATION_DISABLED, PERMISSION_DENIED, SCAN_FEATURE_UNSUPPORTED
	 *              (Android 7), SCANNER_UNAVAILABLE, UNKNOWN_ERROR
	 */
	default void onBackgroundScanFailed(int error) {}
}
