package ovh.karewan.knble.tasks;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.interfaces.BleNotifyCallback;

public class EnableNotifyTask extends GattTask {
	private final UUID mServiceUUID;
	private final BluetoothGattService mService;
	private final UUID mCharacteristicUUID;
	private final BluetoothGattCharacteristic mCharacteristic;
	private final UUID mDescriptorUUID;
	private final BleNotifyCallback mCallback;

	public EnableNotifyTask(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID, @NonNull BleNotifyCallback callback) {
		mServiceUUID = serviceUUID;
		mService = null;
		mCharacteristicUUID = characteristicUUID;
		mCharacteristic = null;
		mDescriptorUUID = descriptorUUID;
		mCallback = callback;
	}

	public EnableNotifyTask(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull UUID descriptorUUID, @NonNull BleNotifyCallback callback) {
		mServiceUUID = null;
		mService = service;
		mCharacteristicUUID = null;
		mCharacteristic = characteristic;
		mDescriptorUUID = descriptorUUID;
		mCallback = callback;
	}

	@Nullable
	public UUID getServiceUUID() {
		return mServiceUUID;
	}

	@Nullable
	public BluetoothGattService getService() {
		return mService;
	}

	@Nullable
	public UUID getCharacteristicUUID() {
		return mCharacteristicUUID;
	}

	@Nullable
	public BluetoothGattCharacteristic getCharacteristic() {
		return mCharacteristic;
	}

	@Nullable
	public UUID getDescriptorUUID() {
		return mDescriptorUUID;
	}

	@NonNull
	public BleNotifyCallback getCallback() {
		return mCallback;
	}
}
