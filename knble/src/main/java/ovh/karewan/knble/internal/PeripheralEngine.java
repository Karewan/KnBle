package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.Utils;
import ovh.karewan.knble.interfaces.BleAdvertiseCallback;
import ovh.karewan.knble.interfaces.BleServerCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.peripheral.BleAdvertiseData;
import ovh.karewan.knble.peripheral.BleServerService;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The phone as a peripheral: advertising and GATT server, confined to their own thread (started on first use). The
 * callbacks of the system (binder threads, main thread for the legacy advertising) are posted there; the reads, the
 * writes and the subscriptions are answered there without the app, which gets its callbacks through the Dispatcher.
 * The notifications go one at a time, the next one when the system says the previous one was sent (its only flow
 * control on the server side). Bluetooth turned off then on: the advertising and the server start again by themselves.
 * Each start and stop call changes a generation at once, on the calling thread: an event of an older one still on its
 * way is dropped, so nothing comes after stopAdvertising or stopServer returned.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@SuppressLint("MissingPermission")
public final class PeripheralEngine {
	private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	private static final long SERVICE_ADD_TIMEOUT_MS = 5000;
	// No answer to a start of the advertising (BLUETOOTH_ADVERTISE refused without any callback on some versions)
	private static final long ADVERTISE_START_TIMEOUT_MS = 5000;
	// onNotificationSent never came: the next notification goes
	private static final long NOTIFY_TIMEOUT_MS = 5000;
	// A busy stack is asked again after 2, 2, 4, 8... up to 50 ms, for 5 s at most
	private static final long BUSY_BUDGET_MS = 5000, BUSY_MAX_DELAY_MS = 50;
	private static final long BLUETOOTH_ON_DELAY_MS = 1000;
	// Longest attribute value of ATT
	private static final int DEFAULT_MTU = 23, MAX_VALUE_LENGTH = 512;
	private static final int GATT_PREPARE_QUEUE_FULL = 0x09, GATT_INVALID_ATTRIBUTE_LENGTH = 0x0D, CCCD_IMPROPERLY_CONFIGURED = 0xFD;
	// Prepared writes kept for a central until its execute: one that never executes cannot fill the memory
	private static final int PREPARED_MAX_BYTES = 4096, PREPARED_MAX_PARTS = 256;
	// Bonded centrals whose subscriptions are kept between their connections
	private static final int BONDED_MAX = 32;
	private static final Object ADVERTISING = new Object(), SERVER = new Object();

	@NonNull
	private final BleEngine mEngine;
	@NonNull
	private final Dispatcher mDispatcher;
	@Nullable
	private volatile Handler mHandler;
	@Nullable
	private HandlerThread mThread;

	// Snapshots, read by any thread
	private volatile boolean mAdvertising, mServerStarted;
	@NonNull
	private volatile List<BleDevice> mCentralsSnapshot = Collections.emptyList();

	// Callbacks of the app, seen from its thread
	private final AppSide<BleAdvertiseCallback> mAdvApp = new AppSide<>(c -> c.onAdvertisingFailed(BleError.CANCELLED));
	private final AppSide<BleServerCallback> mServerApp = new AppSide<>(c -> c.onServerFailed(BleError.CANCELLED));

	// Advertising (thread of the peripheral)
	@Nullable
	private BleAdvertiseData mAdvData;
	@Nullable
	private Session<BleAdvertiseCallback> mAdv;
	// The advertising of mAdv started at least once (else Bluetooth turned off gives the result of the start)
	private boolean mAdvStartedOnce;
	// AdvertiseCallback or AdvertisingSetCallback of the advertising in progress: the events of an older one are ignored
	@Nullable
	private Object mAdvHandle;
	@Nullable
	private BluetoothLeAdvertiser mAdvertiser;

	// Server (thread of the peripheral)
	@Nullable
	private List<BleServerService> mServices;
	@Nullable
	private Session<BleServerCallback> mSrv;
	// The server of mSrv started at least once (else Bluetooth turned off gives the result of the start)
	private boolean mServerStartedOnce;
	@Nullable
	private BluetoothGattServer mServer;
	// Callback given to the open server: the events of a closed one are ignored
	@Nullable
	private ServerBridge mBridge;
	private final ArrayDeque<BluetoothGattService> mToAdd = new ArrayDeque<>();
	private final HashMap<String, Chr> mChrs = new HashMap<>();
	// Values of the characteristics, kept when the server opens again
	private final HashMap<String, byte[]> mValues = new HashMap<>();
	// Every LE link of the phone, the centrals of this server among them (announced)
	private final LinkedHashMap<Long, Central> mCentrals = new LinkedHashMap<>();
	// Subscriptions (CCCD value by characteristic key) of the bonded centrals, by MAC, the oldest first
	private final LinkedHashMap<Long, HashMap<String, Integer>> mBondedSubscriptions = new LinkedHashMap<>();
	private final ArrayDeque<Notification> mNotifications = new ArrayDeque<>();
	@Nullable
	private Notification mInFlight;
	private final Runnable mPump = this::pump;

	PeripheralEngine(@NonNull BleEngine engine, @NonNull Dispatcher dispatcher) {
		mEngine = engine;
		mDispatcher = dispatcher;
	}

	@NonNull
	private Handler handler() {
		Handler h = mHandler;
		if (h != null) return h;
		synchronized (this) {
			if (mHandler == null) {
				HandlerThread thread = new HandlerThread("KnBle-peripheral");
				thread.start();
				mThread = thread;
				mHandler = new Handler(thread.getLooper());
			}
			return mHandler;
		}
	}

	private void post(@NonNull Runnable r) {
		handler().post(r);
	}

	private void postDelayed(@NonNull Runnable r, @NonNull Object token, long delayMs) {
		Message m = Message.obtain(handler(), r);
		m.obj = token;
		handler().sendMessageDelayed(m, delayMs);
	}

	// region Callbacks of the app

