package ovh.karewan.knble.cache;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServiceCache {
	private final ConcurrentHashMap<UUID, BluetoothGattService> cache = new ConcurrentHashMap<>();

	@Nullable
	public BluetoothGattService get(@Nullable UUID uuid, @Nullable BluetoothGatt gatt) {
		return uuid == null ? null : cache.computeIfAbsent(uuid, u -> gatt == null ? null : gatt.getService(u));
	}

	public void clear() {
		cache.clear();
	}
}
