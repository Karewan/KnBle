/*
	KnBle

	Released under the MIT License (MIT)

	Copyright (c) 2019-2020 Florent VIALATTE

	Permission is hereby granted, free of charge, to any person obtaining a copy
	of this software and associated documentation files (the "Software"), to deal
	in the Software without restriction, including without limitation the rights
	to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
	copies of the Software, and to permit persons to whom the Software is
	furnished to do so, subject to the following conditions:

	The above copyright notice and this permission notice shall be included in
	all copies or substantial portions of the Software.

	THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
	IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
	AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
	LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
	OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
	THE SOFTWARE.
 */
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
