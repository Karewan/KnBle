package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

/**
 * Notifications (or indications) of a characteristic. Only onNotify() is mandatory: a lambda works.
 * Never dropped nor merged: each value comes once, in order.
 */
public interface BleNotifyCallback {
	void onNotify(@NonNull byte[] data);

	/**
	 * Subscribed: the device sends its notifications from now on
	 */
	default void onNotifyEnabled() {}

	/**
	 * Not subscribed any more: disableNotify, or the services changed (subscribe again). A disconnection ends every
	 * subscription without this call: subscribe again in onConnectSuccess (also after an auto-reconnection).
	 */
	default void onNotifyDisabled() {}

	/**
	 * The subscription failed
	 * @param error BleError (negative) or GATT status of the device
	 */
	default void onNotifyFailed(int error) {
		onNotifyDisabled();
	}
}
