package ovh.karewan.knble.scan;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ScanFilters {
	private final ArrayList<String> mDevicesNames;
	private final ArrayList<String> mDevicesStartsWithNames;
	private final ArrayList<String> mDevicesEndsWithNames;
	private final ArrayList<String> mDevicesMacs;
	private final ArrayList<String> mDevicesMacsStartsWith;
	private final ArrayList<Integer> mManufacturerIds;
	private final ArrayList<UUID> mBeaconUUIDs;
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
			boolean useAndroid6Filters) {

		this.mDevicesNames = devicesNames;
		this.mDevicesStartsWithNames = devicesStartsWithNames;
		this.mDevicesEndsWithNames = devicesEndsWithNames;
		this.mDevicesMacs = devicesMacs;
		this.mDevicesMacsStartsWith = devicesMacsStartsWith;
		this.mManufacturerIds = manufacturerIds;
		this.mBeaconUUIDs = beaconUUIDs;
		this.mUseAndroid6Filters = useAndroid6Filters;
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
	 * Return mac address list
	 * @return mDevicesMacs
	 */
	@NonNull
	public List<String> getDevicesMacs() {
		return mDevicesMacs;
	}

	/**
	 * Return mac address starts with list
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
				+ mBeaconUUIDs.size();
	}

	/**
	 * Return nb of android 6 filters
	 * @return int
	 */
	public int count6Filters() {
		return mDevicesNames.size()
				+ mDevicesMacs.size()
				+ mManufacturerIds.size()
				+ mBeaconUUIDs.size();
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
		private boolean mUseAndroid6Filters = true;

		public Builder addDeviceName(@NonNull String deviceName) {
			this.mDevicesNames.add(deviceName);
			return this;
		}

		public Builder addDeviceNameStartsWith(@NonNull String startsWith) {
			this.mDevicesStartsWithNames.add(startsWith);
			return this;
		}

		public Builder addDeviceNameEndsWith(@NonNull String endsWith) {
			this.mDevicesEndsWithNames.add(endsWith);
			return this;
		}


		public Builder addMacAddress(@NonNull String macAddress) {
			this.mDevicesMacs.add(macAddress);
			return this;
		}

		public Builder addMacAddressStartsWith(@NonNull String macAddressStartsWith) {
			this.mDevicesMacsStartsWith.add(macAddressStartsWith);
			return this;
		}

		public Builder addManufacturerId(int manufacturerId) {
			this.mManufacturerIds.add(manufacturerId);
			return this;
		}

		public Builder addBeaconUUID(UUID uuid) {
			this.mBeaconUUIDs.add(uuid);
			return this;
		}

		public Builder setUseAndroid6Filters(boolean use) {
			this.mUseAndroid6Filters = use;
			return this;
		}

		public ScanFilters build() {
			return new ScanFilters(mDevicesNames, mDevicesStartsWithNames, mDevicesEndsWithNames, mDevicesMacs, mDevicesMacsStartsWith, mManufacturerIds, mBeaconUUIDs, mUseAndroid6Filters);
		}
	}
}
