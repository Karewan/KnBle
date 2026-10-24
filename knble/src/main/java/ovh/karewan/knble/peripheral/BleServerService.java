package ovh.karewan.knble.peripheral;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A service of the GATT server of the phone (KnBle.startServer). KnBle answers the reads with the stored value of each
 * characteristic, stores the writes (long writes included) and handles the subscriptions (CCCD): the app only sets
 * values, gets the writes and notifies.
 */
public final class BleServerService {
	/** Properties of a characteristic, combined with | */
	public static final int READ = 1;
	public static final int WRITE = 2;
	public static final int WRITE_NO_RESPONSE = 4;
	public static final int NOTIFY = 8;
	public static final int INDICATE = 16;
	/** Reads and writes need an encrypted link: the central pairs first */
	public static final int ENCRYPTED = 32;

	static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	// Longest attribute value of ATT
	private static final int MAX_VALUE_LENGTH = 512;

	@NonNull
	private final UUID mUuid;
	private final List<Characteristic> mCharacteristics = new ArrayList<>();

	public BleServerService(@NonNull UUID uuid) {
		mUuid = uuid;
	}

	public BleServerService(@NonNull String uuid) {
		this(UUID.fromString(uuid));
	}

	@NonNull
	public UUID getUuid() {
		return mUuid;
	}

	/**
	 * @param properties READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE, ENCRYPTED combined with |
	 * @throws IllegalArgumentException for a characteristic UUID already in this service
	 */
	@NonNull
	public BleServerService addCharacteristic(@NonNull UUID uuid, int properties) {
		return addCharacteristic(uuid, properties, null);
	}

	/**
	 * @param value Initial value served to the reads (512 bytes at most)
	 * @throws IllegalArgumentException for a characteristic UUID already in this service, or a value over 512 bytes
	 */
	@NonNull
	public BleServerService addCharacteristic(@NonNull UUID uuid, int properties, @Nullable byte[] value) {
		for (Characteristic c : mCharacteristics) {
			if (c.uuid.equals(uuid)) throw new IllegalArgumentException("characteristic " + uuid + " already in service " + mUuid);
		}
		if (value != null && value.length > MAX_VALUE_LENGTH) {
			throw new IllegalArgumentException("value of " + value.length + " bytes, " + MAX_VALUE_LENGTH + " at most");
		}
		mCharacteristics.add(new Characteristic(uuid, properties, value == null ? new byte[0] : value.clone()));
		return this;
	}

	@NonNull
	public BleServerService addCharacteristic(@NonNull String uuid, int properties) {
		return addCharacteristic(UUID.fromString(uuid), properties, null);
	}

	@NonNull
	public BleServerService addCharacteristic(@NonNull String uuid, int properties, @Nullable byte[] value) {
		return addCharacteristic(UUID.fromString(uuid), properties, value);
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public List<Characteristic> characteristics() {
		return Collections.unmodifiableList(mCharacteristics);
	}

	/**
	 * A copy of this service as it is now (KnBle.startServer): the app may still change this one, the copy is read by
	 * the thread of the peripheral at each opening of the server
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public BleServerService snapshot() {
		BleServerService copy = new BleServerService(mUuid);
		for (Characteristic c : mCharacteristics) copy.mCharacteristics.add(new Characteristic(c.uuid, c.properties, c.initialValue.clone()));
		return copy;
	}

	/**
	 * A new service of the system (a server opened again after Bluetooth came back needs new objects)
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public BluetoothGattService toSystem() {
		BluetoothGattService service = new BluetoothGattService(mUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		for (Characteristic c : mCharacteristics) service.addCharacteristic(c.toSystem());
		return service;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static final class Characteristic {
		@NonNull
		public final UUID uuid;
		public final int properties;
		@NonNull
		public final byte[] initialValue;

		Characteristic(@NonNull UUID uuid, int properties, @NonNull byte[] initialValue) {
			this.uuid = uuid;
			this.properties = properties;
			this.initialValue = initialValue;
		}

		@NonNull
		BluetoothGattCharacteristic toSystem() {
			boolean encrypted = (properties & ENCRYPTED) != 0;
			int props = 0, perms = 0;
			if ((properties & READ) != 0) {
				props |= BluetoothGattCharacteristic.PROPERTY_READ;
				perms |= encrypted ? BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED : BluetoothGattCharacteristic.PERMISSION_READ;
			}
			if ((properties & (WRITE | WRITE_NO_RESPONSE)) != 0) {
				if ((properties & WRITE) != 0) props |= BluetoothGattCharacteristic.PROPERTY_WRITE;
				if ((properties & WRITE_NO_RESPONSE) != 0) props |= BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
				perms |= encrypted ? BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED : BluetoothGattCharacteristic.PERMISSION_WRITE;
			}
			if ((properties & NOTIFY) != 0) props |= BluetoothGattCharacteristic.PROPERTY_NOTIFY;
			if ((properties & INDICATE) != 0) props |= BluetoothGattCharacteristic.PROPERTY_INDICATE;

			BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid, props, perms);
			if ((properties & (NOTIFY | INDICATE)) != 0) {
				int cccdPerms = encrypted
						? BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
						: BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE;
				c.addDescriptor(new BluetoothGattDescriptor(CCCD, cccdPerms));
			}
			return c;
		}

		public boolean canRead() {
			return (properties & READ) != 0;
		}

		public boolean canWrite() {
			return (properties & (WRITE | WRITE_NO_RESPONSE)) != 0;
		}
	}
}
