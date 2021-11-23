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
	private final List<Integer> mManufacturerIds;

	/**
	 * Class constructor
	 */
	private ScanFilters(@NonNull List<String> devicesNames, @NonNull List<String> devicesStartsWithNames, @NonNull List<String> devicesEndsWithNames, @NonNull List<String> devicesMacs, @NonNull List<Integer> manufacturerIds) {
		this.mDevicesNames = devicesNames;
		this.mDevicesStartsWithNames = devicesStartsWithNames;
		this.mDevicesEndsWithNames = devicesEndsWithNames;
		this.mDevicesMacs = devicesMacs;
		this.mManufacturerIds = manufacturerIds;
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
	 * Return manufacturer IDs list
	 * @return mManufacturerIds
	 */
	@NonNull
	public List<Integer> getManufacturerIds() {
		return mManufacturerIds;
	}

	/**
	 * Return nb filters
	 * @return int
	 */
	public int count() {
		return mDevicesNames.size() + mDevicesStartsWithNames.size() + mDevicesEndsWithNames.size() + mDevicesMacs.size() + mManufacturerIds.size();
	}

	/**
	 * Builder
	 */
	public static final class Builder {
		private final List<String> mDevicesNames = new ArrayList<>();
		private final List<String> mDevicesStartsWithNames = new ArrayList<>();
		private final List<String> mDevicesEndsWithNames = new ArrayList<>();
		private final List<String> mDevicesMacs = new ArrayList<>();
		private final List<Integer> mManufacturerIds = new ArrayList<>();

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

		public Builder addManufacturerId(int manufacturerId) {
			this.mManufacturerIds.add(manufacturerId);
			return this;
		}

		public ScanFilters build() {
			return new ScanFilters(mDevicesNames, mDevicesStartsWithNames, mDevicesEndsWithNames, mDevicesMacs, mManufacturerIds);
		}
	}
}
