package ovh.karewan.knble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.List;

import ovh.karewan.knble.ble.DeviceOp;
import ovh.karewan.knble.ble.DevicesManager;
import ovh.karewan.knble.interfaces.BleCheckCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleMtuChangedCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BlePhyValueCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.scan.Scanner;
import ovh.karewan.knble.struct.BleDevice;

@SuppressWarnings({"MissingPermission", "unused"})
public class KnBle {
	private static volatile KnBle sInstance;
	public static volatile boolean DEBUG = false;
	private final DevicesManager mDevicesManager = new DevicesManager();
	private final Scanner mScanner = new Scanner();
	private WeakReference<Context> mContext;
	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;

	private KnBle() {}

	/**
	 * Get instance
	 * @return KnBle
	 */
	@NonNull
	public static KnBle gi() {
		if(sInstance == null) {
			synchronized(KnBle.class) {
				if(sInstance == null) sInstance = new KnBle();
			}
		}

		return sInstance;
	}

	/**
	 * Init KnBle
	 * @param context The application context
	 * @return boolean
	 */
	public boolean init(@NonNull Context context) {
		// Prevent double init
		if(mContext != null && mContext.get() != null) {
			Utils.log("already successful init");
			return true;
		}

		// Check if device support BLE
		if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			Utils.log("failed to init => missing Bluetooth LE feature");
			return false;
		}

		// Get the bluetooth manager service
		mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if(mBluetoothManager == null) {
			Utils.log("failed to init => no bluetooth manager");
			return false;
		}

		// Get the bluetooth adapter
		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if(mBluetoothAdapter == null) {
			Utils.log("failed to init => no bluetooth adapter");
			return false;
		}

		// Store context into weakref to avoid memory leaks
		mContext = new WeakReference<>(context);

		// Register BT state changed receiver
		mContext.get().registerReceiver(mBtStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

		// Init success
		Utils.log("init success");
		return true;
	}

	/**
	 * Check if KnBle has been successfully initialized
	 * @return boolean
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean isInit() {
		return mContext != null && mContext.get() != null;
	}

	/**
	 * Return context
	 * @return Context
	 */
	@Nullable
	public Context getContext() {
		if(mContext == null) return null;
		return mContext.get();
	}

	/**
	 * Return bluetooth manager service
	 * @return BluetoothManager
	 */
	@Nullable
	public BluetoothManager getBluetoothManager() {
		return mBluetoothManager;
	}

	/**
	 * Return bluetooth adapter
	 * @return BluetoothAdapter
	 */
	@Nullable
	public BluetoothAdapter getBluetoothAdapter() {
		return mBluetoothAdapter;
	}

	/**
	 * Check if bluetooth adapter is enabled
	 * @return boolean
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public boolean isBluetoothEnabled() {
		if(mBluetoothAdapter == null) {
			Utils.log("bluetooth adapter is null");
			return false;
		}

		return mBluetoothAdapter.isEnabled();
	}

	/**
	 * Enable/Disable bluetooth
	 * @param enable Enable or disable
	 * @return boolean
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	@DeprecatedSinceApi(api=Build.VERSION_CODES.TIRAMISU)
	public boolean enableBluetooth(boolean enable) {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			Utils.log("enableBluetooth is deprecated since android tiramisu");
			return false;
		}

		if(mBluetoothAdapter == null) {
			Utils.log("bluetooth adapter is null");
			return false;
		}

		return enable ? mBluetoothAdapter.enable() : mBluetoothAdapter.disable();
	}

	/**
	 * Get a BleDevice object from a mac address
	 * @param mac The mac address
	 * @return BleDevice
	 * @noinspection CallToPrintStackTrace
	 */
	@Nullable
	public BleDevice getBleDeviceFromMac(@NonNull String mac) {
		if(mBluetoothAdapter == null) {
			Utils.log("bluetooth adapter is null");
			return null;
		}

		try {
			return new BleDevice(mBluetoothAdapter.getRemoteDevice(mac));
		} catch (IllegalArgumentException e) {
			if(KnBle.DEBUG) e.printStackTrace();
			return null;
		}
	}

	/**
	 * Return scanning status
	 * @return boolean
	 */
	public boolean isScanning() {
		return mScanner.isScanning();
	}

	/**
	 * Get last scan error
	 * @return int from ScanCallback
	 */
	public int getLastScanError() {
		return mScanner.getLastError();
	}

