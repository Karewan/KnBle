package ovh.karewan.knble.scan;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.struct.ScanRecord;
import ovh.karewan.knble.Utils;

@SuppressWarnings("MissingPermission")
public class Scanner {
	private final Handler mHandler;
	private final ConcurrentHashMap<String, BleDevice> mScannedDevices = new ConcurrentHashMap<>();

	private boolean mIsScanning = false;
	private int mLastError = BleScanCallback.NO_ERROR;

	private ScanSettings mScanSettings = new ScanSettings.Builder().build();
	private ScanFilters mScanFilters = new ScanFilters.Builder().build();

	private BleScanCallback mCallback;

	private ScanCallback mScanCallback; // Android 6+
	private BluetoothLeScanner mBluetoothLeScanner; // Android 6+

	/**
	 * Class constructor
	 */
	public Scanner() {
		HandlerThread hd = new HandlerThread("KnBleScan");
		hd.start();
		mHandler =  new Handler(hd.getLooper());
	}

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
	public ScanFilters getScanFilters() {
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
	public ScanSettings getScanSettings() {
		return mScanSettings;
	}

	/**
	 * Set scan callback
	 * @param callback BleScanCallback
	 */
	private synchronized void setCallback(@Nullable BleScanCallback callback) {
		mCallback = callback;
	}

	/**
	 * Set scan callback (Android 6+)
	 * @param callback ScanCallback
	 */
	private synchronized void setScanCallback(@Nullable ScanCallback callback) {
		mScanCallback = callback;
	}

	/**
	 * Set BluetoothLeScanner (Android 6+)
	 * @param scanner BluetoothLeScanner
	 */
	private synchronized void setBluetoothLeScanner(@Nullable BluetoothLeScanner scanner) {
		mBluetoothLeScanner = scanner;
	}

	/**
	 * Set is scanning
	 * @param isScanning Is scanning ?
	 */
	private synchronized void setIsScanning(boolean isScanning) {
		mIsScanning = isScanning;
	}

	/**
	 * Check is scan is running
	 * @return boolean
	 */
	public boolean isScanning() {
		return mIsScanning;
	}

	/**
	 * Set last error
	 * @param error the error
	 */
	private synchronized void setLastError(int error) {
		mLastError = error;
	}

	/**
	 * Get the last scan error
	 * @return mLastError
	 */
	public int getLastError() {
		return mLastError;
	}

	/**
	 * Clear scanned devices
	 */
	public void clearScannedDevices() {
		mScannedDevices.clear();
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public List<BleDevice> getScannedDevices() {
		return new ArrayList<>(mScannedDevices.values());
	}

	/**
	 * Start devices scan
	 * @param callback BleScanCallback
	 */
	public void startScan(final @NonNull BleScanCallback callback) {
		// Delay before starting the scan
		int delayBeforeStart = 0;

		// Check if scan already running
		if(mIsScanning) {
			// Add 500ms between two scan
			delayBeforeStart += 500;

			// Stop the previous scan
			stopScan();
		} else {
			// Clear the scan handler
			mHandler.removeCallbacksAndMessages(null);
		}

		// Check if bluetooth is enabled
		if(!KnBle.gi().isBluetoothEnabled()) {
			// Enable bluetooth
			if(!KnBle.gi().enableBluetooth(true)) {
				// Error
				setLastError(BleScanCallback.BT_DISABLED);
				callback.onScanFailed(mLastError);
				return;
			} else {
				// Add delay to be sure the adapter has time to init before start scan
				delayBeforeStart += 5000;
			}
		}

		// Clear previous scanned devices
		clearScannedDevices();

		// Set the scan callback
		setCallback(callback);

		// Set last error
		setLastError(BleScanCallback.NO_ERROR);

		// Check if bluetooth adapter is init
		if(KnBle.gi().getBluetoothAdapter() == null) {
			setLastError(BleScanCallback.BT_DISABLED);
			callback.onScanFailed(mLastError);
			return;
		}

		// Check if location services are enabled on Android 6+
		if(!Utils.areLocationServicesEnabled(KnBle.gi().getContext())) {
			setLastError(BleScanCallback.LOCATION_DISABLED);
			callback.onScanFailed(mLastError);
			return;
		}

		// Scan started
		setIsScanning(true);
		mCallback.onScanStarted();

		// Start the scan
		mHandler.postDelayed(this::startScan, delayBeforeStart);

		// Stop the scan after the timeout
		if(mScanSettings != null) {

			// If scan autorestart is enable
			if(mScanSettings.getAutoRestartScanAfter() > 0) {
				// Restart the scan with the same callback
				mHandler.postDelayed(() -> startScan(callback), mScanSettings.getAutoRestartScanAfter()+delayBeforeStart);

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
	private void startScan() {
		// Clear previous scanned devices
		clearScannedDevices();

		// Init LE Scanner
		if(mBluetoothLeScanner == null) {
			if(KnBle.gi().getBluetoothAdapter() != null) {
				//noinspection ConstantConditions
				setBluetoothLeScanner(KnBle.gi().getBluetoothAdapter().getBluetoothLeScanner());
			}

			if(mBluetoothLeScanner == null) {
				setLastError(BleScanCallback.SCANNER_UNAVAILABLE);
				if(mCallback != null) mCallback.onScanFailed(mLastError);
				return;
			}
		}

		if(mScanCallback == null) {
			// Init the callback
			setScanCallback(new android.bluetooth.le.ScanCallback() {
				@Override
				public void onScanResult(int callbackType, ScanResult result) {
					mHandler.post(() -> {
						// Scan record in bytes
						byte[] scanRecord = result.getScanRecord() != null ? result.getScanRecord().getBytes() : null;

						// Process
						processScanResult(result.getDevice(), result.getRssi(), scanRecord);
					});
				}

				@Override
				public void onScanFailed(int errorCode) {
					mHandler.post(() -> {
						// Set last error
						if(errorCode == android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) setLastError(BleScanCallback.SCAN_FEATURE_UNSUPPORTED);
						else setLastError(BleScanCallback.UNKNOWN_ERROR);

						// Callback
						if(mCallback != null) mCallback.onScanFailed(mLastError);

						// Stop the scan
						stopScan();
					});
				}
			});
		}

		// Scan filters
		List<ScanFilter> scanFilters = new ArrayList<>();
		if(mScanFilters != null && mScanFilters.isUsingAndroid6Filters()) {
			// Devices names
			for(String deviceName : mScanFilters.getDeviceNames())
				scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceName(deviceName).build());

			// Devices mac address
			for(String macAdress : mScanFilters.getDevicesMacs())
				scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(macAdress).build());

			// Manufacturer IDs
			for(int manufacturerId : mScanFilters.getManufacturerIds())
				scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setManufacturerData(manufacturerId, new byte[] {}).build());

			// Beacon UUIDs
			for(UUID uuid : mScanFilters.getBeaconUUIDs()) {
				byte[] one = new byte[]{ 0x02, 0x15 };
				byte[] two = Utils.uuidAsBytes(uuid);
				byte[] manufacturerData = new byte[one.length + two.length];
				System.arraycopy(one,0, manufacturerData,0, one.length);
				System.arraycopy(two,0, manufacturerData, one.length, two.length);
				scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setManufacturerData(0x004c, manufacturerData).build());
			}
		}

		// No filter => Add an empty filter
		if(scanFilters.isEmpty()) scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().build());

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
	}

