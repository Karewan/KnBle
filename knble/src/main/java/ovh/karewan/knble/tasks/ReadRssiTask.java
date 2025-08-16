package ovh.karewan.knble.tasks;

import androidx.annotation.NonNull;

import ovh.karewan.knble.interfaces.BleReadRssiCallback;

public class ReadRssiTask extends GattTask {
	private final BleReadRssiCallback mCallback;

	public ReadRssiTask(@NonNull BleReadRssiCallback callback) {
		mCallback = callback;
	}

	@NonNull
	public BleReadRssiCallback getCallback() {
		return mCallback;
	}
}