	/**
	 * Set the scan filter
	 * @param scanFilter ScanFilter
	 */
	public void setScanFilter(@NonNull ScanFilters scanFilter) {
		mScanner.setScanFilter(scanFilter);
	}

	/**
	 * Return current ScanFilters
	 * @return ScanFilters
	 */
	public ScanFilters getScanFilters() {
		return mScanner.getScanFilters();
	}

	/**
	 * Set the scan settings
	 * @param scanSettings ScanSettings
	 */
	public void setScanSettings(@NonNull ScanSettings scanSettings) {
		mScanner.setScanSettings(scanSettings);
	}

	/**
	 * Return current ScanSettings
	 * @return ScanSettings
	 */
	public ScanSettings getScanSettings() {
		return mScanner.getScanSettings();
	}

	/**
	 * Start devices scan
	 * @param callback BleScanCallback
	 */
	public void startScan(@NonNull BleScanCallback callback) {
		if(!isInit()) {
			Utils.log("KnBle is not init");
			callback.onScanFailed(BleScanCallback.UNKNOWN_ERROR);
			return;
		}

		mScanner.startScan(callback);
	}

	/**
	 * Stop devices scan
	 */
	public void stopScan() {
		mScanner.stopScan();
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public List<BleDevice> getScannedDevices() {
		return mScanner.getScannedDevices();
	}

	/**
	 * Clear scanned devices list
	 */
	public void clearScannedDevices() {
		mScanner.clearScannedDevices();
	}

	/**
	 * Stop and reset devices scan
	 * @param resetSettings Reset settings
	 * @param resetFilters Reset filters
 	 */
	public void resetScan(boolean resetSettings, boolean resetFilters) {
		mScanner.reset(resetSettings, resetFilters);
	}

	/**
	 * Get connected devices list
	 * @return Connected devices
	 */
	@NonNull
	public List<BleDevice> getConnectedDevices() {
		return mDevicesManager.getConnectedDevices();
	}

	/**
	 * Check if a device is connected
	 * @param device The device
	 * @return boolean
	 */
	public boolean isConnected(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		return deviceOp != null && deviceOp.isConnected();
	}

	/**
	 * Get device connection state
	 * @param device The device
	 * @return The state
	 */
	public int getDeviceConnState(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		return deviceOp == null ? BleGattCallback.DISCONNECTED : deviceOp.getState();
	}

	/**
	 * Get the BluetoothGatt of a device
	 * @param device The device
	 * @return BluetoothGatt|null
	 */
	@Nullable
	public BluetoothGatt getBluetoothGatt(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		return deviceOp == null ? null : deviceOp.getBluetoothGatt();
	}

	/**
	 * Get the last gatt status of a device
	 * @param device The device
	 * @return The last gatt status
	 */
	public int getLastGattStatusOfDevice(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		return deviceOp == null ? BluetoothGatt.GATT_SUCCESS : deviceOp.getLastGattStatus();
	}

	/**
	 * Connect to a device
	 * @param device The device
	 * @param callback The callback
	 */
	public void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		if(!isInit()) {
			Utils.log("KnBle is not init");
			callback.onDisconnected(true);
			return;
		}

		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);

		if(deviceOp == null) deviceOp = mDevicesManager.addDevice(device);
		else deviceOp.setDevice(device);

