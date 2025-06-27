package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.struct.BleDevice;

public interface BleScanCallback {
	int NO_ERROR = -1; // No error
	int BT_DISABLED = 0; // BT disabled or BT Problem
	int LOCATION_DISABLED = 1; // Location services disabled (Needed for Android 6+)
	int SCANNER_UNAVAILABLE = 2; // Scanner not available
	int UNKNOWN_ERROR = 3; // Unknown error
	int SCAN_FEATURE_UNSUPPORTED = 4; // A requested scan feature is not available on this device

	/**
	 * onScanStarted
	 */
	void onScanStarted();

	/**
	 * onScanFailed
	 * @param error The error
	 */
	void onScanFailed(int error);

	/**
	 * onScanResult
	 * @param bleDevice Discovered device
	 */
	void onScanResult(@NonNull BleDevice bleDevice);

	/**
	 * onDeviceUpdated
	 * @param bleDevice Updated device
	 */
	void onDeviceUpdated(@NonNull BleDevice bleDevice);

	/**
	 * onScanFinished
	 * @param scanResult List with all discovered devices
	 */
	void onScanFinished(@NonNull ConcurrentHashMap<String, BleDevice> scanResult);
}
