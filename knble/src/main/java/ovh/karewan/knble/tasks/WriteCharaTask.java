package ovh.karewan.knble.tasks;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.interfaces.BleWriteCallback;

public class WriteCharaTask extends GattTask {
	private final UUID mServiceUUID;
	private BluetoothGattService mService;
	private final UUID mCharacteristicUUID;
	private BluetoothGattCharacteristic mCharacteristic;
	private final byte[] mData;
	private final boolean mNoResponse;
	private final BleWriteCallback mCallback;

	public WriteCharaTask(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
		mServiceUUID = serviceUUID;
		mService = null;
		mCharacteristicUUID = characteristicUUID;
		mCharacteristic = null;
		mData = data;
		mNoResponse = noResponse;
		mCallback = callback;
	}

	public WriteCharaTask(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
		mServiceUUID = null;
		mService = service;
		mCharacteristicUUID = null;
		mCharacteristic = characteristic;
		mData = data;
		mNoResponse = noResponse;
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

	@NonNull
	public byte[] getData() {
		return mData;
	}

	public boolean isNoResponse() {
		return mNoResponse;
	}

	@NonNull
	public BleWriteCallback getCallback() {
		return mCallback;
	}
}
