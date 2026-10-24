package ovh.karewan.knble.sample;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleOtaCallback;
import ovh.karewan.knble.struct.BleProgress;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Scan, connection, transfer and OTA scenarios, run one at a time on the scenario thread.
 * Each one ends with RESULT lines (Report.result) so that runs on several phones can be compared.
 */
class Scenarios {
	static final int IMAGE_SEED = 42;

	protected final Report r;
	protected final Handler mMain = new Handler(Looper.getMainLooper());
	protected final IntConsumer mProgress;

	// Bench found by the last scan, shared by the scenarios
	@Nullable
	protected static volatile BenchLink sLink;
	// The bench is the phone bench (testbench/benchhost, found by its service UUID), not the ESP32-C3
	static volatile boolean sPcBench;

	Scenarios(@NonNull Report report, @NonNull IntConsumer progress) {
		r = report;
		mProgress = progress;
	}

	// region Helpers

	protected static void sleep(long ms) {
		SystemClock.sleep(ms);
	}

	/**
	 * Runs on the main thread and waits for the end
	 */
	protected void onMain(@NonNull Runnable run) {
		CountDownLatch latch = new CountDownLatch(1);
		mMain.post(() -> {
			try {
				run.run();
			} finally {
				latch.countDown();
			}
		});
		try {
			latch.await(60, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Progress of a transfer, as the apps do: posted to the main thread, at most every 100 ms
	 */
	@NonNull
	protected BiConsumer<Integer, Integer> uiProgress(@NonNull AtomicInteger calls) {
		AtomicLong last = new AtomicLong();
		return (current, total) -> {
			calls.incrementAndGet();
			long now = SystemClock.elapsedRealtime();
			if (now - last.get() < 100 && current < total) return;
			last.set(now);
			int permille = (int) (current * 1000L / Math.max(1, total));
			mMain.post(() -> mProgress.accept(permille));
		};
	}

	/**
	 * Scans with KnBle until a device matches or the timeout
	 */
	@Nullable
	protected BleDevice scanFor(@NonNull ScanFilters filters, @NonNull Predicate<BleDevice> match, long timeoutMs) {
		CountDownLatch found = new CountDownLatch(1);
		BleDevice[] result = new BleDevice[1];
		KnBle.setScanSettings(new ScanSettings.Builder().build());
		KnBle.setScanFilter(filters);
		KnBle.startScan(new BleScanCallback() {
			@Override
			public void onScanStarted() {}

			@Override
			public void onScanFailed(int error) {
				r.log("scan failed: error %d", error);
				found.countDown();
			}

			@Override
			public void onScanResult(@NonNull BleDevice device) {
				check(device);
			}

			@Override
			public void onDeviceUpdated(@NonNull BleDevice device) {
				check(device);
			}

			private void check(BleDevice device) {
				if (result[0] == null && match.test(device)) {
					result[0] = device;
					found.countDown();
				}
			}

			@Override
			public void onScanFinished(@NonNull List<BleDevice> scanResult) {}
		});

		try {
			found.await(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		KnBle.stopScan();
		KnBle.setScanFilter(new ScanFilters.Builder().build());
		return result[0];
	}

	/**
	 * Bench in normal mode, found by its name (filter on the name prefix, like the Nogema apps)
	 */
	@Nullable
	protected BenchLink findBench() {
		BenchLink link = sLink;
		if (link != null) return link;

		r.log("Looking for %s...", sPcBench ? "the phone bench" : Bench.NAME);
		BleDevice device = sPcBench
				? scanFor(new ScanFilters.Builder().build(), d -> d.getScanRecord() != null && Bench.advertisesBenchService(d.getScanRecord().getRawRecord()), 15000)
				: scanFor(new ScanFilters.Builder().setUseAndroid6Filters(false).addDeviceNameStartsWith(Bench.NAME).build(), d -> true, 15000);
		if (device == null) {
			r.log("Bench not found: is it powered and flashed (testbench/esp32-c3), or the bench app open (testbench/benchhost)?");
			return null;
		}
		r.log("Bench %s, RSSI %d", device.getMac(), device.getRssi());
		sLink = link = new BenchLink(device);
		return link;
	}

	/**
	 * Bench connected and subscribed to its answers
	 */
	@Nullable
	protected BenchLink ensureConnected() {
		BenchLink link = findBench();
		if (link == null) return null;
		if (link.connected()) return link;

		for (int attempt = 1; attempt <= 3; attempt++) {
			BenchLink.Outcome<Void> c = link.connect(BenchLink.CONNECT_TIMEOUT);
			if (c.ok()) {
				r.log("Connected in %d ms, MTU %d", c.ms, link.mtu());
				return link;
			}
			r.log("Connection %s after %d ms (attempt %d)", c.label(), c.ms, attempt);
			link.disconnect(BenchLink.OP_TIMEOUT);
			sleep(1000);
		}
		return null;
	}

	protected static int splitSize(int mtu) {
		// 512 bytes is the maximum length of an attribute value
		return Math.min(mtu - 3, 512);
	}

	protected static double kbps(long bytes, long ms) {
		return ms <= 0 ? 0 : bytes / 1024.0 / (ms / 1000.0);
	}

	/**
	 * Sink statistics of the bench: bytes, writes, dropped, crc, first-to-last ms, longest pause ms, pauses of 50 ms or more
	 */
	@Nullable
	protected long[] sinkStats(@NonNull BenchLink link) {
		BenchLink.Outcome<byte[]> s = link.command(Bench.command(Bench.CMD_GET_STATS), BenchLink.OP_TIMEOUT);
		if (!s.ok() || s.value == null || s.value.length < 22) return null;
		byte[] a = s.value;
		boolean pauses = a.length >= 30;
		return new long[]{Bench.le32(a, 2), Bench.le32(a, 6), Bench.le32(a, 10), Bench.le32(a, 14), Bench.le32(a, 18),
				pauses ? Bench.le32(a, 22) : -1, pauses ? Bench.le32(a, 26) : -1};
	}

	/**
	 * Android acknowledges a write without response as soon as it is queued in its stack (hundreds of KB on a Pixel 9a):
	 * a transfer is over when the bench has received every byte
	 * @return ms from startNs to the answer of the bench holding every byte, -1 on timeout
	 */
	protected long awaitDelivered(@NonNull BenchLink link, long size, long startNs, long timeoutMs) {
		long deadline = startNs + timeoutMs * 1_000_000;
		while (BenchLink.now() < deadline) {
			long[] s = sinkStats(link);
			if (s != null && s[0] >= size) return BenchLink.msSince(startNs);
			sleep(20);
		}
		return -1;
	}

	/**
	 * OTA result published by the bench: protocol, state, bytes, crc, dropped, ms
	 */
	@Nullable
	protected long[] otaResult(@NonNull BenchLink link, @NonNull UUID service, @NonNull UUID characteristic) {
		BenchLink.Outcome<byte[]> res = link.read(service, characteristic, BenchLink.OP_TIMEOUT);
		if (!res.ok() || res.value == null || res.value.length < 18) return null;
		byte[] v = res.value;
		return new long[]{v[0], v[1], Bench.le32(v, 2), Bench.le32(v, 6), Bench.le32(v, 10), Bench.le32(v, 14)};
	}

	/**
	 * Main thread of an app with a heavy UI: busy 12 ms out of every 16 ms while the scenario runs
	 */
	protected void withBusyMain(@NonNull String label, @NonNull Runnable scenario) {
		java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
		Runnable[] load = new Runnable[1];
		load[0] = () -> {
			if (!running.get()) return;
			long end = SystemClock.elapsedRealtime() + 12;
			// Busy loop (Thread.onSpinWait is Android 13+)
			while (SystemClock.elapsedRealtime() < end) {
				Math.sqrt(end);
			}
			mMain.postDelayed(load[0], 4);
		};
		r.log("%s with the main thread busy 75 %% of the time", label);
		mMain.post(load[0]);
		try {
			scenario.run();
		} finally {
			running.set(false);
		}
	}

	protected static long percentile(@NonNull List<Long> values, double p) {
		if (values.isEmpty()) return 0;
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
	}

	// endregion

	// region Scan

	private static final class DeviceStats {
		long firstMs, lastMs, maxGapMs;
		int reports;
		final Set<Integer> rssi = new HashSet<>();
		final Set<Integer> counters = new HashSet<>();
		final Set<String> systemNames = new HashSet<>();
		final Set<String> advertisedNames = new HashSet<>();
		int reportsWithoutAdvertisedName;
		// onDeviceLost (KnBle 4+), onScanResult again after a loss
		int lost, foundAgain;
		long firstTimestamp, lastTimestamp;
	}

	/**
	 * Scans for a while and records, per device, how often its data is refreshed
	 */
	@NonNull
	private Map<String, DeviceStats> scanStats(@NonNull ScanFilters filters, long durationMs) {
		return scanStats(filters, new ScanSettings.Builder().build(), durationMs);
	}

	@NonNull
	private Map<String, DeviceStats> scanStats(@NonNull ScanFilters filters, @NonNull ScanSettings settings, long durationMs) {
		Map<String, DeviceStats> stats = new ConcurrentHashMap<>();
		long start = SystemClock.elapsedRealtime();

		KnBle.setScanSettings(settings);
		KnBle.setScanFilter(filters);
		KnBle.startScan(new BleScanCallback() {
			@Override
			public void onScanStarted() {}

			@Override
			public void onScanFailed(int error) {
				r.log("scan failed: error %d", error);
			}

			@Override
			public void onScanResult(@NonNull BleDevice device) {
				DeviceStats known = stats.get(device.getMac());
				if (known != null) known.foundAgain++;
				record(device);
			}

			@Override
			public void onDeviceUpdated(@NonNull BleDevice device) {
				record(device);
			}

			// KnBle 4+ (no @Override: the same sample builds on 3.0.5)
			public void onDeviceLost(@NonNull BleDevice device) {
				DeviceStats s = stats.get(device.getMac());
				if (s != null) s.lost++;
			}

			private void record(BleDevice device) {
				long t = SystemClock.elapsedRealtime() - start;
				DeviceStats s = stats.computeIfAbsent(device.getMac(), k -> {
					DeviceStats n = new DeviceStats();
					n.firstMs = t;
					n.lastMs = t;
					n.firstTimestamp = device.getTimestamp();
					return n;
				});
				s.maxGapMs = Math.max(s.maxGapMs, t - s.lastMs);
				s.lastMs = t;
				s.reports++;
				s.rssi.add(device.getRssi());
				s.lastTimestamp = device.getTimestamp();
				int counter = Bench.advertisedCounter(device);
				if (counter >= 0) s.counters.add(counter);
				String name = Bench.advertisedName(device.getScanRecord());
				if (name == null) s.reportsWithoutAdvertisedName++;
				else s.advertisedNames.add(name);
			}

			@Override
			public void onScanFinished(@NonNull List<BleDevice> scanResult) {}
		});

		sleep(durationMs);
		KnBle.stopScan();
		KnBle.setScanFilter(new ScanFilters.Builder().build());

		// System names read after the scan, out of the scan callbacks (binder call each)
		for (BleDevice d : KnBle.getScannedDevices()) {
			DeviceStats s = stats.get(d.getMac());
			String name = d.getName();
			if (s != null && name != null) s.systemNames.add(name);
		}
		return stats;
	}

	/**
	 * Every device around: does this phone keep reporting a device seen once (or does it filter the duplicates,
	 * which made the Nogema apps restart the scan every 10 s)?
	 */
	void scanDiagnostic() {
		long durationMs = 20000;
		r.log("Scan of %d s, no filter, LOW_LATENCY, without restart", durationMs / 1000);
		Map<String, DeviceStats> stats = scanStats(new ScanFilters.Builder().build(), durationMs);

		int reports = 0, early = 0, earlyUpdated = 0;
		List<Long> perDevice = new ArrayList<>();
		List<Long> gaps = new ArrayList<>();
		for (DeviceStats s : stats.values()) {
			reports += s.reports;
			if (s.firstMs < durationMs / 2) {
				early++;
				if (s.reports >= 2) earlyUpdated++;
				perDevice.add((long) s.reports);
				gaps.add(s.maxGapMs);
			}
		}

		String verdict = early < 3 ? "undetermined (not enough devices around)"
				: earlyUpdated == 0 ? "YES (each device reported once: restart needed)"
				: earlyUpdated * 2 < early ? "partial" : "no";
		r.log("%d devices, %d reports (%.1f/s); seen in the first %d s: %d, updated afterwards: %d",
				stats.size(), reports, reports * 1000.0 / durationMs, durationMs / 2000, early, earlyUpdated);
		r.log("Duplicate filtering by the phone: %s", verdict);
		r.result("scan_diagnostic", "devices=%d reports=%d rate=%.1f early=%d earlyUpdated=%d medianReports=%d medianMaxGapMs=%d duplicateFiltering=%s",
				stats.size(), reports, reports * 1000.0 / durationMs, early, earlyUpdated, percentile(perDevice, 0.5), percentile(gaps, 0.5),
				verdict.split(" ")[0]);
	}

	/**
	 * Cost of BleDevice.getName() (BluetoothDevice.getName(): a binder call to the Bluetooth process) on the
	 * main thread, as in the list adapters of the apps
	 */
	void nameCost() {
		List<BleDevice> devices = KnBle.getScannedDevices();
		if (devices.size() < 5) {
			r.log("Short scan first (8 s)");
			scanStats(new ScanFilters.Builder().build(), 8000);
			devices = KnBle.getScannedDevices();
		}
		if (devices.isEmpty()) {
			r.log("No device around");
			return;
		}

		List<BleDevice> list = devices.subList(0, Math.min(60, devices.size()));
		List<Long> us = Collections.synchronizedList(new ArrayList<>());
		long[] firstPass = new long[1];
		onMain(() -> {
			long t0 = SystemClock.elapsedRealtimeNanos();
			for (BleDevice d : list) {
				long t = SystemClock.elapsedRealtimeNanos();
				d.getName();
				us.add((SystemClock.elapsedRealtimeNanos() - t) / 1000);
			}
			firstPass[0] = (SystemClock.elapsedRealtimeNanos() - t0) / 1000;
			for (int k = 0; k < 2; k++) {
				for (BleDevice d : list) {
					long t = SystemClock.elapsedRealtimeNanos();
					d.getName();
					us.add((SystemClock.elapsedRealtimeNanos() - t) / 1000);
				}
			}
		});

		long sum = 0;
		for (long v : us) sum += v;
		long avg = us.isEmpty() ? 0 : sum / us.size();
		r.log("getName(): %d calls on the main thread, avg %d µs, p95 %d µs, max %d µs; one pass over %d devices: %.1f ms (a frame is 16 ms)",
				us.size(), avg, percentile(us, 0.95), percentile(us, 1), list.size(), firstPass[0] / 1000.0);
		r.result("name_cost", "calls=%d avgUs=%d p95Us=%d maxUs=%d devices=%d passMs=%.1f", us.size(), avg, percentile(us, 0.95), percentile(us, 1),
				list.size(), firstPass[0] / 1000.0);
	}

	/**
	 * Refresh of the data of a device (name, manufacturer data, RSSI, timestamp) for each advertising configuration of the bench
	 */
	void advertisingModes() {
		int[][] modes = {
				{Bench.ADV_NORMAL, 0},
				{Bench.ADV_NAME_IN_SCAN_RESPONSE, 0},
				{Bench.ADV_SLOW, 2000},
				{Bench.ADV_STOPPED, 6000},
				// Long enough for a loss (onDeviceLost), then found again
				{Bench.ADV_STOPPED, 15000},
				// Last: the bench cannot be connected to for 20 s
				{Bench.ADV_NON_CONNECTABLE, 0},
		};
		String[] labels = {"normal", "name_in_scan_response", "interval_2000ms", "stopped_6s", "stopped_15s", "non_connectable"};

		for (int i = 0; i < modes.length; i++) {
			BenchLink link = ensureConnected();
			if (link == null) return;
			BenchLink.Outcome<byte[]> set = link.command(Bench.command(Bench.CMD_ADV, (byte) modes[i][0], modes[i][1]), BenchLink.OP_TIMEOUT);
			if (!set.ok()) {
				r.log("Advertising mode %s: %s", labels[i], set.label());
				continue;
			}
			link.disconnect(BenchLink.OP_TIMEOUT);

			long durationMs = modes[i][0] == Bench.ADV_STOPPED && modes[i][1] > 10000 ? 32000 : 12000;
			r.log("Advertising %s: scan of %d s", labels[i], durationMs / 1000);
			Map<String, DeviceStats> stats = scanStats(new ScanFilters.Builder().addMacAddress(link.device.getMac()).build(), durationMs);
			DeviceStats s = stats.get(link.device.getMac());
			if (s == null) {
				r.result("adv_" + labels[i], "reports=0");
				continue;
			}
			long refreshedMs = s.lastTimestamp - s.firstTimestamp;
			r.log("First seen after %d ms, %d reports, %d counter values, %d RSSI values, max gap %d ms, timestamp refreshed over %d ms, system name %s, advertised name %s (%d reports without)",
					s.firstMs, s.reports, s.counters.size(), s.rssi.size(), s.maxGapMs, refreshedMs, s.systemNames, s.advertisedNames, s.reportsWithoutAdvertisedName);
			r.result("adv_" + labels[i], "firstSeenMs=%d reports=%d counters=%d rssiValues=%d maxGapMs=%d timestampSpanMs=%d systemName=%s advertisedName=%s reportsWithoutName=%d lost=%d foundAgain=%d",
					s.firstMs, s.reports, s.counters.size(), s.rssi.size(), s.maxGapMs, refreshedMs, s.systemNames, s.advertisedNames, s.reportsWithoutAdvertisedName,
					s.lost, s.foundAgain);
		}

		r.log("The bench goes back to the normal advertising 20 s after the last mode");
	}

	/**
	 * Loss and return of a device during a scan: the bench stops advertising while it is connected (20 s)
	 */
	void scanLostAndFound() {
		BenchLink link = findBench();
		if (link == null) return;
		link.disconnect(BenchLink.OP_TIMEOUT);
		String mac = link.device.getMac();
		long start = SystemClock.elapsedRealtime();
		AtomicLong lastReport = new AtomicLong(-1), lostAt = new AtomicLong(-1), foundAt = new AtomicLong(-1);
		AtomicInteger results = new AtomicInteger(), updates = new AtomicInteger(), losses = new AtomicInteger();
		AtomicLong connectedAt = new AtomicLong(-1);

		KnBle.setScanSettings(new ScanSettings.Builder().build());
		KnBle.setScanFilter(new ScanFilters.Builder().addMacAddress(mac).build());
		KnBle.startScan(new BleScanCallback() {
			@Override
			public void onScanStarted() {}

			@Override
			public void onScanFailed(int error) {
				r.log("scan failed: error %d", error);
			}

			@Override
			public void onScanResult(@NonNull BleDevice device) {
				results.incrementAndGet();
				long t = SystemClock.elapsedRealtime() - start;
				if (lostAt.get() >= 0) foundAt.compareAndSet(-1, t);
				else if (connectedAt.get() < 0) lastReport.set(t);
			}

			@Override
			public void onDeviceUpdated(@NonNull BleDevice device) {
				updates.incrementAndGet();
				if (connectedAt.get() < 0) lastReport.set(SystemClock.elapsedRealtime() - start);
			}

			// KnBle 4+ (no @Override: the same sample builds on 3.0.5)
			public void onDeviceLost(@NonNull BleDevice device) {
				losses.incrementAndGet();
				lostAt.compareAndSet(-1, SystemClock.elapsedRealtime() - start);
			}

			@Override
			public void onScanFinished(@NonNull List<BleDevice> scanResult) {}
		});

		sleep(6000);
		BenchLink.Outcome<Void> c = link.connect(BenchLink.CONNECT_TIMEOUT);
		connectedAt.set(SystemClock.elapsedRealtime() - start);
		r.log("Bench connected (%s): advertising stops for 20 s", c.label());
		sleep(20000);
		link.disconnect(BenchLink.OP_TIMEOUT);
		long disconnectedAt = SystemClock.elapsedRealtime() - start;
		sleep(15000);
		KnBle.stopScan();
		KnBle.setScanFilter(new ScanFilters.Builder().build());

		r.result("scan_lost_found", "connected=%b lastReportMs=%d connectedAtMs=%d lostAtMs=%d lostAfterLastReportMs=%d disconnectedAtMs=%d foundAgainAtMs=%d foundAfterDisconnectMs=%d results=%d updates=%d losses=%d",
				c.ok(), lastReport.get(), connectedAt.get(), lostAt.get(), lostAt.get() < 0 ? -1 : lostAt.get() - lastReport.get(), disconnectedAt, foundAt.get(),
				foundAt.get() < 0 ? -1 : foundAt.get() - disconnectedAt, results.get(), updates.get(), losses.get());
	}

	/**
	 * A plain Android scan next to the KnBle one, same settings, no filter: per device, how many reports the system gave
	 * and how many callbacks the app got (KnBle 4 gives the latest data of a device when the app lags behind)
	 */
	@SuppressLint("MissingPermission")
	void scanVersusRaw(@NonNull Context context) {
		BluetoothManager manager = context.getSystemService(BluetoothManager.class);
		BluetoothLeScanner scanner = manager == null || manager.getAdapter() == null ? null : manager.getAdapter().getBluetoothLeScanner();
		if (scanner == null) {
			r.log("No scanner");
			return;
		}
		long durationMs = 15000;

		for (boolean legacy : new boolean[]{true, false}) {
			Map<String, AtomicInteger> raw = new ConcurrentHashMap<>();
			ScanCallback rawCallback = new ScanCallback() {
				@Override
				public void onScanResult(int callbackType, ScanResult result) {
					raw.computeIfAbsent(result.getDevice().getAddress(), k -> new AtomicInteger()).incrementAndGet();
				}
			};
			android.bluetooth.le.ScanSettings.Builder b = new android.bluetooth.le.ScanSettings.Builder()
					.setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				b.setLegacy(legacy);
				if (!legacy) b.setPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M);
			}
			scanner.startScan(null, b.build(), rawCallback);
			ScanSettings settings = new ScanSettings.Builder().setLegacy(legacy).setPhy(ScanSettings.PHY_LE_1M).build();
			Map<String, DeviceStats> stats = scanStats(new ScanFilters.Builder().build(), settings, durationMs);
			scanner.stopScan(rawCallback);

			int rawReports = 0, callbacks = 0, worstRaw = 0, worstCallbacks = 0;
			for (Map.Entry<String, AtomicInteger> e : raw.entrySet()) {
				int n = e.getValue().get();
				DeviceStats s = stats.get(e.getKey());
				int c = s == null ? 0 : s.reports;
				rawReports += n;
				callbacks += c;
				if (n > worstRaw) {
					worstRaw = n;
					worstCallbacks = c;
				}
			}
			r.result("scan_vs_raw", "mode=%s devices=%d knbleDevices=%d rawReports=%d callbacks=%d ratio=%.2f busiestRaw=%d busiestCallbacks=%d",
					legacy ? "legacy" : "default", raw.size(), stats.size(), rawReports, callbacks, callbacks / (double) Math.max(1, rawReports), worstRaw, worstCallbacks);
		}
	}

	/**
	 * Legacy advertisements only, then the default settings (extended ones too when the phone supports them on KnBle 4):
	 * does the default lose reports or names of a legacy device?
	 */
	void scanLegacyVersusDefault() {
		BenchLink link = findBench();
		if (link == null) return;
		link.disconnect(BenchLink.OP_TIMEOUT);
		ScanFilters bench = new ScanFilters.Builder().addMacAddress(link.device.getMac()).build();
		long durationMs = 15000;

		for (int round = 0; round < 2; round++) {
			for (boolean legacy : new boolean[]{true, false}) {
				ScanSettings settings = legacy ? new ScanSettings.Builder().setLegacy(true).build() : new ScanSettings.Builder().build();
				Map<String, DeviceStats> stats = scanStats(bench, settings, durationMs);
				DeviceStats s = stats.get(link.device.getMac());
				String name = legacy ? "scan_legacy_only" : "scan_default";
				if (s == null) {
					r.result(name, "reports=0");
					continue;
				}
				r.result(name, "reports=%d perS=%.1f counters=%d rssiValues=%d maxGapMs=%d reportsWithoutName=%d", s.reports, s.reports * 1000.0 / durationMs,
						s.counters.size(), s.rssi.size(), s.maxGapMs, s.reportsWithoutAdvertisedName);
			}
		}
	}

	// endregion

	// region Connection

	void connect() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);
		r.log("Services: %d, bench service %b", link.services().size(), link.hasService(Bench.SVC_BENCH));
	}

	void disconnect() {
		BenchLink link = sLink;
		if (link == null) return;
		BenchLink.Outcome<Void> d = link.disconnect(BenchLink.OP_TIMEOUT);
		r.log("Disconnect: %s in %d ms", d.label(), d.ms);
	}

	/**
	 * Connections and disconnections in a row, as fast as the callbacks allow
	 */
	void stressConnections(int cycles) {
		BenchLink link = findBench();
		if (link == null) return;
		link.disconnect(BenchLink.OP_TIMEOUT);

		List<Long> times = new ArrayList<>();
		int failed = 0, timeouts = 0, disconnectTimeouts = 0;
		for (int i = 1; i <= cycles; i++) {
			BenchLink.Outcome<Void> c = link.connect(BenchLink.CONNECT_TIMEOUT);
			if (c.ok()) times.add(c.ms);
			else if (c.status == BenchLink.Outcome.TIMEOUT) timeouts++;
			else failed++;

			if (!link.disconnect(BenchLink.OP_TIMEOUT).ok()) disconnectTimeouts++;
			mMain.post(() -> mProgress.accept(0));
			if (i % 10 == 0) r.log("%d/%d cycles, %d ok", i, cycles, times.size());
		}

		long sum = 0;
		for (long t : times) sum += t;
		r.result("stress_connect", "cycles=%d ok=%d failed=%d timeouts=%d disconnectTimeouts=%d avgMs=%d p50Ms=%d p95Ms=%d maxMs=%d",
				cycles, times.size(), failed, timeouts, disconnectTimeouts, times.isEmpty() ? 0 : sum / times.size(),
				percentile(times, 0.5), percentile(times, 0.95), percentile(times, 1));
	}

	/**
	 * Link loss: the bench disconnects by itself, how long until onDisconnected?
	 */
	void linkLoss() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		BenchLink.Outcome<byte[]> c = link.command(Bench.command(Bench.CMD_DISCONNECT_IN, 1000), BenchLink.OP_TIMEOUT);
		long expected = BenchLink.now() + 1_000_000_000L;
		if (!c.ok()) {
			r.log("Command: %s", c.label());
			return;
		}
		boolean seen = link.awaitDisconnection(15000);
		long latency = seen ? (link.disconnectedAtNs - expected) / 1_000_000 : -1;
		r.result("link_loss", "seen=%b latencyMs=%d connectFailedFlag=%b", seen, latency, link.lastConnectFailed);
	}

