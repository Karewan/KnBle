package ovh.karewan.knble;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes of a KnBle.reliableWrite: queued by the device, then applied together (or none of them). Each value goes in
 * one Prepare Write: MTU - 5 bytes at most (18 with the default MTU)
 */
public final class BleReliableWrite {
	final List<UUID> mServices = new ArrayList<>();
	final List<UUID> mCharacteristics = new ArrayList<>();
	final List<byte[]> mValues = new ArrayList<>();

	/**
	 * @param data Value of the characteristic (MTU - 5 bytes at most), written with response
	 */
	@NonNull
	public BleReliableWrite add(@NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] data) {
		mServices.add(service);
		mCharacteristics.add(characteristic);
		mValues.add(data.clone());
		return this;
	}

	@NonNull
	public BleReliableWrite add(@NonNull String service, @NonNull String characteristic, @NonNull byte[] data) {
		return add(UUID.fromString(service), UUID.fromString(characteristic), data);
	}

	public int size() {
		return mValues.size();
	}
}
