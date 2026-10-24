package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Bluetooth classic server of the phone (KnBle.startClassicServer), on the main thread (or the Looper of KnBleSettings).
 * Only onClientConnected() is mandatory: a lambda works. Nothing comes after stopClassicServer, nor after another
 * startClassicServer (except CANCELLED).
 */
public interface BleClassicServerCallback {
	/**
	 * A device connected to the server. The connection is then the one of any classic device: writeClassic,
	 * disconnectClassic, getClassicConnState with this device.
	 * @return The callback of this connection: onConnected at once, then onData (the bytes of the client, none lost:
	 * the reading starts once it is given), then one onDisconnected(false, status). Null refuses the client (its socket
	 * is closed).
	 */
	@Nullable
	BleClassicCallback onClientConnected(@NonNull BleDevice client);

	/**
	 * Listening: the clients may connect. When Bluetooth turns off then on, the server listens again by itself (no
	 * second call).
	 */
	default void onServerStarted() {}

	/**
	 * @param error BleError: BLUETOOTH_DISABLED, PERMISSION_DENIED (BLUETOOTH_CONNECT on Android 12+), REFUSED (Android
	 *              refused the server, the UUID may be used by another app), CANCELLED (another startClassicServer before
	 *              the result of this one), INTERNAL (the server could not listen again after Android closed it: then
	 *              after onServerStarted). The clients already connected stay connected.
	 */
	default void onServerFailed(int error) {}

	/**
	 * A pairing that asks the user while a secure server listens, by a device KnBle does not otherwise work with (most
	 * likely a client of the server, which pairs before it connects). Same contract as
	 * BleClassicCallback.onPairingRequest: when request.canAnswer() (a legacy PIN), return true to answer it with
	 * request.setPin() or request.cancel(); else, or when false is returned (default), the dialog of the system asks
	 * the user, shown over the app while it is in the foreground.
	 */
	default boolean onPairingRequest(@NonNull BlePairingRequest request) {
		return false;
	}
}