		deviceOp.connect(callback);
	}

	/**
	 * Request connection priority
	 * @param device The device
	 * @param connectionPriority The connection priority
	 */
	public void requestConnectionPriority(@NonNull BleDevice device, int connectionPriority) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.requestConnectionPriority(connectionPriority);
	}

	/**
	 * Request MTU
	 * @param device The device
	 * @param mtu The MTU
	 */
	public void requestMtu(@NonNull BleDevice device, int mtu) {
		requestMtu(device, mtu, null);
	}

	/**
	 * Request MTU
	 * @param device The device
	 * @param mtu The MTU
	 * @param callback The callback
	 */
	public void requestMtu(@NonNull BleDevice device, int mtu, @Nullable BleMtuChangedCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.requestMtu(mtu, callback);
	}

	/**
	 * Set prefered PHY
	 * @param device The device
	 * @param txPhy TX PHY
	 * @param rxPhy RX PHY
	 * @param phyOptions CODING FOR LE CODED PHY
	 */
	@RequiresApi(Build.VERSION_CODES.O)
	public void setPreferredPhy(@NonNull BleDevice device, int txPhy, int rxPhy, int phyOptions) {
		setPreferredPhy(device, txPhy, rxPhy, phyOptions, null);
	}

	/**
	 * Set prefered PHY
	 * @param device The device
	 * @param txPhy TX PHY
	 * @param rxPhy RX PHY
	 * @param phyOptions CODING FOR LE CODED PHY
	 * @param callback Callback
	 */
	@RequiresApi(Build.VERSION_CODES.O)
	public void setPreferredPhy(@NonNull BleDevice device, int txPhy, int rxPhy, int phyOptions, @Nullable BlePhyValueCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.setPreferredPhy(txPhy, rxPhy, phyOptions, callback);
	}

	/**
	 * Read PHY
	 * @param device The device
	 * @param callback Callback
	 */
	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	public void readPhy(@NonNull BleDevice device, @Nullable BlePhyValueCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.readPhy(callback);
	}

	/**
	 * Get MTU of a device
	 * @param device The device
	 * @return The MTU
	 */
	public int getMtu(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		return deviceOp == null ? 0 : deviceOp.getMtu();
	}

	/**
	 * Check if a service exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param callback The callback
	 */
	public void hasService(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull BleCheckCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.hasService(serviceUUID, callback);
		else callback.onResponse(false);
	}

	/**
	 * Check if a characteristic exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public void hasCharacteristic(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleCheckCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.hasCharacteristic(serviceUUID, characteristicUUID, callback);
		else callback.onResponse(false);
	}

	/**
	 * Set BleGattCallback of a device
	 * @param device The device
	 * @param callback The callback
	 */
	public void setGattCallback(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.setGattCallback(callback);
	}

	/**
	 * Write data into a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param callback The callback
	 */
	public void write(@NonNull BleDevice device,
					  @NonNull String serviceUUID,
					  @NonNull String characteristicUUID,
					  @NonNull byte[] data,
					  @NonNull BleWriteCallback callback) {

		write(device, serviceUUID, characteristicUUID, data, false, 20, true, 0, callback);
	}

	/**
	 * Write data into a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param split Split data if data > 20 bytes
	 * @param splitSize Packet split size
	 * @param sendNextWhenLastSuccess If split send next package when last sucess
	 * @param intervalBetweenTwoPackage Interval between two package
	 * @param callback The callback
	 */
	public void write(@NonNull BleDevice device,
					  @NonNull String serviceUUID,
					  @NonNull String characteristicUUID,
					  @NonNull byte[] data,
					  boolean split,
					  int splitSize,
					  boolean sendNextWhenLastSuccess,
					  long intervalBetweenTwoPackage,
					  @NonNull BleWriteCallback callback) {

		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.write(serviceUUID, characteristicUUID, data, split, splitSize, sendNextWhenLastSuccess, intervalBetweenTwoPackage, callback);
		else callback.onWriteFailed();
	}

	/**
	 * Read data from a gatt characteristic
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The call back
	 */
	public void read(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.read(serviceUUID, characteristicUUID, callback);
		else callback.onReadFailed();
	}

	/**
	 * Enable notify
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The call back
	 */
	public void enableNotify(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleNotifyCallback callback) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.enableNotify(serviceUUID, characteristicUUID, callback);
		else callback.onNotifyDisabled();
	}

	/**
	 * Disable notify
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 */
	public void disableNotify(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.disableNotify(serviceUUID, characteristicUUID);
	}

	/**
	 * Disconnect a device
	 * @param device The device
	 */
	public void disconnect(@NonNull BleDevice device) {
		DeviceOp deviceOp = mDevicesManager.getDeviceOp(device);
		if(deviceOp != null) deviceOp.disconnect();
	}

	/**
	 * Disconnect all devices
	 */
	public void disconnectAll() {
		mDevicesManager.disconnectAll();
	}

	/**
	 * Destroy (and disconnect) a device instance
	 * @param device The device
	 */
	public void destroyDevice(@NonNull BleDevice device) {
		mDevicesManager.removeDevice(device);
	}

	/**
	 * Destroy (and disconnect) all devices instances
	 */
	public void destroyAllDevices() {
		mDevicesManager.destroyAll();
	}

	/**
	 * BT State Receiver
	 */
	private final BroadcastReceiver mBtStateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if((intent == null || intent.getAction() == null || !intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED) || mBluetoothAdapter == null)
					|| (mBluetoothAdapter.getState() != BluetoothAdapter.STATE_TURNING_OFF && mBluetoothAdapter.getState() != BluetoothAdapter.STATE_OFF)) return;

			mScanner.handleBtTurningOff();
			mDevicesManager.disconnectAll();
		}
	};
}
