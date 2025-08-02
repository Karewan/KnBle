package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

public interface BleReadCallback {
	void onReadSuccess(@NonNull byte[] data);
	void onReadFailed();
}
