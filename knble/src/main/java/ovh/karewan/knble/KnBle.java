package ovh.karewan.knble;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.interfaces.BleAdvertiseCallback;
import ovh.karewan.knble.interfaces.BleAssociateCallback;
import ovh.karewan.knble.interfaces.BleBackgroundScanCallback;
import ovh.karewan.knble.interfaces.BleBondCallback;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicDiscoveryCallback;
import ovh.karewan.knble.interfaces.BleClassicServerCallback;
import ovh.karewan.knble.interfaces.BleDevicesCallback;
import ovh.karewan.knble.interfaces.BleFastTransferCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleGetCharacteristic;
import ovh.karewan.knble.interfaces.BleGetDescriptor;
import ovh.karewan.knble.interfaces.BleGetService;
import ovh.karewan.knble.interfaces.BleMtuChangedCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BleOtaCallback;
import ovh.karewan.knble.interfaces.BlePhyValueCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleReadRssiCallback;
import ovh.karewan.knble.interfaces.BleRequestCallback;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.interfaces.BleServerCallback;
import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;
import ovh.karewan.knble.interfaces.BleStateListener;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.internal.BleEngine;
import ovh.karewan.knble.internal.Companion;
import ovh.karewan.knble.internal.DeviceActor;
import ovh.karewan.knble.internal.Dispatcher;
import ovh.karewan.knble.internal.GattOp;
import ovh.karewan.knble.internal.GattOpFactory;
import ovh.karewan.knble.internal.ScanEngine;
import ovh.karewan.knble.peripheral.BleAdvertiseData;
import ovh.karewan.knble.peripheral.BleServerService;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.struct.BleAdapterInfo;
import ovh.karewan.knble.struct.BleConnectionParameters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Entry point of KnBle, all static. Every method returns at once (no binder call except getAdapterInfo, no waiting)
 * and every callback is delivered on the main thread (or the Looper of KnBleSettings), the operations of a device run
 * one at a time on its own thread.
 */
@SuppressWarnings({"MissingPermission", "unused"})
public final class KnBle {
	public static volatile boolean DEBUG = false;
	private static final ConcurrentHashMap<String, UUID> sUuids = new ConcurrentHashMap<>();

	private KnBle() {}

	@Nullable
	private static BleEngine engine() {
		return BleEngine.get();
	}

	@NonNull
	private static UUID uuid(@NonNull String uuid) {
		return sUuids.computeIfAbsent(uuid, UUID::fromString);
	}

	// region Init

	/**
	 * Init KnBle with the default settings (keeps the application context)
	 * @param context A context
	 * @return false when the device has no Bluetooth LE
	 */
	public static boolean init(@NonNull Context context) {
		return BleEngine.init(context, null);
	}

	/**
	 * Init KnBle (keeps the application context). Only the first init counts.
	 * @param context A context
	 * @param settings The settings
	 * @return false when the device has no Bluetooth LE
	 */
	public static boolean init(@NonNull Context context, @NonNull KnBleSettings settings) {
		return BleEngine.init(context, settings);
	}

	/**
	 * Check if KnBle has been successfully initialized
	 * @return boolean
	 */
	public static boolean isInit() {
		return engine() != null;
	}

	@Nullable
	public static Context getContext() {
		BleEngine e = engine();
		return e == null ? null : e.context();
	}

	@Nullable
	public static BluetoothManager getBluetoothManager() {
		BleEngine e = engine();
		return e == null ? null : e.manager();
	}

	@Nullable
	public static BluetoothAdapter getBluetoothAdapter() {
		BleEngine e = engine();
		return e == null ? null : e.adapter();
	}

	/**
	 * Check if bluetooth adapter is enabled (kept up to date by KnBle, no binder call)
	 * @return boolean
	 */
	public static boolean isBluetoothEnabled() {
		BleEngine e = engine();
		return e != null && e.isBluetoothEnabled();
	}

	/**
	 * What the Bluetooth of the phone supports, as the "Device information" of nRF Connect shows it: PHY 2M and Coded,
	 * extended and periodic advertising, peripheral role, offloaded filtering and batching, longest advertising data, LE
	 * Audio, Channel Sounding, Bluetooth classic, whether the app may give the pairing codes... toString() makes a report
	 * for the logs. Works before init too. Android gives the LE features only while Bluetooth is on: KnBle keeps the
	 * last ones it read for the times it is off (BleAdapterInfo.isKnown). A few binder calls: off the main thread when
	 * called often.
	 * @param context A context (the one of init is used once KnBle is initialized)
	 */
	@NonNull
	public static BleAdapterInfo getAdapterInfo(@NonNull Context context) {
		return BleEngine.adapterInfo(context);
	}

	/**
	 * Told on the main thread when Bluetooth turns on or off (not of the current state: isBluetoothEnabled)
	 */
	public static void addBluetoothStateListener(@NonNull BleStateListener listener) {
		BleEngine e = engine();
		if (e != null) e.addStateListener(listener);
	}

	public static void removeBluetoothStateListener(@NonNull BleStateListener listener) {
		BleEngine e = engine();
		if (e != null) e.removeStateListener(listener);
	}

	/**
	 * Enable/Disable bluetooth (refused since Android 13)
	 * @param enable Enable or disable
	 * @return boolean
	 */
	@DeprecatedSinceApi(api = Build.VERSION_CODES.TIRAMISU)
	public static boolean enableBluetooth(boolean enable) {
		BleEngine e = engine();
		return e != null && e.enableBluetooth(enable);
	}

