package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import java.util.UUID;

import ovh.karewan.knble.struct.BleDevice;

/**
 * GATT server of the phone (KnBle.startServer), on the main thread (or the Looper of KnBleSettings). Every method is
 * optional. The reads are answered by KnBle with the stored values, without the app. Nothing comes after stopServer,
 * nor after another startServer (except CANCELLED).
 */
public interface BleServerCallback {
	/**
	 * Services added, the server is ready (again after Bluetooth came back: KnBle opens it again by itself)
	 */
	default void onServerStarted() {}

	/**
	 * @param error BleError: BLUETOOTH_DISABLED (also turned off before the server started), PERMISSION_DENIED
	 *              (BLUETOOTH_CONNECT on Android 12+), REFUSED (a service not added), TIMEOUT, CANCELLED (another
	 *              startServer before the result of this one), INTERNAL
	 */
	default void onServerFailed(int error) {}

	/**
	 * A central of this server: at its first request (read, write, subscription...), or at its connection for a bonded
	 * central that kept its subscriptions. Android tells every server of every LE link of the phone (a watch, a keyboard,
	 * the GATT client of an app): a link without request to this server is not one of its centrals.
	 */
	default void onCentralConnected(@NonNull BleDevice central) {}

	/**
	 * A central of onCentralConnected disconnected
	 */
	default void onCentralDisconnected(@NonNull BleDevice central) {}

	/**
	 * A central wrote a characteristic: the whole value (a long write once executed), already stored and served to the
	 * next reads
	 */
	default void onWrite(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] value) {}

	/**
	 * A central subscribed to (or unsubscribed from) the notifications or indications of a characteristic. A bonded
	 * central keeps its subscriptions between its connections (Bluetooth spec): subscribed again at its connection.
	 */
	default void onSubscriptionChanged(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, boolean subscribed) {}

	/**
	 * MTU of a central (after onCentralConnected when it was exchanged before): a notification carries MTU - 3 bytes at
	 * most (512 at most)
	 */
	default void onMtuChanged(@NonNull BleDevice central, int mtu) {}
}
