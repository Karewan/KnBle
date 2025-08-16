package ovh.karewan.knble.tasks;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.interfaces.BleReadCallback;

public class ReadCharaTask extends GattTask {
	private final UUID mServiceUUID;
	private BluetoothGattService mService;
	private final UUID mCharacteristicUUID;
	private BluetoothGattCharacteristic mCharacteristic;
	private final BleReadCallback mCallback;

	public ReadCharaTask(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull BleReadCallback callback) {
		mServiceUUID = serviceUUID;
		mService = null;
		mCharacteristicUUID = characteristicUUID;
		mCharacteristic = null;
		mCallback = callback;
	}

	public ReadCharaTask(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BleReadCallback callback) {
		mServiceUUID = null;
		mService = service;
		mCharacteristicUUID = null;
		mCharacteristic = characteristic;
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
	public BluetoothGattService setService(@Nullable BluetoothGattService service) {
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
	public BluetoothGattCharacteristic setCharacteristic(@Nullable BluetoothGattCharacteristic characteristic) {
		return (mCharacteristic = characteristic);
	}

	@NonNull
	public BleReadCallback getCallback() {
		return mCallback;
	}
}
