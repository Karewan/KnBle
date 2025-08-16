package ovh.karewan.knble.cache;

import androidx.annotation.NonNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UuidCache {
	private final ConcurrentHashMap<String, UUID> cache = new ConcurrentHashMap<>();

	@NonNull
	public UUID get(@NonNull String uuidString) {
		return cache.computeIfAbsent(uuidString, UUID::fromString);
	}

	public void clear() {
		cache.clear();
	}
}
