package ovh.karewan.knble.interfaces;

public interface BleWriteCallback {
	/**
	 * On write failed
	 */
	void onWriteFailed();

	/**
	 * On write success
	 */
	void onWriteSuccess();
}
