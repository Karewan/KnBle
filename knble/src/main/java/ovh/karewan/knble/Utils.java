package ovh.karewan.knble;

import android.content.Context;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

@SuppressWarnings("MissingPermission")
public class Utils {
	/**
	 * Log (when DEBUG == true)
	 * @param msg String
	 */
	public static void log(String msg) {
		if(!KnBle.DEBUG) return;

		StackTraceElement ste = new Throwable().getStackTrace()[1];

		String tag = String.format("(%s:%s)",
				ste.getFileName(),
				ste.getLineNumber());

		Log.d("KnBle", tag + " " + msg);
	}

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

	/**
	 * UUID as bytes
	 * @param uuid UUID
	 * @return byte[]
	 */
	public static byte[] uuidAsBytes(UUID uuid) {
		ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}
}
