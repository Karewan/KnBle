package ovh.karewan.knble.interfaces;

import android.bluetooth.BluetoothGattDescriptor;

import androidx.annotation.NonNull;

public interface BleGetDescriptor {
	void onSuccess(@NonNull BluetoothGattDescriptor descriptor);
	void onFailed();
}