	/**
	 * When a new device is scanned
	 * @param device The device
	 * @param rssi The RSSI
	 * @param rawScanRecord The scan record
	 */
	private void processScanResult(@NonNull BluetoothDevice device, int rssi, @Nullable byte[] rawScanRecord) {
		// The scan record
		ScanRecord scanRecord = rawScanRecord == null ? null : new ScanRecord(rawScanRecord);

		// Check if filters match
		if(!isFiltersMatch(device, scanRecord)) return;

		// Check if device already exist
		if(!mScannedDevices.containsKey(device.getAddress())) {
			// Create BleDevice
			BleDevice bleDevice = new BleDevice(device, rssi, scanRecord, System.currentTimeMillis());

			// Add into hashmap
			mScannedDevices.put(device.getAddress(), bleDevice);

			// Notify the UI
			if(mCallback != null) mCallback.onScanResult(bleDevice);
		} else {
			// Update device already in hashmap
			BleDevice bleDevice = mScannedDevices.get(device.getAddress());

			//noinspection ConstantConditions
			bleDevice.updateDevice(device, rssi, scanRecord, System.currentTimeMillis());

			// Notify the UI
			if(mCallback != null) mCallback.onDeviceUpdated(bleDevice);
		}
	}

	/**
	 * Is filters match
	 * @param device BluetoothDevice
	 * @param scanRecord ScanRecord
	 * @return boolean
	 */
	private boolean isFiltersMatch(@NonNull BluetoothDevice device, @Nullable ScanRecord scanRecord) {
		// No filters
		if(mScanFilters == null || mScanFilters.count() == 0) return true;
		else {
			// Device name
			String deviceName = device.getName();
			if(deviceName != null) {
				// Device name starts with name
				for(String startsWith : mScanFilters.getDeviceStartsWithNames()) {
					if(deviceName.startsWith(startsWith)) return true;
				}

				// Device name ends with name
				for(String endsWith : mScanFilters.getDeviceEndsWithNames()) {
					if(deviceName.endsWith(endsWith)) return true;
				}
			}

			// Mac address starts with
			String deviceAddress = device.getAddress();
			for(String startsWith : mScanFilters.getDevicesMacsStartsWith()) {
				if(deviceAddress.startsWith(startsWith)) return true;
			}

			// If using Android 6 filters
			if(mScanFilters.isUsingAndroid6Filters()) return mScanFilters.count6Filters() > 0;
			else {
				// Device name
				if(deviceName != null && mScanFilters.getDeviceNames().contains(deviceName)) return true;

				// Mac address
				if(mScanFilters.getDevicesMacs().contains(deviceAddress)) return true;

				// Beacon UUIDs
				if(scanRecord != null && scanRecord.getBeaconUUID() != null && mScanFilters.getBeaconUUIDs().contains(scanRecord.getBeaconUUID())) return true;

				// Manufacturer Id
				return scanRecord != null && scanRecord.getManufacturerId() != null && mScanFilters.getManufacturerIds().contains(scanRecord.getManufacturerId());
			}
		}
	}

