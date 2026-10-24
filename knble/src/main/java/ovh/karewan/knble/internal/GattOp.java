package ovh.karewan.knble.internal;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.BleError;

/**
 * An operation of the GATT queue of a device. Runs on the thread of its DeviceActor only: start() issues the GATT
 * call, then the GATT events of the device are offered to the current operation until it ends with actor.finish(this).
 * Its result goes to the app through the Dispatcher, exactly once (success or failure).
 */
@androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY)
public abstract class GattOp {
	@NonNull
	final Dispatcher mDispatcher;
	// Time given to each answer of the stack or the device (ms), set by the actor before start()
	long timeout;
	// SystemClock.elapsedRealtime() after which the watchdog of the actor ends the operation
	long deadline;
	// Already run again after a pairing started by the stack (once at most)
	boolean bondReplayed;
	// A GATT call of this operation was issued and its answer has not come: a GATT event only counts as its answer then
	// (a late answer of a timed out or cancelled request on the same characteristic must not end it)
	boolean awaiting;
	private boolean mDone;

	GattOp(@NonNull Dispatcher dispatcher) {
		mDispatcher = dispatcher;
	}

	/**
	 * Time for an answer, the one of the settings by default
	 */
	long timeoutMs(long defaultMs) {
		return defaultMs;
	}

	/**
	 * A new answer is awaited (operations made of several GATT calls)
	 */
	final void refreshDeadline() {
		deadline = SystemClock.elapsedRealtime() + timeout;
	}

	/**
	 * Issues the operation on the connected gatt (the actor ends it with finish() when it fails at once)
	 */
	abstract void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt);

	/**
	 * Runs again once the device is bonded (the stack asked for a pairing): from the start by default
	 */
	void replay(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
		start(actor, gatt);
	}

	/**
	 * Delivers the failure to the app
	 */
	abstract void onFailure(int error);

	/**
	 * No answer in time. Operations whose callback some stacks never send (MTU, PHY) end with the current values instead.
	 * @return true when the GATT stack probably lost the request (counted by the actor, which disconnects when it repeats)
	 */
	boolean onTimeout() {
		fail(BleError.TIMEOUT);
		return true;
	}

	/**
	 * Ends the operation for the app: false when it already ended
	 */
	final boolean done() {
		if (mDone) return false;
		mDone = true;
		return true;
	}

	final void fail(int error) {
		if (done()) onFailure(error);
	}

	/**
	 * Its result went to the app
	 */
	final boolean ended() {
		return mDone;
	}

	/**
	 * Takes a GATT event as the answer of the call this operation issued
	 * @param matches the event is about the attribute of the call
	 * @return false when it is not its answer (not about its attribute, or nothing issued: a late answer)
	 */
	final boolean answer(boolean matches) {
		if (!awaiting || !matches) return false;
		awaiting = false;
		return true;
	}

	// GATT events of the device, ignored by default

	void onCharacteristicRead(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {}

	void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {}

	void onDescriptorRead(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, @NonNull byte[] value, int status) {}

	void onDescriptorWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, int status) {}

	void onMtuChanged(@NonNull DeviceActor actor, int mtu, int status) {}

	void onPhy(@NonNull DeviceActor actor, int txPhy, int rxPhy, int status, boolean read) {}

	void onRssi(@NonNull DeviceActor actor, int rssi, int status) {}

	void onReliableWriteCompleted(@NonNull DeviceActor actor, int status) {}

	/**
	 * A notification, before the subscriptions of the app: the protocols run by KnBle (TI OAD) answer it right here
	 * @return true when the operation took it (not given to the app)
	 */
	boolean onNotification(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
		return false;
	}

	/**
	 * The characteristic an operation targets: by UUID, or by the objects the app got from KnBle (found again in the
	 * current GATT table, they may come from a previous connection)
	 */
	public static final class CharTarget {
		@Nullable
		final UUID serviceUuid, characteristicUuid;
		@Nullable
		final BluetoothGattService service;
		@Nullable
		final BluetoothGattCharacteristic characteristic;

		CharTarget(@NonNull UUID serviceUuid, @NonNull UUID characteristicUuid) {
			this.serviceUuid = serviceUuid;
			this.characteristicUuid = characteristicUuid;
			this.service = null;
			this.characteristic = null;
		}

		CharTarget(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic) {
			this.serviceUuid = null;
			this.characteristicUuid = null;
			this.service = service;
			this.characteristic = characteristic;
		}

		@Nullable
		BluetoothGattCharacteristic resolve(@NonNull BluetoothGatt gatt) {
			if (characteristic == null) {
				BluetoothGattService s = gatt.getService(serviceUuid);
				return s == null ? null : s.getCharacteristic(characteristicUuid);
			}

			// Same service and characteristic instances in the current table: two of them may share a UUID
			BluetoothGattService ownService = characteristic.getService() != null ? characteristic.getService() : service;
			for (BluetoothGattService s : gatt.getServices()) {
				if (ownService != null && (!s.getUuid().equals(ownService.getUuid()) || s.getInstanceId() != ownService.getInstanceId())) continue;
				for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
					if (c.getUuid().equals(characteristic.getUuid()) && c.getInstanceId() == characteristic.getInstanceId()) return c;
				}
			}
			// Not in the current table (the table changed since): by its UUIDs, never the old object (its handle may be the
			// one of another attribute now)
			BluetoothGattService s = ownService == null ? null : gatt.getService(ownService.getUuid());
			return s == null ? null : s.getCharacteristic(characteristic.getUuid());
		}
	}

	/**
	 * Same characteristic: UUID and instance (the GATT handle), never the object identity (a late callback of a previous
	 * operation or connection must not end the current one)
	 */
	static boolean same(@Nullable BluetoothGattCharacteristic a, @Nullable BluetoothGattCharacteristic b) {
		return a != null && b != null && a.getInstanceId() == b.getInstanceId() && a.getUuid().equals(b.getUuid());
	}

	static boolean same(@Nullable BluetoothGattDescriptor a, @Nullable BluetoothGattDescriptor b) {
		return a != null && b != null && a.getUuid().equals(b.getUuid()) && same(a.getCharacteristic(), b.getCharacteristic());
	}
}
