package ovh.karewan.knble;

import android.content.Context;
import android.location.LocationManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ovh.karewan.knble.internal.BleLog;

import java.nio.ByteBuffer;
import java.util.UUID;

public class Utils {
	/**
	 * Debug log (when KnBle.DEBUG), to Logcat or the logger of KnBleSettings
	 * @deprecated a log of the app, not of KnBle
	 */
	@Deprecated
	public static void log(@NonNull String msg) {
		if (KnBle.DEBUG) BleLog.d(msg);
	}

	/**
	 * Split a bytes array and fill a queue
	 * @param data The data
	 * @param splitSize Packet size (1 at least)
	 * @param queue Queue
	 * @deprecated KnBle.splittedWrite splits the data itself
	 */
	@Deprecated
	public static void splitBytesArrayAndFillQueue(@NonNull byte[] data, int splitSize, @NonNull java.util.Queue<byte[]> queue) {
		int size = Math.max(1, splitSize);
		if (data.length == 0) {
			queue.offer(data);
			return;
		}
		for (int offset = 0; offset < data.length; offset += size) {
			int n = Math.min(size, data.length - offset);
			byte[] packet = new byte[n];
			System.arraycopy(data, offset, packet, 0, n);
			queue.offer(packet);
		}
	}

	/**
	 * Check if at least one location service is enabled (BLE scan on Android 6 to 11). A binder call: not on the main thread.
	 * @param context The context
	 * @return boolean
	 */
	public static boolean areLocationServicesEnabled(@Nullable Context context) {
		if (context == null) return false;

		LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		if (locationManager == null) return false;

		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return locationManager.isLocationEnabled();
			return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (Exception e) {
			if (KnBle.DEBUG) e.printStackTrace();
			return false;
		}
	}

	/**
	 * UUID as bytes
	 * @param uuid UUID
	 * @return byte[]
	 */
	@NonNull
	public static byte[] uuidAsBytes(@NonNull UUID uuid) {
		ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
		bb.putLong(uuid.getMostSignificantBits());
		bb.putLong(uuid.getLeastSignificantBits());
		return bb.array();
	}

	/**
	 * Mac address to long
	 * @param mac String
	 * @return long
	 */
	public static long macToLong(@NonNull String mac) {
		return Long.parseLong(mac.replace(":", ""), 16);
	}
}
