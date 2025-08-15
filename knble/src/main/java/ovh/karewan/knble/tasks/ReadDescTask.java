package ovh.karewan.knble.tasks;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.interfaces.BleReadCallback;

public class ReadDescTask extends GattTask {
	private final UUID mServiceUUID;
	private BluetoothGattService mService;
	private final UUID mCharacteristicUUID;
	private BluetoothGattCharacteristic mCharacteristic;
	private final UUID mDescriptorUUID;
	private BluetoothGattDescriptor mDescriptor;
	private final BleReadCallback mCallback;

	public ReadDescTask(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID, @NonNull BleReadCallback callback) {
		mServiceUUID = serviceUUID;
		mService = null;
		mCharacteristicUUID = characteristicUUID;
		mCharacteristic = null;
		mDescriptorUUID = descriptorUUID;
		mDescriptor = null;
		mCallback = callback;
	}

	public ReadDescTask(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BluetoothGattDescriptor descriptor, @NonNull BleReadCallback callback) {
		mServiceUUID = null;
		mService = service;
		mCharacteristicUUID = null;
		mCharacteristic = characteristic;
		mDescriptorUUID = null;
		mDescriptor = descriptor;
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

	@Nullable
	public UUID getDescriptorUUID() {
		return mDescriptorUUID;
	}

	@Nullable
	public BluetoothGattDescriptor getDescriptor() {
		return mDescriptor;
	}

	@Nullable
	public synchronized BluetoothGattDescriptor setDescriptor(@Nullable BluetoothGattDescriptor descriptor) {
		return (mDescriptor = descriptor);
	}

	@NonNull
	public BleReadCallback getCallback() {
		return mCallback;
	}
}