	/**
	 * Get a BleDevice object from a mac address
	 * @param mac The mac address (any case)
	 * @return BleDevice, null when invalid
	 */
	@Nullable
	public static BleDevice getBleDeviceFromMac(@NonNull String mac) {
		BleEngine e = engine();
		if (e == null) return null;
		try {
			BleDevice d = new BleDevice(e.adapter().getRemoteDevice(mac.toUpperCase(Locale.ROOT)));
			// The name known by KnBle (a bonded or connected device), no binder call
			String name = e.knownName(d);
			return name == null ? d : d.withName(name);
		} catch (IllegalArgumentException ex) {
			if (DEBUG) ex.printStackTrace();
			return null;
		}
	}

	// endregion

	// region Scan

	/**
	 * True from startScan() until stopScan(), the timeout or a failure; changes at once with startScan() and stopScan()
	 */
	public static boolean isScanning() {
		BleEngine e = engine();
		return e != null && e.scan().isScanning();
	}

	/**
	 * Get last scan error
	 * @return int from BleScanCallback
	 */
	public static int getLastScanError() {
		BleEngine e = engine();
		return e == null ? BleScanCallback.NO_ERROR : e.scan().lastError();
	}

	/**
	 * Filters of the next scans (a running scan keeps its filters until it is started again)
	 */
	public static void setScanFilter(@NonNull ScanFilters scanFilter) {
		BleEngine e = engine();
		if (e != null) e.scan().setFilters(scanFilter);
	}

	@Nullable
	public static ScanFilters getScanFilters() {
		BleEngine e = engine();
		return e == null ? null : e.scan().filters();
	}

	public static void setScanSettings(@NonNull ScanSettings scanSettings) {
		BleEngine e = engine();
		if (e != null) e.scan().setSettings(scanSettings);
	}

	@Nullable
	public static ScanSettings getScanSettings() {
		BleEngine e = engine();
		return e == null ? null : e.scan().settings();
	}

	/**
	 * Start devices scan (a running scan ends first). The scan lasts until stopScan() or its timeout: KnBle starts the
	 * scan of the system again by itself when the phone needs it, without telling the app. Fails with
	 * onScanFailed(PERMISSION_DENIED or LOCATION_DISABLED) when Android would deliver nothing.
	 * @param callback BleScanCallback
	 */
	public static void startScan(@NonNull BleScanCallback callback) {
		BleEngine e = engine();
		if (e == null) {
			noEngine(() -> callback.onScanFailed(BleScanCallback.SCANNER_UNAVAILABLE));
			return;
		}
		e.scan().start(callback);
	}

	public static void stopScan() {
		BleEngine e = engine();
		if (e != null) e.scan().stop();
	}

	/**
	 * Return scanned device list (the devices lost for more than 5 min are dropped)
	 * @return The latest state of each device
	 */
	@NonNull
	public static List<BleDevice> getScannedDevices() {
		BleEngine e = engine();
		return e == null ? Collections.emptyList() : e.scan().scannedDevices();
	}

	public static void clearScannedDevices() {
		BleEngine e = engine();
		if (e != null) e.scan().clearScannedDevices();
	}

	/**
	 * Stop and reset devices scan: onScanFinished gets the devices of the scan, the list is empty right after
	 * @param resetSettings Reset settings
	 * @param resetFilters Reset filters
	 */
	public static void resetScan(boolean resetSettings, boolean resetFilters) {
		BleEngine e = engine();
		if (e != null) e.scan().reset(resetSettings, resetFilters);
	}

	/**
	 * Background scan (Android 8+): the system delivers the results to the PendingIntent (a BroadcastReceiver, mutable on
	 * Android 12+), even when the app is not running. The hardware filters (names, MACs, manufacturer IDs, service UUIDs
	 * and data) go to the system even alongside name prefix or suffix and MAC prefix filters, which only narrow down the
	 * results in parseBackgroundScan; without a hardware filter nothing comes with the screen off. Started again by KnBle when Bluetooth comes
	 * back while the process lives; the app starts it again at each launch.
	 * @param callback Start result, may be null
	 */
	public static void startBackgroundScan(@NonNull PendingIntent intent, @NonNull ScanFilters filters, @NonNull ScanSettings settings,
										   @Nullable BleBackgroundScanCallback callback) {
		BleEngine e = engine();
		if (e != null) e.scan().startBackground(intent, filters, settings, callback);
		else if (callback != null) noEngine(() -> callback.onBackgroundScanFailed(BleScanCallback.SCANNER_UNAVAILABLE));
	}

	public static void stopBackgroundScan(@NonNull PendingIntent intent) {
		BleEngine e = engine();
		if (e != null) e.scan().stopBackground(intent);
	}

	/**
	 * Devices delivered to the PendingIntent of a background scan (in the BroadcastReceiver), no binder call
	 * @param filters The filters of the scan, to check those the system cannot (null: all devices)
	 */
	@NonNull
	public static List<BleDevice> parseBackgroundScan(@NonNull Intent intent, @Nullable ScanFilters filters) {
		return ScanEngine.parseBackground(intent, filters);
	}

	/**
	 * @return BleScanCallback error when the system stopped the background scan, NO_ERROR otherwise
	 */
	public static int getBackgroundScanError(@NonNull Intent intent) {
		return ScanEngine.backgroundError(intent);
	}

	// endregion

	// region Connection

	@NonNull
	public static List<BleDevice> getConnectedDevices() {
		BleEngine e = engine();
		return e == null ? Collections.emptyList() : e.connectedDevices();
	}

	public static boolean isConnected(@NonNull BleDevice device) {
		return getDeviceConnState(device) == BleGattCallback.CONNECTED;
	}

	/**
	 * Get device connection state
	 * @param device The device
	 * @return BleGattCallback.DISCONNECTED, CONNECTING or CONNECTED
	 */
	public static int getDeviceConnState(@NonNull BleDevice device) {
		BleEngine e = engine();
		return e == null ? BleGattCallback.DISCONNECTED : e.state(device);
	}

