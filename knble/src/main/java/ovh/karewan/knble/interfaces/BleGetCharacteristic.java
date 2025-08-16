package ovh.karewan.knble.interfaces;

import android.bluetooth.BluetoothGattCharacteristic;

import androidx.annotation.NonNull;

public interface BleGetCharacteristic {
	void onSuccess(@NonNull BluetoothGattCharacteristic characteristic);
	void onFailed();
}
