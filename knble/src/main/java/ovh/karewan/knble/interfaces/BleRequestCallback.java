package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

/**
 * Result of KnBle.request: the notification that answered the write. Only onResponse() is mandatory: a lambda works.
 */
public interface BleRequestCallback {
	void onResponse(@NonNull byte[] response);

	/**
	 * @param error GATT status or BleError (TIMEOUT when no notification came)
	 */
	default void onRequestFailed(int error) {}
}
