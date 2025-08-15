package ovh.karewan.knble.tasks;

import androidx.annotation.Nullable;

import ovh.karewan.knble.interfaces.BleMtuChangedCallback;

public class UpdateMtuTask extends GattTask {
	private final int mMtu;
	private final BleMtuChangedCallback mCallback;

	public UpdateMtuTask(int mtu, @Nullable BleMtuChangedCallback callback) {
		mMtu = mtu;
		mCallback = callback;
	}

	public int getMtu() {
		return mMtu;
	}

	@Nullable
	public BleMtuChangedCallback getCallback() {
		return mCallback;
	}
}
