package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import ovh.karewan.knble.struct.BleProgress;

/**
 * Result and progress of an OTA (KnBle.otaSilabs, KnBle.otaTi). Only onOtaSuccess() is mandatory: a lambda works.
 */
public interface BleOtaCallback {
	/**
	 * The device received and took the whole image (it usually reboots on it next)
	 */
	void onOtaSuccess();

	/**
	 * At most one every 100 ms, plus the last one
	 */
	default void onOtaProgress(@NonNull BleProgress progress) {}

	/**
	 * @param error BleError (negative) or GATT status of the device
	 */
	default void onOtaFailed(int error) {}

	/**
	 * A new attempt starts from the beginning (OtaOptions.setRetries)
	 * @param attempt 1 for the first new attempt
	 */
	default void onOtaRetry(int attempt) {}
}
