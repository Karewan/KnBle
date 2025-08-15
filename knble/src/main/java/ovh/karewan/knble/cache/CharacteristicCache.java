package ovh.karewan.knble.cache;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CharacteristicCache {
	private final ConcurrentHashMap<UUID, BluetoothGattCharacteristic> cache = new ConcurrentHashMap<>();

	@Nullable
	public BluetoothGattCharacteristic get(@Nullable UUID uuid, @NonNull BluetoothGattService service) {
		return uuid == null ? null : cache.computeIfAbsent(uuid, service::getCharacteristic);
	}

	public void clear() {
		cache.clear();
	}
}
