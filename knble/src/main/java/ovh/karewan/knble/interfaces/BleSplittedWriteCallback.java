package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import ovh.karewan.knble.struct.BleProgress;

/**
 * Result and progress of a splittedWrite. Only onWriteSuccess() is mandatory: a lambda works.
 */
public interface BleSplittedWriteCallback extends BleWriteCallback {
	/**
	 * At most one every 100 ms, plus the last one: bytes, packets and rate
	 */
	default void onWriteProgress(@NonNull BleProgress progress) {
		onWriteProgress(progress.getPackets(), progress.getTotalPackets());
	}

	/**
	 * Form of KnBle 3, called by the default onWriteProgress(BleProgress)
	 * @param current Packets written
	 * @param total Total packets
	 */
	default void onWriteProgress(int current, int total) {}
}
