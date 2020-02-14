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
package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import java.util.HashMap;

import ovh.karewan.knble.struct.BleDevice;

public interface BleScanCallback {
	int NO_ERROR = -1; // No error
	int BT_DISABLED = 0; // BT disabled or BT Problem
	int LOCATION_DISABLED = 1; // Location services disabled (Needed for Android 6+)
	int SCANNER_UNAVAILABLE = 2; // Scanner not available
	int UNKNOWN_ERROR = 3; // Unknown error
	int SCAN_FEATURE_UNSUPPORTED = 4; // A requested scan feature is not available on this device

	/**
	 * onScanStarted
	 */
	void onScanStarted();

	/**
	 * onScanFailed
	 * @param error The error
	 */
	void onScanFailed(int error);

	/**
	 * onScanResult
	 * @param bleDevice Discovered device
	 */
	void onScanResult(@NonNull BleDevice bleDevice);

	/**
	 * onScanFinished
	 * @param scanResult List with all discovered devices
	 */
	void onScanFinished(@NonNull HashMap<String, BleDevice> scanResult);
}
