package ovh.karewan.knble.interfaces;

import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;

public interface BleGetService {
	void onSuccess(@NonNull BluetoothGattService service);
	void onFailed();
}
