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
	private WeakReference<Context> mContext;
	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	public static volatile boolean DEBUG = false;

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
		if(mContext != null && mContext.get() != null) return true;

		// Check if device support BLE
		if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false;

		// Get the bluetooth manager service
		mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if(mBluetoothManager == null) return false;

		// Get the bluetooth adapter
		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if(mBluetoothAdapter == null) return false;

		// Init the scanner
		Scanner.gi();

		// Init the devices manager
		DevicesManager.gi();

		// Store context into weakref to avoid memory leaks
		mContext = new WeakReference<>(context);
		mContext.get().registerReceiver(mBtStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

		// Init success
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
		if(mBluetoothAdapter == null) return false;
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
		if(mBluetoothAdapter == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false;
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
		if(mBluetoothAdapter == null) return null;

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
		return Scanner.gi().isScanning();
	}

	/**
	 * Get last scan error
	 * @return int from ScanCallback
	 */
	public int getLastScanError() {
		return Scanner.gi().getLastError();
	}

	/**
	 * Set the scan filter
	 * @param scanFilter ScanFilter
	 */
	public void setScanFilter(@NonNull ScanFilters scanFilter) {
		Scanner.gi().setScanFilter(scanFilter);
	}

	/**
	 * Return current ScanFilters
	 * @return ScanFilters
	 */
	public ScanFilters getScanFilters() {
		return Scanner.gi().getScanFilters();
	}

	/**
	 * Set the scan settings
	 * @param scanSettings ScanSettings
	 */
	public void setScanSettings(@NonNull ScanSettings scanSettings) {
		Scanner.gi().setScanSettings(scanSettings);
	}

	/**
	 * Return current ScanSettings
	 * @return ScanSettings
	 */
	public ScanSettings getScanSettings() {
		return Scanner.gi().getScanSettings();
	}

	/**
	 * Start devices scan
	 * @param callback BleScanCallback
	 */
	public void startScan(@NonNull BleScanCallback callback) {
		Scanner.gi().startScan(callback);
	}

	/**
	 * Stop devices scan
	 */
	public void stopScan() {
		Scanner.gi().stopScan();
	}

	/**
	 * Return scanned device list
	 * @return mScannedDevices
	 */
	@NonNull
	public List<BleDevice> getScannedDevices() {
		return Scanner.gi().getScannedDevices();
	}

	/**
	 * Clear scanned devices list
	 */
	public void clearScannedDevices() {
		Scanner.gi().clearScannedDevices();
	}

	/**
	 * Stop and reset devices scan
	 * @param resetSettings Reset settings
	 * @param resetFilters Reset filters
 	 */
	public void resetScan(boolean resetSettings, boolean resetFilters) {
		Scanner.gi().reset(resetSettings, resetFilters);
	}

	/**
	 * Get connected devices list
	 * @return Connected devices
	 */
	@NonNull
	public List<BleDevice> getConnectedDevices() {
		return DevicesManager.gi().getConnectedDevices();
	}

	/**
	 * Check if a device is connected
	 * @param device The device
	 * @return boolean
	 */
	public boolean isConnected(@NonNull BleDevice device) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		return deviceOp != null && deviceOp.isConnected();
	}

	/**
	 * Get device connection state
	 * @param device The device
	 * @return The state
	 */
	public int getDeviceConnState(@NonNull BleDevice device) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		return deviceOp == null ? BleGattCallback.DISCONNECTED : deviceOp.getState();
	}

	/**
	 * Get the BluetoothGatt of a device
	 * @param device The device
	 * @return BluetoothGatt|null
	 */
	@Nullable
	public BluetoothGatt getBluetoothGatt(@NonNull BleDevice device) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		return deviceOp == null ? null : deviceOp.getBluetoothGatt();
	}

	/**
	 * Get the last gatt status of a device
	 * @param device The device
	 * @return The last gatt status
	 */
	public int getLastGattStatusOfDevice(@NonNull BleDevice device) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		return deviceOp == null ? 0 : deviceOp.getLastGattStatus();
	}

	/**
	 * Connect to a device
	 * @param device The device
	 * @param callback The callback
	 */
	public void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		if(!isInit()) {
			callback.onConnectFailed();
			return;
		}

		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);

		if(deviceOp == null) deviceOp = DevicesManager.gi().addDevice(device);
		else deviceOp.setDevice(device);

		deviceOp.connect(callback);
	}

	/**
	 * Request connection priority
	 * @param device The device
	 * @param connectionPriority The connection priority
	 */
	public void requestConnectionPriority(@NonNull BleDevice device, int connectionPriority) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		if(deviceOp != null) deviceOp.setPreferredPhy(txPhy, rxPhy, phyOptions, callback);
	}

	/**
	 * Read PHY
	 * @param device The device
	 * @param callback Callback
	 */
	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	public void readPhy(@NonNull BleDevice device, @Nullable BlePhyValueCallback callback) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		if(deviceOp != null) deviceOp.readPhy(callback);
	}

	/**
	 * Get MTU of a device
	 * @param device The device
	 * @return The MTU
	 */
	public int getMtu(@NonNull BleDevice device) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		return deviceOp == null ? 0 : deviceOp.getMtu();
	}

	/**
	 * Check if a service exist
	 * @param device The device
	 * @param serviceUUID The service UUID
	 * @param callback The callback
	 */
	public void hasService(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull BleCheckCallback callback) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		if(deviceOp != null) deviceOp.hasCharacteristic(serviceUUID, characteristicUUID, callback);
		else callback.onResponse(false);
	}

	/**
	 * Set BleGattCallback of a device
	 * @param device The device
	 * @param callback The callback
	 */
	public void setGattCallback(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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

		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
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
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		if(deviceOp != null) deviceOp.disableNotify(serviceUUID, characteristicUUID);
	}

	/**
	 * Disconnect a device
	 * @param device The device
	 */
	public void disconnect(@NonNull BleDevice device) {
		DeviceOp deviceOp = DevicesManager.gi().getDeviceOp(device);
		if(deviceOp != null) deviceOp.disconnect();
	}

	/**
	 * Disconnect all devices
	 */
	public void disconnectAll() {
		DevicesManager.gi().disconnectAll();
	}

	/**
	 * Destroy all devices instances
	 */
	public void destroyAllDevices() {
		DevicesManager.gi().destroy();
	}

	/**
	 * BT State Receiver
	 */
	private final BroadcastReceiver mBtStateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if((intent == null || intent.getAction() == null || !intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED) || mBluetoothAdapter == null)
					|| (mBluetoothAdapter.getState() != BluetoothAdapter.STATE_TURNING_OFF && mBluetoothAdapter.getState() != BluetoothAdapter.STATE_OFF)) return;

			Scanner.gi().handleBtTurningOff();
			DevicesManager.gi().disconnectAll();
		}
	};
}
