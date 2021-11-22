package ovh.karewan.knble.interfaces;

public interface BleReadCallback {
	void onReadSuccess(byte[] data);
	void onReadFailed();
}
