package ovh.karewan.knble.peripheral;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the phone advertises (KnBle.startAdvertising) and how. Legacy advertising (all phones): the data in the
 * advertising packet (31 bytes), the name of the phone in the scan response. Extended advertising (setExtended, Android
 * 8+ and a Bluetooth 5 controller, else legacy): everything in one packet of up to 251 bytes, not seen by Bluetooth 4
 * scanners.
 */
public final class BleAdvertiseData {
	/** Advertising interval: about 1 s (default 250 ms with BALANCED, 100 ms with LOW_LATENCY) */
	public static final int MODE_LOW_POWER = 0;
	public static final int MODE_BALANCED = 1;
	public static final int MODE_LOW_LATENCY = 2;

	public static final int TX_POWER_ULTRA_LOW = 0;
	public static final int TX_POWER_LOW = 1;
	public static final int TX_POWER_MEDIUM = 2;
	public static final int TX_POWER_HIGH = 3;

	private final List<UUID> mServiceUuids;
	private final Map<UUID, byte[]> mServiceData;
	private final Map<Integer, byte[]> mManufacturerData;
	private final boolean mIncludeName, mIncludeTxPower, mConnectable, mExtended;
	private final int mMode, mTxPower;
	private final long mTimeoutMs;

	private BleAdvertiseData(@NonNull Builder b) {
		mServiceUuids = Collections.unmodifiableList(new ArrayList<>(b.mServiceUuids));
		mServiceData = Collections.unmodifiableMap(new LinkedHashMap<>(b.mServiceData));
		mManufacturerData = Collections.unmodifiableMap(new LinkedHashMap<>(b.mManufacturerData));
		mIncludeName = b.mIncludeName;
		mIncludeTxPower = b.mIncludeTxPower;
		mConnectable = b.mConnectable;
		mExtended = b.mExtended;
		mMode = b.mMode;
		mTxPower = b.mTxPower;
		mTimeoutMs = b.mTimeoutMs;
	}

	@NonNull
	public List<UUID> getServiceUuids() {
		return mServiceUuids;
	}

	@NonNull
	public Map<UUID, byte[]> getServiceData() {
		return mServiceData;
	}

	@NonNull
	public Map<Integer, byte[]> getManufacturerData() {
		return mManufacturerData;
	}

	public boolean isIncludeName() {
		return mIncludeName;
	}

	public boolean isIncludeTxPower() {
		return mIncludeTxPower;
	}

	public boolean isConnectable() {
		return mConnectable;
	}

	public boolean isExtended() {
		return mExtended;
	}

	public int getMode() {
		return mMode;
	}

	public int getTxPower() {
		return mTxPower;
	}

	public long getTimeoutMs() {
		return mTimeoutMs;
	}

	public static final class Builder {
		private final List<UUID> mServiceUuids = new ArrayList<>();
		private final Map<UUID, byte[]> mServiceData = new LinkedHashMap<>();
		private final Map<Integer, byte[]> mManufacturerData = new LinkedHashMap<>();
		private boolean mIncludeName, mIncludeTxPower, mExtended;
		private boolean mConnectable = true;
		private int mMode = MODE_BALANCED, mTxPower = TX_POWER_MEDIUM;
		private long mTimeoutMs;

		/**
		 * Service UUID advertised (16-bit forms take 2 bytes, 128-bit ones 16)
		 */
		@NonNull
		public Builder addServiceUuid(@NonNull UUID uuid) {
			mServiceUuids.add(uuid);
			return this;
		}

		@NonNull
		public Builder addServiceData(@NonNull UUID uuid, @NonNull byte[] data) {
			mServiceData.put(uuid, data.clone());
			return this;
		}

		/**
		 * @param id Company identifier (0xFFFF for tests)
		 */
		@NonNull
		public Builder addManufacturerData(int id, @NonNull byte[] data) {
			mManufacturerData.put(id, data.clone());
			return this;
		}

		/**
		 * The Bluetooth name of the phone (in the scan response with legacy advertising)
		 */
		@NonNull
		public Builder setIncludeName(boolean include) {
			mIncludeName = include;
			return this;
		}

		@NonNull
		public Builder setIncludeTxPower(boolean include) {
			mIncludeTxPower = include;
			return this;
		}

		/**
		 * Centrals may connect (to the GATT server of KnBle.startServer). Default true.
		 */
		@NonNull
		public Builder setConnectable(boolean connectable) {
			mConnectable = connectable;
			return this;
		}

		/**
		 * MODE_LOW_POWER, MODE_BALANCED (default) or MODE_LOW_LATENCY
		 * @throws IllegalArgumentException for another value
		 */
		@NonNull
		public Builder setMode(int mode) {
			// Else refused by the legacy advertising of the system, ignored by the extended one
			if (mode < MODE_LOW_POWER || mode > MODE_LOW_LATENCY) throw new IllegalArgumentException("unknown advertising mode " + mode);
			mMode = mode;
			return this;
		}

		/**
		 * TX_POWER_ULTRA_LOW, TX_POWER_LOW, TX_POWER_MEDIUM (default) or TX_POWER_HIGH
		 * @throws IllegalArgumentException for another value
		 */
		@NonNull
		public Builder setTxPower(int txPower) {
			if (txPower < TX_POWER_ULTRA_LOW || txPower > TX_POWER_HIGH) throw new IllegalArgumentException("unknown TX power " + txPower);
			mTxPower = txPower;
			return this;
		}

		/**
		 * Advertising stops after this long (onAdvertisingStopped), 0 = until stopAdvertising (default)
		 */
		@NonNull
		public Builder setTimeout(long timeoutMs) {
			mTimeoutMs = Math.max(0, timeoutMs);
			return this;
		}

		/**
		 * Extended advertising when the phone has it (Android 8+, Bluetooth 5 controller), legacy otherwise
		 */
		@NonNull
		public Builder setExtended(boolean extended) {
			mExtended = extended;
			return this;
		}

		@NonNull
		public BleAdvertiseData build() {
			return new BleAdvertiseData(this);
		}
	}
}
