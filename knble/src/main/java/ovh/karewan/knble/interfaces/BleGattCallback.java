package ovh.karewan.knble.interfaces;

import android.bluetooth.BluetoothGattService;

import java.util.List;

public interface BleGattCallback {
	/**
	 * STATES
	 */
	int DISCONNECTED = 0;
	int CONNECTING = 1;
	int CONNECTED = 2;

	/**
	 * onConnecting
	 */
	void onConnecting();

	/**
	 * onConnectFailed
	 */
	void onConnectFailed();

	/**
	 * onConnectSuccess
	 */
	void onConnectSuccess(List<BluetoothGattService> services);

	/**
	 * onDisconnected
	 */
	void onDisconnected();
}
