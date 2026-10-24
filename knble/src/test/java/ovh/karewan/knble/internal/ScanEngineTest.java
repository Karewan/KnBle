package ovh.karewan.knble.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothLeScanner;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Scan of the app over the system scans: silent restarts, the 5 starts in 30 s limit, duplicate filtering, merge of the
 * reports, lost devices, Bluetooth off and on, failures of the system. Against the Robolectric scanner shadow, the
 * results given by the test like the system would.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ScanEngineTest {
	private static final int MANUFACTURER = 0x6957;

	private BluetoothAdapter mAdapter;
	private ShadowBluetoothLeScanner mScanner;
	private final List<String> mEvents = new CopyOnWriteArrayList<>();
	private final List<BleDevice> mReports = new CopyOnWriteArrayList<>();
	private final List<List<BleDevice>> mFinished = new CopyOnWriteArrayList<>();

	private final BleScanCallback mCallback = new BleScanCallback() {
		@Override
		public void onScanStarted() {
			mEvents.add("started");
		}

		@Override
		public void onScanFailed(int error) {
			mEvents.add("failed " + error);
		}

		@Override
		public void onScanResult(@NonNull BleDevice bleDevice) {
			mEvents.add("result " + bleDevice.getMac());
			mReports.add(bleDevice);
		}

		@Override
		public void onDeviceUpdated(@NonNull BleDevice bleDevice) {
			mReports.add(bleDevice);
		}

		@Override
		public void onDeviceLost(@NonNull BleDevice bleDevice) {
			mEvents.add("lost " + bleDevice.getMac());
		}

		@Override
		public void onScanFinished(@NonNull List<BleDevice> scanResult) {
			mEvents.add("finished");
			mFinished.add(scanResult);
		}
	};

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		Context context = ApplicationProvider.getApplicationContext();
		shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		mAdapter = context.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(mAdapter).setEnabled(true);
		mScanner = shadowOf(mAdapter.getBluetoothLeScanner());

		assertTrue(KnBle.init(context));
	}

	@After
	public void tearDown() {
		KnBle.stopScan();
		advance(1000);
		BleEngine.resetForTests();
	}

	// region Helpers

	/**
	 * Advances the clock step by step: the main looper (callbacks of the app) and the thread of the engine run meanwhile
	 */
	private static void advance(long ms) {
		for (long done = 0; done < ms; done += 100) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
			try {
				Thread.sleep(2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static ScanEngine engine() {
		BleEngine e = BleEngine.get();
		assertNotNull(e);
		return e.scan();
	}

	private static byte[] record(String name, Integer manufacturer, int counter) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(2);
		out.write(0x01);
		out.write(0x06);
		if (manufacturer != null) {
			out.write(5);
			out.write(0xFF);
			out.write(manufacturer & 0xFF);
			out.write(manufacturer >> 8);
			out.write(counter);
			out.write(0x42);
		}
		if (name != null) {
			byte[] n = name.getBytes(StandardCharsets.UTF_8);
			out.write(n.length + 1);
			out.write(0x09);
			out.write(n, 0, n.length);
		}
		return out.toByteArray();
	}

	/**
	 * A report given by the system to every running scan, on the main thread like Android
	 */
	private void report(String mac, byte[] raw, int rssi) throws Exception {
		BluetoothDevice device = mAdapter.getRemoteDevice(mac);
		android.bluetooth.le.ScanRecord record = (android.bluetooth.le.ScanRecord) android.bluetooth.le.ScanRecord.class
				.getMethod("parseFromBytes", byte[].class).invoke(null, (Object) raw);
		ScanResult result = new ScanResult(device, 0x1B, 1, 0, 0xFF, 127, rssi, 0, record, SystemClock.elapsedRealtimeNanos());
		for (ScanCallback cb : new ArrayList<>(mScanner.getScanCallbacks())) cb.onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
		advance(100);
	}

	private void failSystemScan(int error) {
		for (ScanCallback cb : new ArrayList<>(mScanner.getScanCallbacks())) cb.onScanFailed(error);
		advance(100);
	}

	private void startScan(ScanSettings settings) throws InterruptedException {
		KnBle.setScanSettings(settings);
		KnBle.startScan(mCallback);
		// The system scan registered by the thread of the engine
		long deadline = System.currentTimeMillis() + 3000;
		while (mScanner.getScanCallbacks().isEmpty() && System.currentTimeMillis() < deadline) {
			advance(100);
			Thread.sleep(10);
		}
		assertEquals("system scan not started", 1, mScanner.getScanCallbacks().size());
	}

	private void bluetoothState(int state) {
		shadowOf(mAdapter).setEnabled(state == BluetoothAdapter.STATE_ON);
		Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
		intent.putExtra(BluetoothAdapter.EXTRA_STATE, state);
		ApplicationProvider.getApplicationContext().sendBroadcast(intent);
		advance(100);
	}

	private long count(String prefix) {
		return mEvents.stream().filter(e -> e.startsWith(prefix)).count();
	}

	/**
	 * Hardware match tracking and batched results, which the adapter of Robolectric does not have
	 */
	@Implements(BluetoothAdapter.class)
	public static class OffloadingAdapter extends ShadowBluetoothAdapter {
		@Implementation
		protected boolean isOffloadedFilteringSupported() {
			return true;
		}

		@Implementation
		protected boolean isOffloadedScanBatchingSupported() {
			return true;
		}
	}

	private ScanResult result(String mac, byte[] raw, int rssi, long nanos) throws Exception {
		BluetoothDevice device = mAdapter.getRemoteDevice(mac);
		android.bluetooth.le.ScanRecord record = (android.bluetooth.le.ScanRecord) android.bluetooth.le.ScanRecord.class
				.getMethod("parseFromBytes", byte[].class).invoke(null, (Object) raw);
		return new ScanResult(device, 0x1B, 1, 0, 0xFF, 127, rssi, 0, record, nanos);
	}

	/**
	 * The only system scan running
	 */
	private ScanCallback systemScan() {
		List<ScanCallback> callbacks = new ArrayList<>(mScanner.getScanCallbacks());
		assertEquals(1, callbacks.size());
		return callbacks.get(0);
	}

	/**
	 * Like a phone filtering the duplicates: each device reported once per system scan, during ms
	 */
	private void reportOncePerSystemScan(long ms, String... macs) throws Exception {
		ScanCallback last = null;
		for (long done = 0; done < ms; done += 100) {
			List<ScanCallback> callbacks = new ArrayList<>(mScanner.getScanCallbacks());
			ScanCallback current = callbacks.isEmpty() ? null : callbacks.get(0);
			if (current != null && current != last) {
				last = current;
				for (String mac : macs) {
					current.onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
							result(mac, record("A", MANUFACTURER, 0), -60, SystemClock.elapsedRealtimeNanos()));
				}
			}
			advance(100);
		}
	}

	/**
	 * Runs r while the thread of the engine waits: what r queues is handled in one go
	 */
	private static void whileEngineWaits(Runnable r) throws InterruptedException {
		BleEngine e = BleEngine.get();
		assertNotNull(e);
		CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
		e.handler().post(() -> {
			blocked.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(blocked.await(5, TimeUnit.SECONDS));
		r.run();
		release.countDown();
		advance(200);
	}

	/**
	 * The manifest of the app declares BLUETOOTH_SCAN with these flags
	 */
	private static void declareScanPermission(int flags) {
		Context context = ApplicationProvider.getApplicationContext();
		PackageInfo info = shadowOf(context.getPackageManager()).getInternalMutablePackageInfo(context.getPackageName());
		info.requestedPermissions = new String[]{Manifest.permission.BLUETOOTH_SCAN};
		info.requestedPermissionsFlags = new int[]{flags};
	}

	// endregion

	@Test
	public void restartsAreSilentForTheApp() throws Exception {
		startScan(new ScanSettings.Builder().setAutoRestartScanAfter(2000).build());
		advance(9000);
		assertTrue("the system scan was not started again", engine().recentStarts() >= 3);
		assertEquals(List.of("started"), mEvents);
		assertEquals(1, mScanner.getScanCallbacks().size());
	}

	@Test
	public void neverMoreThanFiveStartsIn30Seconds() throws Exception {
		// The app asks for a restart every 500 ms: Android would stop delivering after the 5th start
		startScan(new ScanSettings.Builder().setAutoRestartScanAfter(500).build());
		for (int i = 0; i < 90; i++) {
			advance(500);
			assertTrue("more than 5 starts in 30 s", engine().recentStarts() <= 5);
		}
		assertEquals(1, count("started"));
	}

	@Test
	public void startScanCalledInALoopStaysUnderTheLimit() {
		KnBle.setScanSettings(new ScanSettings.Builder().build());
		for (int i = 0; i < 12; i++) {
			KnBle.startScan(mCallback);
			advance(600);
			assertTrue(engine().recentStarts() <= 5);
		}
		advance(31000);
		assertEquals("the last scan never started", 1, mScanner.getScanCallbacks().size());
	}

	@Test
	public void reportWithoutTheScanResponseKeepsTheNameAndTheData() throws Exception {
		startScan(new ScanSettings.Builder().build());
		String mac = "AA:BB:CC:00:00:01";
		report(mac, record("PTIBLUE5", MANUFACTURER, 1), -50);
		// Advertising only (no scan response): no name
		report(mac, record(null, MANUFACTURER, 2), -51);
		// Scan response only: no manufacturer data
		report(mac, record("PTIBLUE5", null, 0), -52);

		assertEquals(3, mReports.size());
		BleDevice second = mReports.get(1), third = mReports.get(2);
		assertEquals("PTIBLUE5", second.getName());
		assertEquals(2, second.getScanRecord().getManufacturerData()[0]);
		assertEquals(-51, second.getRssi());
		assertEquals(Integer.valueOf(MANUFACTURER), third.getScanRecord().getManufacturerId());
		assertEquals("the latest manufacturer data is kept", 2, third.getScanRecord().getManufacturerData()[0]);
		assertEquals(-52, third.getRssi());
	}

	@Test
	public void newAdvertisedNameReplacesTheOldOneAtOnce() throws Exception {
		startScan(new ScanSettings.Builder().build());
		String mac = "AA:BB:CC:00:00:02";
		report(mac, record("PTIBLUE5", MANUFACTURER, 1), -50);
		// Rebooted in DFU mode: same MAC, other name
		report(mac, record("OTA", MANUFACTURER, 1), -50);
		assertEquals("events " + mEvents + ", callbacks " + mScanner.getScanCallbacks().size(), 2, mReports.size());
		assertEquals("OTA", mReports.get(1).getName());
	}

	@Test
	public void minimumRssiAndServiceUuidFilters() throws Exception {
		java.util.UUID service = java.util.UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
		KnBle.setScanFilter(new ovh.karewan.knble.scan.ScanFilters.Builder().setUseAndroid6Filters(false).addServiceUuid(service).setMinRssi(-70).build());
		startScan(new ScanSettings.Builder().build());
		byte[] withService = {2, 0x01, 0x06, 3, 0x03, 0x0D, 0x18};
		report("AA:BB:CC:00:00:61", withService, -60);
		// Too far
		report("AA:BB:CC:00:00:62", withService, -80);
		// Without the service
		report("AA:BB:CC:00:00:63", record("X", MANUFACTURER, 0), -50);
		assertEquals(List.of("started", "result AA:BB:CC:00:00:61"), mEvents);
	}

	@Test
	public void phoneFilteringDuplicatesGetsARestartEvery10Seconds() throws Exception {
		startScan(new ScanSettings.Builder().build());
		// Each device reported once only, like on the phones that filter the duplicates (3 devices: judged on a few)
		report("AA:BB:CC:00:00:11", record("A", MANUFACTURER, 0), -60);
		report("AA:BB:CC:00:00:12", record("B", MANUFACTURER, 0), -60);
		report("AA:BB:CC:00:00:13", record("C", MANUFACTURER, 0), -60);
		advance(13000);
		assertEquals(Boolean.TRUE, engine().filtersDuplicates());
		int starts = engine().recentStarts();
		advance(11000);
		assertTrue("no restart every 10 s", engine().recentStarts() > starts);
		assertEquals(1, count("started"));
	}

	@Test
	public void phoneReportingDuplicatesKeepsOneContinuousScan() throws Exception {
		startScan(new ScanSettings.Builder().build());
		for (int i = 0; i < 13; i++) {
			report("AA:BB:CC:00:00:21", record("A", MANUFACTURER, i), -60);
			advance(900);
		}
		assertEquals(Boolean.FALSE, engine().filtersDuplicates());
		assertEquals(1, engine().recentStarts());
	}

	@Test
	public void silentDeviceIsLostThenFoundAgain() throws Exception {
		startScan(new ScanSettings.Builder().build());
		String mac = "AA:BB:CC:00:00:31";
		for (int i = 0; i < 5; i++) {
			report(mac, record("A", MANUFACTURER, i), -60);
			advance(900);
		}
		assertEquals(0, count("lost"));
		// Reports every second: lost after 10 s (the minimum) without one
		advance(11000);
		assertEquals(List.of("started", "result " + mac, "lost " + mac), mEvents);

		report(mac, record("A", MANUFACTURER, 9), -60);
		assertEquals("found again: a new result", 2, count("result"));
	}

	@Test
	public void gapsBetweenSystemScansDoNotMakeADeviceLost() throws Exception {
		// Restart every 3 s: 500 ms without a system scan each time
		startScan(new ScanSettings.Builder().setAutoRestartScanAfter(3000).build());
		String mac = "AA:BB:CC:00:00:41";
		for (int i = 0; i < 15; i++) {
			report(mac, record("A", MANUFACTURER, i), -60);
			advance(1900);
		}
		assertEquals(0, count("lost"));
	}

	@Test
	public void bluetoothOffSuspendsTheScanAndOnResumesIt() throws Exception {
		startScan(new ScanSettings.Builder().build());
		report("AA:BB:CC:00:00:51", record("A", MANUFACTURER, 0), -60);
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		bluetoothState(BluetoothAdapter.STATE_OFF);
		assertTrue(KnBle.isScanning());
		int starts = engine().recentStarts();

		// Off for 20 s: no loss (nothing was scanned), no end of scan
		advance(20000);
		bluetoothState(BluetoothAdapter.STATE_ON);
		advance(1500);
		assertEquals(starts + 1, engine().recentStarts());
		assertEquals(List.of("started", "result AA:BB:CC:00:00:51"), mEvents);
	}

	@Test
	public void unsupportedFeatureFallsBackBeforeFailing() throws Exception {
		startScan(new ScanSettings.Builder().build());
		failSystemScan(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
		advance(1000);
		assertEquals("failed after the first refusal", 0, count("failed"));
		assertEquals(2, engine().recentStarts());

		// Nothing left to drop: the app is told
		for (int i = 0; i < 6 && count("failed") == 0; i++) {
			failSystemScan(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
			advance(1000);
		}
		assertEquals(List.of("started", "failed " + BleScanCallback.SCAN_FEATURE_UNSUPPORTED, "finished"), mEvents);
	}

	@Test
	public void registrationFailureIsRetriedThenReported() throws Exception {
		startScan(new ScanSettings.Builder().build());
		for (int i = 0; i < 3; i++) {
			failSystemScan(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
			advance(9000);
			assertEquals(0, count("failed"));
		}
		failSystemScan(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
		assertEquals(List.of("started", "failed " + BleScanCallback.SCANNER_UNAVAILABLE, "finished"), mEvents);
		assertFalse("still scanning", KnBle.isScanning());
	}

	// region Duplicate filtering and losses

	@Test
	public void phoneFilteringDuplicatesDoesNotLoseTheDevicesBetweenRestarts() throws Exception {
		startScan(new ScanSettings.Builder().build());
		// Heard once per system scan: lost after 10 s of their single report before, found again after the restart
		reportOncePerSystemScan(60000, "AA:BB:CC:00:01:01", "AA:BB:CC:00:01:02", "AA:BB:CC:00:01:03");
		assertEquals(Boolean.TRUE, engine().filtersDuplicates());
		assertEquals(0, count("lost"));
		assertEquals(3, count("result"));
	}

	@Test
	public void fewDevicesOrSparseReportsDoNotJudgeTheFiltering() throws Exception {
		// A single device heard once in 12 s: maybe a sensor advertising every 15 s
		startScan(new ScanSettings.Builder().build());
		report("AA:BB:CC:00:02:01", record("A", MANUFACTURER, 0), -60);
		advance(13000);
		assertEquals(null, engine().filtersDuplicates());
		KnBle.stopScan();
		advance(1000);

		// Low power mode: a scan window every 5 s, few reports
		startScan(new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build());
		for (int i = 1; i <= 3; i++) report("AA:BB:CC:00:02:1" + i, record("A", MANUFACTURER, 0), -60);
		advance(13000);
		assertEquals(null, engine().filtersDuplicates());
		KnBle.stopScan();
		advance(1000);

		// Hardware filtered: maybe a few sensors advertising every 6 s
		KnBle.setScanFilter(new ScanFilters.Builder().addManufacturerId(MANUFACTURER).build());
		startScan(new ScanSettings.Builder().build());
		for (int i = 1; i <= 3; i++) report("AA:BB:CC:00:02:2" + i, record("A", MANUFACTURER, 0), -60);
		advance(13000);
		assertEquals(null, engine().filtersDuplicates());
	}

	@Test
	public void filteringVerdictIsJudgedAgainAtEachSystemScan() throws Exception {
		startScan(new ScanSettings.Builder().build());
		for (int i = 1; i <= 3; i++) report("AA:BB:CC:00:03:0" + i, record("A", MANUFACTURER, 0), -60);
		advance(13000);
		assertEquals(Boolean.TRUE, engine().filtersDuplicates());

		// A device reported 3 times in one system scan: the phone does not filter, the restarts every 10 s stop
		for (int i = 0; i < 3; i++) report("AA:BB:CC:00:03:01", record("A", MANUFACTURER, i), -60);
		assertEquals(Boolean.FALSE, engine().filtersDuplicates());
		ScanCallback scan = systemScan();
		advance(25000);
		assertEquals("restarted after the verdict changed", scan, systemScan());
	}

	@Test
	public void deviceHeardOnceIsNotLostAfter10Seconds() throws Exception {
		startScan(new ScanSettings.Builder().build());
		// The phone reports the duplicates (one device reported 3 times), the other device advertises rarely
		for (int i = 0; i < 3; i++) report("AA:BB:CC:00:04:01", record("A", MANUFACTURER, i), -60);
		report("AA:BB:CC:00:04:02", record("B", MANUFACTURER, 0), -60);
		assertEquals(Boolean.FALSE, engine().filtersDuplicates());
		advance(20000);
		assertFalse("heard once, lost after 10 s", mEvents.contains("lost AA:BB:CC:00:04:02"));
		advance(12000);
		assertTrue(mEvents.contains("lost AA:BB:CC:00:04:02"));
	}

	@Test
	public void deviceBelowTheMinimumRssiGetsLost() throws Exception {
		KnBle.setScanFilter(new ScanFilters.Builder().setMinRssi(-70).build());
		startScan(new ScanSettings.Builder().build());
		String mac = "AA:BB:CC:00:05:01";
		for (int i = 0; i < 5; i++) {
			report(mac, record("A", MANUFACTURER, i), -60);
			advance(900);
		}
		// Still heard, but too far: neither updated nor kept alive
		for (int i = 0; i < 15; i++) {
			report(mac, record("A", MANUFACTURER, i), -90);
			advance(900);
		}
		assertEquals(List.of("started", "result " + mac, "lost " + mac), mEvents);
		assertEquals(5, mReports.size());
	}

	@Test
	public void onlyMatchingDevicesAreFollowedAndLostOnesAreForgotten() throws Exception {
		KnBle.setScanFilter(new ScanFilters.Builder().setUseAndroid6Filters(false).addManufacturerId(MANUFACTURER).build());
		startScan(new ScanSettings.Builder().build());
		for (int i = 0; i < 20; i++) report(String.format("AA:BB:CC:00:06:%02X", i), record("X", 0x1234, 0), -60);
		assertEquals(0, engine().trackedDevices());

		String mac = "AA:BB:CC:00:06:FF";
		for (int i = 0; i < 5; i++) {
			report(mac, record("A", MANUFACTURER, i), -60);
			advance(900);
		}
		assertEquals(1, engine().trackedDevices());
		advance(11000);
		assertEquals(1, count("lost"));
		assertEquals(1, KnBle.getScannedDevices().size());
		// Lost for 5 min: forgotten
		advance(5 * 60000);
		assertEquals(0, engine().trackedDevices());
		assertEquals(0, KnBle.getScannedDevices().size());
	}

	@Test
	public void systemNameNeedsBluetoothConnect() throws Exception {
		String mac = "AA:BB:CC:00:07:01";
		shadowOf(mAdapter.getRemoteDevice(mac)).setName("SYS");
		KnBle.setScanFilter(new ScanFilters.Builder().setUseAndroid6Filters(false).addDeviceName("SYS").build());
		startScan(new ScanSettings.Builder().build());
		// No advertised name: the one known by the system matches
		report(mac, record(null, MANUFACTURER, 0), -60);
		assertEquals(1, mReports.size());
		assertEquals("SYS", mReports.get(0).getName());
		KnBle.stopScan();
		advance(1000);

		shadowOf((Application) ApplicationProvider.getApplicationContext()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT);
		mReports.clear();
		startScan(new ScanSettings.Builder().build());
		report(mac, record(null, MANUFACTURER, 0), -60);
		assertEquals("getName() would throw without BLUETOOTH_CONNECT", 0, mReports.size());
	}

	@Test
	public void lowerCaseMacFiltersMatch() throws Exception {
		KnBle.setScanFilter(new ScanFilters.Builder().setUseAndroid6Filters(false).addMacAddress("aa:bb:cc:00:08:01")
				.addMacAddressStartsWith("aa:bb:cc:00:09").build());
		startScan(new ScanSettings.Builder().build());
		report("AA:BB:CC:00:08:01", record("A", MANUFACTURER, 0), -60);
		report("AA:BB:CC:00:09:01", record("B", MANUFACTURER, 0), -60);
		report("AA:BB:CC:00:0A:01", record("C", MANUFACTURER, 0), -60);
		assertEquals(List.of("started", "result AA:BB:CC:00:08:01", "result AA:BB:CC:00:09:01"), mEvents);
	}

	// endregion

	// region Batched results and hardware match tracking

	@Test
	@Config(shadows = OffloadingAdapter.class)
	public void batchedResultsAreNeitherLostNorRestartedBetweenBatches() throws Exception {
		startScan(new ScanSettings.Builder().setReportDelay(60000).build());
		ScanCallback scan = systemScan();
		String mac = "AA:BB:CC:00:10:01";
		for (int batch = 0; batch < 3; batch++) {
			advance(60000);
			List<ScanResult> results = new ArrayList<>();
			long now = SystemClock.elapsedRealtimeNanos();
			for (int i = 5; i > 0; i--) results.add(result(mac, record("A", MANUFACTURER, i), -60, now - i * 1_000_000_000L));
			scan.onBatchScanResults(results);
			advance(100);
		}
		assertEquals(List.of("started", "result " + mac), mEvents);
		assertEquals("the system scan was started again between two batches", scan, systemScan());
	}

	@Test
	@Config(shadows = OffloadingAdapter.class)
	public void hardwareMatchTrackingLeavesTheLossesToTheController() throws Exception {
		KnBle.setScanFilter(new ScanFilters.Builder().addManufacturerId(MANUFACTURER).build());
		startScan(new ScanSettings.Builder()
				.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST).build());
		ScanCallback scan = systemScan();
		assertEquals(ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST,
				mScanner.getActiveScans().get(0).scanSettings().getCallbackType());
		String mac = "AA:BB:CC:00:11:01";
		ScanResult first = result(mac, record("A", MANUFACTURER, 0), -60, SystemClock.elapsedRealtimeNanos());
		scan.onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH, first);
		// First match only, then silence: neither lost by KnBle nor started again (the controller would start over)
		advance(70000);
		assertEquals(List.of("started", "result " + mac), mEvents);
		assertEquals(scan, systemScan());

		// Lost then found again, handled in one go: in this order
		ScanResult again = result(mac, record("A", MANUFACTURER, 1), -60, SystemClock.elapsedRealtimeNanos());
		whileEngineWaits(() -> {
			scan.onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST, first);
			scan.onScanResult(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH, again);
		});
		assertEquals(List.of("started", "result " + mac, "lost " + mac, "result " + mac), mEvents);
	}

	@Test
	@Config(shadows = OffloadingAdapter.class)
	public void outOfHardwareResourcesDropsTheMatchTrackingFirst() throws Exception {
		KnBle.setScanFilter(new ScanFilters.Builder().addManufacturerId(MANUFACTURER).build());
		startScan(new ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH).build());
		failSystemScan(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES);
		advance(1000);
		// The shadow keeps the failed scan: the new one is the last
		List<ShadowBluetoothLeScanner.ScanParams> scans = mScanner.getActiveScans();
		assertEquals(2, scans.size());
		ShadowBluetoothLeScanner.ScanParams params = scans.get(1);
		assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, params.scanSettings().getCallbackType());
		assertEquals("the filters stay in the controller", 1, params.scanFilters().size());
		assertEquals(MANUFACTURER, params.scanFilters().get(0).getManufacturerId());
		assertEquals(0, count("failed"));
	}

	// endregion

	// region State of the scan of the app

	@Test
	public void isScanningChangesAtOnce() {
		KnBle.setScanSettings(new ScanSettings.Builder().build());
		KnBle.startScan(mCallback);
		assertTrue(KnBle.isScanning());
		KnBle.stopScan();
		assertFalse(KnBle.isScanning());
		// A double tap
		KnBle.startScan(mCallback);
		KnBle.stopScan();
		KnBle.startScan(mCallback);
		assertTrue(KnBle.isScanning());
		advance(2000);
		assertTrue(KnBle.isScanning());
		assertEquals(1, mScanner.getScanCallbacks().size());
	}

	@Test
	public void failedStartIsNotScanning() {
		shadowOf((Application) ApplicationProvider.getApplicationContext()).denyPermissions(Manifest.permission.BLUETOOTH_SCAN);
		KnBle.startScan(mCallback);
		assertTrue(KnBle.isScanning());
		advance(500);
		assertEquals(List.of("failed " + BleScanCallback.PERMISSION_DENIED), mEvents);
		assertFalse(KnBle.isScanning());
		assertTrue(mScanner.getScanCallbacks().isEmpty());
	}

	@Test
	public void resetScanGivesTheDevicesToOnScanFinished() throws Exception {
		startScan(new ScanSettings.Builder().build());
		report("AA:BB:CC:00:12:01", record("A", MANUFACTURER, 0), -60);
		report("AA:BB:CC:00:12:02", record("B", MANUFACTURER, 0), -60);
		KnBle.resetScan(false, false);
		assertFalse(KnBle.isScanning());
		assertTrue(KnBle.getScannedDevices().isEmpty());
		advance(500);
		assertEquals(1, mFinished.size());
		assertEquals(2, mFinished.get(0).size());
	}

	@Test
	public void timeoutIsIgnoredWithAnAutomaticRestart() throws Exception {
		// As in 3.x: the restarts asked by the app make a scan until stopScan
		startScan(new ScanSettings.Builder().setScanTimeout(3000).setAutoRestartScanAfter(2000).build());
		advance(6000);
		assertTrue(KnBle.isScanning());
		assertEquals(0, count("finished"));
		KnBle.stopScan();
		advance(1000);

		startScan(new ScanSettings.Builder().setScanTimeout(3000).build());
		advance(4000);
		assertFalse(KnBle.isScanning());
		assertEquals(2, count("finished"));
	}

	// endregion

	// region Requirements and limits of Android

	@Test
	public void requirementsOfEachAndroidVersion() {
		Context c = ApplicationProvider.getApplicationContext();
		Application app = (Application) c;
		shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
		assertEquals(BleScanCallback.PERMISSION_DENIED, ScanEngine.requirements(c, Build.VERSION_CODES.P, false));
		shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
		assertEquals(BleScanCallback.NO_ERROR, ScanEngine.requirements(c, Build.VERSION_CODES.P, false));
		// Android 10 and 11: the fine location for an app targeting them
		assertEquals(BleScanCallback.PERMISSION_DENIED, ScanEngine.requirements(c, Build.VERSION_CODES.R, false));
		shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
		assertEquals(BleScanCallback.NO_ERROR, ScanEngine.requirements(c, Build.VERSION_CODES.R, false));
		shadowOf(c.getSystemService(LocationManager.class)).setLocationEnabled(false);
		assertEquals(BleScanCallback.LOCATION_DISABLED, ScanEngine.requirements(c, Build.VERSION_CODES.R, false));
		// Android 12+: BLUETOOTH_SCAN, enough when never used for the location
		assertEquals(BleScanCallback.NO_ERROR, ScanEngine.requirements(c, Build.VERSION_CODES.S, true));
		assertEquals(BleScanCallback.LOCATION_DISABLED, ScanEngine.requirements(c, Build.VERSION_CODES.S, false));
		shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN);
		assertEquals(BleScanCallback.PERMISSION_DENIED, ScanEngine.requirements(c, Build.VERSION_CODES.S, true));
	}

	@Test
	public void manifestWithoutNeverForLocationNeedsTheLocation() throws Exception {
		declareScanPermission(PackageInfo.REQUESTED_PERMISSION_GRANTED);
		KnBle.startScan(mCallback);
		advance(500);
		assertEquals(List.of("failed " + BleScanCallback.PERMISSION_DENIED), mEvents);

		shadowOf((Application) ApplicationProvider.getApplicationContext()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
		mEvents.clear();
		startScan(new ScanSettings.Builder().build());
		assertEquals(List.of("started"), mEvents);
	}

	@Test
	public void manifestWithNeverForLocationNeedsNoLocation() throws Exception {
		declareScanPermission(PackageInfo.REQUESTED_PERMISSION_GRANTED | PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION);
		shadowOf(((Context) ApplicationProvider.getApplicationContext()).getSystemService(LocationManager.class)).setLocationEnabled(false);
		startScan(new ScanSettings.Builder().build());
		assertEquals(List.of("started"), mEvents);
	}

	@Test
	public void silenceRestarts() {
		// Android 12L and below ignore a start past their limit without an error: no result at all restarts too
		assertEquals(30000, ScanEngine.silenceRestartMs(Build.VERSION_CODES.S_V2, false, 0));
		assertEquals(0, ScanEngine.silenceRestartMs(Build.VERSION_CODES.TIRAMISU, false, 0));
		assertEquals(30000, ScanEngine.silenceRestartMs(Build.VERSION_CODES.TIRAMISU, true, 0));
		// Batched results: 2 report delays
		assertEquals(150000, ScanEngine.silenceRestartMs(Build.VERSION_CODES.TIRAMISU, true, 60000));
	}

	@Test
	public void longScanIsStartedAgainBeforeTheLimitOfAndroid() throws Exception {
		assertEquals(25 * 60000L, ScanEngine.longScanRestartMs(Build.VERSION_CODES.TIRAMISU));
		assertEquals(8 * 60000L, ScanEngine.longScanRestartMs(Build.VERSION_CODES.UPSIDE_DOWN_CAKE));

		// Android 14+ makes a scan opportunistic or low power after 10 min
		startScan(new ScanSettings.Builder().build());
		ScanCallback scan = systemScan();
		for (int i = 0; i < 47; i++) {
			report("AA:BB:CC:00:13:01", record("A", MANUFACTURER, i), -60);
			advance(9900);
		}
		assertEquals("started again before 8 min", scan, systemScan());
		for (int i = 0; i < 3; i++) {
			report("AA:BB:CC:00:13:01", record("A", MANUFACTURER, i), -60);
			advance(9900);
		}
		assertFalse("not started again after 8 min", scan.equals(systemScan()));
		assertEquals(List.of("started", "result AA:BB:CC:00:13:01"), mEvents);
	}

	@Test
	public void scanningTooFrequentlyIsRetriedLater() throws Exception {
		// Android 13+ tells it (other scans of the app count too)
		startScan(new ScanSettings.Builder().build());
		failSystemScan(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY);
		// The shadow keeps the failed scan: a new one comes 30 s later
		advance(29000);
		assertEquals(1, mScanner.getScanCallbacks().size());
		advance(2000);
		assertEquals(2, mScanner.getScanCallbacks().size());
		assertEquals(List.of("started"), mEvents);
	}

	// endregion

	// region Background scan

	@Test
	public void backgroundScanKeepsTheHardwareFiltersAndIsRegisteredOnce() throws Exception {
		PendingIntent pi = PendingIntent.getBroadcast(ApplicationProvider.getApplicationContext(), 0, new Intent("scan"), PendingIntent.FLAG_MUTABLE);
		// A name prefix and a manufacturer ID: the manufacturer ID goes to the system, the prefix narrows down
		ScanFilters filters = new ScanFilters.Builder().addDeviceNameStartsWith("SENSOR").addManufacturerId(MANUFACTURER).build();
		KnBle.startBackgroundScan(pi, filters, new ScanSettings.Builder().build(), null);
		advance(200);
		KnBle.startBackgroundScan(pi, filters, new ScanSettings.Builder().build(), null);
		advance(200);
		List<ShadowBluetoothLeScanner.ScanParams> scans = new ArrayList<>();
		for (ShadowBluetoothLeScanner.ScanParams p : mScanner.getActiveScans()) if (pi.equals(p.pendingIntent())) scans.add(p);
		assertEquals("registered twice", 1, scans.size());
		assertEquals(1, scans.get(0).scanFilters().size());
		assertEquals(MANUFACTURER, scans.get(0).scanFilters().get(0).getManufacturerId());

		Intent intent = new Intent();
		ArrayList<ScanResult> results = new ArrayList<>();
		long now = SystemClock.elapsedRealtimeNanos();
		results.add(result("AA:BB:CC:00:14:01", record("SENSOR-1", MANUFACTURER, 0), -60, now));
		results.add(result("AA:BB:CC:00:14:02", record("OTHER", MANUFACTURER, 0), -60, now));
		intent.putParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, results);
		List<BleDevice> devices = KnBle.parseBackgroundScan(intent, filters);
		assertEquals(1, devices.size());
		assertEquals("SENSOR-1", devices.get(0).getName());
		KnBle.stopBackgroundScan(pi);
		advance(200);
	}

	// endregion
}
