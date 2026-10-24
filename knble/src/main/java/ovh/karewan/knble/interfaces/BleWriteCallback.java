package ovh.karewan.knble.interfaces;

/**
 * Result of a write (characteristic or descriptor). Only onWriteSuccess() is mandatory: a lambda works.
 */
public interface BleWriteCallback {
	/**
	 * Written (acknowledged by the device, or taken by Android for a write without response)
	 */
	void onWriteSuccess();

	/**
	 * @param error BleError (negative) or GATT status of the device
	 */
	default void onWriteFailed(int error) {
		onWriteFailed();
	}

	/**
	 * Form of KnBle 3, called by the default onWriteFailed(int)
	 */
	default void onWriteFailed() {}
}
