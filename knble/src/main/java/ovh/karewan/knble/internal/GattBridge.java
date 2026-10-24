package ovh.karewan.knble.internal;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Callbacks of the Android stack, moved to the thread of the actor. Android 13+ calls them there (connectGatt with the
 * Handler or the Executor of the actor), with their values: they run right away. Android 7 to 12 call them on a binder
 * thread (no Handler given: with one, Android 8.0 sets the value of the characteristic before posting, the next
 * notification may overwrite it): the values are copied at once and the handling is posted.
 */
final class GattBridge extends BluetoothGattCallback {
	private static final byte[] EMPTY = new byte[0];

	@NonNull
	private final DeviceActor mActor;

	GattBridge(@NonNull DeviceActor actor) {
		mActor = actor;
	}

	@NonNull
	private static byte[] copy(@Nullable byte[] value) {
		return value == null ? EMPTY : value.clone();
	}

	@Override
	public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
		if (mActor.inline()) mActor.onConnectionStateChange(gatt, status, newState);
		else mActor.post(() -> mActor.onConnectionStateChange(gatt, status, newState));
	}

	@Override
	public void onServicesDiscovered(BluetoothGatt gatt, int status) {
		if (mActor.inline()) mActor.onServicesDiscovered(gatt, status);
		else mActor.post(() -> mActor.onServicesDiscovered(gatt, status));
	}

	@Override
	public void onServiceChanged(@NonNull BluetoothGatt gatt) {
		if (mActor.inline()) mActor.onServiceChanged(gatt);
		else mActor.post(() -> mActor.onServiceChanged(gatt));
	}

	// Android 13+: the value comes with the callback
	@Override
	public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
		if (mActor.inline()) mActor.onCharacteristicRead(gatt, characteristic, value, status);
		else mActor.post(() -> mActor.onCharacteristicRead(gatt, characteristic, value, status));
	}

	// Android 12 and below
	@Override
	@SuppressWarnings("deprecation")
	public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		byte[] value = copy(characteristic.getValue());
		if (mActor.inline()) mActor.onCharacteristicRead(gatt, characteristic, value, status);
		else mActor.post(() -> mActor.onCharacteristicRead(gatt, characteristic, value, status));
	}

	@Override
	public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		if (mActor.inline()) mActor.onCharacteristicWrite(gatt, characteristic, status);
		else mActor.post(() -> mActor.onCharacteristicWrite(gatt, characteristic, status));
	}

	// Android 13+
	@Override
	public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
		if (mActor.inline()) mActor.onCharacteristicChanged(gatt, characteristic, value);
		else mActor.post(() -> mActor.onCharacteristicChanged(gatt, characteristic, value));
	}

	// Android 12 and below
	@Override
	@SuppressWarnings("deprecation")
	public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
		byte[] value = copy(characteristic.getValue());
		if (mActor.inline()) mActor.onCharacteristicChanged(gatt, characteristic, value);
		else mActor.post(() -> mActor.onCharacteristicChanged(gatt, characteristic, value));
	}

	// Android 13+
	@Override
	public void onDescriptorRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status, @NonNull byte[] value) {
		if (mActor.inline()) mActor.onDescriptorRead(gatt, descriptor, value, status);
		else mActor.post(() -> mActor.onDescriptorRead(gatt, descriptor, value, status));
	}

	// Android 12 and below
	@Override
	@SuppressWarnings("deprecation")
	public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
		byte[] value = copy(descriptor.getValue());
		if (mActor.inline()) mActor.onDescriptorRead(gatt, descriptor, value, status);
		else mActor.post(() -> mActor.onDescriptorRead(gatt, descriptor, value, status));
	}

	@Override
	public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
		if (mActor.inline()) mActor.onDescriptorWrite(gatt, descriptor, status);
		else mActor.post(() -> mActor.onDescriptorWrite(gatt, descriptor, status));
	}

	@Override
	public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
		if (mActor.inline()) mActor.onReadRemoteRssi(gatt, rssi, status);
		else mActor.post(() -> mActor.onReadRemoteRssi(gatt, rssi, status));
	}

	@Override
	public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
		if (mActor.inline()) mActor.onMtuChanged(gatt, mtu, status);
		else mActor.post(() -> mActor.onMtuChanged(gatt, mtu, status));
	}

	@Override
	public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
		if (mActor.inline()) mActor.onPhy(gatt, txPhy, rxPhy, status, false);
		else mActor.post(() -> mActor.onPhy(gatt, txPhy, rxPhy, status, false));
	}

	@Override
	public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
		if (mActor.inline()) mActor.onPhy(gatt, txPhy, rxPhy, status, true);
		else mActor.post(() -> mActor.onPhy(gatt, txPhy, rxPhy, status, true));
	}

	@Override
	public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
		if (mActor.inline()) mActor.onReliableWriteCompleted(gatt, status);
		else mActor.post(() -> mActor.onReliableWriteCompleted(gatt, status));
	}

	/**
	 * Parameters of the connection (Android 8+): a method of BluetoothGattCallback hidden from the SDK, called by the stack
	 * on this subclass (kept by the consumer ProGuard rules of KnBle). Interval in 1.25 ms, timeout in 10 ms.
	 */
	@SuppressWarnings("unused")
	public void onConnectionUpdated(BluetoothGatt gatt, int interval, int latency, int timeout, int status) {
		if (mActor.inline()) mActor.onConnectionUpdated(gatt, interval, latency, timeout, status);
		else mActor.post(() -> mActor.onConnectionUpdated(gatt, interval, latency, timeout, status));
	}
}
