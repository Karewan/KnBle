package ovh.karewan.knble.sample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Results of the background scan (KnBle.startBackgroundScan), delivered by the system to this receiver: what an app
 * declares in its manifest. Counted for the "Scan: background scan" scenario.
 */
public final class ScanReceiver extends BroadcastReceiver {
	static final AtomicInteger sDeliveries = new AtomicInteger(), sDevices = new AtomicInteger();
	// elapsedRealtime of the first device, 0 for none
	static final AtomicLong sFirstAt = new AtomicLong();

	static void reset() {
		sDeliveries.set(0);
		sDevices.set(0);
		sFirstAt.set(0);
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		sDeliveries.incrementAndGet();
		List<BleDevice> devices = KnBle.parseBackgroundScan(intent, null);
		if (!devices.isEmpty()) {
			sDevices.addAndGet(devices.size());
			sFirstAt.compareAndSet(0, SystemClock.elapsedRealtime());
		}
	}
}
