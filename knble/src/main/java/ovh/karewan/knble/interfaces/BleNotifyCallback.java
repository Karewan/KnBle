package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

public interface BleNotifyCallback {
	void onNotifyEnabled();
	void onNotifyDisabled();
	void onNotify(@NonNull byte[] data);
}
