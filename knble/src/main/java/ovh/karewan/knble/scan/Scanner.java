package ovh.karewan.knble.scan;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.utils.Utils;

@SuppressWarnings("MissingPermission")
public class Scanner {
	private final Handler mHandler = new Handler();
	private final HashMap<String, BleDevice> mScannedDevices = new HashMap<>();
	private boolean mIsScanning = false;
	private int mLastError = BleScanCallback.NO_ERROR;
	private ScanSettings mScanSettings = new ScanSettings.Builder().build();
	private ScanFilters mScanFilters = new ScanFilters.Builder().build();
	private BleScanCallback mCallback;
	private BluetoothAdapter.LeScanCallback mLeScanCallback; // Android 4.4+
	private android.bluetooth.le.ScanCallback mScanCallback; // Android 6+
	private BluetoothLeScanner mBluetoothLeScanner; // Android 6+

	/**
	 * Set the scan filters
	 * @param scanFilters ScanFilters
	 */
	public synchronized void setScanFilter(@NonNull ScanFilters scanFilters) {
		mScanFilters = scanFilters;
	}

	/**
	 * Get the current scan filters
	 * @return ScanFilter
	 */
	@NonNull
	public synchronized ScanFilters getScanFilters() {
		return mScanFilters;
	}

	/**
	 * Set the scan settings
	 * @param scanSettings ScanSettings
	 */
	public synchronized void setScanSettings(@NonNull ScanSettings scanSettings) {
		mScanSettings = scanSettings;
	}

	/**
	 * Get the current scan settings
	 * @return ScanSettings
	 */
	@NonNull
	public synchronized ScanSettings getScanSettings() {
		return mScanSettings;
	}

	/**
	 * Check is scan is running
	 * @return boolean
	 */
	public synchronized boolean isScanning() {
		return mIsScanning;
	}

	/**
	 * Get the last scan error
	 * @return mLastError
	 */
	public synchronized int getLastError() {
		return mLastError;
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public synchronized HashMap<String, BleDevice> getScannedDevices() {
		return mScannedDevices;
	}

	/**
	 * Start devices scan
	 * @param callback BleScanCallback
	 */
	public synchronized void startScan(final @NonNull BleScanCallback callback) {
		// Clear the scan handler
		mHandler.removeCallbacksAndMessages(null);

		// Delay before starting the scan
		int delayBeforeStart = 0;

		// Check if scan already running
		if(mIsScanning) {
			// Add 500ms between two scan
			delayBeforeStart += 500;

			// Stop the previous scan
			stopScan();
		}

		// Clear previous scanned devices
		mScannedDevices.clear();

		// Set the scan callback
		mCallback = callback;

		// Set last error
		mLastError = BleScanCallback.NO_ERROR;

		// Check if bluetooth adapter is init
		if(KnBle.getBluetoothAdapter() == null) {
			mLastError = BleScanCallback.BT_DISABLED;
			callback.onScanFailed(mLastError);
			return;
		}

		// Init LE Scanner for Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mBluetoothLeScanner == null) {
			mBluetoothLeScanner = KnBle.getBluetoothAdapter().getBluetoothLeScanner();
			if(mBluetoothLeScanner == null) {
				mLastError = BleScanCallback.SCANNER_UNAVAILABLE;
				callback.onScanFailed(mLastError);
				return;
			}
		}

		// Check if location services are enabled on Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Utils.areLocationServicesEnabled(KnBle.getContext())) {
			mLastError = BleScanCallback.LOCATION_DISABLED;
			callback.onScanFailed(mLastError);
			return;
		}

		// Check if bluetooth is enabled
		if(!KnBle.isBluetoothEnabled()) {
			// Enable bluetooth
			KnBle.enableBluetooth(true);
			// Add delay to be sure the adapter has time to init before start scan
			delayBeforeStart += 5000;
		}

		// Scan started
		mIsScanning = true;
		mCallback.onScanStarted();

		// Start the scan
		mHandler.postDelayed(this::startScan, delayBeforeStart);

