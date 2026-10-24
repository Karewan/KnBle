package ovh.karewan.knble.scan;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Filters of a scan (any of them matches). Immutable: the Builder checks them when they are added (an invalid one throws
 * there, on the thread of the app, not later in the scan) and stores the MAC addresses in upper case, as Android gives them.
 */
public class ScanFilters {
	private final List<String> mDevicesNames;
	private final List<String> mDevicesStartsWithNames;
	private final List<String> mDevicesEndsWithNames;
	private final List<String> mDevicesMacs;
	private final List<String> mDevicesMacsStartsWith;
	private final List<Integer> mManufacturerIds;
	private final List<UUID> mBeaconUUIDs;
	private final List<UUID> mServiceUuids;
	private final List<UUID> mServiceDataUuids;
	private final List<byte[]> mServiceDataPrefixes;
	private final int mMinRssi;
	private final boolean mUseAndroid6Filters;

	/**
	 * Class constructor
	 * @param devicesNames ArrayList<String>
	 * @param devicesStartsWithNames ArrayList<String>
	 * @param devicesEndsWithNames ArrayList<String>
	 * @param devicesMacs ArrayList<String>
	 * @param devicesMacsStartsWith ArrayList<String>
	 * @param manufacturerIds ArrayList<Integer>
	 * @param useAndroid6Filters boolean
	 */
	private ScanFilters(
			@NonNull ArrayList<String> devicesNames,
			@NonNull ArrayList<String> devicesStartsWithNames,
			@NonNull ArrayList<String> devicesEndsWithNames,
			@NonNull ArrayList<String> devicesMacs,
			@NonNull ArrayList<String> devicesMacsStartsWith,
			@NonNull ArrayList<Integer> manufacturerIds,
			@NonNull ArrayList<UUID> beaconUUIDs,
			@NonNull ArrayList<UUID> serviceUuids,
			@NonNull ArrayList<UUID> serviceDataUuids,
			@NonNull ArrayList<byte[]> serviceDataPrefixes,
			int minRssi,
			boolean useAndroid6Filters) {

		// Copies: a Builder used again (or a list changed by the app) does not change the filters of a running scan
		this.mDevicesNames = copy(devicesNames);
		this.mDevicesStartsWithNames = copy(devicesStartsWithNames);
		this.mDevicesEndsWithNames = copy(devicesEndsWithNames);
		this.mDevicesMacs = copy(devicesMacs);
		this.mDevicesMacsStartsWith = copy(devicesMacsStartsWith);
		this.mManufacturerIds = copy(manufacturerIds);
		this.mBeaconUUIDs = copy(beaconUUIDs);
		this.mServiceUuids = copy(serviceUuids);
		this.mServiceDataUuids = copy(serviceDataUuids);
		this.mServiceDataPrefixes = copy(serviceDataPrefixes);
		this.mMinRssi = minRssi;
		this.mUseAndroid6Filters = useAndroid6Filters;
	}

	@NonNull
	private static <T> List<T> copy(@NonNull List<T> list) {
		return Collections.unmodifiableList(new ArrayList<>(list));
	}

	/**
	 * Return devices names list
	 * @return mDevicesNames
	 */
	@NonNull
	public List<String> getDeviceNames() {
		return mDevicesNames;
	}

	/**
	 * Return devices starts with names list
	 * @return mDevicesStartsWithNames
	 */
	@NonNull
	public List<String> getDeviceStartsWithNames() {
		return mDevicesStartsWithNames;
	}

	/**
	 * Return devices ends with names list
	 * @return mDevicesEndsWithNames
	 */
	@NonNull
	public List<String> getDeviceEndsWithNames() {
		return mDevicesEndsWithNames;
	}

	/**
	 * Return mac address list (upper case)
	 * @return mDevicesMacs
	 */
	@NonNull
	public List<String> getDevicesMacs() {
		return mDevicesMacs;
	}

