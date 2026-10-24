package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.KnBleSettings;
import ovh.karewan.knble.Utils;
import ovh.karewan.knble.interfaces.BleBondCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleStateListener;
import ovh.karewan.knble.struct.BleAdapterInfo;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The single internal singleton behind KnBle: Bluetooth adapter, thread of the engine (scan), one DeviceActor per
 * device in use and the Dispatcher of the app callbacks. Every public method returns at once: it only reads snapshots
 * or posts to a thread of KnBle. The threads start with the first scan or connection.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@SuppressLint("MissingPermission")
public final class BleEngine {
	private static volatile BleEngine sInstance;
	// BluetoothDevice.ACTION_KEY_MISSING (Android 16) and EXTRA_BOND_LOSS_REASON (Android 16 QPR2)
	@VisibleForTesting
	static final String ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING";
	@VisibleForTesting
	static final String EXTRA_BOND_LOSS_REASON = "android.bluetooth.device.extra.BOND_LOSS_REASON";
	// A pairing asked by the app: the user has this long to answer
	private static final long BOND_TIMEOUT_MS = 60000;
	// Hidden extra of ACTION_BOND_STATE_CHANGED: why a pairing failed (logs)
	private static final String EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON";

	@NonNull
	private final Context mContext;
	@NonNull
	private final BluetoothManager mManager;
	@NonNull
	private final BluetoothAdapter mAdapter;
	@NonNull
	private final KnBleSettings mSettings;
	@NonNull
	private final Dispatcher mDispatcher;
	@NonNull
	private final ScanEngine mScan;
	@NonNull
	private final PeripheralEngine mPeripheral;
	@NonNull
	private final ClassicEngine mClassic;
	@NonNull
	private final PairingAgent mPairing;
	private final ConcurrentHashMap<Long, DeviceActor> mActors = new ConcurrentHashMap<>();
	private volatile boolean mBluetoothOn;
	private final CopyOnWriteArrayList<BleStateListener> mStateListeners = new CopyOnWriteArrayList<>();
	// Last state given to the listeners (main thread)
	private boolean mNotifiedOn;
	// Bonded devices with the name known by the system, kept by ACTION_BOND_STATE_CHANGED: null until loaded (at init when
	// Bluetooth is on, else at the first use). Written on the thread of the engine only, in the order of the broadcasts
	@Nullable
	private volatile ConcurrentHashMap<Long, BleDevice> mBonded;
	private final Set<Long> mBonding = ConcurrentHashMap.newKeySet();
	// Pairings asked by the app (thread of the engine)
	private final HashMap<Long, BondRequest> mBondRequests = new HashMap<>();
	// LE features of the adapter, last read with Bluetooth on (Android gives them only then)
	@Nullable
	private volatile BleAdapterInfo.LeFeatures mLeFeatures;
	// Thread of the engine, made on first use
	@Nullable
	private volatile Handler mHandler;
	@Nullable
	private HandlerThread mThread;

	// On the main thread: a field written, the rest posted to the threads of KnBle
	private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
			mBluetoothOn = state == BluetoothAdapter.STATE_ON;
			Handler h = mHandler;
			if (state == BluetoothAdapter.STATE_ON) {
				notifyState(true);
				if (h != null) {
					h.post(mScan::onBluetoothOn);
					// Loaded with Bluetooth on only (the list is empty while it is off)
					if (mBonded == null) h.post(BleEngine.this::loadBonds);
					if (mLeFeatures == null) h.post(BleEngine.this::readLeFeatures);
				}
				for (DeviceActor a : mActors.values()) a.submit(a::onBluetoothOn);
				mPeripheral.onBluetoothOn();
				mClassic.onBluetoothOn();
				return;
			}
			if (state != BluetoothAdapter.STATE_TURNING_OFF && state != BluetoothAdapter.STATE_OFF) return;

