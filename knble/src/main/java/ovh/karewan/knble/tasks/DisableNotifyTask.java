package ovh.karewan.knble.tasks;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.interfaces.BleNotifyCallback;

public class DisableNotifyTask extends GattTask {
	private final UUID mServiceUUID;
	private BluetoothGattService mService;
	private final UUID mCharacteristicUUID;
	private BluetoothGattCharacteristic mCharacteristic;
	private final UUID mDescriptorUUID;
	private BleNotifyCallback mCallback;

	public DisableNotifyTask(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID) {
		mServiceUUID = serviceUUID;
		mService = null;
		mCharacteristicUUID = characteristicUUID;
		mCharacteristic = null;
		mDescriptorUUID = descriptorUUID;
	}

	public DisableNotifyTask(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull UUID descriptorUUID) {
		mServiceUUID = null;
		mService = service;
		mCharacteristicUUID = null;
		mCharacteristic = characteristic;
		mDescriptorUUID = descriptorUUID;
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
	public synchronized BluetoothGattService setService(@Nullable BluetoothGattService service) {
		return (mService = service);
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
	public synchronized BluetoothGattCharacteristic setCharacteristic(@Nullable BluetoothGattCharacteristic characteristic) {
		return (mCharacteristic = characteristic);
	}

	@Nullable
	public UUID getDescriptorUUID() {
		return mDescriptorUUID;
	}

	public void setCallback(@NonNull BleNotifyCallback callback) {
		mCallback = callback;
	}

	@Nullable
	public BleNotifyCallback getCallback() {
		return mCallback;
	}
}