		// Stop the scan after the timeout
		if(mScanSettings != null) {

			// If scan autorestart is enable
			if(mScanSettings.getAutoRestartScanAfter() > 0) {

				// Restart the scan with the same callback
				mHandler.postDelayed(() -> {
					startScan(callback);
				}, mScanSettings.getAutoRestartScanAfter()+delayBeforeStart);

				// No timeout if autorestart
				return;
			}

			// If timeout has been set
			if(mScanSettings.getScanTimeout() > 0) mHandler.postDelayed(this::stopScan, mScanSettings.getScanTimeout()+delayBeforeStart);
		}
	}

	/**
	 * Start devices scan
	 */
	private synchronized void startScan() {
		// Clear previous scanned devices
		mScannedDevices.clear();

		// Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(mScanCallback == null) {
				// Init the callback
				mScanCallback = new android.bluetooth.le.ScanCallback() {
					@Override
					public void onScanResult(int callbackType, ScanResult result) {
						// Scan record in bytes
						byte[] scanRecord;
						if(result.getScanRecord() != null) scanRecord = result.getScanRecord().getBytes();
						else scanRecord = null;

						// Process
						processScanResult(result.getDevice(), result.getRssi(), scanRecord, false);
					}

					@Override
					public void onScanFailed(int errorCode) {
						// Set last error
						if(errorCode == android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) mLastError = BleScanCallback.SCAN_FEATURE_UNSUPPORTED;
						else mLastError = BleScanCallback.UNKNOWN_ERROR;

						// Callback
						if(mCallback != null) mCallback.onScanFailed(mLastError);

						// Stop the scan
						stopScan();
					}
				};
			}

			// Scan filters
			List<ScanFilter> scanFilters = new ArrayList<>();
			if(mScanFilters != null) {
				// Devices names
				if(mScanFilters.getDeviceNames().size() > 0) {
					for (String deviceName : mScanFilters.getDeviceNames()) scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceName(deviceName).build());
				}

				// Devices mac address
				if(mScanFilters.getDevicesMacs().size() > 0) {
					for (String macAdress : mScanFilters.getDevicesMacs()) scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(macAdress).build());
				}

				// Manufacturer IDs
				if(mScanFilters.getManufacturerIds().size() > 0) {
					for (int manufacturerId : mScanFilters.getManufacturerIds()) scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setManufacturerData(manufacturerId, new byte[] {}).build());
				}

				// No filter => Add an empty filter
				if(scanFilters.size() == 0) scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().build());
			}

			// Scan settings
			android.bluetooth.le.ScanSettings.Builder scanSettingBuilder = new android.bluetooth.le.ScanSettings.Builder();
			if(mScanSettings != null) {
				// Set the settings
				scanSettingBuilder.setScanMode(mScanSettings.getScanMode())
						.setMatchMode(mScanSettings.getMatchMode())
						.setNumOfMatches(mScanSettings.getNbMatch())
						.setCallbackType(mScanSettings.getCallbackType())
						.setReportDelay(mScanSettings.getReportDelay());

				// Scan settings for android 8+
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					scanSettingBuilder.setLegacy(mScanSettings.isLegacy());
					scanSettingBuilder.setPhy(mScanSettings.getPhy());
				}
			}

			// Start scanning
			mBluetoothLeScanner.startScan(scanFilters, scanSettingBuilder.build(), mScanCallback);
		} else {
			if(mLeScanCallback == null) {
				// Init the callback
				mLeScanCallback = (device, rssi, scanRecord) -> {
					processScanResult(device, rssi, scanRecord, true);
				};
			}

			// Start scanning
			if(KnBle.getBluetoothAdapter() != null) KnBle.getBluetoothAdapter().startLeScan(mLeScanCallback);
		}
	}

	/**
	 * When a new device is scanned
	 * @param device The device
	 * @param rssi The RSSI
	 * @param scanRecord The scan record
	 * @param manualFilter Use manual filter or not
	 */
	private synchronized void processScanResult(@NonNull BluetoothDevice device, int rssi, @Nullable byte[] scanRecord, boolean manualFilter) {
		// Apply filter manually ?
		if(manualFilter && mScanFilters != null) {
			// Nb of filters
			boolean deviceMatch = false;
			int nbMacFilter = mScanFilters.getDevicesMacs().size();
			int nbManuFilter = mScanFilters.getManufacturerIds().size();

			// Devices names
			if(mScanFilters.getDeviceNames().size() > 0) {
				// Check if device name match
				if(!mScanFilters.getDeviceNames().contains(device.getName())) {
					// If no other filters
					if(nbMacFilter == 0 && nbManuFilter == 0) return;
				} else {
					// Device match
					deviceMatch = true;
				}

			}

			// Devices mac address
			if(!deviceMatch && nbMacFilter > 0) {
				// Check if device mac match
				if(!mScanFilters.getDevicesMacs().contains(device.getAddress())) {
					// If no others filters
					if(nbManuFilter == 0) return;
				} else {
					// Device match
					deviceMatch = true;
				}
			}

			// Manufacturer IDs
			if(!deviceMatch && nbManuFilter > 0) {
				int manufacturerId = Utils.getManufacturerIdFromScanRecord(scanRecord);
				if(manufacturerId > -1 && !mScanFilters.getManufacturerIds().contains(manufacturerId)) return;
			}
		}

		// Check if device already exist
		boolean exist = mScannedDevices.containsKey(device.getAddress());

		// Create BleDevice
		BleDevice bleDevice = new BleDevice(device, rssi, scanRecord, System.currentTimeMillis());

		// Add/Update hashmap
		mScannedDevices.put(device.getAddress(), bleDevice);

		// Notify the UI
		if(!exist && mCallback != null) mCallback.onScanResult(bleDevice);
	}

	/**
	 * Stop devices scan
	 */
	public synchronized void stopScan() {
		// Clear the scan handler
		mHandler.removeCallbacksAndMessages(null);

		// Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(mBluetoothLeScanner != null && mScanCallback != null) mBluetoothLeScanner.stopScan(mScanCallback);
		} else {
			if(KnBle.getBluetoothAdapter() != null && mLeScanCallback != null) KnBle.getBluetoothAdapter().stopLeScan(mLeScanCallback);
		}

		// Scanned finished
		mIsScanning = false;
		if(mCallback != null) {
			mCallback.onScanFinished(mScannedDevices);
			mCallback = null;
		}
	}

	/**
	 * Destroy
	 */
	public synchronized void destroy() {
		stopScan();
		mHandler.removeCallbacksAndMessages(null);
		mScannedDevices.clear();
		mCallback = null;
		mScanCallback = null;
		mLeScanCallback = null;
		mBluetoothLeScanner = null;
		mLastError = BleScanCallback.NO_ERROR;
		mIsScanning = false;
	}
}