			BleLog.d("bluetooth turning off");
			notifyState(false);
			if (h != null) h.post(mScan::onBluetoothOff);
			for (DeviceActor a : mActors.values()) a.submit(a::onBluetoothOff);
			mPeripheral.onBluetoothOff();
			mClassic.onBluetoothOff();
		}
	};

	// Pairings (those the stack starts when an operation needs a bond): to the thread of the device
	private final BroadcastReceiver mBondReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null) return;
			if (ACTION_KEY_MISSING.equals(intent.getAction())) {
				onKeyMissing(intent);
				return;
			}
			if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
			BluetoothDevice device = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
					? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
					: deviceExtra(intent);
			if (device == null) return;
			int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
			int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
			long key = Utils.macToLong(device.getAddress());
			DeviceActor a = mActors.get(key);
			if (KnBle.DEBUG) BleLog.d("bond state " + previous + " -> " + state + (state == BluetoothDevice.BOND_NONE ? " reason " + intent.getIntExtra(EXTRA_UNBOND_REASON, -1) : ""));
			Handler bh = mHandler;
			if (bh != null) bh.post(() -> updateBond(key, device, state));
			if (a != null) a.submit(() -> a.onBondStateChanged(state));
			mClassic.onBondStateChanged(key, state);
			Handler h = mHandler;
			if (h != null) h.post(() -> onBondEvent(key, state, previous));
		}

		/**
		 * Android 16+: a bonded device could not give its keys. Its connection (BLE, classic) stops trying.
		 */
		private void onKeyMissing(@NonNull Intent intent) {
			BluetoothDevice device = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
					? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
					: deviceExtra(intent);
			if (device == null) return;
			int reason = intent.getIntExtra(EXTRA_BOND_LOSS_REASON, 0);
			long key = Utils.macToLong(device.getAddress());
			BleLog.d("key missing, reason " + reason);
			DeviceActor a = mActors.get(key);
			if (a != null) a.submit(() -> a.onBondLost(reason));
			mClassic.onBondLost(key, reason);
		}

		@SuppressWarnings("deprecation")
		@Nullable
		private BluetoothDevice deviceExtra(@NonNull Intent intent) {
			return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
		}
	};

	private BleEngine(@NonNull Context context, @NonNull BluetoothManager manager, @NonNull BluetoothAdapter adapter, @NonNull KnBleSettings settings) {
		mContext = context;
		mManager = manager;
		mAdapter = adapter;
		mSettings = settings;
		Looper callbacks = settings.getCallbackLooper();
		mDispatcher = new Dispatcher(callbacks != null ? callbacks : Looper.getMainLooper());
		mScan = new ScanEngine(this, mDispatcher);
		mPeripheral = new PeripheralEngine(this, mDispatcher);
		mClassic = new ClassicEngine(this, mDispatcher);
		mPairing = new PairingAgent(this);
		mBluetoothOn = adapter.isEnabled();
		mNotifiedOn = mBluetoothOn;
		context.registerReceiver(mStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
		IntentFilter bonds = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		// Android 16+ (BLUETOOTH_CONNECT): a bonded device lost its keys
		if (Build.VERSION.SDK_INT >= 36) bonds.addAction(ACTION_KEY_MISSING);
		context.registerReceiver(mBondReceiver, bonds);
	}

	/**
	 * @return false when the device has no Bluetooth LE
	 */
	public static synchronized boolean init(@NonNull Context context, @Nullable KnBleSettings settings) {
		if (sInstance != null) return true;
		KnBleSettings s = settings != null ? settings : new KnBleSettings.Builder().build();
		BleLog.setLogger(s.getLogger());
		if (s.isDebug()) KnBle.DEBUG = true;

		// The application context: KnBle lives as long as the process
		Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
		if (!app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			BleLog.d("init failed: no Bluetooth LE");
			return false;
		}
		BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
		if (adapter == null) {
			BleLog.d("init failed: no Bluetooth adapter");
			return false;
		}

		BleEngine engine = new BleEngine(app, manager, adapter, s);
		sInstance = engine;
		// The bonds of the system read once off the main thread: isBonded, getBondedDevices then make no binder call
		if (engine.mBluetoothOn) {
			engine.handler().post(engine::loadBonds);
			engine.handler().post(engine::readLeFeatures);
		}
		BleLog.d("init done");
		return true;
	}

	@Nullable
	public static BleEngine get() {
		return sInstance;
	}

	// region State

	@NonNull
	public Context context() {
		return mContext;
	}

	@NonNull
	public BluetoothManager manager() {
		return mManager;
	}

	@NonNull
	public BluetoothAdapter adapter() {
		return mAdapter;
	}

	@NonNull
	public KnBleSettings settings() {
		return mSettings;
	}

	@NonNull
	public Dispatcher dispatcher() {
		return mDispatcher;
	}

	@NonNull
	public ScanEngine scan() {
		return mScan;
	}

	@NonNull
	public PeripheralEngine peripheral() {
		return mPeripheral;
	}

	@NonNull
	public ClassicEngine classic() {
		return mClassic;
	}

	/**
	 * Thread of the engine, started on first use
	 */
	@NonNull
	Handler handler() {
		Handler h = mHandler;
		if (h != null) return h;
		synchronized (this) {
			if (mHandler == null) {
				HandlerThread thread = new HandlerThread("KnBle");
				thread.start();
				mThread = thread;
				mHandler = new Handler(thread.getLooper());
			}
			return mHandler;
		}
	}

	boolean hasThread() {
		return mHandler != null;
	}

	/**
	 * Kept up to date by ACTION_STATE_CHANGED: no binder call (old Android versions do one per isEnabled())
	 */
	public boolean isBluetoothEnabled() {
		return mBluetoothOn;
	}

	/**
	 * Asks the adapter (binder call): threads of KnBle only, before a scan or a connection
	 */
	boolean checkBluetoothEnabled() {
		try {
			mBluetoothOn = mAdapter.isEnabled();
		} catch (RuntimeException e) {
			BleLog.w("isEnabled", e);
		}
		return mBluetoothOn;
	}

	/**
	 * Binder calls: read again while Bluetooth is on (LE Audio may be turned on or off in the developer options), else
	 * the last ones read
	 * @param on Bluetooth is on now
	 */
	@Nullable
	private BleAdapterInfo.LeFeatures leFeatures(boolean on) {
		if (!on) return mLeFeatures;
		BleAdapterInfo.LeFeatures f = BleAdapterInfo.readLeFeatures(mAdapter);
		// Turned off meanwhile: the values may be false, the previous ones are kept
		if (!enabledNow(mAdapter)) return mLeFeatures;
		mLeFeatures = f;
		return f;
	}

	/**
	 * Thread of the engine: kept for the times Bluetooth is off
	 */
	private void readLeFeatures() {
		leFeatures(enabledNow(mAdapter));
	}

	private static boolean enabledNow(@Nullable BluetoothAdapter adapter) {
		if (adapter == null) return false;
		try {
			return adapter.isEnabled();
		} catch (RuntimeException e) {
			BleLog.w("isEnabled", e);
			return false;
		}
	}

	/**
	 * Any thread (binder calls)
	 */
	@NonNull
	public BleAdapterInfo adapterInfo() {
		// Not checkBluetoothEnabled: any thread here, mBluetoothOn stays the one of the receiver
		boolean on = enabledNow(mAdapter);
		return BleAdapterInfo.read(mContext, mAdapter, on, leFeatures(on), mPairing.canSetPin());
	}

	/**
	 * Without init (KnBle.getAdapterInfo): the LE features when Bluetooth is on now
	 */
	@NonNull
	public static BleAdapterInfo adapterInfo(@NonNull Context context) {
		BleEngine e = sInstance;
		if (e != null) return e.adapterInfo();
		Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
		BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
		boolean on = enabledNow(adapter);
		BleAdapterInfo.LeFeatures f = on ? BleAdapterInfo.readLeFeatures(adapter) : null;
		return BleAdapterInfo.read(app, adapter, on, f, PairingAgent.probeSetPin(app, adapter));
	}

	/**
	 * Main thread: each listener once per change (STATE_TURNING_OFF then STATE_OFF give one "off")
	 */
	private void notifyState(boolean on) {
		if (on == mNotifiedOn) return;
		mNotifiedOn = on;
		for (BleStateListener l : mStateListeners) {
			mDispatcher.post(() -> {
				if (mStateListeners.contains(l)) l.onBluetoothStateChanged(on);
			});
		}
	}

	public void addStateListener(@NonNull BleStateListener listener) {
		mStateListeners.addIfAbsent(listener);
	}

	public void removeStateListener(@NonNull BleStateListener listener) {
		mStateListeners.remove(listener);
	}

	/**
	 * Android 12 and below only (refused by Android 13+)
	 */
	@SuppressWarnings("deprecation")
	public boolean enableBluetooth(boolean enable) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false;
		try {
			return enable ? mAdapter.enable() : mAdapter.disable();
		} catch (RuntimeException e) {
			BleLog.w("enable", e);
			return false;
		}
	}

	// endregion

	// region Devices

	@Nullable
	public DeviceActor actor(@NonNull BleDevice device) {
		return mActors.get(device.key());
	}

	@Nullable
	DeviceActor actor(long key) {
		return mActors.get(key);
	}

	public int state(@NonNull BleDevice device) {
		DeviceActor a = actor(device);
		return a == null ? BleGattCallback.DISCONNECTED : a.state();
	}

	@NonNull
	public List<BleDevice> connectedDevices() {
		List<BleDevice> devices = new ArrayList<>();
		for (DeviceActor a : mActors.values()) if (a.state() == BleGattCallback.CONNECTED) devices.add(a.device());
		return devices;
	}

	public void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback, boolean autoReconnect) {
		ensurePairingAgent();
		for (int attempt = 0; attempt < 3; attempt++) {
			DeviceActor a = mActors.computeIfAbsent(device.key(), k -> new DeviceActor(this, mDispatcher, device));
			if (a.submit(() -> a.connect(device, callback, autoReconnect))) return;
			// The thread of this actor just ended
			mActors.remove(device.key(), a);
		}
		mDispatcher.post(() -> callback.onDisconnected(true, BleError.INTERNAL));
	}

	/**
	 * Operation of the GATT queue of the device, failed at once when it is not connected
	 */
	public void enqueue(@NonNull BleDevice device, @NonNull GattOp op) {
		DeviceActor a = actor(device);
		if (a == null || !a.submit(() -> {
			// The actor ended meanwhile (destroyDevice then connect): the new one of the device gets the operation
			if (a.isRetired()) enqueue(device, op);
			else a.enqueue(op);
		})) op.fail(BleError.NOT_CONNECTED);
	}

	/**
	 * Runs on the thread of the device, when there is one
	 */
	public void onDevice(@NonNull BleDevice device, @NonNull DeviceTask task) {
		DeviceActor a = actor(device);
		if (a != null) a.submit(() -> {
			if (a.isRetired()) onDevice(device, task);
			else task.run(a);
		});
	}

	public interface DeviceTask {
		void run(@NonNull DeviceActor actor);
	}

	public void disconnectAll(boolean destroy) {
		for (DeviceActor a : mActors.values()) a.submit(() -> a.disconnect(destroy));
	}

	void onRetired(@NonNull DeviceActor actor) {
		mActors.remove(actor.macLong, actor);
	}

	// endregion

	// region Bonds

	/**
	 * From the cache, no binder call (the first use asks the system once and loads the cache on the thread of the engine)
	 */
	public int bondState(@NonNull BleDevice device) {
		ConcurrentHashMap<Long, BleDevice> bonded = mBonded;
		long key = device.key();
		if (bonded != null) {
			if (bonded.containsKey(key)) return BluetoothDevice.BOND_BONDED;
			return mBonding.contains(key) ? BluetoothDevice.BOND_BONDING : BluetoothDevice.BOND_NONE;
		}
		handler().post(this::loadBonds);
		return systemBondState(device);
	}

	@NonNull
	public List<BleDevice> bondedDevices() {
		ConcurrentHashMap<Long, BleDevice> bonded = mBonded;
		List<BleDevice> devices = new ArrayList<>();
		if (bonded != null) {
			devices.addAll(bonded.values());
			return devices;
		}
		handler().post(this::loadBonds);
		try {
			Set<BluetoothDevice> system = mAdapter.getBondedDevices();
			if (system != null) for (BluetoothDevice d : system) devices.add(new BleDevice(d));
		} catch (RuntimeException e) {
			BleLog.w("getBondedDevices", e);
		}
		return devices;
	}

	/**
	 * Binder call: thread of the engine only
	 */
	private static int deviceType(@NonNull BleDevice device) {
		try {
			return device.getDevice().getType();
		} catch (RuntimeException e) {
			BleLog.w("getType", e);
			return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
		}
	}

	private int systemBondState(@NonNull BleDevice device) {
		try {
			return device.getDevice().getBondState();
		} catch (RuntimeException e) {
			BleLog.w("getBondState", e);
			return BluetoothDevice.BOND_NONE;
		}
	}

	/**
	 * Thread of the engine, Bluetooth on only (the system gives an empty list while it is off)
	 */
	private void loadBonds() {
		if (mBonded != null || !checkBluetoothEnabled()) return;
		try {
			Set<BluetoothDevice> system = mAdapter.getBondedDevices();
			if (system == null) return;
			ConcurrentHashMap<Long, BleDevice> bonded = new ConcurrentHashMap<>();
			for (BluetoothDevice d : system) bonded.put(Utils.macToLong(d.getAddress()), named(d));
			mBonded = bonded;
			BleLog.d(bonded.size() + " bonded devices");
		} catch (RuntimeException e) {
			// Permission missing: loaded at the next use
			BleLog.w("getBondedDevices", e);
		}
	}

	/**
	 * Thread of the engine, in the order of the broadcasts: the cache follows the system. Not loaded yet: the load reads
	 * the state of the system after this change.
	 */
	private void updateBond(long key, @NonNull BluetoothDevice device, int state) {
		ConcurrentHashMap<Long, BleDevice> bonded = mBonded;
		if (bonded == null) return;
		if (state == BluetoothDevice.BOND_BONDING) mBonding.add(key);
		else mBonding.remove(key);
		if (state == BluetoothDevice.BOND_BONDED) bonded.put(key, named(device));
		else bonded.remove(key);
	}

	/**
	 * With the name known by the system (a binder call: thread of the engine only)
	 */
	@NonNull
	private static BleDevice named(@NonNull BluetoothDevice device) {
		String name = null;
		try {
			name = device.getName();
		} catch (RuntimeException e) {
			BleLog.w("getName", e);
		}
		return new BleDevice(device).withName(name);
	}

	/**
	 * Name of a device known by KnBle (bonded, or connected), no binder call
	 */
	@Nullable
	public String knownName(@NonNull BleDevice device) {
		ConcurrentHashMap<Long, BleDevice> bonded = mBonded;
		BleDevice b = bonded == null ? null : bonded.get(device.key());
		if (b != null && b.getName() != null) return b.getName();
		DeviceActor a = actor(device);
		return a == null ? null : a.device().getName();
	}

	/**
	 * A pairing asked by the app (thread of the engine), read by the PairingAgent
	 */
	static final class BondRequest {
		final List<BleBondCallback> callbacks = new ArrayList<>();
		// Code given in advance (legacy PIN or BLE passkey), null: asked to the callbacks, else the dialog of the system
		@Nullable
		String pin;
		// Over BLE (a passkey), else Bluetooth classic (a PIN)
		boolean le;
		// Started by createBond here (not one already running)
		boolean started;
	}

	/**
	 * The receiver of the pairing requests, before the first pairing KnBle may run (any thread)
	 */
	void ensurePairingAgent() {
		handler().post(mPairing::register);
	}

	/**
	 * Thread of the engine
	 */
	@Nullable
	BondRequest bondRequest(long key) {
		return mBondRequests.get(key);
	}

	/**
	 * @param pin Code given when Android asks for one, may be null
	 */
	public void createBond(@NonNull BleDevice device, @Nullable String pin, @NonNull BleBondCallback callback) {
		handler().post(() -> {
			mPairing.register();
			startBond(device, pin, callback);
		});
	}

	/**
	 * Thread of the engine: the pairing of the system (its dialog, or none with "Just Works"), followed until bonded,
	 * failed or BOND_TIMEOUT_MS
	 */
	private void startBond(@NonNull BleDevice device, @Nullable String pin, @NonNull BleBondCallback callback) {
		long key = device.key();
		BondRequest pending = mBondRequests.get(key);
		if (pending != null) {
			pending.callbacks.add(callback);
			if (pending.pin == null) pending.pin = pin;
			return;
		}
		int state = systemBondState(device);
		if (state == BluetoothDevice.BOND_BONDED) {
			mDispatcher.post(callback::onBonded);
			return;
		}

		BondRequest request = new BondRequest();
		request.callbacks.add(callback);
		request.pin = pin;
		// Transport: the one KnBle uses with the device (a classic connection, a BLE one), else its type, given explicitly
		// on Android 17+. Else (before 17, a dual mode device not in use, a device of unknown type created from its MAC)
		// the choice of the stack: TRANSPORT_AUTO, LE for a BLE only device or one connected over BLE, else BR/EDR
		int type = deviceType(device);
		DeviceActor actor = mActors.get(key);
		boolean leLink = actor != null && actor.state() != BleGattCallback.DISCONNECTED;
		ClassicLink link = mClassic.link(key);
		boolean classicLink = link != null && link.state() != BleGattCallback.DISCONNECTED;
		int transport;
		if (classicLink) transport = BluetoothDevice.TRANSPORT_BREDR;
		else if (leLink || type == BluetoothDevice.DEVICE_TYPE_LE) transport = BluetoothDevice.TRANSPORT_LE;
		else if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC) transport = BluetoothDevice.TRANSPORT_BREDR;
		else transport = BluetoothDevice.TRANSPORT_AUTO;
		// AUTO: BR/EDR for a dual mode or unknown device without BLE link
		request.le = transport == BluetoothDevice.TRANSPORT_LE;
		mBondRequests.put(key, request);
		if (state != BluetoothDevice.BOND_BONDING) {
			boolean started;
			try {
				started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && transport != BluetoothDevice.TRANSPORT_AUTO
						? device.getDevice().createBond(transport)
						: device.getDevice().createBond();
			} catch (SecurityException e) {
				BleLog.w("createBond", e);
				endBond(key, BleError.PERMISSION_DENIED);
				return;
			} catch (RuntimeException e) {
				BleLog.w("createBond", e);
				started = false;
			}
			if (!started) {
				// Bonded meanwhile, else refused by the stack
				endBond(key, systemBondState(device) == BluetoothDevice.BOND_BONDED ? 0 : BleError.REFUSED);
				return;
			}
			request.started = true;
		}
		Message m = Message.obtain(handler(), () -> endBond(key, BleError.TIMEOUT));
		m.obj = request;
		handler().sendMessageDelayed(m, BOND_TIMEOUT_MS);
	}

	/**
	 * Thread of the engine
	 */
	private void onBondEvent(long key, int state, int previous) {
		mPairing.onBondStateChanged(key, state);
		if (!mBondRequests.containsKey(key)) return;
		if (state == BluetoothDevice.BOND_BONDED) endBond(key, 0);
		else if (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING) endBond(key, BleError.BOND_FAILED);
	}

	/**
	 * Thread of the engine: the callbacks of the pairing asked by the app get its result
	 */
	void endBond(long key, int error) {
		BondRequest request = mBondRequests.remove(key);
		if (request == null) return;
		handler().removeCallbacksAndMessages(request);
		for (BleBondCallback callback : request.callbacks) {
			if (error == 0) mDispatcher.post(callback::onBonded);
			else mDispatcher.post(() -> callback.onBondFailed(error));
		}
	}

	/**
	 * Removes the bond on the phone side: public API for an associated device (Android 16+), else the hidden method of
	 * the system (available on every version)
	 */
	@SuppressWarnings("JavaReflectionMemberAccess")
	public void removeBond(@NonNull BleDevice device) {
		handler().post(() -> {
			if (Companion.removeBond(mContext, device)) {
				BleLog.d("removeBond: done by the companion device manager");
				return;
			}
			try {
				Method m = BluetoothDevice.class.getMethod("removeBond");
				Object removed = m.invoke(device.getDevice());
				BleLog.d("removeBond: " + removed);
			} catch (Exception e) {
				BleLog.w("removeBond", e);
			}
		});
	}

	// endregion

	// region Tests

	/**
	 * Runs on the thread of the device (tests: holding it lets the GATT callbacks pile up)
	 */
	@VisibleForTesting
	public boolean runOnDeviceThread(@NonNull BleDevice device, @NonNull Runnable r) {
		DeviceActor a = actor(device);
		return a != null && a.runOnThread(r);
	}

	@VisibleForTesting
	@NonNull
	PairingAgent pairingForTests() {
		return mPairing;
	}

	/**
	 * A new engine for the next test
	 */
	@VisibleForTesting
	public static synchronized void resetForTests() {
		BleEngine e = sInstance;
		sInstance = null;
		KnBle.DEBUG = false;
		BleLog.setLogger(null);
		DeviceActor.resetLearntForTests();
		if (e == null) return;
		try {
			e.mContext.unregisterReceiver(e.mStateReceiver);
			e.mContext.unregisterReceiver(e.mBondReceiver);
		} catch (RuntimeException ignored) {
		}
		for (DeviceActor a : e.mActors.values()) a.submit(() -> a.disconnect(true));
		HandlerThread thread = e.mThread;
		if (thread != null) thread.quitSafely();
		e.mPairing.unregisterForTests();
		e.mPeripheral.quitForTests();
		e.mClassic.quitForTests();
	}

	// endregion
}
