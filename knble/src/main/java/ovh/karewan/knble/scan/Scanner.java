/*
	KnBle

	Released under the MIT License (MIT)

	Copyright (c) 2019-2020 Florent VIALATTE

	Permission is hereby granted, free of charge, to any person obtaining a copy
	of this software and associated documentation files (the "Software"), to deal
	in the Software without restriction, including without limitation the rights
	to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
	copies of the Software, and to permit persons to whom the Software is
	furnished to do so, subject to the following conditions:

	The above copyright notice and this permission notice shall be included in
	all copies or substantial portions of the Software.

	THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
	IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
	AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
	LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
	OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
	THE SOFTWARE.
 */
package ovh.karewan.knble.scan;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
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

	private ScanCallback mScanCallback; // Android 6+
	private BluetoothLeScanner mBluetoothLeScanner; // Android 6+

	private Scanner() {}

	private static class Loader {
		static final Scanner INSTANCE = new Scanner();
	}

	@NonNull
	public static Scanner getInstance() {
		return Loader.INSTANCE;
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
	@TargetApi(Build.VERSION_CODES.M)
	private synchronized void setScanCallback(@Nullable ScanCallback callback) {
		mScanCallback = callback;
	}

	/**
	 * Set LeScanCallback (Android 4.4-5.1)
	 * @param callback LeScanCallback
	 */
	private synchronized void setLeScanCallback(@Nullable BluetoothAdapter.LeScanCallback callback) {
		mLeScanCallback = callback;
	}

	/**
	 * Set BluetoothLeScanner (Android 6+)
	 * @param scanner BluetoothLeScanner
	 */
	@TargetApi(Build.VERSION_CODES.M)
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
	public synchronized void clearScannedDevices() {
		mScannedDevices.clear();
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public HashMap<String, BleDevice> getScannedDevices() {
		return mScannedDevices;
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
		if(!KnBle.getInstance().isBluetoothEnabled()) {
			// Enable bluetooth
			KnBle.getInstance().enableBluetooth(true);
			// Add delay to be sure the adapter has time to init before start scan
			delayBeforeStart += 5000;
		}

		// Clear previous scanned devices
		clearScannedDevices();

		// Set the scan callback
		setCallback(callback);

		// Set last error
		setLastError(BleScanCallback.NO_ERROR);

		// Check if bluetooth adapter is init
		if(KnBle.getInstance().getBluetoothAdapter() == null) {
			setLastError(BleScanCallback.BT_DISABLED);
			callback.onScanFailed(mLastError);
			return;
		}

		// Check if location services are enabled on Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Utils.areLocationServicesEnabled(KnBle.getInstance().getContext())) {
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

		// Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			// Init LE Scanner
			if(mBluetoothLeScanner == null) {
				if(KnBle.getInstance().getBluetoothAdapter() != null) setBluetoothLeScanner(KnBle.getInstance().getBluetoothAdapter().getBluetoothLeScanner());

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
						if(errorCode == android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) setLastError(BleScanCallback.SCAN_FEATURE_UNSUPPORTED);
						else setLastError(BleScanCallback.UNKNOWN_ERROR);

						// Callback
						if(mCallback != null) mCallback.onScanFailed(mLastError);

						// Stop the scan
						stopScan();
					}
				});
			}

			// Scan filters
			List<ScanFilter> scanFilters = new ArrayList<>();
			if(mScanFilters != null) {
				// Devices names
				for(String deviceName : mScanFilters.getDeviceNames())
					scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceName(deviceName).build());

				// Devices mac address
				for(String macAdress : mScanFilters.getDevicesMacs())
					scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(macAdress).build());

				// Manufacturer IDs
				for(int manufacturerId : mScanFilters.getManufacturerIds())
					scanFilters.add(new android.bluetooth.le.ScanFilter.Builder().setManufacturerData(manufacturerId, new byte[] {}).build());

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
			// Init the callback
			if(mLeScanCallback == null) setLeScanCallback((device, rssi, scanRecord) -> processScanResult(device, rssi, scanRecord, true));

			// Start scanning
			if(KnBle.getInstance().getBluetoothAdapter() != null) KnBle.getInstance().getBluetoothAdapter().startLeScan(mLeScanCallback);
		}
	}

	/**
	 * When a new device is scanned
	 * @param device The device
	 * @param rssi The RSSI
	 * @param scanRecord The scan record
	 * @param manualFilter Use manual filter or not
	 */
	private void processScanResult(@NonNull BluetoothDevice device, int rssi, @Nullable byte[] scanRecord, boolean manualFilter) {
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
		}
	}

	/**
	 * Stop devices scan
	 */
	public void stopScan() {
		// Clear the scan handler
		mHandler.removeCallbacksAndMessages(null);

		// Android 6+
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(mBluetoothLeScanner != null && mScanCallback != null) mBluetoothLeScanner.stopScan(mScanCallback);
		} else {
			if(KnBle.getInstance().getBluetoothAdapter() != null && mLeScanCallback != null) KnBle.getInstance().getBluetoothAdapter().stopLeScan(mLeScanCallback);
		}

		// Scanned finished
		setIsScanning(false);
		if(mCallback != null) {
			mCallback.onScanFinished(mScannedDevices);
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
		setLeScanCallback(null);
		setScanCallback(null);
		clearScannedDevices();
		setLastError(BleScanCallback.NO_ERROR);
	}
}