	/**
	 * Stop devices scan
	 */
	public void stopScan() {
		// Clear the scan handler
		mHandler.removeCallbacksAndMessages(null);

		// Stop scan
		if(mBluetoothLeScanner != null && mScanCallback != null && KnBle.gi().isBluetoothEnabled()) mBluetoothLeScanner.stopScan(mScanCallback);

		// Scanned finished
		setIsScanning(false);
		if(mCallback != null) {
			mCallback.onScanFinished(getScannedDevices());
			setCallback(null);
		}
	}

	/**
	 * Stop scan and reset
	 * @param resetSettings Reset settings
	 * @param resetFilters Reset filters
	 */
	public void reset(boolean resetSettings, boolean resetFilters) {
		stopScan();
		if(resetSettings) setScanSettings(new ScanSettings.Builder().build());
		if(resetFilters) setScanFilter(new ScanFilters.Builder().build());
		setBluetoothLeScanner(null);
		setScanCallback(null);
		clearScannedDevices();
		setLastError(BleScanCallback.NO_ERROR);
	}

	/**
	 * Handle BT turning off
	 */
	public void handleBtTurningOff() {
		// Scanning must be in progress
		if(!isScanning()) return;

		// If scan autorestart is enable
		if(mScanSettings.getAutoRestartScanAfter() > 0 && mCallback != null) startScan(mCallback);
		else stopScan();
	}
}
