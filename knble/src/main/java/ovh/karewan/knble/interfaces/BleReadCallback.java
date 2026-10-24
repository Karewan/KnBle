package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

/**
 * Result of a read (characteristic or descriptor). Only onReadSuccess() is mandatory: a lambda works.
 */
public interface BleReadCallback {
	void onReadSuccess(@NonNull byte[] data);

	/**
	 * @param error BleError (negative) or GATT status of the device
	 */
	default void onReadFailed(int error) {
		onReadFailed();
	}

	/**
	 * Form of KnBle 3, called by the default onReadFailed(int)
	 */
	default void onReadFailed() {}
}
