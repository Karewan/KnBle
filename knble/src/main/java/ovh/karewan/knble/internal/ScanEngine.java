package ovh.karewan.knble.internal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.Utils;
import ovh.karewan.knble.interfaces.BleBackgroundScanCallback;
import ovh.karewan.knble.interfaces.BleScanCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.struct.ScanRecord;

/**
 * The scan, confined to the thread of the engine. Two levels: the scan of the app (startScan to stopScan or the
 * timeout, what the app sees) and the scans of the system under it, started again silently when the phone needs it
 * (duplicate filtering, silence, the time limit of Android, a failure, Bluetooth back on), never more than 5 starts in
 * 30 s (the limit of Android, past which it delivers nothing). Android delivers the results on the main thread: there
 * they are only queued. Parsing, filters and the binder calls run here; the app gets its callbacks through the
 * Dispatcher, in order, the updates of a device coalesced while the app lags.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@SuppressLint("MissingPermission")
public final class ScanEngine {
	private static final long RESTART_DELAY_MS = 500;
	private static final long ENABLE_BLUETOOTH_DELAY_MS = 5000;
	// Android ignores the 6th start of an app within 30 s: the starts are spaced to stay under it
	private static final int MAX_STARTS = 5;
	private static final long STARTS_WINDOW_MS = 30000, STARTS_MARGIN_MS = 500;
	// Duplicate filtering by the phone: after 12 s of a system scan, several devices seen but none reported 3 times (2
	// reports can be the advertising and the scan response). The scan is then started again every 10 s, the only way to
	// get news. Judged again at each system scan: one device reported 3 times proves the phone does not filter.
	private static final long DUPLICATE_CHECK_MS = 12000;
	private static final int DUPLICATE_MIN_REPORTS = 3, DUPLICATE_MIN_DEVICES = 3, DUPLICATE_MAX_DEVICES = 1000;
	private static final long FILTERING_RESTART_MS = 10000;
	// Backup restart: no result for 30 s (plus 2 report delays of the batched results)
	private static final long SILENCE_RESTART_MS = 30000;
	// Android makes a long scan opportunistic (no filter) or low power (filtered) after 30 min, 10 min from Android 14:
	// started again before
	private static final long LONG_SCAN_RESTART_MS = 25 * 60000L, LONG_SCAN_RESTART_14_MS = 8 * 60000L;
	// Registration or internal failure of the system: started again after 2, 4 then 8 s before telling the app
	private static final int FAILURE_RETRIES = 3;
	private static final long FAILURE_RETRY_MS = 2000;
	// Bluetooth back on: the scan goes on a bit later (the adapter finishes starting)
	private static final long BLUETOOTH_ON_DELAY_MS = 1000;
	// A device is lost after 5 of its usual intervals without a report, between 10 s and 2 min; 30 s while its interval
	// is not known (heard once). On a phone that may filter the duplicates its next report only comes with the next
	// system scan: at least 2 restarts (45 s while the filtering is not known: the silence restart comes after 30 s).
	private static final long LOST_MIN_MS = 10000, LOST_MAX_MS = 120000, LOST_UNKNOWN_MS = 30000;
	private static final long LOST_UNKNOWN_FILTERING_MS = 45000, LOST_MARGIN_MS = 5000;
	private static final int LOST_INTERVALS = 5;
	// A device lost and silent for 5 min is forgotten: a long scan in a crowd keeps only the devices around
	private static final long FORGET_AFTER_MS = 5 * 60000L;
	// Names read from the system for the devices advertising none, cleared past this size
	private static final int MAX_SYSTEM_NAMES = 1000;
	private static final long TICK_MS = 1000;
	// Tokens of the delayed messages: the scan of the app (timeout, tick) and the system scan (start, restarts)
	private static final Object SCAN = new Object(), SYSTEM = new Object();

	@NonNull
	private final BleEngine mEngine;
	@NonNull
	private final Dispatcher mDispatcher;

	// Read by any thread
	private final ConcurrentHashMap<String, BleDevice> mDevices = new ConcurrentHashMap<>();
	// Updates waiting for the app, by MAC address
	private final ConcurrentHashMap<String, Pending> mPending = new ConcurrentHashMap<>();
	// Names known by the system, read once per device when it advertises none
	private final ConcurrentHashMap<String, String> mSystemNames = new ConcurrentHashMap<>();
	// (number of startScan and stopScan calls << 1) | 1 while the scan of the app runs. Set at once by the calls (isScanning
	// right after them, as in 3.x); the thread of the engine clears the bit (failed start, timeout, failure) only when no
	// call came since.
	private final AtomicLong mRun = new AtomicLong();
	private volatile int mLastError = BleScanCallback.NO_ERROR;
	@NonNull
	private volatile ScanSettings mSettings = new ScanSettings.Builder().build();
	@NonNull
	private volatile ScanFilters mFilters = new ScanFilters.Builder().build();
	// Does this phone filter the duplicates? Learnt per process, judged again at each system scan (null: not known yet)
	@Nullable
	private volatile Boolean mFiltersDuplicates;
	// Does the manifest declare BLUETOOTH_SCAN with usesPermissionFlags="neverForLocation"? (null: not read yet)
	@Nullable
	private volatile Boolean mManifestNeverForLocation;

	// Thread of the engine only
	@Nullable
	private BleScanCallback mCallback;
	// Run (mRun) of the scan of the app
	private long mScanRun;
	@Nullable
	private Options mOptions;
	@Nullable
	private Session mSession;
	@Nullable
	private BluetoothLeScanner mScanner;
	private boolean mHadResults, mDuplicateJudged, mCanReadNames;
	private long mSessionStart, mLastResult;
	// Time really scanned during the scan of the app (restart gaps and Bluetooth off excluded): the clock of the losses
	private long mScannedMs, mScanClockAt;
	private int mFailures;
	private final ArrayDeque<Long> mStarts = new ArrayDeque<>();
	// The devices matching the filters, followed for their loss
	private final HashMap<String, Track> mTracks = new HashMap<>();
	// Reports of each device (filtered or not) during the system scan, until the duplicate filtering is judged
	private final HashMap<String, Integer> mSessionReports = new HashMap<>();
	private final Runnable mTick = this::tick;
	// Background scans of the app (PendingIntent): started again when Bluetooth comes back
	private final HashMap<PendingIntent, BackgroundScan> mBackgroundScans = new HashMap<>();

	ScanEngine(@NonNull BleEngine engine, @NonNull Dispatcher dispatcher) {
		mEngine = engine;
		mDispatcher = dispatcher;
	}

	/**
	 * Thread of the engine (started by the first scan)
	 */
	@NonNull
	private Handler handler() {
		return mEngine.handler();
	}

	// region Any thread

	public boolean isScanning() {
		return (mRun.get() & 1) != 0;
	}

	public int lastError() {
		return mLastError;
	}

	@NonNull
	public ScanSettings settings() {
		return mSettings;
	}

	@NonNull
	public ScanFilters filters() {
		return mFilters;
	}

	public void setSettings(@NonNull ScanSettings settings) {
		mSettings = settings;
	}

	public void setFilters(@NonNull ScanFilters filters) {
		mFilters = filters;
	}

	@NonNull
	public List<BleDevice> scannedDevices() {
		return new ArrayList<>(mDevices.values());
	}

	public void clearScannedDevices() {
		mDevices.clear();
	}

	public void start(@NonNull BleScanCallback callback) {
		long run = mRun.updateAndGet(v -> (((v >>> 1) + 1) << 1) | 1);
		handler().post(() -> handleStart(callback, run));
	}

	public void stop() {
		mRun.updateAndGet(v -> ((v >>> 1) + 1) << 1);
		// No thread yet: no scan ever started
		if (mEngine.hasThread()) handler().post(() -> stopNow(null));
	}

	public void reset(boolean resetSettings, boolean resetFilters) {
		if (resetSettings) mSettings = new ScanSettings.Builder().build();
		if (resetFilters) mFilters = new ScanFilters.Builder().build();
		mLastError = BleScanCallback.NO_ERROR;
		mRun.updateAndGet(v -> ((v >>> 1) + 1) << 1);
		// The devices of the scan go to its onScanFinished, the list is empty for the app from now on (as in 3.x)
		List<BleDevice> devices = scannedDevices();
		mDevices.clear();
		if (!mEngine.hasThread()) return;
		handler().post(() -> {
			stopNow(devices);
			mScanner = null;
			mDevices.clear();
			mTracks.clear();
			mSystemNames.clear();
		});
	}

	/**
	 * Duplicate filtering seen on this phone: null when not known yet
	 */
	@Nullable
	public Boolean filtersDuplicates() {
		return mFiltersDuplicates;
	}

	public void startBackground(@NonNull PendingIntent intent, @NonNull ScanFilters filters, @NonNull ScanSettings settings, @Nullable BleBackgroundScanCallback callback) {
		handler().post(() -> {
			BackgroundScan scan = new BackgroundScan(filters, settings);
			mBackgroundScans.put(intent, scan);
			startBackgroundNow(intent, scan, callback);
		});
	}

	public void stopBackground(@NonNull PendingIntent intent) {
		handler().post(() -> {
			mBackgroundScans.remove(intent);
			handler().removeCallbacksAndMessages(intent);
			BluetoothLeScanner scanner = scanner();
			if (scanner == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !mEngine.checkBluetoothEnabled()) return;
			try {
				scanner.stopScan(intent);
			} catch (RuntimeException e) {
				BleLog.w("stopScan", e);
			}
		});
	}

	/**
	 * Devices of an Intent of a background scan, with the filters only KnBle can check (name prefix or suffix, MAC
	 * prefix: they narrow down what the hardware filters let through; minimum RSSI). No binder call: the name is the
	 * advertised one.
	 */
	@NonNull
	@SuppressWarnings("deprecation")
	public static List<BleDevice> parseBackground(@NonNull Intent intent, @Nullable ScanFilters filters) {
		List<ScanResult> results = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				? intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult.class)
				: Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) : null;
		if (results == null || results.isEmpty()) return Collections.emptyList();

		boolean hardwareFiltered = filters != null && backgroundHardwareFiltered(filters);
		List<BleDevice> devices = new ArrayList<>(results.size());
		for (ScanResult r : results) {
			if (filters != null && r.getRssi() < filters.getMinRssi()) continue;
			byte[] raw = r.getScanRecord() == null ? null : r.getScanRecord().getBytes();
			ScanRecord record = raw == null ? null : new ScanRecord(raw);
			String name = record == null ? null : record.getDeviceName();
			String mac = r.getDevice().getAddress();
			if (filters != null) {
				// The system checked the hardware filters: the others only narrow down
				boolean match = hardwareFiltered ? !hasSoftwareOnlyFilters(filters) || matchesSoftwareOnly(filters, mac, name)
						: matches(filters, mac, name, record, false);
				if (!match) continue;
			}
			devices.add(toBleDevice(r, name, record));
		}
		return devices;
	}

	/**
	 * @return BleScanCallback error of an Intent of a background scan (the system stopped it), NO_ERROR otherwise
	 */
	public static int backgroundError(@NonNull Intent intent) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !intent.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE)) return BleScanCallback.NO_ERROR;
		return scanError(intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, ScanCallback.SCAN_FAILED_INTERNAL_ERROR));
	}

	private static int scanError(int code) {
		return switch (code) {
			case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> BleScanCallback.SCAN_FEATURE_UNSUPPORTED;
			case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> BleScanCallback.SCANNER_UNAVAILABLE;
			default -> BleScanCallback.UNKNOWN_ERROR;
		};
	}

	// endregion

	// region Requirements (thread of the engine)

	/**
	 * What Android needs to deliver results, which startScan does not check (no result and no error before Android 12)
	 * @return BleScanCallback.NO_ERROR, PERMISSION_DENIED or LOCATION_DISABLED
	 */
	private int requirements(@NonNull ScanSettings settings) {
		boolean withoutLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.isNeverForLocation() && manifestNeverForLocation();
		return requirements(mEngine.context(), Build.VERSION.SDK_INT, withoutLocation);
	}

	/**
	 * Android 12+: BLUETOOTH_SCAN. The location (its permission and the location services) before Android 12, and on 12+
	 * unless the scan is never used for the location: Android delivers nothing without them, without an error.
	 */
	@VisibleForTesting
	static int requirements(@NonNull Context c, int sdk, boolean withoutLocation) {
		if (sdk >= Build.VERSION_CODES.S && !granted(c, Manifest.permission.BLUETOOTH_SCAN)) return BleScanCallback.PERMISSION_DENIED;
		if (withoutLocation) return BleScanCallback.NO_ERROR;
		// Android 10+ wants the fine location of the apps targeting it, the coarse one is enough before
		boolean coarseEnough = sdk < Build.VERSION_CODES.Q || c.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.Q;
		if (!granted(c, Manifest.permission.ACCESS_FINE_LOCATION) && !(coarseEnough && granted(c, Manifest.permission.ACCESS_COARSE_LOCATION))) {
			return BleScanCallback.PERMISSION_DENIED;
		}
		return Utils.areLocationServicesEnabled(c) ? BleScanCallback.NO_ERROR : BleScanCallback.LOCATION_DISABLED;
	}

	private static boolean granted(@NonNull Context c, @NonNull String permission) {
		return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
	}

	/**
	 * Android 12+ delivers the results without the location only when the manifest declares BLUETOOTH_SCAN with
	 * usesPermissionFlags="neverForLocation". Read once (a binder call). Not declared at all, or unreadable: trusted.
	 */
	@RequiresApi(Build.VERSION_CODES.S)
	@SuppressWarnings("deprecation")
	private boolean manifestNeverForLocation() {
		Boolean known = mManifestNeverForLocation;
		if (known != null) return known;
		boolean never = true;
		try {
			Context c = mEngine.context();
			PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
			String[] names = info.requestedPermissions;
			int[] flags = info.requestedPermissionsFlags;
			if (names != null && flags != null) {
				for (int i = 0; i < names.length && i < flags.length; i++) {
					if (Manifest.permission.BLUETOOTH_SCAN.equals(names[i])) never = (flags[i] & PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0;
				}
			}
		} catch (Exception e) {
			BleLog.w("package info", e);
		}
		if (!never) BleLog.d("BLUETOOTH_SCAN without neverForLocation in the manifest: the scan needs the location");
		mManifestNeverForLocation = never;
		return never;
	}

	// endregion

	// region Background scan (thread of the engine)

	private static final class BackgroundScan {
		@NonNull
		final ScanFilters filters;
		@NonNull
		final ScanSettings settings;

		BackgroundScan(@NonNull ScanFilters filters, @NonNull ScanSettings settings) {
			this.filters = filters;
			this.settings = settings;
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private static int startSystemBackground(@NonNull BluetoothLeScanner scanner, @NonNull List<ScanFilter> filters,
											 @NonNull android.bluetooth.le.ScanSettings settings, @NonNull PendingIntent intent) {
		// Started again with the same PendingIntent: some stacks would keep the old filters, others register it twice
		try {
			scanner.stopScan(intent);
		} catch (RuntimeException e) {
			BleLog.w("stopScan", e);
		}
		return scanner.startScan(filters, settings, intent);
	}

	/**
	 * Scan of the system delivered to a PendingIntent (Android 8+), even when the app is not running: hardware filters
	 * only (the system needs some to scan with the screen off), counted in the 5 starts in 30 s
	 */
	private void startBackgroundNow(@NonNull PendingIntent intent, @NonNull BackgroundScan scan, @Nullable BleBackgroundScanCallback callback) {
		if (mBackgroundScans.get(intent) != scan) return;
		int error = BleScanCallback.NO_ERROR;
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) error = BleScanCallback.SCAN_FEATURE_UNSUPPORTED;
		else if (!mEngine.checkBluetoothEnabled()) error = BleScanCallback.BT_DISABLED;
		else error = requirements(scan.settings);
		BluetoothLeScanner scanner = error == BleScanCallback.NO_ERROR ? scanner() : null;
		if (error == BleScanCallback.NO_ERROR && scanner == null) error = BleScanCallback.SCANNER_UNAVAILABLE;
		if (error != BleScanCallback.NO_ERROR) {
			// Bluetooth off: started again when it is back, the app is told now
			if (error != BleScanCallback.BT_DISABLED) mBackgroundScans.remove(intent);
			reportBackground(callback, error);
			return;
		}

		long now = SystemClock.elapsedRealtime();
		long wait = startsWait(now);
		if (wait > 0) {
			BleLog.d("5 scan starts in 30 s: background scan in " + wait + " ms");
			android.os.Message m = android.os.Message.obtain(handler(), () -> startBackgroundNow(intent, scan, callback));
			m.obj = intent;
			handler().sendMessageDelayed(m, wait);
			return;
		}

		int result;
		try {
			// The hardware filters, even when the app also has filters only KnBle can check: parseBackground narrows down
			List<ScanFilter> systemFilters = scan.filters.isUsingAndroid6Filters() ? hardwareFilters(scan.filters, true) : new ArrayList<>();
			if (systemFilters.isEmpty()) {
				BleLog.d("background scan without hardware filter: no result with the screen off");
				systemFilters.add(new ScanFilter.Builder().build());
			}
			android.bluetooth.le.ScanSettings settings = new Options(scan.settings, scan.filters).toSystem();
			mStarts.addLast(now);
			// Android 8+ only (refused above before)
			result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? startSystemBackground(scanner, systemFilters, settings, intent) : -1;
		} catch (SecurityException e) {
			BleLog.w("startScan", e);
			result = -1;
		} catch (RuntimeException e) {
			BleLog.w("startScan", e);
			result = ScanCallback.SCAN_FAILED_INTERNAL_ERROR;
		}
		// Already started with this PendingIntent: it goes on
		if (result == 0 || result == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
			BleLog.d("background scan started");
			if (callback != null) mDispatcher.post(callback::onBackgroundScanStarted);
			return;
		}
		mBackgroundScans.remove(intent);
		reportBackground(callback, result == -1 ? BleScanCallback.PERMISSION_DENIED : scanError(result));
	}

	private void reportBackground(@Nullable BleBackgroundScanCallback callback, int error) {
		BleLog.d("background scan failed: " + error);
		if (callback != null) mDispatcher.post(() -> callback.onBackgroundScanFailed(error));
	}

	// endregion

	// region Scan of the app (thread of the engine)

	private void handleStart(@NonNull BleScanCallback callback, long run) {
		long delay = 0;

		// A scan running: it ends (onScanFinished) and the new one starts a bit later
		if (mCallback != null) {
			stopNow(null);
			delay += RESTART_DELAY_MS;
		} else {
			handler().removeCallbacksAndMessages(SCAN);
		}

		if (!mEngine.checkBluetoothEnabled()) {
			if (!mEngine.enableBluetooth(true)) {
				failStart(callback, run, BleScanCallback.BT_DISABLED);
				return;
			}
			delay += ENABLE_BLUETOOTH_DELAY_MS;
		}

		mDevices.clear();
		mTracks.clear();
		mSystemNames.clear();
		mLastError = BleScanCallback.NO_ERROR;

		ScanSettings settings = mSettings;
		// Without its permission (or the location when Android needs it), Android would deliver nothing, silently
		int error = requirements(settings);
		if (error != BleScanCallback.NO_ERROR) {
			failStart(callback, run, error);
			return;
		}

		mCallback = callback;
		mScanRun = run;
		mOptions = new Options(settings, mFilters);
		mFailures = 0;
		mHadResults = false;
		mScannedMs = 0;
		// The name known by the system needs BLUETOOTH_CONNECT on Android 12+ (a SecurityException otherwise)
		mCanReadNames = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(mEngine.context(), Manifest.permission.BLUETOOTH_CONNECT);
		mDispatcher.post(callback::onScanStarted);
		scheduleSystemStart(delay);

		// As in 3.x, no timeout with an automatic restart: the scan lasts until stopScan()
		if (settings.getScanTimeout() > 0 && settings.getAutoRestartScanAfter() <= 0) {
			postDelayed(() -> stopNow(null), SCAN, settings.getScanTimeout() + delay);
		}
		postDelayed(mTick, SCAN, TICK_MS);
	}

	private void failStart(@NonNull BleScanCallback callback, long run, int error) {
		fail(callback, error);
		// Not scanning, unless the app called startScan or stopScan again meanwhile
		mRun.compareAndSet(run, run & ~1L);
	}

	/**
	 * End of the scan of the app (stopScan, timeout, failure)
	 * @param devices Devices for onScanFinished (null: the current ones)
	 */
	void stopNow(@Nullable List<BleDevice> devices) {
		handler().removeCallbacksAndMessages(SCAN);
		stopSystemScan(false);

		BleScanCallback callback = mCallback;
		mCallback = null;
		mOptions = null;
		if (callback == null) return;
		mRun.compareAndSet(mScanRun, mScanRun & ~1L);
		List<BleDevice> finished = devices != null ? devices : scannedDevices();
		mDispatcher.post(() -> callback.onScanFinished(finished));
	}

	private void fail(@NonNull BleScanCallback callback, int error) {
		mLastError = error;
		mDispatcher.post(() -> callback.onScanFailed(error));
	}

	/**
	 * Is the scan of the app still the one asked by the app? (none after stopScan, even before its end here)
	 */
	private boolean isCurrentRun() {
		return mCallback != null && mRun.get() == mScanRun;
	}

	/**
	 * Every second of a scan: duplicate filtering, silence, lost devices
	 */
	private void tick() {
		if (mCallback == null) return;
		postDelayed(mTick, SCAN, TICK_MS);
		if (mSession == null) {
			// No system scan and none coming while Bluetooth is on (a missed state broadcast): started again
			if (!handler().hasMessages(0, SYSTEM) && mEngine.isBluetoothEnabled()) scheduleSystemStart(0);
			return;
		}
		Options o = mOptions;
		if (o == null || !isCurrentRun()) return;

		long now = SystemClock.elapsedRealtime();
		advanceScanClock(now);
		if (checkDuplicateFiltering(now)) return;

		// Hardware match tracking: silence is normal (first match only) and the controller tells the losses. Silence is
		// normal too for an opportunistic scan (results only when another app scans).
		boolean tracking = o.matchTracking();
		long silence = tracking || o.scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC ? 0
				: silenceRestartMs(Build.VERSION.SDK_INT, mHadResults, o.reportDelay);
		if (silence > 0 && now - Math.max(mLastResult, mSessionStart) > silence) {
			BleLog.d("scan silent for " + silence / 1000 + " s: started again");
			restartSystemScan();
			return;
		}

		checkLostDevices(o, !tracking);
	}

	/**
	 * Silence after which the system scan starts again (0: none). Batched results come every reportDelay. Android 12L
	 * and below ignore a start past their limit without an error: a scan without any result yet starts again too.
	 */
	@VisibleForTesting
	static long silenceRestartMs(int sdk, boolean hadResults, long reportDelay) {
		if (!hadResults && sdk > Build.VERSION_CODES.S_V2) return 0;
		return SILENCE_RESTART_MS + 2 * reportDelay;
	}

	/**
	 * Duplicate filtering after 12 s of a system scan: several devices seen, none reported again (a device reported 3
	 * times proves the contrary at once, see countReport). Not judged in low power or opportunistic mode, nor on a
	 * hardware filtered scan: their reports are sparse anyway.
	 * @return true when the system scan was started again
	 */
	private boolean checkDuplicateFiltering(long now) {
		Options o = mOptions;
		Session s = mSession;
		if (mDuplicateJudged || o == null || s == null || !o.countsDuplicates() || o.scanMode == ScanSettings.SCAN_MODE_LOW_POWER
				|| s.hardwareFiltered || now - mSessionStart < DUPLICATE_CHECK_MS || mSessionReports.size() < DUPLICATE_MIN_DEVICES) {
			return false;
		}
		return judgeDuplicates(true);
	}

	/**
	 * A report of the current system scan, for the duplicate check
	 */
	private void countReport(@NonNull String mac) {
		Options o = mOptions;
		if (mDuplicateJudged || o == null || !o.countsDuplicates()) return;
		Integer n = mSessionReports.get(mac);
		if (n == null && mSessionReports.size() >= DUPLICATE_MAX_DEVICES) return;
		int reports = n == null ? 1 : n + 1;
		mSessionReports.put(mac, reports);
		if (reports >= DUPLICATE_MIN_REPORTS) judgeDuplicates(false);
	}

	/**
	 * @return true when the system scan was started again
	 */
	private boolean judgeDuplicates(boolean filters) {
		int seen = mSessionReports.size();
		mDuplicateJudged = true;
		mSessionReports.clear();
		Boolean before = mFiltersDuplicates;
		mFiltersDuplicates = filters;
		if (Boolean.valueOf(filters).equals(before)) return false;
		BleLog.d("duplicate filtering by the phone: " + filters + " (" + seen + " devices)");
		if (filters) {
			restartSystemScan();
			return true;
		}
		// Not filtering after all: no more restarts every 10 s
		if (Boolean.TRUE.equals(before)) scheduleRestart();
		return false;
	}

	/**
	 * Counts the time scanned since the last call, only while a system scan runs
	 */
	private void advanceScanClock(long now) {
		if (mSession != null) mScannedMs += now - mScanClockAt;
		mScanClockAt = now;
	}

	/**
	 * Lost devices (when KnBle detects them), and those lost long ago forgotten
	 */
	private void checkLostDevices(@NonNull Options o, boolean detect) {
		BleScanCallback callback = mCallback;
		if (callback == null) return;

		for (Iterator<Map.Entry<String, Track>> it = mTracks.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Track> e = it.next();
			Track t = e.getValue();
			// Scanned time without a report: the gaps between two system scans are not the fault of the device
			long silentMs = mScannedMs - t.scannedAt;
			if (t.lost) {
				if (silentMs < FORGET_AFTER_MS) continue;
				it.remove();
				mDevices.remove(e.getKey());
				mSystemNames.remove(e.getKey());
				continue;
			}
			if (!detect || silentMs < lossDelayMs(t, o)) continue;

			BleDevice device = mDevices.get(e.getKey());
			if (device == null) continue;
			t.lost = true;
			// A report coming after the loss is a new device, not an update merged in a waiting one
			mPending.remove(e.getKey());
			if (KnBle.DEBUG) BleLog.d("lost " + device.getMac() + " after " + silentMs + " ms");
			mDispatcher.post(() -> callback.onDeviceLost(device));
		}
	}

	/**
	 * Scanned time without a report after which a device is lost
	 */
	private long lossDelayMs(@NonNull Track t, @NonNull Options o) {
		long delay = t.intervalMs > 0 ? Math.max(LOST_MIN_MS, Math.min(LOST_MAX_MS, t.intervalMs * LOST_INTERVALS)) : LOST_UNKNOWN_MS;
		// A phone that may filter the duplicates reports a device again only in the next system scan: 2 restarts, or
		// the silence restart when the restarts every 10 s do not apply
		Boolean filtering = mFiltersDuplicates;
		if (Boolean.TRUE.equals(filtering) && o.countsDuplicates()) delay = Math.max(delay, 2 * restartInterval() + LOST_MARGIN_MS);
		else if (!Boolean.FALSE.equals(filtering)) delay = Math.max(delay, LOST_UNKNOWN_FILTERING_MS);
		// Batched results: the next batch comes reportDelay later
		return delay + o.reportDelay;
	}

	// endregion

	// region System scan (thread of the engine)

	/**
	 * Start of a system scan, later when the last 30 s already had 5 starts
	 */
	private void scheduleSystemStart(long delayMs) {
		handler().removeCallbacksAndMessages(SYSTEM);
		long wait = Math.max(delayMs, startsWait(SystemClock.elapsedRealtime()));
		if (wait > delayMs) BleLog.d("5 scan starts in 30 s: next one in " + wait + " ms");
		postDelayed(this::startSystemScan, SYSTEM, wait);
	}

	private long startsWait(long now) {
		while (!mStarts.isEmpty() && now - mStarts.peekFirst() >= STARTS_WINDOW_MS) mStarts.pollFirst();
		if (mStarts.size() < MAX_STARTS) return 0;
		return mStarts.peekFirst() + STARTS_WINDOW_MS + STARTS_MARGIN_MS - now;
	}

	private void startSystemScan() {
		BleScanCallback callback = mCallback;
		Options o = mOptions;
		if (callback == null || o == null || mSession != null) return;
		// Bluetooth off: goes on when it is back (onBluetoothOn)
		if (!mEngine.checkBluetoothEnabled()) return;

		BluetoothLeScanner scanner = scanner();
		if (scanner == null) {
			fail(callback, BleScanCallback.SCANNER_UNAVAILABLE);
			stopNow(null);
			return;
		}

		List<ScanFilter> systemFilters;
		try {
			systemFilters = o.hardwareFilters ? systemFilters(o.filters) : new ArrayList<>();
		} catch (RuntimeException e) {
			// A filter refused by the system (ScanFilters checks them first): a failure, not a crash of the thread
			BleLog.w("scan filters", e);
			fail(callback, BleScanCallback.UNKNOWN_ERROR);
			stopNow(null);
			return;
		}
		boolean hardwareFiltered = !systemFilters.isEmpty();
		if (!hardwareFiltered) {
			systemFilters.add(new ScanFilter.Builder().build());
			// Android refuses the match tracking without a filter: KnBle makes the first match and the loss
			if (o.matchTracking()) {
				BleLog.d("no hardware filter: no hardware match tracking");
				o.callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
			}
		}

		Session session = new Session(callback, mScanRun, o.filters, hardwareFiltered);
		long now = SystemClock.elapsedRealtime();
		advanceScanClock(now);
		mSession = session;
		mSessionStart = now;
		mStarts.addLast(now);
		mSessionReports.clear();
		mDuplicateJudged = false;

		try {
			scanner.startScan(systemFilters, o.toSystem(), session);
		} catch (SecurityException e) {
			BleLog.w("startScan", e);
			mSession = null;
			fail(callback, BleScanCallback.PERMISSION_DENIED);
			stopNow(null);
			return;
		} catch (RuntimeException e) {
			BleLog.w("startScan", e);
			mSession = null;
			retryAfterFailure(callback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
			return;
		}

		scheduleRestart();
	}

	/**
	 * Next silent restart of the current system scan
	 */
	private void scheduleRestart() {
		// While a system scan runs, the only message of SYSTEM is its restart
		handler().removeCallbacksAndMessages(SYSTEM);
		long elapsed = SystemClock.elapsedRealtime() - mSessionStart;
		postDelayed(this::restartSystemScan, SYSTEM, Math.max(0, restartInterval() - elapsed));
	}

	/**
	 * Interval of the silent restarts: the one asked by the app, else 10 s on a phone filtering the duplicates, else
	 * before the time limit of Android
	 */
	private long restartInterval() {
		long max = longScanRestartMs(Build.VERSION.SDK_INT);
		Options o = mOptions;
		if (o == null) return max;
		long asked = o.settings.getAutoRestartScanAfter();
		if (asked > 0) return Math.min(asked, max);
		// Not with batched results (a batch would never come) nor with hardware match tracking (it would start over)
		if (Boolean.TRUE.equals(mFiltersDuplicates) && o.countsDuplicates()) return FILTERING_RESTART_MS;
		return max;
	}

	/**
	 * Android 7 to 13 degrade a scan after 30 min, Android 14+ after 10 min
	 */
	@VisibleForTesting
	static long longScanRestartMs(int sdk) {
		return sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ? LONG_SCAN_RESTART_14_MS : LONG_SCAN_RESTART_MS;
	}

	private void restartSystemScan() {
		stopSystemScan(true);
		scheduleSystemStart(RESTART_DELAY_MS);
	}

	/**
	 * @param flush The batched results waiting in the controller are asked first (they still count once it stops)
	 */
	private void stopSystemScan(boolean flush) {
		handler().removeCallbacksAndMessages(SYSTEM);
		advanceScanClock(SystemClock.elapsedRealtime());
		Session session = mSession;
		mSession = null;
		BluetoothLeScanner scanner = mScanner;
		Options o = mOptions;
		if (session == null || scanner == null || !mEngine.checkBluetoothEnabled()) return;
		if (flush && o != null && o.reportDelay > 0) {
			try {
				scanner.flushPendingScanResults(session);
			} catch (RuntimeException e) {
				BleLog.w("flushPendingScanResults", e);
			}
		}
		try {
			scanner.stopScan(session);
		} catch (RuntimeException e) {
			BleLog.w("stopScan", e);
		}
	}

	@Nullable
	private BluetoothLeScanner scanner() {
		if (mScanner == null) {
			try {
				mScanner = mEngine.adapter().getBluetoothLeScanner();
			} catch (RuntimeException e) {
				BleLog.w("getBluetoothLeScanner", e);
			}
		}
		return mScanner;
	}

	private void onScanFailed(@NonNull Session session, int errorCode) {
		if (session != mSession) return;
		BleScanCallback callback = mCallback;
		Options o = mOptions;
		BleLog.d("scan failed: error " + errorCode);
		// Started twice for the system: the first one goes on
		if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return;

		advanceScanClock(SystemClock.elapsedRealtime());
		mSession = null;
		handler().removeCallbacksAndMessages(SYSTEM);
		if (callback == null || o == null) return;

		switch (errorCode) {
			case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> {
				if (o.fallBack()) {
					scheduleSystemStart(RESTART_DELAY_MS);
					return;
				}
				fail(callback, BleScanCallback.SCAN_FEATURE_UNSUPPORTED);
				stopNow(null);
				return;
			}
			case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> {
				if (o.matchTracking()) {
					// The match tracking takes slots of the controller: KnBle makes the first match and the loss, the
					// filters stay in the controller
					BleLog.d("no hardware match tracking: out of hardware resources");
					o.callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
					scheduleSystemStart(RESTART_DELAY_MS);
					return;
				}
				if (o.hardwareFilters) {
					// Too many filters for the controller: filtered here
					BleLog.d("filters checked by KnBle, not by the controller");
					o.hardwareFilters = false;
					scheduleSystemStart(RESTART_DELAY_MS);
					return;
				}
			}
			// Over the 5 starts in 30 s (an error from Android 13, other scans of the app count too): later
			case ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> {
				scheduleSystemStart(STARTS_WINDOW_MS);
				return;
			}
			default -> {}
		}
		retryAfterFailure(callback, errorCode);
	}

	private void retryAfterFailure(@NonNull BleScanCallback callback, int errorCode) {
		if (mFailures < FAILURE_RETRIES) {
			long delay = FAILURE_RETRY_MS << mFailures;
			mFailures++;
			BleLog.d("scan started again in " + delay + " ms (error " + errorCode + ")");
			scheduleSystemStart(delay);
			return;
		}
		fail(callback, errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ? BleScanCallback.SCANNER_UNAVAILABLE : BleScanCallback.UNKNOWN_ERROR);
		stopNow(null);
	}

	/**
	 * Bluetooth turning off: the system scan ends, the scan of the app waits for Bluetooth to come back
	 */
	void onBluetoothOff() {
		mScanner = null;
		if (mCallback == null) return;
		BleLog.d("bluetooth off: scan suspended");
		handler().removeCallbacksAndMessages(SYSTEM);
		advanceScanClock(SystemClock.elapsedRealtime());
		mSession = null;
	}

	void onBluetoothOn() {
		// The system forgets the background scans when Bluetooth turns off
		for (Map.Entry<PendingIntent, BackgroundScan> e : mBackgroundScans.entrySet()) {
			PendingIntent intent = e.getKey();
			BackgroundScan scan = e.getValue();
			android.os.Message m = android.os.Message.obtain(handler(), () -> startBackgroundNow(intent, scan, null));
			m.obj = intent;
			handler().sendMessageDelayed(m, BLUETOOTH_ON_DELAY_MS);
		}
		if (mCallback == null || mSession != null) return;
		BleLog.d("bluetooth on: scan resumed");
		scheduleSystemStart(BLUETOOTH_ON_DELAY_MS);
	}

	private void postDelayed(@NonNull Runnable r, @NonNull Object token, long delayMs) {
		android.os.Message m = android.os.Message.obtain(handler(), r);
		m.obj = token;
		handler().sendMessageDelayed(m, delayMs);
	}

	/**
	 * Filters given to the system (hardware filtering, needed to scan with the screen off). None when the app also has
	 * filters only KnBle can check (name prefix or suffix, MAC prefix): the system would hide the devices they match.
	 */
	@NonNull
	private static List<ScanFilter> systemFilters(@NonNull ScanFilters filters) {
		if (!filters.isUsingAndroid6Filters() || hasSoftwareOnlyFilters(filters)) return new ArrayList<>();
		return hardwareFilters(filters, true);
	}

	/**
	 * A background scan gives its hardware filters to the system, whatever the others
	 */
	private static boolean backgroundHardwareFiltered(@NonNull ScanFilters filters) {
		return filters.isUsingAndroid6Filters() && filters.count6Filters() > 0;
	}

	private static boolean hasSoftwareOnlyFilters(@NonNull ScanFilters f) {
		return !f.getDeviceStartsWithNames().isEmpty() || !f.getDeviceEndsWithNames().isEmpty() || !f.getDevicesMacsStartsWith().isEmpty();
	}

	private static boolean hasNameFilters(@NonNull ScanFilters f) {
		return !f.getDeviceNames().isEmpty() || !f.getDeviceStartsWithNames().isEmpty() || !f.getDeviceEndsWithNames().isEmpty();
	}

	/**
	 * The filters a controller can check (with or without the exact names)
	 */
	@NonNull
	static List<ScanFilter> hardwareFilters(@NonNull ScanFilters filters, boolean withNames) {
		List<ScanFilter> list = new ArrayList<>();
		if (withNames) for (String name : filters.getDeviceNames()) list.add(new ScanFilter.Builder().setDeviceName(name).build());
		for (String mac : filters.getDevicesMacs()) list.add(new ScanFilter.Builder().setDeviceAddress(mac.toUpperCase(java.util.Locale.ROOT)).build());
		for (int id : filters.getManufacturerIds()) list.add(new ScanFilter.Builder().setManufacturerData(id, new byte[0]).build());
		for (UUID uuid : filters.getBeaconUUIDs()) {
			byte[] data = new byte[18];
			data[0] = 0x02;
			data[1] = 0x15;
			System.arraycopy(Utils.uuidAsBytes(uuid), 0, data, 2, 16);
			list.add(new ScanFilter.Builder().setManufacturerData(0x004C, data).build());
		}
		for (UUID uuid : filters.getServiceUuids()) list.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(uuid)).build());
		List<UUID> dataUuids = filters.getServiceDataUuids();
		for (int i = 0; i < dataUuids.size(); i++) {
			list.add(new ScanFilter.Builder().setServiceData(new ParcelUuid(dataUuids.get(i)), filters.getServiceDataPrefixes().get(i)).build());
		}
		return list;
	}

	/**
	 * Options of the system scans of a scan of the app: what the app asked, minus what this phone cannot do (checked
	 * before the start, then dropped one by one on a FEATURE_UNSUPPORTED)
	 */
	private final class Options {
		@NonNull
		final ScanSettings settings;
		// The filters of the scan of the app, kept by its silent restarts
		@NonNull
		final ScanFilters filters;
		final int scanMode;
		int callbackType, matchMode, numMatches, phy;
		long reportDelay;
		boolean legacy, hardwareFilters = true;

		Options(@NonNull ScanSettings s, @NonNull ScanFilters f) {
			settings = s;
			filters = f;
			scanMode = s.getScanMode();
			callbackType = s.getCallbackType();
			matchMode = s.getMatchMode();
			numMatches = s.getNbMatch();
			phy = s.getPhy();
			reportDelay = s.getReportDelay();
			legacy = s.isLegacy();

			BluetoothAdapter adapter = mEngine.adapter();
			try {
				if (reportDelay > 0 && !adapter.isOffloadedScanBatchingSupported()) {
					BleLog.d("no batched results on this phone");
					reportDelay = 0;
				}
				if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES && !adapter.isOffloadedFilteringSupported()) {
					// First match and loss are made by KnBle
					BleLog.d("no hardware match tracking on this phone");
					callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
				}
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					if (!legacy && !adapter.isLeExtendedAdvertisingSupported()) legacy = true;
					if ((phy == ScanSettings.PHY_LE_CODED || phy == ScanSettings.PHY_LE_ALL_SUPPORTED) && !adapter.isLeCodedPhySupported()) {
						phy = ScanSettings.PHY_LE_1M;
					}
				}
			} catch (RuntimeException e) {
				BleLog.w("adapter features", e);
			}
		}

		/**
		 * First match or loss by the controller (CALLBACK_TYPE_FIRST_MATCH, CALLBACK_TYPE_MATCH_LOST)
		 */
		boolean matchTracking() {
			return callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
		}

		/**
		 * Every report delivered at once: the duplicate filtering of the phone shows (and matters)
		 */
		boolean countsDuplicates() {
			return !matchTracking() && reportDelay == 0 && scanMode != ScanSettings.SCAN_MODE_OPPORTUNISTIC;
		}

		/**
		 * One option less after a FEATURE_UNSUPPORTED: false when none is left to drop
		 */
		boolean fallBack() {
			String dropped;
			if (reportDelay > 0) {
				reportDelay = 0;
				dropped = "batched results";
			} else if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
				callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
				dropped = "match tracking";
			} else if (!legacy || phy != ScanSettings.PHY_LE_1M) {
				legacy = true;
				phy = ScanSettings.PHY_LE_1M;
				dropped = "extended advertising";
			} else if (matchMode != ScanSettings.MATCH_MODE_AGGRESSIVE || numMatches != ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT) {
				matchMode = ScanSettings.MATCH_MODE_AGGRESSIVE;
				numMatches = ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT;
				dropped = "match mode";
			} else if (hardwareFilters) {
				hardwareFilters = false;
				dropped = "hardware filters";
			} else {
				return false;
			}
			BleLog.d("scan option not supported by this phone, dropped: " + dropped);
			return true;
		}

		@NonNull
		android.bluetooth.le.ScanSettings toSystem() {
			android.bluetooth.le.ScanSettings.Builder b = new android.bluetooth.le.ScanSettings.Builder()
					.setScanMode(scanMode)
					.setReportDelay(reportDelay);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				b.setCallbackType(callbackType).setMatchMode(matchMode).setNumOfMatches(numMatches);
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				b.setLegacy(legacy);
				if (!legacy) b.setPhy(phy);
			}
			return b.build();
		}
	}

	// endregion

	// region Results (thread of the engine)

	/**
	 * What KnBle follows of a device matching the filters during the scan of the app: its reports, their usual
	 * interval, its loss
	 */
	private static final class Track {
		long lastSeenNanos;
		long intervalMs;
		// mScannedMs at the last report
		long scannedAt;
		boolean lost;

		void seen(long nanos, long scannedMs) {
			scannedAt = scannedMs;
			if (lastSeenNanos != 0 && nanos > lastSeenNanos) {
				// Interval between two reports, smoothed; a gap of a restart or a loss does not count fully
				long ms = Math.min((nanos - lastSeenNanos) / 1_000_000L, LOST_MAX_MS);
				intervalMs = intervalMs == 0 ? ms : (intervalMs * 4 + ms) / 5;
			}
			lastSeenNanos = Math.max(lastSeenNanos, nanos);
		}
	}

	private void process(@NonNull Session session, @NonNull ScanResult result, boolean matchLost) {
		// The reports of a system scan replaced by a restart still count (batch flushed, results already queued), none
		// once the app stopped the scan
		if (session.run != mScanRun || !isCurrentRun()) return;

		BluetoothDevice device = result.getDevice();
		String mac = device.getAddress();
		Track track = mTracks.get(mac);

		if (matchLost) {
			// Loss reported by the controller (MATCH_LOST)
			BleDevice known = mDevices.get(mac);
			BleScanCallback callback = mCallback;
			if (track != null && known != null && callback != null && !track.lost) {
				track.lost = true;
				mPending.remove(mac);
				mDispatcher.post(() -> callback.onDeviceLost(known));
			}
			return;
		}

		long now = SystemClock.elapsedRealtime();
		advanceScanClock(now);
		if (session == mSession) {
			mLastResult = now;
			mHadResults = true;
			mFailures = 0;
			// Every report counts for the duplicate filtering, filtered out or not
			countReport(mac);
		}

		// The cheap filters first: no parsing nor binder call for the reports thrown away
		ScanFilters filters = session.filters;
		if (result.getRssi() < filters.getMinRssi()) return;

		BleDevice previous = mDevices.get(mac);
		android.bluetooth.le.ScanRecord systemRecord = result.getScanRecord();
		byte[] raw = systemRecord == null ? null : systemRecord.getBytes();
		// A report without the scan response (or with the scan response only) keeps the fields seen before
		ScanRecord record = raw == null ? null : new ScanRecord(raw);
		ScanRecord before = previous == null ? null : previous.getScanRecord();
		if (record == null) record = before;
		else if (before != null) record = record.mergedWith(before);

		String name = record == null ? null : record.getDeviceName();
		if (name == null && previous != null) name = previous.getName();
		boolean match = matches(filters, mac, name, record, session.hardwareFiltered);
		// No advertised name: the one known by the system may match a name filter
		if (!match && name == null && hasNameFilters(filters)) {
			name = systemName(mac, device);
			match = name != null && matches(filters, mac, name, record, false);
		}
		if (!match) return;
		if (name == null) name = systemName(mac, device);

		// New, or found again after its loss (a lost device forgotten has no track left)
		boolean isNew = previous == null || track == null || track.lost;
		if (track == null) {
			track = new Track();
			mTracks.put(mac, track);
		}
		long nanos = result.getTimestampNanos();
		// The loss clock moves for the matching reports only: a device below the minimum RSSI gets lost
		track.seen(nanos > 0 ? nanos : SystemClock.elapsedRealtimeNanos(), mScannedMs);
		track.lost = false;
		BleDevice bleDevice = toBleDevice(result, name, record);
		mDevices.put(mac, bleDevice);
		deliver(session.callback, bleDevice, isNew);
	}

	/**
	 * Name known by the system for a device advertising none (a binder call, once per device, on this thread; none
	 * without BLUETOOTH_CONNECT on Android 12+)
	 */
	@Nullable
	private String systemName(@NonNull String mac, @NonNull BluetoothDevice device) {
		if (!mCanReadNames) return null;
		String known = mSystemNames.get(mac);
		if (known != null) return known.isEmpty() ? null : known;
		try {
			known = device.getName();
		} catch (RuntimeException e) {
			BleLog.w("getName", e);
		}
		if (mSystemNames.size() >= MAX_SYSTEM_NAMES) mSystemNames.clear();
		mSystemNames.put(mac, known == null ? "" : known);
		return known;
	}

	/**
	 * A device of a scan result of the system (no binder call: the advertised name only)
	 */
	@NonNull
	static BleDevice toBleDevice(@NonNull ScanResult result) {
		byte[] raw = result.getScanRecord() == null ? null : result.getScanRecord().getBytes();
		ScanRecord record = raw == null ? null : new ScanRecord(raw);
		return toBleDevice(result, record == null ? null : record.getDeviceName(), record);
	}

	@NonNull
	private static BleDevice toBleDevice(@NonNull ScanResult result, @Nullable String name, @Nullable ScanRecord record) {
		boolean connectable = true;
		int primaryPhy = BluetoothDevice.PHY_LE_1M, secondaryPhy = 0, txPower = BleDevice.TX_POWER_UNKNOWN;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			connectable = result.isConnectable();
			primaryPhy = result.getPrimaryPhy();
			secondaryPhy = result.getSecondaryPhy();
			txPower = result.getTxPower();
		}
		return new BleDevice(result.getDevice(), name, result.getRssi(), record, System.currentTimeMillis(), connectable, primaryPhy, secondaryPhy, txPower);
	}

	/**
	 * Any filter matches (the minimum RSSI is checked before)
	 */
	private static boolean matches(@NonNull ScanFilters f, @NonNull String mac, @Nullable String name, @Nullable ScanRecord record, boolean hardwareFiltered) {
		if (f.count() == 0 || hardwareFiltered) return true;

		if (matchesSoftwareOnly(f, mac, name)) return true;
		if (name != null && f.getDeviceNames().contains(name)) return true;
		if (f.getDevicesMacs().contains(mac)) return true;
		if (record == null) return false;
		if (record.getBeaconUUID() != null && f.getBeaconUUIDs().contains(record.getBeaconUUID())) return true;
		for (Integer id : record.getManufacturerSpecificData().keySet()) if (f.getManufacturerIds().contains(id)) return true;
		for (UUID uuid : record.getServiceUuids()) if (f.getServiceUuids().contains(uuid)) return true;
		List<UUID> dataUuids = f.getServiceDataUuids();
		for (int i = 0; i < dataUuids.size(); i++) {
			byte[] data = record.getServiceData(dataUuids.get(i));
			if (data != null && startsWith(data, f.getServiceDataPrefixes().get(i))) return true;
		}
		return false;
	}

	/**
	 * The filters only KnBle can check: name prefix or suffix, MAC prefix
	 */
	private static boolean matchesSoftwareOnly(@NonNull ScanFilters f, @NonNull String mac, @Nullable String name) {
		if (name != null) {
			for (String prefix : f.getDeviceStartsWithNames()) if (name.startsWith(prefix)) return true;
			for (String suffix : f.getDeviceEndsWithNames()) if (name.endsWith(suffix)) return true;
		}
		for (String prefix : f.getDevicesMacsStartsWith()) if (mac.startsWith(prefix)) return true;
		return false;
	}

	private static boolean startsWith(@NonNull byte[] data, @NonNull byte[] prefix) {
		if (data.length < prefix.length) return false;
		for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
		return true;
	}

	/**
	 * At most one delivery waits per device: a slow app gets the latest data of each device, never a backlog. The first
	 * report of a device (or the first after its loss) always comes as onScanResult.
	 */
	private void deliver(@NonNull BleScanCallback callback, @NonNull BleDevice device, boolean isNew) {
		String mac = device.getMac();
		Pending pending = mPending.get(mac);
		if (pending != null && !isNew) {
			for (BleDevice current; (current = pending.latest.get()) != null; ) {
				if (pending.latest.compareAndSet(current, device)) return;
			}
		}

		Pending fresh = new Pending(device, isNew);
		mPending.put(mac, fresh);
		mDispatcher.post(() -> {
			BleDevice latest = fresh.latest.getAndSet(null);
			mPending.remove(mac, fresh);
			if (latest == null) return;
			if (fresh.isNew) callback.onScanResult(latest);
			else callback.onDeviceUpdated(latest);
		});
	}

	private static final class Pending {
		final AtomicReference<BleDevice> latest;
		final boolean isNew;

		Pending(@NonNull BleDevice device, boolean isNew) {
			latest = new AtomicReference<>(device);
			this.isNew = isNew;
		}
	}

	// endregion

	// region Tests

	/**
	 * Starts recorded in the window of Android (tests)
	 */
	@VisibleForTesting
	int recentStarts() {
		startsWait(SystemClock.elapsedRealtime());
		return mStarts.size();
	}

	/**
	 * Devices followed for their loss (tests)
	 */
	@VisibleForTesting
	int trackedDevices() {
		return mTracks.size();
	}

	// endregion

	/**
	 * One system scan. Android calls it on the main thread: the results are only queued for the thread of the engine,
	 * in their order (a MATCH_LOST then a FIRST_MATCH of a device is a loss then a new device).
	 */
	private final class Session extends ScanCallback {
		@NonNull
		final BleScanCallback callback;
		// Run (mRun) of the scan of the app
		final long run;
		@NonNull
		final ScanFilters filters;
		// The system checks the filters (given to the controller)
		final boolean hardwareFiltered;
		private final ConcurrentLinkedQueue<Event> mEvents = new ConcurrentLinkedQueue<>();
		private final AtomicBoolean mScheduled = new AtomicBoolean();
		private final Runnable mDrain = this::drain;

		Session(@NonNull BleScanCallback callback, long run, @NonNull ScanFilters filters, boolean hardwareFiltered) {
			this.callback = callback;
			this.run = run;
			this.filters = filters;
			this.hardwareFiltered = hardwareFiltered;
		}

		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			if (result == null) return;
			mEvents.offer(new Event(result, callbackType == android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST));
			schedule();
		}

		@Override
		public void onBatchScanResults(List<ScanResult> results) {
			if (results == null) return;
			for (ScanResult r : results) if (r != null) mEvents.offer(new Event(r, false));
			schedule();
		}

		@Override
		public void onScanFailed(int errorCode) {
			handler().post(() -> ScanEngine.this.onScanFailed(this, errorCode));
		}

		private void schedule() {
			if (mScheduled.compareAndSet(false, true)) handler().post(mDrain);
		}

		private void drain() {
			mScheduled.set(false);
			for (Event e; (e = mEvents.poll()) != null; ) {
				try {
					process(this, e.result, e.lost);
				} catch (RuntimeException ex) {
					if (KnBle.DEBUG) BleLog.w("scan result", ex);
				}
			}
		}
	}

	/**
	 * A report of the system, or a loss (MATCH_LOST)
	 */
	private static final class Event {
		@NonNull
		final ScanResult result;
		final boolean lost;

		Event(@NonNull ScanResult result, boolean lost) {
			this.result = result;
			this.lost = lost;
		}
	}
}