	/**
	 * Return mac address starts with list (upper case)
	 * @return mDevicesMacsStartsWith
	 */
	@NonNull
	public List<String> getDevicesMacsStartsWith() {
		return mDevicesMacsStartsWith;
	}

	/**
	 * Return manufacturer IDs list
	 * @return mManufacturerIds
	 */
	@NonNull
	public List<Integer> getManufacturerIds() {
		return mManufacturerIds;
	}

	/**
	 * Return beacon UUIDs list
	 * @return mBeaconUUIDs
	 */
	@NonNull
	public List<UUID> getBeaconUUIDs() {
		return mBeaconUUIDs;
	}

	/**
	 * Service UUIDs advertised (any of them)
	 */
	@NonNull
	public List<UUID> getServiceUuids() {
		return mServiceUuids;
	}

	/**
	 * Service data UUIDs, each one with its prefix in getServiceDataPrefixes() (same index)
	 */
	@NonNull
	public List<UUID> getServiceDataUuids() {
		return mServiceDataUuids;
	}

	@NonNull
	public List<byte[]> getServiceDataPrefixes() {
		return mServiceDataPrefixes;
	}

	/**
	 * Reports below this RSSI are ignored, whatever the other filters (Integer.MIN_VALUE: none)
	 */
	public int getMinRssi() {
		return mMinRssi;
	}

	/**
	 * Is using android 6 filters
	 * @return boolean
	 */
	public boolean isUsingAndroid6Filters() {
		return mUseAndroid6Filters;
	}

	/**
	 * Return nb filters
	 * @return int
	 */
	public int count() {
		return mDevicesNames.size()
				+ mDevicesStartsWithNames.size()
				+ mDevicesEndsWithNames.size()
				+ mDevicesMacs.size()
				+ mDevicesMacsStartsWith.size()
				+ mManufacturerIds.size()
				+ mBeaconUUIDs.size()
				+ mServiceUuids.size()
				+ mServiceDataUuids.size();
	}

	/**
	 * Return nb of android 6 filters
	 * @return int
	 */
	public int count6Filters() {
		return mDevicesNames.size()
				+ mDevicesMacs.size()
				+ mManufacturerIds.size()
				+ mBeaconUUIDs.size()
				+ mServiceUuids.size()
				+ mServiceDataUuids.size();
	}

	/**
	 * Builder
	 */
	public static final class Builder {
		private final ArrayList<String> mDevicesNames = new ArrayList<>();
		private final ArrayList<String> mDevicesStartsWithNames = new ArrayList<>();
		private final ArrayList<String> mDevicesEndsWithNames = new ArrayList<>();
		private final ArrayList<String> mDevicesMacs = new ArrayList<>();
		private final ArrayList<String> mDevicesMacsStartsWith = new ArrayList<>();
		private final ArrayList<Integer> mManufacturerIds = new ArrayList<>();
		private final ArrayList<UUID> mBeaconUUIDs = new ArrayList<>();
		private final ArrayList<UUID> mServiceUuids = new ArrayList<>();
		private final ArrayList<UUID> mServiceDataUuids = new ArrayList<>();
		private final ArrayList<byte[]> mServiceDataPrefixes = new ArrayList<>();
		private int mMinRssi = Integer.MIN_VALUE;
		private boolean mUseAndroid6Filters = true;

		/**
		 * Devices advertising exactly this name (case sensitive)
		 * @throws NullPointerException when null
		 */
		public Builder addDeviceName(@NonNull String deviceName) {
			this.mDevicesNames.add(Objects.requireNonNull(deviceName, "deviceName"));
			return this;
		}

		/**
		 * @throws NullPointerException when null
		 */
		public Builder addDeviceNameStartsWith(@NonNull String startsWith) {
			this.mDevicesStartsWithNames.add(Objects.requireNonNull(startsWith, "startsWith"));
			return this;
		}

