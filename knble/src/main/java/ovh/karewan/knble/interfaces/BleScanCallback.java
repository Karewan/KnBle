package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import java.util.List;

import ovh.karewan.knble.struct.BleDevice;

/**
 * Scan. Only onScanResult() is mandatory: a lambda works.
 */
public interface BleScanCallback {
	int NO_ERROR = -1; // No error
	int BT_DISABLED = 0; // BT disabled or BT Problem
	int LOCATION_DISABLED = 1; // Location services disabled (Android 6 to 11, or a scan used for the location)
	int SCANNER_UNAVAILABLE = 2; // Scanner not available
	int UNKNOWN_ERROR = 3; // Unknown error
	int SCAN_FEATURE_UNSUPPORTED = 4; // A requested scan feature is not available on this device
	// Scan permission missing: BLUETOOTH_SCAN on Android 12+ (plus ACCESS_FINE_LOCATION when the scan may be used for the
	// location: see ScanSettings.isNeverForLocation), the location before (fine on Android 10 and 11, coarse or fine
	// before). Checked at the start: Android would deliver nothing, without an error.
	int PERMISSION_DENIED = 5;

	/**
	 * A device seen for the first time during this scan (or again after onDeviceLost)
	 * @param bleDevice Discovered device
	 */
	void onScanResult(@NonNull BleDevice bleDevice);

	default void onScanStarted() {}

	/**
	 * The scan ended on an error (one of the constants above), onScanFinished follows when it was running
	 */
	default void onScanFailed(int error) {}

	/**
	 * New data of a device already reported (RSSI, advertising, name, time). A slow app gets the latest data of each
	 * device, not a backlog.
	 * @param bleDevice Updated device
	 */
	default void onDeviceUpdated(@NonNull BleDevice bleDevice) {}

	/**
	 * A device reported before is no longer heard: after 5 of its usual intervals without a report matching the filters
	 * (between 10 s and 2 min of scanning, 30 s when heard once; longer on a phone that filters the duplicates and with
	 * batched results), or when the controller says so (CALLBACK_TYPE_MATCH_LOST: KnBle then leaves the losses to it).
	 * Its next report comes as onScanResult.
	 * @param bleDevice Last data of the device
	 */
	default void onDeviceLost(@NonNull BleDevice bleDevice) {}

	/**
	 * End of the scan (stopScan, timeout, failure, new startScan, resetScan)
	 * @param scanResult Every device seen during the scan, except those lost for more than 5 min (forgotten)
	 */
	default void onScanFinished(@NonNull List<BleDevice> scanResult) {}
}
