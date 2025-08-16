package ovh.karewan.knble.interfaces;

import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;

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
	 * onConnectSuccess
	 */
	void onConnectSuccess(@NonNull List<BluetoothGattService> services);


	/**
	 * onDisconnected
	 * @param connectFailed connection failed
	 */
	void onDisconnected(boolean connectFailed);
}