		/**
		 * @throws NullPointerException when null
		 */
		public Builder addDeviceNameEndsWith(@NonNull String endsWith) {
			this.mDevicesEndsWithNames.add(Objects.requireNonNull(endsWith, "endsWith"));
			return this;
		}

		/**
		 * A device by its MAC address, "AA:BB:CC:DD:EE:FF" in any case
		 * @throws IllegalArgumentException when it is not a MAC address
		 */
		public Builder addMacAddress(@NonNull String macAddress) {
			String mac = macAddress.toUpperCase(Locale.ROOT);
			if (mac.length() != 17 || !isMacPrefix(mac)) throw new IllegalArgumentException("invalid MAC address: " + macAddress);
			this.mDevicesMacs.add(mac);
			return this;
		}

		/**
		 * The devices whose MAC address starts with this, "AA:BB:CC" in any case
		 * @throws IllegalArgumentException when it is not the start of a MAC address
		 */
		public Builder addMacAddressStartsWith(@NonNull String macAddressStartsWith) {
			String prefix = macAddressStartsWith.toUpperCase(Locale.ROOT);
			if (prefix.length() > 17 || !isMacPrefix(prefix)) throw new IllegalArgumentException("invalid MAC address prefix: " + macAddressStartsWith);
			this.mDevicesMacsStartsWith.add(prefix);
			return this;
		}

		/**
		 * Devices advertising manufacturer data of this company identifier
		 * @throws IllegalArgumentException when not between 0 and 0xFFFF
		 */
		public Builder addManufacturerId(int manufacturerId) {
			if (manufacturerId < 0 || manufacturerId > 0xFFFF) throw new IllegalArgumentException("invalid manufacturer ID: " + manufacturerId);
			this.mManufacturerIds.add(manufacturerId);
			return this;
		}

		/**
		 * iBeacons of this proximity UUID
		 * @throws NullPointerException when null
		 */
		public Builder addBeaconUUID(@NonNull UUID uuid) {
			this.mBeaconUUIDs.add(Objects.requireNonNull(uuid, "uuid"));
			return this;
		}

		/**
		 * Devices advertising this service UUID (filtered by the controller with the Android 6 filters)
		 * @throws NullPointerException when null
		 */
		public Builder addServiceUuid(@NonNull UUID uuid) {
			this.mServiceUuids.add(Objects.requireNonNull(uuid, "uuid"));
			return this;
		}

		/**
		 * Devices advertising service data of this UUID that starts with these bytes (empty: any data)
		 * @throws NullPointerException when null
		 */
		public Builder addServiceData(@NonNull UUID uuid, @NonNull byte[] prefix) {
			Objects.requireNonNull(uuid, "uuid");
			byte[] copy = prefix.clone();
			this.mServiceDataUuids.add(uuid);
			this.mServiceDataPrefixes.add(copy);
			return this;
		}

		/**
		 * Ignores the reports below this RSSI (dBm), whatever the other filters
		 */
		public Builder setMinRssi(int rssi) {
			this.mMinRssi = rssi;
			return this;
		}

		public Builder setUseAndroid6Filters(boolean use) {
			this.mUseAndroid6Filters = use;
			return this;
		}

		/**
		 * Hex digits with a colon after each byte, in upper case (the start of "AA:BB:CC:DD:EE:FF")
		 */
		private static boolean isMacPrefix(@NonNull String s) {
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				boolean valid = i % 3 == 2 ? c == ':' : (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
				if (!valid) return false;
			}
			return true;
		}

		public ScanFilters build() {
			return new ScanFilters(mDevicesNames, mDevicesStartsWithNames, mDevicesEndsWithNames, mDevicesMacs, mDevicesMacsStartsWith, mManufacturerIds, mBeaconUUIDs,
					mServiceUuids, mServiceDataUuids, mServiceDataPrefixes, mMinRssi, mUseAndroid6Filters);
		}
	}
}