	// endregion

	// region Transfers

	/**
	 * splittedWrite to the sink, then the statistics of the bench (bytes, CRC)
	 */
	void sinkWrite(int size, boolean noResponse) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		int split = splitSize(link.prepareFastLink(r));

		link.command(Bench.command(Bench.CMD_RESET_STATS), BenchLink.OP_TIMEOUT);
		byte[] image = Bench.image(size, IMAGE_SEED);
		AtomicInteger calls = new AtomicInteger();
		r.log("splittedWrite %d KB in packets of %d bytes, %s", size / 1024, split, noResponse ? "without response" : "with response");

		long start = BenchLink.now();
		BenchLink.Outcome<Long> w = link.splittedWrite(Bench.SVC_BENCH, Bench.CHR_SINK, image, split, noResponse, 0, uiProgress(calls), 300000);
		String name = noResponse ? "write_no_response" : "write_with_response";
		if (!w.ok() || w.value == null) {
			r.result(name, "size=%d status=%s afterMs=%d", size, w.label(), w.ms);
			return;
		}
		long acceptedMs = w.value / 1_000_000;
		long deliveredMs = awaitDelivered(link, size, start, 120000);
		long[] s = sinkStats(link);
		if (s == null || deliveredMs < 0) {
			r.result(name, "size=%d acceptedMs=%d status=not_delivered", size, acceptedMs);
			return;
		}

