package ovh.karewan.knble.struct;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Parameters of a connection, as the controller applied them (Android 8+): for the diagnostic of a slow link
 */
public final class BleConnectionParameters {
	private final int mInterval, mLatency, mTimeout;

	public BleConnectionParameters(int interval, int latency, int timeout) {
		mInterval = interval;
		mLatency = latency;
		mTimeout = timeout;
	}

	/**
	 * Connection interval in ms (units of 1.25 ms: 7.5 ms to 4 s)
	 */
	public double getIntervalMs() {
		return mInterval * 1.25;
	}

	/**
	 * Connection events the device may skip when it has nothing to send
	 */
	public int getLatency() {
		return mLatency;
	}

	/**
	 * Supervision timeout in ms: the connection is lost after this long without a packet
	 */
	public int getSupervisionTimeoutMs() {
		return mTimeout * 10;
	}

	@NonNull
	@Override
	public String toString() {
		return String.format(Locale.US, "interval %.2f ms, latency %d, timeout %d ms", getIntervalMs(), mLatency, getSupervisionTimeoutMs());
	}
}
