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
package ovh.karewan.knble.utils;

import android.content.Context;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedList;
import java.util.Queue;

@SuppressWarnings("MissingPermission")
public class Utils {
	/**
	 * Check if at least one location service is enabled (For BLE scan on Android 6+)
	 * @param context The context
	 * @return boolean
	 */
	public static boolean areLocationServicesEnabled(@Nullable Context context) {
		if(context == null) return false;

		// Get the location manager service
		LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		if(locationManager == null) return false;

		// Disabled by default
		boolean gps_enabled = false;
		boolean network_enabled = false;

		// Check the GPS provider
		try {
			gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Check the network provider
		try {
			network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return gps_enabled || network_enabled;
	}

	/**
	 * Get the manufacturer ID from the MANUFACTURER_SPECIFIC_DATA (Bluetooth 4.1 specification)
	 * @param scanRecord The scan record
	 * @return int
	 */
	public static int getManufacturerIdFromScanRecord(@Nullable byte[] scanRecord) {
		if(scanRecord == null) return -1;

		try {
			int currentPos = 0;

			while (currentPos < scanRecord.length) {
				int length = scanRecord[currentPos++] & 0xFF;
				if (length == 0) break;

				// MANUFACTURER_SPECIFIC_DATA
				if((scanRecord[currentPos++] & 0xFF) == 0xFF) {
					return ((scanRecord[currentPos + 1] & 0xFF) << 8) + (scanRecord[currentPos] & 0xFF);
				}

				currentPos += length - 1;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return -1;
	}

	/**
	 * Split a data pkg into a queue
	 * @param data The data
	 * @param split Split
	 * @param spliteSize Packet size
	 */
	@NonNull
	public static Queue<byte[]> splitBytesArray(@NonNull byte[] data, boolean split, int spliteSize) {
		Queue<byte[]> queue = new LinkedList<>();

		int pkgCount = 0;
		if(split) {
			if (data.length % spliteSize == 0) {
				pkgCount = data.length / spliteSize;
			} else {
				pkgCount = Math.round((float) (data.length / spliteSize + 1));
			}
		}

		if (pkgCount > 0) {
			for (int i = 0; i < pkgCount; i++) {
				byte[] dataPkg;
				int j;

				if (pkgCount == 1 || i == pkgCount - 1) {
					j = data.length % spliteSize == 0 ? spliteSize : data.length % spliteSize;
					System.arraycopy(data, i * spliteSize, dataPkg = new byte[j], 0, j);
				} else {
					System.arraycopy(data, i * spliteSize, dataPkg = new byte[spliteSize], 0, spliteSize);
				}

				queue.offer(dataPkg);
			}
		} else {
			queue.offer(data);
		}

		return queue;
	}
}
