package ovh.karewan.knble.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ScanFilters {
	private final List<String> mDevicesNames;
	private final List<String> mDevicesMacs;
	private final List<Integer> mManufacturerIds;

	/**
	 * Class constructor
	 */
	private ScanFilters(@NonNull List<String> devicesNames, @NonNull List<String> devicesMacs, @NonNull List<Integer> manufacturerIds) {
		this.mDevicesNames = devicesNames;
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
	 * Builder
	 */
	public static final class Builder {
		private List<String> mDevicesNames = new ArrayList<>();
		private List<String> mDevicesMacs = new ArrayList<>();
		private List<Integer> mManufacturerIds = new ArrayList<>();

		public Builder addDeviceName(@Nullable String deviceName) {
			this.mDevicesNames.add(deviceName);
			return this;
		}

		public Builder addMacAddress(@Nullable String macAddress) {
			this.mDevicesMacs.add(macAddress);
			return this;
		}

		public Builder addManufacturerId(int manufacturerId) {
			this.mManufacturerIds.add(manufacturerId);
			return this;
		}

		public ScanFilters build() {
			return new ScanFilters(mDevicesNames, mDevicesMacs, mManufacturerIds);
		}
	}
}
