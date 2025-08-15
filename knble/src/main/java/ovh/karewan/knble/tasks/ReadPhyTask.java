package ovh.karewan.knble.tasks;

import androidx.annotation.NonNull;

import ovh.karewan.knble.interfaces.BlePhyValueCallback;

public class ReadPhyTask extends GattTask {
	private final BlePhyValueCallback mCallback;

	public ReadPhyTask(@NonNull BlePhyValueCallback callback) {
		mCallback = callback;
	}

	@NonNull
	public BlePhyValueCallback getCallback() {
		return mCallback;
	}
}