	/**
	 * A start call of the app: its callback and its generation
	 */
	private static final class Session<T> {
		@NonNull
		final T callback;
		@NonNull
		final AtomicInteger generation;
		final int gen;
		// The app got the result of the start (thread of the app only)
		boolean answered;

		Session(@NonNull T callback, @NonNull AtomicInteger generation) {
			this.callback = callback;
			this.generation = generation;
			this.gen = generation.incrementAndGet();
		}

		/**
		 * No start or stop called since
		 */
		boolean current() {
			return generation.get() == gen;
		}
	}

	/**
	 * The advertising or the server, seen from the thread of the app: each start and stop call changes the generation at
	 * once, the events of an older generation still waiting in the Dispatcher are dropped
	 */
	private final class AppSide<T> {
		private final AtomicInteger mGeneration = new AtomicInteger();
		private final AtomicReference<Session<T>> mLatest = new AtomicReference<>();
		// Generation of the last stop call
		private volatile int mStopped;
		@NonNull
		private final Consumer<T> mCancelled;

		AppSide(@NonNull Consumer<T> cancelled) {
			mCancelled = cancelled;
		}

		/**
		 * Any thread. A start replaced before its result gets CANCELLED (unless a stop came since, or the new start has
		 * the same callback: it gets the result of the new one).
		 */
		@NonNull
		Session<T> start(@NonNull T callback) {
			Session<T> s = new Session<>(callback, mGeneration);
			Session<T> replaced = mLatest.getAndSet(s);
			if (replaced != null && replaced.callback != callback) {
				// After the events of the replaced one already in the Dispatcher (dropped, the generation changed)
				mDispatcher.post(() -> {
					if (replaced.answered || mStopped > s.gen) return;
					replaced.answered = true;
					mCancelled.accept(replaced.callback);
				});
			}
			return s;
		}

		/**
		 * Any thread
		 * @return The generation of this stop
		 */
		int stop() {
			int gen = mGeneration.incrementAndGet();
			mStopped = gen;
			mLatest.set(null);
			return gen;
		}

		boolean isCurrent(int gen) {
			return mGeneration.get() == gen;
		}

		/**
		 * An event for the app, dropped if a start or a stop came before its turn
		 * @param result The result of the start (onAdvertisingStarted/Failed, onServerStarted/Failed)
		 */
		void tell(@Nullable Session<T> s, boolean result, @NonNull Consumer<T> event) {
			if (s == null) return;
			mDispatcher.post(() -> {
				if (!s.current()) return;
				if (result) s.answered = true;
				event.accept(s.callback);
			});
		}
	}

	// endregion

	// region Any thread

	public boolean isAdvertising() {
		return mAdvertising;
	}

	public boolean isServerStarted() {
		return mServerStarted;
	}

	@NonNull
	public List<BleDevice> centrals() {
		return mCentralsSnapshot;
	}

	public void startAdvertising(@NonNull BleAdvertiseData data, @NonNull BleAdvertiseCallback callback) {
		Session<BleAdvertiseCallback> s = mAdvApp.start(callback);
		post(() -> {
			// A newer start or stop came meanwhile: that one acts
			if (!s.current()) return;
			stopAdvertisingNow(false);
			mAdvData = data;
			mAdv = s;
			mAdvStartedOnce = false;
			advertise(true);
		});
	}

	public void stopAdvertising() {
		int gen = mAdvApp.stop();
		if (mHandler != null) post(() -> {
			if (mAdvApp.isCurrent(gen)) stopAdvertisingNow(false);
		});
	}

	public void startServer(@NonNull List<BleServerService> services, @NonNull BleServerCallback callback) {
		// Copied as they are now: the thread of the peripheral reads them at each opening, the app may change them
		List<BleServerService> copy = new ArrayList<>(services.size());
		for (BleServerService s : services) copy.add(s.snapshot());
		Session<BleServerCallback> session = mServerApp.start(callback);
		post(() -> {
			// A newer start or stop came meanwhile: that one acts
			if (!session.current()) return;
			stopServerNow();
			mServices = copy;
			mSrv = session;
			mServerStartedOnce = false;
			for (BleServerService s : copy) {
				for (BleServerService.Characteristic c : s.characteristics()) mValues.put(key(s.getUuid(), c.uuid), c.initialValue);
			}
			openServer(true);
		});
	}

	public void stopServer() {
		int gen = mServerApp.stop();
		if (mHandler != null) post(() -> {
			if (mServerApp.isCurrent(gen)) stopServerNow();
		});
	}

