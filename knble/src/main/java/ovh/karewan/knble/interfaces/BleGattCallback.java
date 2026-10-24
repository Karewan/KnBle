package ovh.karewan.knble.interfaces;

import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;

import java.util.List;

import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.struct.BleConnectionParameters;

/**
 * Connection of a device. Only onConnectSuccess() is mandatory: a lambda works.
 */
public interface BleGattCallback {
	/**
	 * STATES
	 */
	int DISCONNECTED = 0;
	int CONNECTING = 1;
	int CONNECTED = 2;

	/**
	 * Connected and services discovered: the device is ready (again after an auto-reconnection)
	 */
	void onConnectSuccess(@NonNull List<BluetoothGattService> services);

	/**
	 * A connection starts: asked by the app, or an auto-reconnection after onDisconnected
	 */
	default void onConnecting() {}

	/**
	 * @param connectFailed true when the connection never succeeded (all its attempts failed), false for a lost connection
	 * or a disconnection asked by the app
	 * @param status Cause: GATT/HCI status of the stack (8 supervision timeout, 19 terminated by the device, 133...),
	 *               a BleError (TIMEOUT, BLUETOOTH_DISABLED, PERMISSION_DENIED, BOND_LOST...), 0 when asked by the app
	 */
	default void onDisconnected(boolean connectFailed, int status) {
		onDisconnected(connectFailed);
	}

	/**
	 * Form of KnBle 3, called by the default onDisconnected(boolean, int)
	 */
	default void onDisconnected(boolean connectFailed) {}

	/**
	 * The device changed its GATT table (Service Changed indication) and KnBle discovered it again. The subscriptions
	 * are lost (each BleNotifyCallback got onNotifyDisabled): the app subscribes again.
	 */
	default void onServicesChanged(@NonNull List<BluetoothGattService> services) {}

	/**
	 * Parameters applied by the controller (Android 8+), while connected. The latest ones: KnBle.getConnectionParameters
	 */
	default void onConnectionParametersChanged(@NonNull BleConnectionParameters parameters) {}

	/**
	 * A pairing of this device needs the user (an encrypted characteristic, or asked by the device), on the main thread.
	 * When request.canAnswer() (the device asks its passkey), return true to answer it with request.setPin() or
	 * request.cancel(), at once or later from a dialog of the app: the dialog of the system is not shown. Else, or when
	 * false is returned (default), the dialog of the system asks the user (a number to confirm, just works), shown over
	 * the app while it is in the foreground. The operation that needed the pairing goes on once bonded.
	 */
	default boolean onPairingRequest(@NonNull BlePairingRequest request) {
		return false;
	}

	/**
	 * Android 16+: the device was bonded to the phone but could not give its keys (reset to factory settings, bond
	 * removed on its side, or another device using its address). KnBle stops the auto-reconnection (it would fail again,
	 * or ask the user again), an operation waiting for the encryption fails with BleError.BOND_LOST, and the end of the
	 * connection is told with BOND_LOST. The app asks the user to pair again: removeBond then createBond, or accept the
	 * re-pairing of the system on Android 17+ (BlePairingRequest.isRepairing). A connect() of the app tries again.
	 * @param reason BluetoothDevice.BOND_LOSS_REASON_* (Android 16 QPR2+), 0 when unknown
	 */
	default void onBondLost(int reason) {}
}