	/**
	 * The BluetoothGatt of a device: calling its methods directly bypasses the queue of KnBle
	 * @param device The device
	 * @return BluetoothGatt|null
	 */
	@Nullable
	public static BluetoothGatt getBluetoothGatt(@NonNull BleDevice device) {
		BleEngine e = engine();
		DeviceActor a = e == null ? null : e.actor(device);
		return a == null ? null : a.gatt();
	}

	/**
	 * Connect to a device (already connected: onConnectSuccess again with the current services). A failed attempt
	 * (133, 62, 147...) is made again by KnBle (KnBleSettings.setConnectAttempts).
	 * @param device The device
	 * @param callback The callback
	 */
	public static void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		connect(device, false, callback);
	}

	/**
	 * Connect to a device, kept connected with autoReconnect: after a loss (onDisconnected), KnBle connects again by
	 * itself (onConnecting, then onConnectSuccess when the device is back): two direct connections 1 and 2 s after the
	 * loss, then a background connection without timeout (it goes on while the phone sleeps), also after Bluetooth comes
	 * back. Ends with disconnect() (onDisconnected(true, CANCELLED) when its onConnecting was told). The operations
	 * asked while reconnecting fail with NOT_CONNECTED.
	 * @param device The device
	 * @param autoReconnect Reconnect after a loss (or a failed first connection) until disconnect()
	 * @param callback The callback
	 */
	public static void connect(@NonNull BleDevice device, boolean autoReconnect, @NonNull BleGattCallback callback) {
		BleEngine e = engine();
		if (e == null) {
			noEngine(() -> callback.onDisconnected(true, BleError.INTERNAL));
			return;
		}
		e.connect(device, callback, autoReconnect);
	}

	/**
	 * Set BleGattCallback of a device
	 * @param device The device
	 * @param callback The callback
	 */
	public static void setGattCallback(@NonNull BleDevice device, @NonNull BleGattCallback callback) {
		BleEngine e = engine();
		if (e != null) e.onDevice(device, a -> a.setCallback(callback));
	}

	/**
	 * Parameters of the connection applied by the controller (Android 8+): interval, latency, supervision timeout
	 * @return null when not connected or not known yet
	 */
	@Nullable
	public static BleConnectionParameters getConnectionParameters(@NonNull BleDevice device) {
		BleEngine e = engine();
		DeviceActor a = e == null ? null : e.actor(device);
		return a == null ? null : a.connectionParameters();
	}

	/**
	 * Disconnect a device (and stop its auto-reconnection)
	 */
	public static void disconnect(@NonNull BleDevice device) {
		BleEngine e = engine();
		if (e != null) e.onDevice(device, a -> a.disconnect(false));
	}

	public static void disconnectAll() {
		BleEngine e = engine();
		if (e != null) e.disconnectAll(false);
	}

	/**
	 * Disconnect a device and end its thread at once (without it, the thread of a disconnected device ends by itself)
	 * @param device The device
	 */
	public static void destroyDevice(@NonNull BleDevice device) {
		BleEngine e = engine();
		if (e != null) e.onDevice(device, a -> a.disconnect(true));
	}

	public static void destroyAllDevices() {
		BleEngine e = engine();
		if (e != null) e.disconnectAll(true);
		sUuids.clear();
	}

	/**
	 * Ends the operation in progress of a device (an OTA or a splittedWrite at its next packet) and the waiting ones:
	 * their callbacks get BleError.CANCELLED
	 * @param device The device
	 */
	public static void cancel(@NonNull BleDevice device) {
		BleEngine e = engine();
		if (e != null) e.onDevice(device, DeviceActor::cancel);
	}

	// endregion

	// region Bonds

	/**
	 * Pair with a device (connected or not), BLE or Bluetooth classic: the system shows its dialog when the device needs
	 * one, unless the app answers it (BleBondCallback.onPairingRequest). Already bonded: onBonded at once. The pairings
	 * the stack starts by itself (an encrypted characteristic, a secure classic connection) need no call: the operation
	 * that needed it goes on once bonded.
	 */
	public static void createBond(@NonNull BleDevice device, @NonNull BleBondCallback callback) {
		createBond(device, null, callback);
	}

	/**
	 * Pair with a device whose code is known: given by KnBle when Android asks for it, without the dialog of the system
	 * (on most phones). The pairings the user confirms (numeric comparison, just works) still show the dialog of the
	 * system: an app cannot confirm them. Android 17+ refuses the codes to an app targeting API 37+: the dialog of the
	 * system asks the user there.
	 * @param pin PIN of a Bluetooth classic device (legacy pairing, 1 to 16 characters, often "0000" or "1234"), or
	 *            passkey of a BLE device (1 to 6 digits); null: the code is asked (onPairingRequest, else the dialog)
	 */
	public static void createBond(@NonNull BleDevice device, @Nullable String pin, @NonNull BleBondCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onBondFailed(BleError.INTERNAL));
		else e.createBond(device, pin == null || pin.isEmpty() ? null : pin, callback);
	}

	/**
	 * Remove the bond on the phone side (the device may keep its own). Android 17+ refuses it to an app targeting API
	 * 37+, except for a device associated with associate().
	 */
	public static void removeBond(@NonNull BleDevice device) {
		BleEngine e = engine();
		if (e != null) e.removeBond(device);
	}

	/**
	 * @return BluetoothDevice.BOND_NONE, BOND_BONDING or BOND_BONDED (kept up to date by KnBle, no binder call once the
	 * bonds are loaded: at init when Bluetooth is on, else at the first call)
	 */
	public static int getBondState(@NonNull BleDevice device) {
		BleEngine e = engine();
		return e == null ? android.bluetooth.BluetoothDevice.BOND_NONE : e.bondState(device);
	}

	public static boolean isBonded(@NonNull BleDevice device) {
		return getBondState(device) == android.bluetooth.BluetoothDevice.BOND_BONDED;
	}

	@NonNull
	public static List<BleDevice> getBondedDevices() {
		BleEngine e = engine();
		return e == null ? Collections.emptyList() : e.bondedDevices();
	}

	// endregion

	// region Companion device

	/**
	 * Associate the app with a device (Android 8+): the system dialog lists the nearby devices matching the filters,
	 * the user picks one. An associated device is found and connected without location permission, and gives the app
	 * the background privileges of a companion app (REQUEST_COMPANION_RUN_IN_BACKGROUND...). KnBle shows the dialog
	 * itself: no onActivityResult in the app. Fails with NOT_SUPPORTED on a phone without the companion device service,
	 * REFUSED when Android does not show the dialog (an app in the background, Android 10+).
	 * @param context An Activity (else the dialog opens in a new task)
	 * @param filters Names (exact, prefix, suffix), MACs, manufacturer IDs, service UUIDs and data; empty: every LE device
	 * @param singleDevice The dialog proposes the first matching device only
	 */
	public static void associate(@NonNull Context context, @NonNull ScanFilters filters, boolean singleDevice, @NonNull BleAssociateCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onAssociationFailed(BleError.INTERNAL));
		else Companion.associate(e, context, filters, singleDevice, callback);
	}

	/**
	 * Devices associated with the app, read off the main thread
	 */
	public static void getAssociatedDevices(@NonNull BleDevicesCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onDevices(Collections.emptyList()));
		else Companion.associatedDevices(e, callback);
	}

	public static void disassociate(@NonNull BleDevice device) {
		BleEngine e = engine();
		if (e != null) Companion.disassociate(e, device);
	}

	// endregion

	// region Services

	@Nullable
	private static List<BluetoothGattService> services(@NonNull BleDevice device) {
		BleEngine e = engine();
		DeviceActor a = e == null ? null : e.actor(device);
		return a == null || a.state() != BleGattCallback.CONNECTED ? null : a.services();
	}

	/**
	 * Services of a connected device, empty when not connected
	 */
	@NonNull
	public static List<BluetoothGattService> getServices(@NonNull BleDevice device) {
		List<BluetoothGattService> services = services(device);
		return services == null ? Collections.emptyList() : services;
	}

	/**
	 * A service of a connected device
	 * @return null when not connected or not found
	 */
	@Nullable
	public static BluetoothGattService getService(@NonNull BleDevice device, @NonNull UUID serviceUUID) {
		List<BluetoothGattService> services = services(device);
		if (services == null) return null;
		for (BluetoothGattService s : services) if (s.getUuid().equals(serviceUUID)) return s;
		return null;
	}

	@Nullable
	public static BluetoothGattService getService(@NonNull BleDevice device, @NonNull String serviceUUID) {
		return getService(device, uuid(serviceUUID));
	}

	@Nullable
	public static BluetoothGattCharacteristic getCharacteristic(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID) {
		BluetoothGattService s = getService(device, serviceUUID);
		return s == null ? null : s.getCharacteristic(characteristicUUID);
	}

	@Nullable
	public static BluetoothGattCharacteristic getCharacteristic(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID) {
		return getCharacteristic(device, uuid(serviceUUID), uuid(characteristicUUID));
	}

	@Nullable
	public static BluetoothGattDescriptor getDescriptor(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID) {
		BluetoothGattCharacteristic c = getCharacteristic(device, serviceUUID, characteristicUUID);
		return c == null ? null : c.getDescriptor(descriptorUUID);
	}

	@Nullable
	public static BluetoothGattDescriptor getDescriptor(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull String descriptorUUID) {
		return getDescriptor(device, uuid(serviceUUID), uuid(characteristicUUID), uuid(descriptorUUID));
	}

	private static void deliver(@NonNull Runnable r) {
		BleEngine e = BleEngine.get();
		if (e != null) e.dispatcher().post(r);
		else noEngine(r);
	}

	@Nullable
	private static volatile Dispatcher sNoEngine;

	/**
	 * A callback while KnBle is not initialised (init not called, or no Bluetooth LE): on the main thread too, never inside
	 * the call of the app
	 */
	private static void noEngine(@NonNull Runnable callback) {
		Dispatcher d = sNoEngine;
		if (d == null) {
			synchronized (KnBle.class) {
				if (sNoEngine == null) sNoEngine = new Dispatcher(android.os.Looper.getMainLooper());
				d = sNoEngine;
			}
		}
		d.post(callback);
	}

	/**
	 * @deprecated the synchronous getService(device, uuid) returns it at once
	 */
	@Deprecated
	public static void getService(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull BleGetService callback) {
		getService(device, uuid(serviceUUID), callback);
	}

	/**
	 * @deprecated the synchronous getService(device, uuid) returns it at once
	 */
	@Deprecated
	public static void getService(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull BleGetService callback) {
		BluetoothGattService s = getService(device, serviceUUID);
		deliver(() -> {
			if (s != null) callback.onSuccess(s);
			else callback.onFailed();
		});
	}

	/**
	 * @deprecated the synchronous getCharacteristic(device, service, characteristic) returns it at once
	 */
	@Deprecated
	public static void getCharacteristic(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleGetCharacteristic callback) {
		getCharacteristic(device, uuid(serviceUUID), uuid(characteristicUUID), callback);
	}

	/**
	 * @deprecated the synchronous getCharacteristic(device, service, characteristic) returns it at once
	 */
	@Deprecated
	public static void getCharacteristic(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull BleGetCharacteristic callback) {
		BluetoothGattCharacteristic c = getCharacteristic(device, serviceUUID, characteristicUUID);
		deliver(() -> {
			if (c != null) callback.onSuccess(c);
			else callback.onFailed();
		});
	}

	/**
	 * @deprecated the synchronous getDescriptor(device, service, characteristic, descriptor) returns it at once
	 */
	@Deprecated
	public static void getDescriptor(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull String descriptorUUID, @NonNull BleGetDescriptor callback) {
		getDescriptor(device, uuid(serviceUUID), uuid(characteristicUUID), uuid(descriptorUUID), callback);
	}

	/**
	 * @deprecated the synchronous getDescriptor(device, service, characteristic, descriptor) returns it at once
	 */
	@Deprecated
	public static void getDescriptor(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID, @NonNull BleGetDescriptor callback) {
		BluetoothGattDescriptor d = getDescriptor(device, serviceUUID, characteristicUUID, descriptorUUID);
		deliver(() -> {
			if (d != null) callback.onSuccess(d);
			else callback.onFailed();
		});
	}

	// endregion

	// region Link parameters

	/**
	 * Request connection priority (BluetoothGatt.CONNECTION_PRIORITY_xxx)
	 */
	public static void requestConnectionPriority(@NonNull BleDevice device, int connectionPriority) {
		BleEngine e = engine();
		if (e != null) e.onDevice(device, a -> a.requestConnectionPriority(connectionPriority));
	}

	/**
	 * Link parameters of a fast transfer, in the queue of the device: priority HIGH, PHY 2M (kept in 1M when refused),
	 * MTU 517. The callback always comes, with the values in use.
	 */
	public static void requestFastTransfer(@NonNull BleDevice device, @NonNull BleFastTransferCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onFastTransfer(23, 1, 1));
		else e.enqueue(device, GattOpFactory.fastTransfer(e, callback));
	}

	/**
	 * Get MTU of a device
	 * @return The MTU, 0 for an unknown device
	 */
	public static int getMtu(@NonNull BleDevice device) {
		BleEngine e = engine();
		DeviceActor a = e == null ? null : e.actor(device);
		return a == null ? 0 : a.mtu();
	}

	public static void requestMtu(@NonNull BleDevice device, int mtu) {
		requestMtu(device, mtu, null);
	}

	/**
	 * Request MTU: the callback always comes, with the MTU in use (also when the request is refused)
	 */
	public static void requestMtu(@NonNull BleDevice device, int mtu, @Nullable BleMtuChangedCallback callback) {
		BleEngine e = engine();
		if (e != null) e.enqueue(device, GattOpFactory.requestMtu(e, mtu, callback));
		else if (callback != null) noEngine(() -> callback.onMtuChanged(23));
	}

	/**
	 * Read PHY (Android 8+, 1M on Android 7)
	 */
	public static void readPhy(@NonNull BleDevice device, @NonNull BlePhyValueCallback callback) {
		BleEngine e = engine();
		if (e != null) e.enqueue(device, GattOpFactory.readPhy(e, callback));
		else noEngine(() -> callback.onPhyValue(1, 1));
	}

	public static void setPreferredPhy(@NonNull BleDevice device, int txPhy, int rxPhy, int phyOptions) {
		setPreferredPhy(device, txPhy, rxPhy, phyOptions, null);
	}

	/**
	 * Set preferred PHY (Android 8+): the callback gives the PHY really in use (1M when the device refuses, and on Android 7)
	 */
	public static void setPreferredPhy(@NonNull BleDevice device, int txPhy, int rxPhy, int phyOptions, @Nullable BlePhyValueCallback callback) {
		BleEngine e = engine();
		if (e != null) e.enqueue(device, GattOpFactory.setPhy(e, txPhy, rxPhy, phyOptions, callback));
		else if (callback != null) noEngine(() -> callback.onPhyValue(1, 1));
	}

	/**
	 * RSSI of the connection
	 */
	public static void readRssi(@NonNull BleDevice device, @NonNull BleReadRssiCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onRssiFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.readRssi(e, callback));
	}

	// endregion

	// region Read, write, notify

	public static void read(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		read(device, uuid(serviceUUID), uuid(characteristicUUID), callback);
	}

	public static void read(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull BleReadCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onReadFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.read(e, GattOpFactory.target(serviceUUID, characteristicUUID), callback));
	}

	public static void read(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BleReadCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onReadFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.read(e, GattOpFactory.target(service, characteristic), callback));
	}

	public static void write(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
		write(device, uuid(serviceUUID), uuid(characteristicUUID), data, noResponse, callback);
	}

	public static void write(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.write(e, GattOpFactory.target(serviceUUID, characteristicUUID), data, noResponse, callback));
	}

	public static void write(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.write(e, GattOpFactory.target(service, characteristic), data, noResponse, callback));
	}

	/**
	 * Write then wait for the answer of the device: the first notification of notifyCharacteristic once the write is
	 * issued (taken by the request, not given to the subscription of the app). Subscribes when the app did not.
	 * Written with response when the characteristic allows it. No answer within the operation timeout: TIMEOUT.
	 */
	public static void request(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String writeCharacteristicUUID, @NonNull String notifyCharacteristicUUID,
							   @NonNull byte[] data, @NonNull BleRequestCallback callback) {
		request(device, uuid(serviceUUID), uuid(writeCharacteristicUUID), uuid(notifyCharacteristicUUID), data, callback);
	}

	public static void request(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID writeCharacteristicUUID, @NonNull UUID notifyCharacteristicUUID,
							   @NonNull byte[] data, @NonNull BleRequestCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onRequestFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.request(e, GattOpFactory.target(serviceUUID, writeCharacteristicUUID),
				GattOpFactory.target(serviceUUID, notifyCharacteristicUUID), data, callback));
	}

	/**
	 * Reliable write: every value is queued by the device (one Prepare Write each: MTU - 5 bytes at most, DATA_TOO_LARGE
	 * otherwise), then the device applies them all
	 * together (execute), or none of them on a failure (abort)
	 */
	public static void reliableWrite(@NonNull BleDevice device, @NonNull BleReliableWrite writes, @NonNull BleWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) {
			noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
			return;
		}
		List<GattOp.CharTarget> targets = new ArrayList<>();
		for (int i = 0; i < writes.size(); i++) targets.add(GattOpFactory.target(writes.mServices.get(i), writes.mCharacteristics.get(i)));
		e.enqueue(device, GattOpFactory.reliableWrite(e, targets, new ArrayList<>(writes.mValues), callback));
	}

	/**
	 * Splitted write data into a gatt characteristic
	 * @param splitSize Bytes per packet, 0 for the largest one of the connection (MTU - 3, 512 at most)
	 * @param intervalBetweenTwoPackage Pause in ms between two packets, 0 for none
	 */
	public static void splittedWrite(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] data, int splitSize, boolean noResponse, long intervalBetweenTwoPackage, @NonNull BleSplittedWriteCallback callback) {
		splittedWrite(device, uuid(serviceUUID), uuid(characteristicUUID), data, splitSize, noResponse, intervalBetweenTwoPackage, callback);
	}

	public static void splittedWrite(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] data, int splitSize, boolean noResponse, long intervalBetweenTwoPackage, @NonNull BleSplittedWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.splittedWrite(e, GattOpFactory.target(serviceUUID, characteristicUUID), data, splitSize, noResponse, intervalBetweenTwoPackage, callback));
	}

	public static void splittedWrite(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data, int splitSize, boolean noResponse, long intervalBetweenTwoPackage, @NonNull BleSplittedWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.splittedWrite(e, GattOpFactory.target(service, characteristic), data, splitSize, noResponse, intervalBetweenTwoPackage, callback));
	}

	/**
	 * Enable notify (or indicate when the characteristic only indicates)
	 */
	public static void enableNotify(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleNotifyCallback callback) {
		enableNotify(device, uuid(serviceUUID), uuid(characteristicUUID), callback);
	}

	public static void enableNotify(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull BleNotifyCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onNotifyFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.enableNotify(e, GattOpFactory.target(serviceUUID, characteristicUUID), callback));
	}

	public static void enableNotify(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BleNotifyCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onNotifyFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.enableNotify(e, GattOpFactory.target(service, characteristic), callback));
	}

	public static void disableNotify(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID) {
		disableNotify(device, uuid(serviceUUID), uuid(characteristicUUID));
	}

	public static void disableNotify(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID) {
		BleEngine e = engine();
		if (e != null) e.enqueue(device, GattOpFactory.disableNotify(e, GattOpFactory.target(serviceUUID, characteristicUUID)));
	}

	public static void disableNotify(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic) {
		BleEngine e = engine();
		if (e != null) e.enqueue(device, GattOpFactory.disableNotify(e, GattOpFactory.target(service, characteristic)));
	}

	public static void readDesc(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull String descriptorUUID, @NonNull BleReadCallback callback) {
		readDesc(device, uuid(serviceUUID), uuid(characteristicUUID), uuid(descriptorUUID), callback);
	}

	public static void readDesc(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID, @NonNull BleReadCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onReadFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.readDesc(e, GattOpFactory.target(serviceUUID, characteristicUUID, descriptorUUID), callback));
	}

	public static void readDesc(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BluetoothGattDescriptor descriptor, @NonNull BleReadCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onReadFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.readDesc(e, GattOpFactory.target(service, characteristic, descriptor), callback));
	}

	public static void writeDesc(@NonNull BleDevice device, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull String descriptorUUID, @NonNull byte[] data, @NonNull BleWriteCallback callback) {
		writeDesc(device, uuid(serviceUUID), uuid(characteristicUUID), uuid(descriptorUUID), data, callback);
	}

	public static void writeDesc(@NonNull BleDevice device, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID, @NonNull byte[] data, @NonNull BleWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.writeDesc(e, GattOpFactory.target(serviceUUID, characteristicUUID, descriptorUUID), data, callback));
	}

	public static void writeDesc(@NonNull BleDevice device, @NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BluetoothGattDescriptor descriptor, @NonNull byte[] data, @NonNull BleWriteCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.writeDesc(e, GattOpFactory.target(service, characteristic, descriptor), data, callback));
	}

	// endregion

	// region Peripheral

	/**
	 * Advertise the phone (a running advertising is replaced: the callback of a start still without result gets
	 * CANCELLED). Started again by KnBle when Bluetooth comes back. No answer of the system in 5 s: TIMEOUT.
	 * Android 12+: BLUETOOTH_ADVERTISE permission.
	 */
	public static void startAdvertising(@NonNull BleAdvertiseData data, @NonNull BleAdvertiseCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onAdvertisingFailed(BleError.INTERNAL));
		else e.peripheral().startAdvertising(data, callback);
	}

	/**
	 * Stop the advertising: no callback after this call
	 */
	public static void stopAdvertising() {
		BleEngine e = engine();
		if (e != null) e.peripheral().stopAdvertising();
	}

	public static boolean isAdvertising() {
		BleEngine e = engine();
		return e != null && e.peripheral().isAdvertising();
	}

	/**
	 * Open the GATT server of the phone with these services (a running server is replaced: the callback of a start still
	 * without result gets CANCELLED). The services are copied: changing them afterwards changes nothing. KnBle answers the
	 * reads with the stored values, stores the writes, handles the subscriptions (kept between the connections of a bonded
	 * central); opened again when Bluetooth comes back (the values are kept). Centrals find it through startAdvertising.
	 * Android 12+: BLUETOOTH_CONNECT permission.
	 */
	public static void startServer(@NonNull BleServerCallback callback, @NonNull BleServerService... services) {
		startServer(Arrays.asList(services), callback);
	}

	public static void startServer(@NonNull List<BleServerService> services, @NonNull BleServerCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onServerFailed(BleError.INTERNAL));
		else e.peripheral().startServer(services, callback);
	}

	/**
	 * Close the GATT server: its centrals are disconnected (not the other LE links of the phone), no callback after this
	 * call
	 */
	public static void stopServer() {
		BleEngine e = engine();
		if (e != null) e.peripheral().stopServer();
	}

	public static boolean isServerStarted() {
		BleEngine e = engine();
		return e != null && e.peripheral().isServerStarted();
	}

	/**
	 * Value served to the next reads of a characteristic of the server
	 * @throws IllegalArgumentException over 512 bytes (longest attribute value of Bluetooth)
	 */
	public static void setServerValue(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] value) {
		if (value.length > 512) throw new IllegalArgumentException("value of " + value.length + " bytes, 512 at most");
		BleEngine e = engine();
		if (e != null) e.peripheral().setValue(serviceUUID, characteristicUUID, value);
	}

	public static void setServerValue(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] value) {
		setServerValue(uuid(serviceUUID), uuid(characteristicUUID), value);
	}

	/**
	 * Notify every central subscribed to a characteristic of the server (an indication to those subscribed to indications
	 * only). The value is also stored for the reads. One notification at a time, in order: the next one goes once the
	 * system sent the previous one.
	 * @param callback Success once every subscribed central got it, else the first failure (NOT_CONNECTED when nobody is
	 *                 subscribed, DATA_TOO_LARGE over MTU - 3 or over 512 bytes); may be null
	 */
	public static void notifyCentrals(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] value, @Nullable BleWriteCallback callback) {
		BleEngine e = engine();
		if (e != null) e.peripheral().notify(null, serviceUUID, characteristicUUID, value, callback);
		else if (callback != null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
	}

	public static void notifyCentrals(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] value, @Nullable BleWriteCallback callback) {
		notifyCentrals(uuid(serviceUUID), uuid(characteristicUUID), value, callback);
	}

	/**
	 * Notify one central (when subscribed)
	 */
	public static void notifyCentral(@NonNull BleDevice central, @NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] value,
									 @Nullable BleWriteCallback callback) {
		BleEngine e = engine();
		if (e != null) e.peripheral().notify(central, serviceUUID, characteristicUUID, value, callback);
		else if (callback != null) noEngine(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
	}

	public static void notifyCentral(@NonNull BleDevice central, @NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull byte[] value,
									 @Nullable BleWriteCallback callback) {
		notifyCentral(central, uuid(serviceUUID), uuid(characteristicUUID), value, callback);
	}

	/**
	 * Centrals connected to the server: the devices that sent it a request, and the bonded centrals back with their
	 * subscriptions (Android tells the server of every LE link of the phone, a link without request is not a central)
	 */
	@NonNull
	public static List<BleDevice> getConnectedCentrals() {
		BleEngine e = engine();
		return e == null ? Collections.emptyList() : e.peripheral().centrals();
	}

	/**
	 * Disconnect a central from the server. The link stays up when another app (or the GATT client of KnBle) still uses it.
	 */
	public static void disconnectCentral(@NonNull BleDevice central) {
		BleEngine e = engine();
		if (e != null) e.peripheral().disconnectCentral(central);
	}

	// endregion

	// region Bluetooth classic

	/**
	 * Discovery of the Bluetooth classic devices (inquiry, about 12 s). The radio is shared: it slows down the BLE
	 * transfers in progress, and a classic connection ends it. Called while one runs: it ends, the new one starts once
	 * Android ended the inquiry. Android 12+: BLUETOOTH_SCAN, Android 6 to 11: location.
	 */
	public static void startClassicDiscovery(@NonNull BleClassicDiscoveryCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onDiscoveryFailed(BleError.INTERNAL));
		else e.classic().startDiscovery(callback);
	}

	public static void stopClassicDiscovery() {
		BleEngine e = engine();
		if (e != null) e.classic().stopDiscovery();
	}

	public static boolean isClassicDiscovering() {
		BleEngine e = engine();
		return e != null && e.classic().isDiscovering();
	}

	/**
	 * Connect to a Bluetooth classic device over RFCOMM, Serial Port Profile (already connected: onConnected again). The
	 * bytes it sends come in onData, writeClassic sends. A failed attempt is made again on channel 1, then after 1 s
	 * (KnBleSettings.setConnectAttempts, within setConnectTimeout). Secure link: the system pairs first when needed.
	 */
	public static void connectClassic(@NonNull BleDevice device, @NonNull BleClassicCallback callback) {
		connectClassic(device, new ClassicOptions.Builder().build(), false, callback);
	}

	/**
	 * @param autoReconnect Reconnect after a loss (or a failed first connection) until disconnectClassic(): 1, 2, 5 then
	 *                      every 10 s, also after Bluetooth comes back
	 */
	public static void connectClassic(@NonNull BleDevice device, boolean autoReconnect, @NonNull BleClassicCallback callback) {
		connectClassic(device, new ClassicOptions.Builder().build(), autoReconnect, callback);
	}

	/**
	 * Called while connecting: the connection goes on for this callback (the one it replaces gets onDisconnected(true,
	 * CANCELLED)). Called right after disconnectClassic: a new connection once the end of the previous one is told.
	 * @param options Service UUID, secure or not, channel
	 */
	public static void connectClassic(@NonNull BleDevice device, @NonNull ClassicOptions options, boolean autoReconnect, @NonNull BleClassicCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onDisconnected(true, BleError.INTERNAL));
		else e.classic().connect(device, options, autoReconnect, callback);
	}

	/**
	 * Bytes to the device, in call order, by a thread of the connection (the stream of RFCOMM holds them when the device
	 * is slow, the caller never waits). Called while connecting: sent once connected.
	 */
	public static void writeClassic(@NonNull BleDevice device, @NonNull byte[] data) {
		writeClassic(device, data, null);
	}

	/**
	 * @param callback onWriteSuccess once given to the stream, onWriteFailed(NOT_CONNECTED) when the connection ends first
	 */
	public static void writeClassic(@NonNull BleDevice device, @NonNull byte[] data, @Nullable BleWriteCallback callback) {
		BleEngine e = engine();
		if (e != null) e.classic().write(device, data, callback);
		else if (callback != null) noEngine(() -> callback.onWriteFailed(BleError.INTERNAL));
	}

	/**
	 * Disconnect a Bluetooth classic device (and stop its auto-reconnection): onDisconnected after the last bytes read;
	 * while connecting or auto-reconnecting: onDisconnected(true, CANCELLED)
	 */
	public static void disconnectClassic(@NonNull BleDevice device) {
		BleEngine e = engine();
		if (e != null) e.classic().disconnect(device);
	}

	public static void disconnectAllClassic() {
		BleEngine e = engine();
		if (e != null) e.classic().disconnectAll();
	}

	public static boolean isClassicConnected(@NonNull BleDevice device) {
		return getClassicConnState(device) == BleGattCallback.CONNECTED;
	}

	/**
	 * @return BleGattCallback.DISCONNECTED, CONNECTING or CONNECTED
	 */
	public static int getClassicConnState(@NonNull BleDevice device) {
		BleEngine e = engine();
		return e == null ? BleGattCallback.DISCONNECTED : e.classic().state(device);
	}

	/**
	 * The connected classic devices: those the app connected to, and the clients of its classic server
	 */
	@NonNull
	public static List<BleDevice> getClassicConnectedDevices() {
		BleEngine e = engine();
		return e == null ? Collections.emptyList() : e.classic().connectedDevices();
	}

	/**
	 * Bluetooth classic server (RFCOMM, Serial Port Profile, secure), with the default ClassicServerOptions
	 */
	public static void startClassicServer(@NonNull BleClassicServerCallback callback) {
		startClassicServer(new ClassicServerOptions.Builder().build(), callback);
	}

	/**
	 * Bluetooth classic server: the phone publishes an RFCOMM service (its UUID in the SDP record) and accepts the
	 * devices that connect to it, up to ClassicServerOptions.getMaxClients(). Each client is then a classic connection
	 * like the ones of connectClassic: its callback is the one onClientConnected returns, writeClassic and
	 * disconnectClassic with its device. A running server is replaced (its clients disconnected; the callback of a
	 * start still without result gets CANCELLED). The server listens again by itself when Bluetooth comes back. A device
	 * that never paired with the phone has to find it first: make the phone discoverable
	 * (BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE). Android 12+: BLUETOOTH_CONNECT permission.
	 */
	public static void startClassicServer(@NonNull ClassicServerOptions options, @NonNull BleClassicServerCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onServerFailed(BleError.INTERNAL));
		else e.classic().startServer(options, callback);
	}

	/**
	 * Stop the classic server: no more clients, those connected are disconnected (onDisconnected(false, 0) after their
	 * last bytes), no callback of the server after this call
	 */
	public static void stopClassicServer() {
		BleEngine e = engine();
		if (e != null) e.classic().stopServer();
	}

	/**
	 * True from onServerStarted until stopClassicServer or onServerFailed (also while Bluetooth is off: it listens again
	 * when Bluetooth is back)
	 */
	public static boolean isClassicServerStarted() {
		BleEngine e = engine();
		return e != null && e.classic().isServerStarted();
	}

	// endregion

	// region OTA

	/**
	 * Silicon Labs AppLoader OTA (BGM111, BGM13P, BGM210P, ESP32 clones), on a connected device in DFU mode: control
	 * 0x00, the image in packets without response, control 0x03. Runs on the thread of the device, in its queue; the
	 * success comes once the device received the whole image.
	 * @param image The image (.gbl / .ebl / .bin)
	 */
	public static void otaSilabs(@NonNull BleDevice device, @NonNull byte[] image, @NonNull BleOtaCallback callback) {
		otaSilabs(device, image, new OtaOptions.Builder().build(), callback);
	}

	public static void otaSilabs(@NonNull BleDevice device, @NonNull byte[] image, @NonNull OtaOptions options, @NonNull BleOtaCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onOtaFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.silabsOta(e, image, options, callback));
	}

	/**
	 * TI Enhanced OAD (CC13xx, CC26xx): the device asks for each block, KnBle answers on the thread of the device.
	 * Needs an MTU of 25 at least (KnBle.requestMtu or requestFastTransfer first).
	 * @param image The OAD image, its 44 bytes header first (length checked)
	 */
	public static void otaTi(@NonNull BleDevice device, @NonNull byte[] image, @NonNull BleOtaCallback callback) {
		BleEngine e = engine();
		if (e == null) noEngine(() -> callback.onOtaFailed(BleError.NOT_CONNECTED));
		else e.enqueue(device, GattOpFactory.tiOad(e, image, callback));
	}

	// endregion
}
