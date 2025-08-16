package ovh.karewan.knble.interfaces;

public interface BleSplittedWriteCallback {
	/**
	 * On write failed
	 */
	void onWriteFailed();

	/**
	 * On write progress
	 * @param current Current packet
	 * @param total Total packets
	 */
	void onWriteProgress(int current, int total);

	/**
	 * On write success (All packets writed)
	 */
	void onWriteSuccess();
}
