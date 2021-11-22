package ovh.karewan.knble.utils;

import android.content.Context;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedList;
import java.util.Queue;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.struct.ScanRecord;

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
			if(KnBle.DEBUG) e.printStackTrace();
		}

		// Check the network provider
		try {
			network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (Exception e) {
			if(KnBle.DEBUG) e.printStackTrace();
		}

		return gps_enabled || network_enabled;
	}

	/**
	 * Get the manufacturer ID and data from the MANUFACTURER_SPECIFIC_DATA (Bluetooth 4.1 specification)
	 * @param scanRecord The scan record
	 * @return ScanRecord|null
	 */
	@Nullable
	public static ScanRecord getScanRecordFromBytes(@Nullable byte[] scanRecord) {
		if(scanRecord == null) return null;

		try {
			int currentPos = 0;

			while (currentPos < scanRecord.length) {
				int length = scanRecord[currentPos++] & 0xFF;
				if (length == 0) break;

				// MANUFACTURER_SPECIFIC_DATA
				if((scanRecord[currentPos++] & 0xFF) == 0xFF) {
					// ID
					int manufacturerId = ((scanRecord[currentPos + 1] & 0xFF) << 8) + (scanRecord[currentPos] & 0xFF);

					// DATA
					byte[] manufacturerData = new byte[length - 3];
					System.arraycopy(scanRecord, currentPos + 2, manufacturerData, 0, length - 3);

					return new ScanRecord(scanRecord, manufacturerId, manufacturerData);
				}

				currentPos += length - 1;
			}
		} catch (Exception e) {
			if(KnBle.DEBUG) e.printStackTrace();
		}

		return new ScanRecord(scanRecord, null, null);
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
