package ovh.karewan.knble.interfaces;

public interface BleNotifyCallback {
	void onNotifyEnabled();
	void onNotifyDisabled();
	void onNotify(byte[] data);
	void onNotifyFailed();
}