	/**
	 * Value served to the next reads
	 * @throws IllegalArgumentException over 512 bytes (longest attribute value)
	 */
	public void setValue(@NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] value) {
		if (value.length > MAX_VALUE_LENGTH) throw new IllegalArgumentException("value of " + value.length + " bytes, " + MAX_VALUE_LENGTH + " at most");
		byte[] copy = value.clone();
		post(() -> {
			String key = key(service, characteristic);
			if (mValues.containsKey(key)) mValues.put(key, copy);
			else BleLog.d("setServerValue: unknown characteristic " + characteristic);
		});
	}

	/**
	 * Notification (or indication, when the central subscribed to those only) to a central, or to every subscribed
	 * central when null. The value is also stored for the reads (not one over 512 bytes: DATA_TOO_LARGE).
	 */
	public void notify(@Nullable BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] value, @Nullable BleWriteCallback callback) {
		if (value.length > MAX_VALUE_LENGTH) {
			// Longer than any attribute value, whatever the MTU
			if (callback != null) mDispatcher.post(() -> callback.onWriteFailed(BleError.DATA_TOO_LARGE));
			return;
		}
		byte[] copy = value.clone();
		post(() -> notifyNow(central, service, characteristic, copy, callback));
	}

	public void disconnectCentral(@NonNull BleDevice central) {
		if (mHandler == null) return;
		post(() -> {
			BluetoothGattServer server = mServer;
			if (server == null) return;
			// A device without link: a connect would open one
			disconnect(server, central.getDevice(), mCentrals.containsKey(central.key()));
		});
	}

	/**
	 * Main thread (receiver of the engine)
	 */
	void onBluetoothOff() {
		if (mHandler == null) return;
		post(() -> {
			// The system dropped the advertising and the server: both start again when Bluetooth is back
			Object handle = mAdvHandle;
			if (handle != null) {
				// Stopped all the same: in BLE_ON mode the stack may keep it on air, and this handle is the only way to stop it
				stopHandle(handle);
				if (!mAdvStartedOnce) {
					// A start without result yet: this is its result
					advertisingFailed(handle, BleError.BLUETOOTH_DISABLED);
				} else {
					handler().removeCallbacksAndMessages(ADVERTISING);
					mAdvHandle = null;
					boolean was = mAdvertising;
					mAdvertising = false;
					if (was) mAdvApp.tell(mAdv, false, BleAdvertiseCallback::onAdvertisingStopped);
				}
			}
			mAdvertiser = null;
			if (mServer != null) {
				if (!mServerStartedOnce) serverFailed(BleError.BLUETOOTH_DISABLED);
				else closeServer();
			}
		});
	}

	void onBluetoothOn() {
		if (mHandler == null) return;
		post(() -> {
			if (mAdvData != null && mAdvHandle == null) postDelayed(() -> advertise(false), ADVERTISING, BLUETOOTH_ON_DELAY_MS);
			if (mServices != null && mServer == null) postDelayed(() -> openServer(false), SERVER, BLUETOOTH_ON_DELAY_MS);
		});
	}

	void quitForTests() {
		HandlerThread thread = mThread;
		if (thread != null) thread.quitSafely();
	}

	/**
	 * The server of the system, read on the thread of the peripheral (tests drive its callback)
	 */
	@androidx.annotation.VisibleForTesting
	@Nullable
	BluetoothGattServer serverForTests() throws InterruptedException {
		BluetoothGattServer[] server = new BluetoothGattServer[1];
		java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
		post(() -> {
			server[0] = mServer;
			read.countDown();
		});
		read.await(3, java.util.concurrent.TimeUnit.SECONDS);
		return server[0];
	}

	// endregion

	// region Advertising (thread of the peripheral)

	/**
	 * @param first Asked by the app (a failure ends it), else a restart after Bluetooth came back
	 */
	private void advertise(boolean first) {
		BleAdvertiseData data = mAdvData;
		if (data == null || mAdv == null || mAdvHandle != null) return;
		if (!mEngine.checkBluetoothEnabled()) {
			if (first) advertisingFailed(null, BleError.BLUETOOTH_DISABLED);
			return;
		}

		BluetoothAdapter adapter = mEngine.adapter();
		BluetoothLeAdvertiser advertiser = null;
		try {
			advertiser = adapter.getBluetoothLeAdvertiser();
		} catch (RuntimeException e) {
			BleLog.w("getBluetoothLeAdvertiser", e);
		}
		if (advertiser == null) {
			advertisingFailed(null, BleError.NOT_SUPPORTED);
			return;
		}
		mAdvertiser = advertiser;

		try {
			if (data.isExtended() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported()) {
				startSet(adapter, advertiser, data);
			} else {
				startLegacy(advertiser, data);
			}
		} catch (SecurityException e) {
			BleLog.w("startAdvertising", e);
			advertisingFailed(mAdvHandle, BleError.PERMISSION_DENIED);
		} catch (IllegalArgumentException e) {
			// AdvertisingSet: data too large for the controller
			BleLog.w("startAdvertising", e);
			advertisingFailed(mAdvHandle, BleError.DATA_TOO_LARGE);
		} catch (RuntimeException e) {
			BleLog.w("startAdvertising", e);
			advertisingFailed(mAdvHandle, BleError.INTERNAL);
		}
	}

	private void startLegacy(@NonNull BluetoothLeAdvertiser advertiser, @NonNull BleAdvertiseData data) {
		// Same values as the system constants
		AdvertiseSettings settings = new AdvertiseSettings.Builder()
				.setAdvertiseMode(data.getMode())
				.setTxPowerLevel(data.getTxPower())
				.setConnectable(data.isConnectable())
				.setTimeout(0)
				.build();
		AdvertiseData scanResponse = data.isIncludeName() ? new AdvertiseData.Builder().setIncludeDeviceName(true).build() : null;
		LegacyCallback callback = new LegacyCallback();
		started(callback);
		advertiser.startAdvertising(settings, systemData(data, false), scanResponse, callback);
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private void startSet(@NonNull BluetoothAdapter adapter, @NonNull BluetoothLeAdvertiser advertiser, @NonNull BleAdvertiseData data) {
		int interval = switch (data.getMode()) {
			case BleAdvertiseData.MODE_LOW_LATENCY -> AdvertisingSetParameters.INTERVAL_LOW;
			case BleAdvertiseData.MODE_LOW_POWER -> AdvertisingSetParameters.INTERVAL_HIGH;
			default -> AdvertisingSetParameters.INTERVAL_MEDIUM;
		};
		int txPower = switch (data.getTxPower()) {
			case BleAdvertiseData.TX_POWER_ULTRA_LOW -> AdvertisingSetParameters.TX_POWER_ULTRA_LOW;
			case BleAdvertiseData.TX_POWER_LOW -> AdvertisingSetParameters.TX_POWER_LOW;
			case BleAdvertiseData.TX_POWER_HIGH -> AdvertisingSetParameters.TX_POWER_HIGH;
			default -> AdvertisingSetParameters.TX_POWER_MEDIUM;
		};
		// Extended and connectable: not scannable, the name goes in the advertising data
		AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder()
				.setLegacyMode(false)
				.setConnectable(data.isConnectable())
				.setScannable(false)
				.setInterval(interval)
				.setTxPowerLevel(txPower)
				.setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
				.setSecondaryPhy(adapter.isLe2MPhySupported() ? BluetoothDevice.PHY_LE_2M : BluetoothDevice.PHY_LE_1M)
				.build();
		SetCallback callback = new SetCallback();
		started(callback);
		advertiser.startAdvertisingSet(parameters, systemData(data, data.isIncludeName()), null, null, null, callback, handler());
	}

	/**
	 * The handle of a start, with its time limit (before the call: the system may answer at once)
	 */
	private void started(@NonNull Object handle) {
		mAdvHandle = handle;
		postDelayed(() -> advertisingTimedOut(handle), ADVERTISING, ADVERTISE_START_TIMEOUT_MS);
	}

	@NonNull
	private static AdvertiseData systemData(@NonNull BleAdvertiseData data, boolean withName) {
		AdvertiseData.Builder b = new AdvertiseData.Builder()
				.setIncludeDeviceName(withName)
				.setIncludeTxPowerLevel(data.isIncludeTxPower());
		for (UUID uuid : data.getServiceUuids()) b.addServiceUuid(new ParcelUuid(uuid));
		for (Map.Entry<UUID, byte[]> e : data.getServiceData().entrySet()) b.addServiceData(new ParcelUuid(e.getKey()), e.getValue());
		for (Map.Entry<Integer, byte[]> e : data.getManufacturerData().entrySet()) b.addManufacturerData(e.getKey(), e.getValue());
		return b.build();
	}

	private static int advertiseError(int code) {
		return switch (code) {
			case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> BleError.DATA_TOO_LARGE;
			case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS, AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> BleError.BUSY;
			case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> BleError.NOT_SUPPORTED;
			default -> BleError.INTERNAL;
		};
	}

	private void advertisingStarted(@NonNull Object handle) {
		if (handle != mAdvHandle) return;
		// The time limit of the start
		handler().removeCallbacksAndMessages(ADVERTISING);
		BleAdvertiseData data = mAdvData;
		mAdvertising = true;
		mAdvStartedOnce = true;
		BleLog.d("advertising started");
		mAdvApp.tell(mAdv, true, BleAdvertiseCallback::onAdvertisingStarted);
		if (data != null && data.getTimeoutMs() > 0) postDelayed(() -> stopAdvertisingNow(true), ADVERTISING, data.getTimeoutMs());
	}

	private void advertisingFailed(@Nullable Object handle, int error) {
		if (handle != mAdvHandle) return;
		BleLog.d("advertising failed: " + BleError.name(error));
		handler().removeCallbacksAndMessages(ADVERTISING);
		Session<BleAdvertiseCallback> s = mAdv;
		mAdvHandle = null;
		mAdvertising = false;
		mAdvData = null;
		mAdv = null;
		mAdvApp.tell(s, true, c -> c.onAdvertisingFailed(error));
	}

	private void advertisingTimedOut(@NonNull Object handle) {
		if (handle != mAdvHandle || mAdvertising) return;
		// Stopped in case it starts late, nothing would stop it then
		stopHandle(handle);
		advertisingFailed(handle, BleError.TIMEOUT);
	}

	/**
	 * @param tell onAdvertisingStopped for the app (timeout), not after stopAdvertising
	 */
	private void stopAdvertisingNow(boolean tell) {
		handler().removeCallbacksAndMessages(ADVERTISING);
		Object handle = mAdvHandle;
		boolean was = mAdvertising;
		Session<BleAdvertiseCallback> s = mAdv;
		mAdvHandle = null;
		mAdvertising = false;
		mAdvData = null;
		mAdv = null;
		stopHandle(handle);
		if (was) BleLog.d("advertising stopped");
		if (tell && was) mAdvApp.tell(s, false, BleAdvertiseCallback::onAdvertisingStopped);
	}

	private void stopHandle(@Nullable Object handle) {
		BluetoothLeAdvertiser advertiser = mAdvertiser;
		if (handle == null || advertiser == null) return;
		try {
			if (handle instanceof AdvertiseCallback c) advertiser.stopAdvertising(c);
			else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && handle instanceof AdvertisingSetCallback c) advertiser.stopAdvertisingSet(c);
		} catch (RuntimeException e) {
			// Bluetooth off meanwhile
			BleLog.w("stopAdvertising", e);
		}
	}

	// Legacy API: called on the main thread
	private final class LegacyCallback extends AdvertiseCallback {
		@Override
		public void onStartSuccess(AdvertiseSettings settingsInEffect) {
			post(() -> advertisingStarted(this));
		}

		@Override
		public void onStartFailure(int errorCode) {
			post(() -> advertisingFailed(this, advertiseError(errorCode)));
		}
	}

	// Called on the thread of the peripheral (Handler given to the system)
	@RequiresApi(Build.VERSION_CODES.O)
	private final class SetCallback extends AdvertisingSetCallback {
		@Override
		public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
			if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) advertisingStarted(this);
			else advertisingFailed(this, advertiseError(status));
		}
	}

	// endregion

	// region Server (thread of the peripheral)

	@NonNull
	private static String key(@NonNull UUID service, @NonNull UUID characteristic) {
		return service + "/" + characteristic;
	}

	/**
	 * A characteristic of the server
	 */
	private static final class Chr {
		@NonNull
		final String key;
		@NonNull
		final UUID service;
		@NonNull
		final BleServerService.Characteristic config;
		@NonNull
		final BluetoothGattCharacteristic system;
		// CCCD values the characteristic allows: 1 notifications, 2 indications
		final int subscribable;

		Chr(@NonNull UUID service, @NonNull BleServerService.Characteristic config, @NonNull BluetoothGattCharacteristic system) {
			this.key = key(service, config.uuid);
			this.service = service;
			this.config = config;
			this.system = system;
			this.subscribable = ((config.properties & BleServerService.NOTIFY) != 0 ? 1 : 0) | ((config.properties & BleServerService.INDICATE) != 0 ? 2 : 0);
		}
	}

	/**
	 * A part of a long write, waiting for the execute
	 */
	private static final class Part {
		@NonNull
		final Chr chr;
		final int offset;
		@NonNull
		final byte[] value;

		Part(@NonNull Chr chr, int offset, @NonNull byte[] value) {
			this.chr = chr;
			this.offset = offset;
			this.value = value;
		}
	}

	/**
	 * An LE link of the phone: a central of this server (announced) once it sent it a request
	 */
	private static final class Central {
		final long key;
		@NonNull
		final BluetoothDevice device;
		@NonNull
		final BleDevice bleDevice;
		int mtu = DEFAULT_MTU;
		boolean announced;
		// CCCD value by characteristic
		final HashMap<Chr, Integer> subscriptions = new HashMap<>();
		// Prepared writes (long writes) waiting for the execute, and their bytes
		final List<Part> prepared = new ArrayList<>();
		int preparedBytes;

		Central(long key, @NonNull BluetoothDevice device) {
			this.key = key;
			this.device = device;
			this.bleDevice = new BleDevice(device);
		}
	}

	/**
	 * @param first Asked by the app (a failure ends it), else a restart after Bluetooth came back
	 */
	private void openServer(boolean first) {
		List<BleServerService> services = mServices;
		if (services == null || mSrv == null || mServer != null) return;
		if (!mEngine.checkBluetoothEnabled()) {
			if (first) serverFailed(BleError.BLUETOOTH_DISABLED);
			return;
		}

		ServerBridge bridge = new ServerBridge();
		BluetoothGattServer server = null;
		try {
			server = mEngine.manager().openGattServer(mEngine.context(), bridge);
		} catch (SecurityException e) {
			BleLog.w("openGattServer", e);
			serverFailed(BleError.PERMISSION_DENIED);
			return;
		} catch (RuntimeException e) {
			BleLog.w("openGattServer", e);
		}
		if (server == null) {
			serverFailed(BleError.INTERNAL);
			return;
		}
		mServer = server;
		mBridge = bridge;
		mChrs.clear();
		mToAdd.clear();
		for (BleServerService s : services) {
			BluetoothGattService system = s.toSystem();
			for (BleServerService.Characteristic c : s.characteristics()) {
				BluetoothGattCharacteristic chr = system.getCharacteristic(c.uuid);
				if (chr != null) {
					Chr entry = new Chr(s.getUuid(), c, chr);
					mChrs.put(entry.key, entry);
				}
			}
			mToAdd.add(system);
		}
		addNextService();
	}

	/**
	 * One service at a time: the next one once the system added the previous one
	 */
	private void addNextService() {
		BluetoothGattServer server = mServer;
		if (server == null) return;
		handler().removeCallbacksAndMessages(SERVER);
		BluetoothGattService service = mToAdd.poll();
		if (service == null) {
			mServerStarted = true;
			mServerStartedOnce = true;
			BleLog.d("server started");
			mServerApp.tell(mSrv, true, BleServerCallback::onServerStarted);
			return;
		}
		boolean added = false;
		try {
			added = server.addService(service);
		} catch (RuntimeException e) {
			BleLog.w("addService", e);
		}
		if (!added) {
			serverFailed(BleError.REFUSED);
			return;
		}
		postDelayed(() -> serverFailed(BleError.TIMEOUT), SERVER, SERVICE_ADD_TIMEOUT_MS);
	}

	private void serverFailed(int error) {
		BleLog.d("server failed: " + BleError.name(error));
		Session<BleServerCallback> s = mSrv;
		mSrv = null;
		mServices = null;
		closeServer();
		mValues.clear();
		mBondedSubscriptions.clear();
		mServerApp.tell(s, true, c -> c.onServerFailed(error));
	}

	private void stopServerNow() {
		// No callback after stopServer
		mSrv = null;
		mServices = null;
		closeServer();
		mValues.clear();
		mBondedSubscriptions.clear();
	}

	/**
	 * Closes the server of the system: the centrals are disconnected, the waiting notifications fail
	 */
	private void closeServer() {
		handler().removeCallbacksAndMessages(SERVER);
		BluetoothGattServer server = mServer;
		mServer = null;
		mBridge = null;
		mServerStarted = false;
		mToAdd.clear();

		List<Central> centrals = new ArrayList<>(mCentrals.values());
		mCentrals.clear();
		mCentralsSnapshot = Collections.emptyList();
		failNotifications(-1, BleError.NOT_CONNECTED);
		// Bluetooth off (the server opens again): given back when they connect again
		if (mServices != null) for (Central c : centrals) rememberSubscriptions(c);

		if (server != null) {
			// The centrals of this server only: the other links of the phone are not its own
			for (Central c : centrals) if (c.announced) disconnect(server, c.device, true);
			try {
				server.close();
			} catch (RuntimeException e) {
				BleLog.w("close", e);
			}
			BleLog.d("server closed");
		}
		Session<BleServerCallback> s = mSrv;
		for (Central c : centrals) if (c.announced) mServerApp.tell(s, false, cb -> cb.onCentralDisconnected(c.bleDevice));
	}

	/**
	 * cancelConnection alone does nothing on a link opened by the central (the server does not hold it, the stack keeps
	 * it): held first with connect, then let go
	 * @param hold The device has a link (else a connect would open one)
	 */
	private static void disconnect(@NonNull BluetoothGattServer server, @NonNull BluetoothDevice device, boolean hold) {
		if (hold) {
			try {
				server.connect(device, false);
			} catch (RuntimeException e) {
				BleLog.w("connect", e);
			}
		}
		try {
			server.cancelConnection(device);
		} catch (RuntimeException e) {
			BleLog.w("cancelConnection", e);
		}
	}

	private void onServiceAdded(@NonNull ServerBridge bridge, int status, @NonNull BluetoothGattService service) {
		if (bridge != mBridge) return;
		if (status != BluetoothGatt.GATT_SUCCESS) {
			BleLog.d("service " + service.getUuid() + " not added: " + status);
			serverFailed(BleError.REFUSED);
			return;
		}
		addNextService();
	}

	/**
	 * The link of a device: the server is told of every LE link of the phone (a watch, a keyboard, the GATT client of
	 * KnBle or of another app) and of their MTU, a link becomes a central of this server at its first request only
	 */
	@NonNull
	private Central link(@NonNull BluetoothDevice device) {
		long key = Utils.macToLong(device.getAddress());
		Central c = mCentrals.get(key);
		if (c == null) {
			c = new Central(key, device);
			mCentrals.put(key, c);
		}
		return c;
	}

	/**
	 * The central of a request (read, write, descriptor, execute): announced at its first one
	 */
	@NonNull
	private Central central(@NonNull BluetoothDevice device) {
		Central c = link(device);
		announce(c);
		return c;
	}

	private void announce(@NonNull Central c) {
		if (c.announced) return;
		c.announced = true;
		updateSnapshot();
		BleDevice central = c.bleDevice;
		BleLog.d("central connected");
		mServerApp.tell(mSrv, false, cb -> cb.onCentralConnected(central));
		// MTU exchanged before its first request
		int mtu = c.mtu;
		if (mtu != DEFAULT_MTU) mServerApp.tell(mSrv, false, cb -> cb.onMtuChanged(central, mtu));
	}

	private void updateSnapshot() {
		List<BleDevice> list = new ArrayList<>();
		for (Central c : mCentrals.values()) if (c.announced) list.add(c.bleDevice);
		mCentralsSnapshot = Collections.unmodifiableList(list);
	}

	private void onCentralState(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int status, int newState) {
		if (bridge != mBridge) return;
		long key = Utils.macToLong(device.getAddress());
		if (newState == BluetoothProfile.STATE_CONNECTED) {
			// Again for a link already known (connect of disconnectCentral)
			if (mCentrals.containsKey(key)) return;
			restoreSubscriptions(link(device));
		} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
			Central c = mCentrals.remove(key);
			if (c == null) return;
			rememberSubscriptions(c);
			failNotifications(key, BleError.NOT_CONNECTED);
			if (!c.announced) return;
			updateSnapshot();
			BleLog.d("central disconnected, status " + status);
			mServerApp.tell(mSrv, false, cb -> cb.onCentralDisconnected(c.bleDevice));
		}
	}

	private static boolean bonded(@NonNull BluetoothDevice device) {
		try {
			return device.getBondState() == BluetoothDevice.BOND_BONDED;
		} catch (RuntimeException e) {
			BleLog.w("getBondState", e);
			return false;
		}
	}

	/**
	 * Core spec (Vol 3 Part G 3.3.3.3): the CCCD of a bonded client keeps its value between connections, such a central
	 * does not subscribe again. Kept for a bonded central, forgotten for another one.
	 */
	private void rememberSubscriptions(@NonNull Central c) {
		mBondedSubscriptions.remove(c.key);
		if (c.subscriptions.isEmpty() || !bonded(c.device)) return;
		HashMap<String, Integer> kept = new HashMap<>();
		for (Map.Entry<Chr, Integer> e : c.subscriptions.entrySet()) kept.put(e.getKey().key, e.getValue());
		mBondedSubscriptions.put(c.key, kept);
		while (mBondedSubscriptions.size() > BONDED_MAX) mBondedSubscriptions.remove(mBondedSubscriptions.keySet().iterator().next());
	}

	/**
	 * A bonded central back: its subscriptions too, and it is a central of this server at once (it may only wait for
	 * the notifications)
	 */
	private void restoreSubscriptions(@NonNull Central c) {
		HashMap<String, Integer> kept = mBondedSubscriptions.remove(c.key);
		if (kept == null || !bonded(c.device)) return;
		for (Map.Entry<String, Integer> e : kept.entrySet()) {
			Chr chr = mChrs.get(e.getKey());
			if (chr != null) c.subscriptions.put(chr, e.getValue());
		}
		if (c.subscriptions.isEmpty()) return;
		announce(c);
		BleDevice central = c.bleDevice;
		for (Chr chr : c.subscriptions.keySet()) {
			mServerApp.tell(mSrv, false, cb -> cb.onSubscriptionChanged(central, chr.service, chr.config.uuid, true));
		}
	}

	@Nullable
	private Chr chr(@Nullable BluetoothGattCharacteristic characteristic) {
		if (characteristic == null || characteristic.getService() == null) return null;
		return mChrs.get(key(characteristic.getService().getUuid(), characteristic.getUuid()));
	}

	private void respond(@NonNull BluetoothDevice device, int requestId, int status, int offset, @Nullable byte[] value) {
		BluetoothGattServer server = mServer;
		if (server == null) return;
		try {
			server.sendResponse(device, requestId, status, offset, value);
		} catch (RuntimeException e) {
			BleLog.w("sendResponse", e);
		}
	}

	private void onRead(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int requestId, int offset, @NonNull BluetoothGattCharacteristic characteristic) {
		if (bridge != mBridge) return;
		Central central = central(device);
		Chr c = chr(characteristic);
		byte[] value = c == null ? null : mValues.get(c.key);
		if (c == null || value == null || !c.config.canRead()) {
			respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
			return;
		}
		if (offset > value.length) {
			respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
			return;
		}
		// The rest is read with the next offsets (read blob)
		int end = Math.min(value.length, offset + central.mtu - 1);
		respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(value, offset, end));
	}

	private void onWrite(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int requestId, @NonNull BluetoothGattCharacteristic characteristic,
						 boolean prepared, boolean responseNeeded, int offset, @NonNull byte[] value) {
		if (bridge != mBridge) return;
		Central central = central(device);
		Chr c = chr(characteristic);
		byte[] current = c == null ? null : mValues.get(c.key);
		if (c == null || current == null || !c.config.canWrite()) {
			if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
			return;
		}
		if (offset + value.length > MAX_VALUE_LENGTH) {
			if (responseNeeded) respond(device, requestId, GATT_INVALID_ATTRIBUTE_LENGTH, offset, null);
			return;
		}
		if (prepared) {
			if (central.prepared.size() >= PREPARED_MAX_PARTS || central.preparedBytes + value.length > PREPARED_MAX_BYTES) {
				if (responseNeeded) respond(device, requestId, GATT_PREPARE_QUEUE_FULL, offset, null);
				return;
			}
			central.prepared.add(new Part(c, offset, value));
			central.preparedBytes += value.length;
			// Echoed: the central checks it
			if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
			return;
		}
		if (offset > current.length) {
			if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
			return;
		}
		byte[] updated = offset == 0 ? value : patch(current, offset, value);
		mValues.put(c.key, updated);
		if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
		deliverWrite(central, c, updated);
	}

	private void onExecuteWrite(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int requestId, boolean execute) {
		if (bridge != mBridge) return;
		Central central = central(device);
		List<Part> parts = new ArrayList<>(central.prepared);
		central.prepared.clear();
		central.preparedBytes = 0;
		if (execute) {
			// Each value rebuilt from its parts (a long write from offset 0 replaces the whole value); a part past the end
			// of its value fails the whole execute, nothing is written (Core spec Vol 3 Part F 3.4.6.3)
			LinkedHashMap<Chr, byte[]> values = new LinkedHashMap<>();
			for (Part p : parts) {
				byte[] base = values.get(p.chr);
				if (base == null) base = p.offset == 0 ? new byte[0] : mValues.get(p.chr.key);
				if (base == null) base = new byte[0];
				if (p.offset > base.length) {
					respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null);
					return;
				}
				values.put(p.chr, patch(base, p.offset, p.value));
			}
			for (Map.Entry<Chr, byte[]> e : values.entrySet()) {
				mValues.put(e.getKey().key, e.getValue());
				deliverWrite(central, e.getKey(), e.getValue());
			}
		}
		respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
	}

	@NonNull
	private static byte[] patch(@NonNull byte[] base, int offset, @NonNull byte[] part) {
		byte[] result = new byte[Math.max(base.length, offset + part.length)];
		System.arraycopy(base, 0, result, 0, base.length);
		System.arraycopy(part, 0, result, offset, part.length);
		return result;
	}

	private void deliverWrite(@NonNull Central central, @NonNull Chr c, @NonNull byte[] value) {
		mServerApp.tell(mSrv, false, cb -> cb.onWrite(central.bleDevice, c.service, c.config.uuid, value));
	}

	private void onDescriptorRead(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int requestId, int offset, @NonNull BluetoothGattDescriptor descriptor) {
		if (bridge != mBridge) return;
		Central central = central(device);
		Chr c = chr(descriptor.getCharacteristic());
		if (c == null || !CCCD.equals(descriptor.getUuid())) {
			respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
			return;
		}
		Integer value = central.subscriptions.get(c);
		byte[] cccd = {(byte) (value == null ? 0 : value), 0};
		if (offset > cccd.length) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
		else respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(cccd, offset, cccd.length));
	}

	private void onDescriptorWrite(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int requestId, @NonNull BluetoothGattDescriptor descriptor,
								   boolean prepared, boolean responseNeeded, int offset, @NonNull byte[] value) {
		if (bridge != mBridge) return;
		Central central = central(device);
		Chr c = chr(descriptor.getCharacteristic());
		if (c == null || !CCCD.equals(descriptor.getUuid()) || prepared || offset != 0) {
			if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
			return;
		}
		int cccd = value.length > 0 ? value[0] & 0xFF : 0;
		if ((cccd & ~c.subscribable) != 0) {
			if (responseNeeded) respond(device, requestId, CCCD_IMPROPERLY_CONFIGURED, offset, null);
			return;
		}
		Integer before = central.subscriptions.get(c);
		if (cccd == 0) central.subscriptions.remove(c);
		else central.subscriptions.put(c, cccd);
		if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);

		boolean subscribed = cccd != 0;
		if ((before != null) != subscribed) {
			mServerApp.tell(mSrv, false, cb -> cb.onSubscriptionChanged(central.bleDevice, c.service, c.config.uuid, subscribed));
		}
	}

	private void onMtuChanged(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int mtu) {
		if (bridge != mBridge) return;
		// Every server gets the MTU of every link: told to the app for a central of this server only (else at its first request)
		Central central = link(device);
		central.mtu = mtu;
		if (central.announced) mServerApp.tell(mSrv, false, cb -> cb.onMtuChanged(central.bleDevice, mtu));
	}

	// endregion

	// region Notifications (thread of the peripheral)

	/**
	 * Result of a notify call: given once every central of the call got its notification (or the first failure)
	 */
	private final class Batch {
		@Nullable
		final BleWriteCallback callback;
		int left;
		int error;

		Batch(@Nullable BleWriteCallback callback, int count) {
			this.callback = callback;
			this.left = count;
		}

		void done(int result) {
			if (result != 0 && error == 0) error = result;
			if (--left > 0 || callback == null) return;
			int e = error;
			if (e == 0) mDispatcher.post(callback::onWriteSuccess);
			else mDispatcher.post(() -> callback.onWriteFailed(e));
		}
	}

	private static final class Notification {
		final long central;
		@NonNull
		final Chr chr;
		@NonNull
		final byte[] value;
		@NonNull
		final Batch batch;
		long busyMs;

		Notification(long central, @NonNull Chr chr, @NonNull byte[] value, @NonNull Batch batch) {
			this.central = central;
			this.chr = chr;
			this.value = value;
			this.batch = batch;
		}
	}

	private void notifyNow(@Nullable BleDevice target, @NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] value, @Nullable BleWriteCallback callback) {
		Chr c = mServer == null ? null : mChrs.get(key(service, characteristic));
		int error = 0;
		if (mServer == null) error = BleError.NOT_CONNECTED;
		else if (c == null) error = BleError.NOT_FOUND;
		else if (c.subscribable == 0) error = BleError.NOT_SUPPORTED;
		if (error != 0) {
			int e = error;
			if (callback != null) mDispatcher.post(() -> callback.onWriteFailed(e));
			return;
		}
		mValues.put(c.key, value);

		List<Central> to = new ArrayList<>();
		for (Central central : mCentrals.values()) {
			if (central.subscriptions.containsKey(c) && (target == null || target.key() == central.key)) to.add(central);
		}
		// Nobody subscribed: nobody gets it
		if (to.isEmpty()) {
			if (callback != null) mDispatcher.post(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
			return;
		}
		Batch batch = new Batch(callback, to.size());
		for (Central central : to) mNotifications.add(new Notification(central.key, c, value, batch));
		pump();
	}

	/**
	 * Sends the next notification when none is in flight
	 */
	private void pump() {
		if (mInFlight != null) return;
		handler().removeCallbacks(mPump);
		Notification n;
		while ((n = mNotifications.peek()) != null) {
			BluetoothGattServer server = mServer;
			Central central = mCentrals.get(n.central);
			Integer cccd = central == null ? null : central.subscriptions.get(n.chr);
			int result;
			if (server == null || cccd == null) result = BleError.NOT_CONNECTED;
			// MTU - 3, and 512 at most whatever the MTU (the stack throws above)
			else if (n.value.length > Math.min(central.mtu - 3, MAX_VALUE_LENGTH)) result = BleError.DATA_TOO_LARGE;
			else {
				// Indications when the central subscribed to those only
				result = send(server, central.device, n.chr.system, (cccd & 1) == 0, n.value);
				if (result == DeviceActor.CALL_OK) {
					mNotifications.poll();
					mInFlight = n;
					Notification sent = n;
					postDelayed(() -> onSent(sent, BleError.TIMEOUT), sent, NOTIFY_TIMEOUT_MS);
					return;
				}
				if (result == DeviceActor.CALL_BUSY) {
					if (n.busyMs < BUSY_BUDGET_MS) {
						long delay = Math.max(2, Math.min(n.busyMs, BUSY_MAX_DELAY_MS));
						n.busyMs += delay;
						handler().postDelayed(mPump, delay);
						return;
					}
					result = BleError.BUSY;
				}
			}
			mNotifications.poll();
			n.batch.done(result);
		}
	}

	@SuppressWarnings("deprecation")
	private static int send(@NonNull BluetoothGattServer server, @NonNull BluetoothDevice device, @NonNull BluetoothGattCharacteristic chr, boolean confirm,
							@NonNull byte[] value) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				return switch (server.notifyCharacteristicChanged(device, chr, confirm, value)) {
					case BluetoothStatusCodes.SUCCESS -> DeviceActor.CALL_OK;
					case BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> DeviceActor.CALL_BUSY;
					case BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> BleError.PERMISSION_DENIED;
					case BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> BleError.BLUETOOTH_DISABLED;
					default -> BleError.REFUSED;
				};
			}
			// Before Android 13 a refusal says nothing about its cause: asked again while it lasts
			chr.setValue(value);
			return server.notifyCharacteristicChanged(device, chr, confirm) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY;
		} catch (SecurityException e) {
			return BleError.PERMISSION_DENIED;
		} catch (RuntimeException e) {
			BleLog.w("notifyCharacteristicChanged", e);
			return BleError.INTERNAL;
		}
	}

	private void onNotificationSent(@NonNull ServerBridge bridge, @NonNull BluetoothDevice device, int status) {
		Notification n = mInFlight;
		if (bridge != mBridge || n == null || n.central != Utils.macToLong(device.getAddress())) return;
		onSent(n, status == BluetoothGatt.GATT_SUCCESS ? 0 : status);
	}

	private void onSent(@NonNull Notification n, int result) {
		if (mInFlight != n) return;
		handler().removeCallbacksAndMessages(n);
		mInFlight = null;
		if (result == BleError.TIMEOUT) BleLog.d("notification: no onNotificationSent");
		n.batch.done(result);
		pump();
	}

	/**
	 * @param central A central, -1 for all
	 */
	private void failNotifications(long central, int error) {
		Notification inFlight = mInFlight;
		if (inFlight != null && (central == -1 || inFlight.central == central)) {
			handler().removeCallbacksAndMessages(inFlight);
			mInFlight = null;
			inFlight.batch.done(error);
		}
		List<Notification> failed = new ArrayList<>();
		for (Notification n : mNotifications) if (central == -1 || n.central == central) failed.add(n);
		mNotifications.removeAll(failed);
		for (Notification n : failed) n.batch.done(error);
		if (mServer != null) pump();
	}

	// endregion

	/**
	 * Callbacks of the server, on binder threads: values copied, everything posted to the thread of the peripheral
	 */
	private final class ServerBridge extends BluetoothGattServerCallback {
		@Override
		public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
			post(() -> onCentralState(this, device, status, newState));
		}

		@Override
		public void onServiceAdded(int status, BluetoothGattService service) {
			post(() -> PeripheralEngine.this.onServiceAdded(this, status, service));
		}

		@Override
		public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
			post(() -> onRead(this, device, requestId, offset, characteristic));
		}

		@Override
		public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite,
												 boolean responseNeeded, int offset, byte[] value) {
			byte[] v = value == null ? new byte[0] : value.clone();
			post(() -> onWrite(this, device, requestId, characteristic, preparedWrite, responseNeeded, offset, v));
		}

		@Override
		public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
			post(() -> PeripheralEngine.this.onDescriptorRead(this, device, requestId, offset, descriptor));
		}

		@Override
		public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite,
											 boolean responseNeeded, int offset, byte[] value) {
			byte[] v = value == null ? new byte[0] : value.clone();
			post(() -> PeripheralEngine.this.onDescriptorWrite(this, device, requestId, descriptor, preparedWrite, responseNeeded, offset, v));
		}

		@Override
		public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
			post(() -> PeripheralEngine.this.onExecuteWrite(this, device, requestId, execute));
		}

		@Override
		public void onNotificationSent(BluetoothDevice device, int status) {
			post(() -> PeripheralEngine.this.onNotificationSent(this, device, status));
		}

		@Override
		public void onMtuChanged(BluetoothDevice device, int mtu) {
			post(() -> PeripheralEngine.this.onMtuChanged(this, device, mtu));
		}
	}
}
