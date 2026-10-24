package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import ovh.karewan.knble.BlePairingRequest;

/**
 * Bluetooth classic connection (RFCOMM, KnBle.connectClassic). Only onData() is mandatory: a lambda works.
 */
public interface BleClassicCallback {
	/**
	 * Bytes sent by the device, in order, never dropped. A stream has no packets: the chunks read while the app is busy
	 * come merged (at most 256 KB per call), the framing of the messages is the job of the app. When the app is 1 MB
	 * behind, KnBle stops reading until it catches up: the RFCOMM flow control slows the device down.
	 */
	void onData(@NonNull byte[] data);

	/**
	 * Connected: the writes go at once (those asked while connecting go first). Again after an auto-reconnection.
	 */
	default void onConnected() {}

	/**
	 * A connection starts: asked by the app, or an auto-reconnection after onDisconnected. Always followed by one
	 * onConnected or onDisconnected (the failed rounds of an auto-reconnection are silent).
	 */
	default void onConnecting() {}

	/**
	 * @param connectFailed true when the connection never succeeded (all its attempts failed), false for a lost connection
	 * or a disconnection asked by the app
	 * @param status 0 when asked by the app, else a BleError: CONNECTION_LOST (link lost or closed by the device), TIMEOUT,
	 *               REFUSED (no answer, service not found or connection refused by the device), BOND_FAILED, CANCELLED
	 *               (disconnectClassic while connecting or reconnecting, a pairing cancelled, or this callback replaced
	 *               by another connectClassic while connecting), BLUETOOTH_DISABLED, PERMISSION_DENIED, BOND_LOST...
	 */
	default void onDisconnected(boolean connectFailed, int status) {}

	/**
	 * The secure connection needs a pairing that asks the user, on the main thread (not called when ClassicOptions.setPin
	 * gave the PIN). When request.canAnswer() (the device asks its PIN, legacy pairing), return true to answer it with
	 * request.setPin() or request.cancel(), at once or later from a dialog of the app: the dialog of the system is not
	 * shown. Else, or when false is returned (default), the dialog of the system asks the user (Secure Simple Pairing:
	 * a number to confirm), shown over the app while it is in the foreground. The time of the pairing does not count in
	 * the connection timeout.
	 */
	default boolean onPairingRequest(@NonNull BlePairingRequest request) {
		return false;
	}

	/**
	 * Android 16+: the device was bonded to the phone but could not give its keys (reset to factory settings, bond
	 * removed on its side, or another device using its address). KnBle stops the auto-reconnection (it would fail again,
	 * or ask the user again), makes no other attempt, and tells the end of the connection with BleError.BOND_LOST. The
	 * app asks the user to pair again: removeBond then createBond, or accept the re-pairing of the system on Android 17+
	 * (BlePairingRequest.isRepairing). A connectClassic of the app tries again.
	 * @param reason BluetoothDevice.BOND_LOSS_REASON_* (Android 16 QPR2+), 0 when unknown
	 */
	default void onBondLost(int reason) {}
}
