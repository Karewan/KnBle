package ovh.karewan.knble.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ScanFilters {
	private final List<String> mDevicesNames;
	private final List<String> mDevicesStartsWithNames;
	private final List<String> mDevicesEndsWithNames;
	private final List<String> mDevicesMacs;
	private final List<String> mDevicesMacsStartsWith;
	private final List<Integer> mManufacturerIds;
	private final boolean mUseAndroid6Filters;

	/**
	 * Class constructor
	 * @param devicesNames List<String>
	 * @param devicesStartsWithNames List<String>
	 * @param devicesEndsWithNames List<String>
	 * @param devicesMacs List<String>
	 * @param devicesMacsStartsWith List<String>
	 * @param manufacturerIds List<Integer>
	 * @param useAndroid6Filters boolean
	 */
	private ScanFilters(
			@NonNull List<String> devicesNames,
			@NonNull List<String> devicesStartsWithNames,
			@NonNull List<String> devicesEndsWithNames,
			@NonNull List<String> devicesMacs,
			@NonNull List<String> devicesMacsStartsWith,
			@NonNull List<Integer> manufacturerIds,
			boolean useAndroid6Filters) {

		this.mDevicesNames = devicesNames;
		this.mDevicesStartsWithNames = devicesStartsWithNames;
		this.mDevicesEndsWithNames = devicesEndsWithNames;
		this.mDevicesMacs = devicesMacs;
		this.mDevicesMacsStartsWith = devicesMacsStartsWith;
		this.mManufacturerIds = manufacturerIds;
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
		return mDevicesNames.size() + mDevicesStartsWithNames.size() + mDevicesEndsWithNames.size() + mDevicesMacs.size() + mDevicesMacsStartsWith.size() + mManufacturerIds.size();
	}

	/**
	 * Return nb of android 6 filters
	 * @return int
	 */
	public int count6Filters() {
		return mDevicesNames.size() + mDevicesMacs.size() + mManufacturerIds.size();
	}

	/**
	 * Builder
	 */
	public static final class Builder {
		private final List<String> mDevicesNames = new ArrayList<>();
		private final List<String> mDevicesStartsWithNames = new ArrayList<>();
		private final List<String> mDevicesEndsWithNames = new ArrayList<>();
		private final List<String> mDevicesMacs = new ArrayList<>();
		private final List<String> mDevicesMacsStartsWith = new ArrayList<>();
		private final List<Integer> mManufacturerIds = new ArrayList<>();
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

		public Builder setUseAndroid6Filters(boolean use) {
			this.mUseAndroid6Filters = use;
			return this;
		}

		public ScanFilters build() {
			return new ScanFilters(mDevicesNames, mDevicesStartsWithNames, mDevicesEndsWithNames, mDevicesMacs, mDevicesMacsStartsWith, mManufacturerIds, mUseAndroid6Filters);
		}
	}
}