		boolean crcOk = s[0] == size && s[3] == Bench.crc32(image);
		// dataSpanMs: first to last packet received by the bench, whatever the phone does around the transfer
		r.result(name, "size=%d mtu=%d split=%d packets=%d acceptedMs=%d deliveredMs=%d kbps=%.1f dataSpanMs=%d spanKbps=%.1f maxPauseMs=%d pauses50=%d benchBytes=%d crcOk=%b progressCalls=%d",
				size, link.mtu(), split, (size + split - 1) / split, acceptedMs, deliveredMs, kbps(size, deliveredMs), s[4], kbps(size, s[4]), s[5], s[6], s[0], crcOk,
				calls.get());
	}

	/**
	 * Silicon Labs AppLoader sequence of the Nogema apps: control 0x00, image without response, control 0x03
	 */
	@Nullable
	long[] silabsOta(@NonNull BenchLink link, int size, long interval, @NonNull UUID resultService, @NonNull UUID resultCharacteristic) {
		int split = splitSize(link.mtu());
		byte[] image = Bench.image(size, IMAGE_SEED);

		BenchLink.Outcome<Void> start = link.write(Bench.SVC_SLABS_OTA, Bench.CHR_SLABS_CONTROL, new byte[]{0x00}, false, BenchLink.OP_TIMEOUT);
		if (!start.ok()) {
			r.log("OTA start: %s", start.label());
			return null;
		}

		AtomicInteger calls = new AtomicInteger();
		BenchLink.Outcome<Long> w = link.splittedWrite(Bench.SVC_SLABS_OTA, Bench.CHR_SLABS_DATA, image, split, true, interval, uiProgress(calls), 600000);
		if (!w.ok() || w.value == null) {
			r.log("OTA upload: %s after %d ms", w.label(), w.ms);
			return null;
		}

		// Queued behind every packet Android still holds (hundreds of KB on a Pixel 9a): answered once the image is delivered
		BenchLink.Outcome<Void> end = link.write(Bench.SVC_SLABS_OTA, Bench.CHR_SLABS_CONTROL, new byte[]{0x03}, false, 60000);
		if (!end.ok()) {
			r.log("OTA end: %s", end.label());
			return null;
		}

		sleep(400);
		long[] res = otaResult(link, resultService, resultCharacteristic);
		if (res == null) return null;
		long acceptedMs = w.value / 1_000_000;
		boolean crcOk = res[2] == size && res[3] == Bench.crc32(image);
		// First to last packet received by the bench (not available in DFU mode)
		long[] stats = link.hasService(Bench.SVC_BENCH) ? sinkStats(link) : null;
		// Bench side: from control 0x00 to the end of the image (the writes accepted by Android are still in its stack)
		return new long[]{acceptedMs, crcOk ? 1 : 0, res[4], split, res[5], stats == null ? -1 : stats[4], stats == null ? -1 : stats[5], stats == null ? -1 : stats[6]};
	}

	void silabsOta(int size) {
		silabsOta(size, "ota_silabs");
	}

	void silabsOtaBusyMain(int size) {
		withBusyMain("Silicon Labs OTA", () -> silabsOta(size, "ota_silabs_busy_main"));
	}

	private void silabsOta(int size, @NonNull String name) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);
		r.log("Silicon Labs OTA emulation, %d KB", size / 1024);
		long[] o = silabsOta(link, size, 0, Bench.SVC_BENCH, Bench.CHR_RESULT);
		if (o == null) r.result(name, "size=%d status=failed", size);
		else r.result(name, "size=%d mtu=%d split=%d acceptedMs=%d deliveredMs=%d kbps=%.1f dataSpanMs=%d spanKbps=%.1f maxPauseMs=%d pauses50=%d crcOk=%b dropped=%d",
				size, link.mtu(), o[3], o[0], o[4], kbps(size, o[4]), o[5], kbps(size, o[5]), o[6], o[7], o[1] == 1, o[2]);
	}

	/**
	 * The same Silicon Labs OTA run by KnBle (KnBle.otaSilabs): bench-side times like the app-driven one, compared with 3.0.5
	 */
	void silabsOtaLib(int size) {
		silabsOtaLib(size, "ota_silabs_lib");
	}

	void silabsOtaLibBusyMain(int size) {
		withBusyMain("Silicon Labs OTA run by KnBle", () -> silabsOtaLib(size, "ota_silabs_lib_busy_main"));
	}

	private void silabsOtaLib(int size, @NonNull String name) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);
		r.log("Silicon Labs OTA run by KnBle, %d KB", size / 1024);
		byte[] image = Bench.image(size, IMAGE_SEED);
		LibOta ota = runLibOta(image, false, link, 120);
		if (ota.error != 0) {
			r.result(name, "size=%d status=%s", size, ovh.karewan.knble.BleError.name(ota.error));
			return;
		}

		sleep(400);
		long[] res = otaResult(link, Bench.SVC_BENCH, Bench.CHR_RESULT);
		long[] stats = sinkStats(link);
		if (res == null || stats == null) {
			r.result(name, "size=%d status=no_result", size);
			return;
		}
		boolean crcOk = res[2] == size && res[3] == Bench.crc32(image);
		r.result(name, "size=%d mtu=%d totalMs=%d deliveredMs=%d kbps=%.1f dataSpanMs=%d spanKbps=%.1f maxPauseMs=%d pauses50=%d crcOk=%b dropped=%d progressCalls=%d lastRate=%s",
				size, link.mtu(), ota.ms, res[5], kbps(size, res[5]), stats[4], kbps(size, stats[4]), stats[5], stats[6], crcOk, res[4], ota.progressCalls,
				ota.last == null ? "-" : ota.last.getRate().replace(' ', '_'));
	}

	/**
	 * The same TI OAD run by KnBle (KnBle.otaTi): each block request answered on the thread of the device
	 */
	void tiOadLib(int size) {
		tiOadLib(size, "ota_ti_lib");
	}

	void tiOadLibBusyMain(int size) {
		withBusyMain("TI OAD run by KnBle", () -> tiOadLib(size, "ota_ti_lib_busy_main"));
	}

	private void tiOadLib(int size, @NonNull String name) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);
		r.log("TI OAD run by KnBle, %d KB", size / 1024);
		byte[] image = Bench.tiImage(size, IMAGE_SEED);
		LibOta ota = runLibOta(image, true, link, 300);
		if (ota.error != 0) {
			r.result(name, "size=%d status=%s", size, ovh.karewan.knble.BleError.name(ota.error));
			return;
		}

		sleep(300);
		long[] res = otaResult(link, Bench.SVC_BENCH, Bench.CHR_RESULT);
		boolean crcOk = res != null && res[2] == size && res[3] == Bench.crc32(image);
		int blocks = ota.last == null ? 0 : ota.last.getTotalPackets();
		long benchMs = res == null ? -1 : res[5];
		r.result(name, "size=%d blocks=%d ms=%d benchMs=%d blocksPerS=%.0f kbps=%.1f crcOk=%b progressCalls=%d lastRate=%s", size, blocks, ota.ms, benchMs,
				blocks * 1000.0 / Math.max(1, benchMs), kbps(size, benchMs), crcOk, ota.progressCalls, ota.last == null ? "-" : ota.last.getRate().replace(' ', '_'));
	}

	private static final class LibOta {
		volatile int error;
		volatile long ms;
		volatile int progressCalls;
		@Nullable
		volatile BleProgress last;
	}

	/**
	 * Runs an OTA of KnBle and waits for its end (the progress goes to the UI, the callbacks are on the main thread)
	 */
	@NonNull
	private LibOta runLibOta(@NonNull byte[] image, boolean ti, @NonNull BenchLink link, long timeoutS) {
		LibOta ota = new LibOta();
		CountDownLatch done = new CountDownLatch(1);
		long start = BenchLink.now();
		BleOtaCallback callback = new BleOtaCallback() {
			@Override
			public void onOtaSuccess() {
				ota.ms = BenchLink.msSince(start);
				done.countDown();
			}

			@Override
			public void onOtaProgress(@NonNull BleProgress progress) {
				ota.progressCalls++;
				ota.last = progress;
				mProgress.accept(progress.getPercent() * 10);
			}

			@Override
			public void onOtaFailed(int error) {
				ota.error = error == 0 ? Integer.MIN_VALUE : error;
				done.countDown();
			}
		};
		if (ti) KnBle.otaTi(link.device, image, callback);
		else KnBle.otaSilabs(link.device, image, callback);
		try {
			if (!done.await(timeoutS, TimeUnit.SECONDS)) ota.error = ovh.karewan.knble.BleError.TIMEOUT;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return ota;
	}

	/**
	 * The buggy bootloader of the field: writes acknowledged at once, kept in a small buffer, what does not fit is lost.
	 * Which packet interval makes the OTA pass?
	 */
	void silabsOtaWithoutFlowControl(int size) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);

		BenchLink.Outcome<byte[]> mode = link.command(Bench.command(Bench.CMD_SINK_BUFFER, (byte) 1, (short) 4, 12000), BenchLink.OP_TIMEOUT);
		if (!mode.ok()) {
			r.log("Bench mode: %s", mode.label());
			return;
		}
		r.log("Bench: no flow control, 4 packets of buffer, 12 ms per packet (about 42 KB/s)");

		for (long interval : new long[]{0, 5, 10, 15, 20}) {
			long[] o = silabsOta(link, size, interval, Bench.SVC_BENCH, Bench.CHR_RESULT);
			if (o == null) r.result("ota_no_flow_control", "interval=%d status=failed", interval);
			else r.result("ota_no_flow_control", "interval=%d size=%d acceptedMs=%d deliveredMs=%d kbps=%.1f crcOk=%b dropped=%d", interval, size, o[0], o[4],
					kbps(size, o[4]), o[1] == 1, o[2]);
		}

		link.command(Bench.command(Bench.CMD_SINK_BUFFER, (byte) 0, (short) 0, 0), BenchLink.OP_TIMEOUT);
	}

	/**
	 * TI Enhanced OAD emulation: the bench asks for each block by a notification, the phone answers with a write.
	 * @param throughMain true: every notification goes through the main thread before the answer (like the TiOad
	 *                    of nogemadm), false: answered on the KnBle callback thread
	 */
	void tiOadBusyMain(int size) {
		withBusyMain("TI OAD", () -> tiOad(size, false, "ota_ti_busy_main"));
	}

	void tiOad(int size, boolean throughMain) {
		tiOad(size, throughMain, throughMain ? "ota_ti_main_thread" : "ota_ti_ble_thread");
	}

	private void tiOad(int size, boolean throughMain, @NonNull String name) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		int mtu = link.prepareFastLink(r);

		CountDownLatch done = new CountDownLatch(1);
		AtomicInteger blocks = new AtomicInteger();
		AtomicInteger blockSize = new AtomicInteger();
		String[] failure = new String[1];
		long[] startNs = new long[1];
		byte[] image = Bench.image(size, IMAGE_SEED);

		Runnable fail = done::countDown;
		java.util.function.Consumer<byte[]> onControl = data -> {
			if (data.length == 0) return;
			switch (data[0]) {
				case 0x01 -> {
					blockSize.set(Bench.le16(data, 1));
					if (blockSize.get() > mtu - 3 || blockSize.get() <= 4) {
						failure[0] = "block size " + blockSize.get();
						fail.run();
						return;
					}
					byte[] identify = new byte[22];
					Bench.putLe32(identify, 14, size);
					link.writeAsync(Bench.SVC_TI_OAD, Bench.CHR_TI_IDENTIFY, identify, true);
				}
				case 0x12 -> {
					if (data.length > 1 && data[1] == 0x0E) {
						link.writeAsync(Bench.SVC_TI_OAD, Bench.CHR_TI_CONTROL, new byte[]{0x04}, true);
					} else if (data.length > 5 && data[1] == 0x00) {
						long block = Bench.le32(data, 2);
						int dataSize = blockSize.get() - 4;
						int offset = (int) block * dataSize;
						int length = Math.min(dataSize, size - offset);
						byte[] packet = new byte[4 + length];
						Bench.putLe32(packet, 0, (int) block);
						System.arraycopy(image, offset, packet, 4, length);
						blocks.incrementAndGet();
						link.writeAsync(Bench.SVC_TI_OAD, Bench.CHR_TI_BLOCK, packet, true);
					} else {
						failure[0] = "block request status " + (data.length > 1 ? data[1] : -1);
						fail.run();
					}
				}
				case 0x04 -> done.countDown();
				default -> {}
			}
		};
		java.util.function.Consumer<byte[]> onIdentify = data -> {
			if (data.length > 0 && data[0] == 0x00) {
				startNs[0] = BenchLink.now();
				link.writeAsync(Bench.SVC_TI_OAD, Bench.CHR_TI_CONTROL, new byte[]{0x03}, true);
			} else {
				failure[0] = "image refused";
				fail.run();
			}
		};

		java.util.function.Consumer<byte[]> control = throughMain ? d -> mMain.post(() -> onControl.accept(d)) : onControl;
		java.util.function.Consumer<byte[]> identify = throughMain ? d -> mMain.post(() -> onIdentify.accept(d)) : onIdentify;

		boolean subscribed = Boolean.TRUE.equals(link.enableNotify(Bench.SVC_TI_OAD, Bench.CHR_TI_CONTROL, control, BenchLink.OP_TIMEOUT).value)
				&& Boolean.TRUE.equals(link.enableNotify(Bench.SVC_TI_OAD, Bench.CHR_TI_IDENTIFY, identify, BenchLink.OP_TIMEOUT).value)
				&& Boolean.TRUE.equals(link.enableNotify(Bench.SVC_TI_OAD, Bench.CHR_TI_BLOCK, d -> {}, BenchLink.OP_TIMEOUT).value);
		if (!subscribed) {
			r.log("TI OAD subscriptions failed");
			return;
		}

		r.log("TI OAD emulation, %d KB, notifications handled %s", size / 1024, throughMain ? "on the main thread" : "on the KnBle thread");
		link.writeAsync(Bench.SVC_TI_OAD, Bench.CHR_TI_CONTROL, new byte[]{0x01}, true);
		boolean finished = false;
		try {
			finished = done.await(300, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long ms = startNs[0] == 0 ? 0 : BenchLink.msSince(startNs[0]);

		link.disableNotify(Bench.SVC_TI_OAD, Bench.CHR_TI_CONTROL);
		link.disableNotify(Bench.SVC_TI_OAD, Bench.CHR_TI_IDENTIFY);
		link.disableNotify(Bench.SVC_TI_OAD, Bench.CHR_TI_BLOCK);

		sleep(300);
		long[] res = otaResult(link, Bench.SVC_BENCH, Bench.CHR_RESULT);
		boolean crcOk = res != null && res[2] == size && res[3] == Bench.crc32(image);
		if (!finished || failure[0] != null) r.result(name, "size=%d status=%s blocks=%d", size, failure[0] != null ? failure[0] : "TIMEOUT", blocks.get());
		else r.result(name, "size=%d blockSize=%d blocks=%d ms=%d benchMs=%d blocksPerS=%.0f kbps=%.1f crcOk=%b", size, blockSize.get(), blocks.get(), ms,
				res == null ? -1 : res[5], blocks.get() * 1000.0 / Math.max(1, ms), kbps(size, ms), crcOk);
	}

	/**
	 * Burst of sequenced notifications: each one must arrive once, in order, with its own content
	 */
	/**
	 * @param size bytes per notification (at least 4), 0 for the largest one
	 */
	void notifyStream(int count, int size) {
		BenchLink link = ensureConnected();
		if (link == null) return;
		int mtu = link.prepareFastLink(r);
		if (size <= 0) size = splitSize(mtu);

		BitSet seen = new BitSet(count);
		AtomicInteger received = new AtomicInteger(), duplicates = new AtomicInteger(), outOfOrder = new AtomicInteger(), corrupted = new AtomicInteger();
		AtomicLong lastSeq = new AtomicLong(-1), firstNs = new AtomicLong(), lastNs = new AtomicLong();

		BenchLink.Outcome<Boolean> sub = link.enableNotify(Bench.SVC_BENCH, Bench.CHR_STREAM, data -> {
			long now = BenchLink.now();
			firstNs.compareAndSet(0, now);
			lastNs.set(now);
			received.incrementAndGet();
			if (data.length < 4) {
				corrupted.incrementAndGet();
				return;
			}
			int seq = (int) Bench.le32(data, 0);
			for (int i = 4; i < data.length; i++) {
				if (data[i] != (byte) (seq + i)) {
					corrupted.incrementAndGet();
					break;
				}
			}
			if (seq < 0 || seq >= count) {
				corrupted.incrementAndGet();
				return;
			}
			synchronized (seen) {
				if (seen.get(seq)) duplicates.incrementAndGet();
				seen.set(seq);
			}
			if (seq <= lastSeq.get()) outOfOrder.incrementAndGet();
			lastSeq.set(seq);
		}, BenchLink.OP_TIMEOUT);
		if (!sub.ok() || !Boolean.TRUE.equals(sub.value)) {
			r.log("Stream subscription: %s", sub.label());
			return;
		}

		r.log("Stream: %d notifications of %d bytes", count, size);
		BenchLink.Outcome<byte[]> s = link.command(Bench.command(Bench.CMD_STREAM, count, (short) size, (short) 0), 180000);
		sleep(1000);
		link.disableNotify(Bench.SVC_BENCH, Bench.CHR_STREAM);

		int unique;
		synchronized (seen) {
			unique = seen.cardinality();
		}
		long ms = (lastNs.get() - firstNs.get()) / 1_000_000;
		long benchRetries = s.ok() && s.value != null && s.value.length >= 10 ? Bench.le32(s.value, 6) : -1;
		r.result("notify_stream", "count=%d size=%d received=%d unique=%d lost=%d duplicates=%d outOfOrder=%d corrupted=%d ms=%d perS=%.0f kbps=%.1f benchAnswer=%s benchRetries=%d",
				count, size, received.get(), unique, count - unique, duplicates.get(), outOfOrder.get(), corrupted.get(), ms,
				received.get() * 1000.0 / Math.max(1, ms), kbps((long) received.get() * size, ms), s.label(), benchRetries);
	}

	/**
	 * Reboot of the bench in DFU mode (same MAC, other name and services), OTA, then back to normal mode:
	 * are the services rediscovered each time (refresh of the GATT cache at the disconnection)?
	 */
	void dfuReboot() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		String mac = link.device.getMac();

		link.command(Bench.command(Bench.CMD_REBOOT, (byte) 1), 1000);
		boolean down = link.awaitDisconnection(10000);
		r.log("Bench rebooting in DFU mode, disconnection seen: %b", down);

		BleDevice dfu = scanFor(new ScanFilters.Builder().addMacAddress(mac).build(), d -> true, 20000);
		if (dfu == null) {
			r.result("dfu_reboot", "status=not_found_after_reboot");
			return;
		}
		String advertised = Bench.advertisedName(dfu.getScanRecord());
		r.log("After reboot: advertised name %s, system name %s", advertised, dfu.getName());

		BenchLink dfuLink = new BenchLink(dfu);
		BenchLink.Outcome<Void> c = dfuLink.connect(BenchLink.CONNECT_TIMEOUT);
		boolean dfuServices = c.ok() && dfuLink.hasService(Bench.SVC_SLABS_OTA) && dfuLink.hasService(Bench.SVC_DFU_INFO) && !dfuLink.hasService(Bench.SVC_BENCH);
		r.log("DFU connection %s, services: OTA %b, DFU info %b, bench %b", c.label(), dfuLink.hasService(Bench.SVC_SLABS_OTA),
				dfuLink.hasService(Bench.SVC_DFU_INFO), dfuLink.hasService(Bench.SVC_BENCH));

		long[] o = null;
		if (dfuServices) {
			dfuLink.prepareFastLink(r);
			o = silabsOta(dfuLink, 32 * 1024, 0, Bench.SVC_DFU_INFO, Bench.CHR_DFU_RESULT);
		}
		boolean backDown = dfuLink.awaitDisconnection(10000);

		// Right after a link loss the first connection often fails (status 147 then 133 on a Pixel 9a): as the apps
		// do, attempts until one works
		BleDevice normal = scanFor(new ScanFilters.Builder().addMacAddress(mac).build(), d -> true, 20000);
		boolean normalServices = false;
		int attempts = 0;
		if (normal != null) {
			BenchLink normalLink = new BenchLink(normal);
			sLink = normalLink;
			for (attempts = 1; attempts <= 3; attempts++) {
				BenchLink.Outcome<Void> nc = normalLink.connect(BenchLink.CONNECT_TIMEOUT);
				if (nc.ok()) break;
				r.log("Normal mode connection %s after %d ms (attempt %d)", nc.label(), nc.ms, attempts);
				sleep(1000);
			}
			normalServices = normalLink.connected() && normalLink.hasService(Bench.SVC_BENCH);
		}

		r.result("dfu_reboot", "advertisedName=%s systemName=%s dfuServicesOk=%b otaOk=%b rebootSeen=%b normalAttempts=%d normalServicesOk=%b",
				advertised, dfu.getName(), dfuServices, o != null && o[1] == 1, backDown, attempts, normalServices);
	}

	// endregion
}
