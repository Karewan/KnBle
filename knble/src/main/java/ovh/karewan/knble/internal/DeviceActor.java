package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattConnectionSettings;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.struct.BleConnectionParameters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * One device: its thread, its connection and its GATT queue. Every field is confined to the thread of the actor,
 * except the volatile snapshots read by the getters of KnBle. Nothing here waits: the GATT calls are issued, their
 * answers come back as messages, the watchdog ends what never answers. The app code never runs here, its
 * callbacks go through the Dispatcher.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@SuppressLint("MissingPermission")
public final class DeviceActor {
	static final int CALL_OK = 0;
	static final int CALL_BUSY = Integer.MIN_VALUE;

	/**
	 * A GATT call of an operation: CALL_OK when issued, CALL_BUSY while the stack is busy, or a BleError
	 */
	interface GattCall {
		int call(@NonNull BluetoothGatt gatt);
	}

	private static final int DEFAULT_MTU = 23;
	// A failed attempt (133, 62, 147... from a busy or buggy stack, services not found) is made again after a close(),
	// KnBleSettings.getConnectAttempts() times at most within getConnectTimeout()
	private static final long RETRY_DELAY_MS = 1000;
	// Some stacks fail (133) a connectGatt that comes right after the close() of the previous connection. Learnt once per
	// process: the first such failure makes every later connection wait this long after a close(), no wait otherwise
	private static final long CLOSE_TO_CONNECT_MS = 500;
	private static volatile boolean sWaitAfterClose;

	@androidx.annotation.VisibleForTesting
	static void resetLearntForTests() {
		sWaitAfterClose = false;
	}

	@androidx.annotation.VisibleForTesting
	static boolean waitsAfterClose() {
		return sWaitAfterClose;
	}
	// Services discovered KnBleSettings.getDiscoveryDelay() after the connection, 1600 ms at least when bonded (the
	// encryption and the Service Changed indication first)
	private static final long DISCOVERY_DELAY_BONDED_MS = 1600;
	private static final long DISCOVERY_TIMEOUT_MS = 10000;
	// HCI "connection limit exceeded": the phone refuses one more connection, another attempt is useless
	private static final int STATUS_CONNECTION_LIMIT = 0x09;
	// Pairing started by the stack (status 5, 15 or 137): the user has 30 s, the operation is replayed once bonded
	private static final long BOND_TIMEOUT_MS = 30000, BOND_START_GRACE_MS = 3000, BOND_SETTLE_MS = 200;
	private static final long ENABLE_BLUETOOTH_DELAY_MS = 5000;
	// A busy stack is asked again after 2, 2, 4, 8... up to 50 ms, for 5 s at most
	private static final long BUSY_BUDGET_MS = 5000, BUSY_MAX_DELAY_MS = 50;
	private static final long WATCHDOG_PERIOD_MS = 500;
	// Requests lost by the stack in a row before the connection is considered dead
	private static final int MAX_LOST_REQUESTS = 2;
	// The thread of a disconnected device ends after this long without a new connection
	private static final long RETIRE_AFTER_MS = 10000;
	// Auto-reconnection: direct connections after these delays, then a background connection (autoConnect: the
	// controller connects when the device comes back, no timeout, low power), made again this long after a failure
	// Two direct connections (the device came back at once), then the background connection: it needs no timer of the app,
	// so it goes on while the phone sleeps (the delays of a Handler stop in deep sleep)
	private static final long[] RECONNECT_DELAYS_MS = {1000, 2000};
	private static final long BACKGROUND_RETRY_MS = 5000;
	private static final long BLUETOOTH_ON_RECONNECT_MS = 1000;

	private static Method sRefresh;

	final long macLong;
	@NonNull
	private final BleEngine mEngine;
	@NonNull
	private final Dispatcher mDispatcher;
	@NonNull
	private final HandlerThread mThread;
	@NonNull
	final Handler mHandler;
	@NonNull
	private final Looper mLooper;
	@NonNull
	private final GattBridge mBridge;
	private final AtomicBoolean mRetired = new AtomicBoolean();

	// Snapshots, read by any thread
	@NonNull
	private volatile BleDevice mDevice;
	private volatile int mState = BleGattCallback.DISCONNECTED;
	private volatile int mMtu = DEFAULT_MTU;
	@NonNull
	private volatile List<BluetoothGattService> mServices = Collections.emptyList();
	@Nullable
	private volatile BluetoothGatt mGattSnapshot;
	// Cause of the last failure or disconnection: a GATT/HCI status or a BleError
	private volatile int mLastStatus = BluetoothGatt.GATT_SUCCESS;
	@Nullable
	private volatile BleConnectionParameters mConnectionParameters;

	// Thread of the actor only
	@Nullable
	private BluetoothGatt mGatt;
	// Also read by the PairingAgent (thread of BleEngine)
	@Nullable
	private volatile BleGattCallback mCallback;
	// Token of the current connection: its delayed messages are removed with it
	@Nullable
	private Object mConnection;
	private long mConnectDeadline, mClosedAt;
	// The attempt in progress started within CLOSE_TO_CONNECT_MS of a close()
	private boolean mSoonAfterClose;
	private int mAttempt;
	// ACTION_KEY_MISSING (Android 16+): the device lost its bond, no attempt nor auto-reconnection until a connect() of the
	// app or a new bond
	private boolean mBondLost;
	private final ArrayDeque<GattOp> mQueue = new ArrayDeque<>();
	@Nullable
	private GattOp mCurrent;
	private boolean mInNext, mInGattCall, mWatchdogScheduled, mDestroyWhenDisconnected;
	private int mLostRequests;
	// Operation waiting for the end of a pairing, and the status that started it
	@Nullable
	private GattOp mBondWait;
	private int mBondStatus;
	// The user refused a pairing (BlePairingRequest.cancel): the operations that need one fail, KnBle does not ask again
	private long mPairingCancelledAt;
	private int mPairingAbandonError = BleError.CANCELLED;
	private int mTxPhy = BluetoothDevice.PHY_LE_1M, mRxPhy = BluetoothDevice.PHY_LE_1M;
	// Auto-reconnection asked by the app: the connection is made again after a loss, until disconnect()
	private boolean mAutoReconnect;
	// Connection made by KnBle (auto-reconnection): its failures are not told to the app
	private boolean mReconnecting, mBackgroundConnect;
	// onConnecting was told and no onConnectSuccess / onDisconnected yet: every onConnecting gets exactly one end
	private boolean mAnnounced;
	private int mReconnectRound;
	private final Object mReconnectToken = new Object();
	// Notification subscriptions by characteristic instance (its GATT handle)
	private final SparseArray<BleNotifyCallback> mSubscriptions = new SparseArray<>();

	// Discovery of the services in progress (its timeout is pushed back by a pairing)
	private boolean mDiscovering;
	private final Runnable mDiscoveryTimeout = () -> {
		mDiscovering = false;
		attemptFailed(BleError.TIMEOUT);
	};
	private final Runnable mWatchdog = this::watchdog;
	private final Runnable mRetireCheck = this::retireIfIdle;

	DeviceActor(@NonNull BleEngine engine, @NonNull Dispatcher dispatcher, @NonNull BleDevice device) {
		mEngine = engine;
		mDispatcher = dispatcher;
		mDevice = device;
		macLong = device.getMacLong();
		mThread = new HandlerThread("KnBle-" + device.getMac());
		mThread.start();
		mLooper = mThread.getLooper();
		mHandler = new Handler(mLooper);
		mBridge = new GattBridge(this);
	}

	// region Any thread

	/**
	 * Runs on the thread of the actor: false when the actor ended (the caller uses a new one)
	 */
	boolean submit(@NonNull Runnable r) {
		return !mRetired.get() && mHandler.post(r);
	}

	/**
	 * Its thread ends: a task that still runs here goes to the new actor of the device
	 */
	boolean isRetired() {
		return mRetired.get();
	}

	@NonNull
	public BleDevice device() {
		return mDevice;
	}

	public int state() {
		return mState;
	}

	public int mtu() {
		return mMtu;
	}

	public int lastStatus() {
		return mLastStatus;
	}

	@NonNull
	public List<BluetoothGattService> services() {
		return mServices;
	}

	@Nullable
	public BluetoothGatt gatt() {
		return mGattSnapshot;
	}

	@Nullable
	public BleConnectionParameters connectionParameters() {
		return mConnectionParameters;
	}

	/**
	 * A GATT callback can run its handler right away: on the thread of the actor (the stack delivers there on Android 8+)
	 * and not inside one of our GATT calls (a synchronous stack would re-enter the queue)
	 */
	boolean inline() {
		return Looper.myLooper() == mLooper && !mInGattCall;
	}

	void post(@NonNull Runnable r) {
		mHandler.post(r);
	}

	// endregion

	// region Connection (thread of the actor)

	void connect(@NonNull BleDevice device, @NonNull BleGattCallback callback, boolean autoReconnect) {
		// Submitted just before the end of the thread: the engine gives it to a new actor
		if (mRetired.get()) {
			mEngine.connect(device, callback, autoReconnect);
			return;
		}

		mHandler.removeCallbacks(mRetireCheck);
		mHandler.removeCallbacksAndMessages(mReconnectToken);
		mDestroyWhenDisconnected = false;
		mAutoReconnect = autoReconnect;
		mReconnectRound = 0;
		// The app tries again (paired again meanwhile, maybe)
		mBondLost = false;
		mDevice = device.getName() != null || mDevice.getName() == null ? device : mDevice;
		BleGattCallback replaced = mCallback;
		mCallback = callback;
		// Another callback takes the place of one told onConnecting: that one gets its end, this one its onConnecting
		if (mAnnounced && replaced != null && replaced != callback && mState == BleGattCallback.CONNECTING) {
			mDispatcher.post(() -> replaced.onDisconnected(true, BleError.CANCELLED));
			mDispatcher.post(callback::onConnecting);
		}

		if (mState == BleGattCallback.CONNECTED) {
			List<BluetoothGattService> services = new ArrayList<>(mServices);
			mAnnounced = false;
			mDispatcher.post(() -> callback.onConnectSuccess(services));
			return;
		}
		if (mState == BleGattCallback.CONNECTING) {
			if (!mReconnecting) return;
			// An auto-reconnection in progress (maybe a background one): a direct connection of the app takes its place
			stopReconnection();
		}
		startConnection(false);
	}

	/**
	 * A connection: of the app (told with onConnecting, its failure with onDisconnected), or an auto-reconnection
	 */
	private void startConnection(boolean reconnection) {
		BleGattCallback callback = mCallback;
		mState = BleGattCallback.CONNECTING;
		mReconnecting = reconnection;
		mBackgroundConnect = reconnection && mReconnectRound > RECONNECT_DELAYS_MS.length;
		// Told once per connection (an auto-reconnection at its first round, not again after Bluetooth comes back)
		if (callback != null && !mAnnounced) {
			mAnnounced = true;
			mDispatcher.post(callback::onConnecting);
		}
		mConnection = new Object();
		mConnectDeadline = mBackgroundConnect ? Long.MAX_VALUE : SystemClock.elapsedRealtime() + mEngine.settings().getConnectTimeout();
		mAttempt = 1;
		mLastStatus = BluetoothGatt.GATT_SUCCESS;
		mLostRequests = 0;
		ensureWatchdog();

		if (!mEngine.isBluetoothEnabled()) {
			BleLog.d("connect: bluetooth is off");
			// An auto-reconnection never turns Bluetooth back on (the user may just have turned it off): it waits for it
			if (reconnection) {
				mConnection = null;
				mState = BleGattCallback.DISCONNECTED;
				mReconnecting = mBackgroundConnect = false;
				return;
			}
			if (!mEngine.enableBluetooth(true)) {
				mLastStatus = BleError.BLUETOOTH_DISABLED;
				linkDown(true);
				return;
			}
			// Time for the adapter to start
			postDelayed(this::openGatt, mConnection, ENABLE_BLUETOOTH_DELAY_MS);
			return;
		}

		long wait = mClosedAt == 0 || !sWaitAfterClose ? 0 : mClosedAt + CLOSE_TO_CONNECT_MS - SystemClock.elapsedRealtime();
		if (wait > 0) postDelayed(this::openGatt, mConnection, wait);
		else openGatt();
	}

	@SuppressWarnings("deprecation")
	private void openGatt() {
		if (mState != BleGattCallback.CONNECTING) return;
		mSoonAfterClose = mClosedAt != 0 && SystemClock.elapsedRealtime() - mClosedAt < CLOSE_TO_CONNECT_MS;

		BluetoothGatt gatt = null;
		try {
			BluetoothDevice device = mDevice.getDevice();
			// autoConnect only for the background round of an auto-reconnection (much slower otherwise). Android 13+: the
			// stack calls back on this thread, with the values
			boolean auto = mBackgroundConnect;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
				// No automatic MTU exchange: the MTU is the one asked by the app (requestMtu, requestFastTransfer), in one
				// exchange like on the previous versions of Android (with it, the device saw a second exchange)
				BluetoothGattConnectionSettings settings = new BluetoothGattConnectionSettings.Builder()
						.setTransport(BluetoothDevice.TRANSPORT_LE)
						.setAutoConnectEnabled(auto)
						.setAutomaticMtuEnabled(false)
						.build();
				gatt = device.connectGatt(settings, mHandler::post, mBridge);
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				gatt = device.connectGatt(mEngine.context(), auto, mBridge, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, mHandler);
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				// Android 8 to 12: no Handler. Android 8.0 sets the value of the characteristic on its binder thread then
				// posts the callback with a Handler, so a later notification could overwrite it before it is read (fixed in
				// 8.1, Nordic avoids the Handler on API 26 too). One path up to API 32, where the value is not a parameter
				// yet: called on the binder thread, GattBridge copies it at once, then posts to this thread (as on Android 7)
				gatt = device.connectGatt(mEngine.context(), auto, mBridge, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, null);
			} else {
				gatt = device.connectGatt(mEngine.context(), auto, mBridge, BluetoothDevice.TRANSPORT_LE);
			}
		} catch (SecurityException e) {
			BleLog.w("connectGatt", e);
			mLastStatus = BleError.PERMISSION_DENIED;
			linkDown(true);
			return;
		} catch (RuntimeException e) {
			BleLog.w("connectGatt", e);
		}

		if (gatt == null) {
			attemptFailed(BleError.INTERNAL);
			return;
		}
		mGatt = gatt;
		mGattSnapshot = gatt;
	}

	/**
	 * A connection attempt failed: closed, made again a bit later while attempts and time remain
	 */
	private void attemptFailed(int status) {
		mDiscovering = false;
		mLastStatus = status;
		if (mState != BleGattCallback.CONNECTING) return;
		if (status == BluetoothGatt.GATT_FAILURE || status == 133) {
			if (mSoonAfterClose && !sWaitAfterClose) {
				sWaitAfterClose = true;
				BleLog.d("this phone needs " + CLOSE_TO_CONNECT_MS + " ms between a close() and a connection");
			}
		}

		boolean again = !mBondLost && mAttempt < mEngine.settings().getConnectAttempts() && status != STATUS_CONNECTION_LIMIT
				&& SystemClock.elapsedRealtime() + RETRY_DELAY_MS < mConnectDeadline && mEngine.checkBluetoothEnabled();
		BleLog.d("connection attempt " + mAttempt + " failed: " + BleError.name(status) + (again ? ", again in " + RETRY_DELAY_MS + " ms" : ""));
		if (!again) {
			linkDown(true);
			return;
		}

		mAttempt++;
		if (mConnection != null) mHandler.removeCallbacksAndMessages(mConnection);
		mConnection = new Object();
		closeGatt();
		postDelayed(this::openGatt, mConnection, RETRY_DELAY_MS);
	}

	void onConnectionStateChange(@NonNull BluetoothGatt gatt, int status, int newState) {
		if (KnBle.DEBUG) BleLog.d("onConnectionStateChange status=" + status + " newState=" + newState + (gatt != mGatt ? " (old connection)" : ""));
		if (gatt != mGatt) return;

		if (newState == BluetoothProfile.STATE_CONNECTED) {
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (mState == BleGattCallback.CONNECTING) attemptFailed(status);
				else {
					mLastStatus = status;
					linkDown(false);
				}
				return;
			}
			if (mState != BleGattCallback.CONNECTING || mConnection == null) return;
			if (mBackgroundConnect) {
				// The device came back: the discovery gets the usual time
				mBackgroundConnect = false;
				mConnectDeadline = SystemClock.elapsedRealtime() + mEngine.settings().getConnectTimeout();
				ensureWatchdog();
			}

			long delay = mEngine.settings().getDiscoveryDelay();
			try {
				if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) delay = Math.max(delay, DISCOVERY_DELAY_BONDED_MS);
			} catch (RuntimeException e) {
				BleLog.w("getBondState", e);
			}
			postDelayed(this::discoverServices, mConnection, delay);
		} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
			if (mState == BleGattCallback.CONNECTING) attemptFailed(status);
			else {
				mLastStatus = status;
				linkDown(false);
			}
		}
	}

	private void discoverServices() {
		BluetoothGatt gatt = mGatt;
		if (gatt == null || mState != BleGattCallback.CONNECTING) return;

		boolean started = false;
		mInGattCall = true;
		try {
			started = gatt.discoverServices();
		} catch (RuntimeException e) {
			BleLog.w("discoverServices", e);
		} finally {
			mInGattCall = false;
		}
		if (!started) attemptFailed(BleError.REFUSED);
		else if (mConnection != null) {
			mDiscovering = true;
			postDelayed(mDiscoveryTimeout, mConnection, DISCOVERY_TIMEOUT_MS);
		}
	}

	void onServicesDiscovered(@NonNull BluetoothGatt gatt, int status) {
		if (KnBle.DEBUG) BleLog.d("onServicesDiscovered status=" + status);
		if (gatt != mGatt) return;
		mDiscovering = false;

		if (status != BluetoothGatt.GATT_SUCCESS) {
			if (mState == BleGattCallback.CONNECTING) attemptFailed(status);
			else if (mCurrent instanceof GattOps.Rediscover op) {
				op.fail(status);
				finish(op);
			}
			return;
		}

		List<BluetoothGattService> services;
		try {
			services = Collections.unmodifiableList(new ArrayList<>(gatt.getServices()));
		} catch (RuntimeException e) {
			BleLog.w("getServices", e);
			services = Collections.emptyList();
		}
		if (mState == BleGattCallback.CONNECTING) {
			// Some stacks end a discovery with an empty table (GAP and GATT are always there): a new attempt, after a refresh
			if (services.isEmpty()) {
				attemptFailed(BleError.NOT_FOUND);
				return;
			}
			if (mConnection != null) mHandler.removeCallbacksAndMessages(mConnection);
			mServices = services;
			mState = BleGattCallback.CONNECTED;
			mLastStatus = BluetoothGatt.GATT_SUCCESS;
			mLostRequests = 0;
			if (mReconnecting) BleLog.d("auto-reconnection done");
			mReconnecting = false;
			mReconnectRound = 0;
			mAnnounced = false;
			fetchSystemName();
			BleGattCallback callback = mCallback;
			// A copy the app may change (3.x gave the list of the stack)
			List<BluetoothGattService> delivered = new ArrayList<>(services);
			if (callback != null) mDispatcher.post(() -> callback.onConnectSuccess(delivered));
			next();
		} else {
			mServices = services;
			if (mCurrent instanceof GattOps.Rediscover op) {
				// The handles may have changed: the subscriptions must be made again by the app
				dropSubscriptions();
				if (op.done()) {
					BleLog.d("services rediscovered after a Service Changed indication");
					BleGattCallback callback = mCallback;
					List<BluetoothGattService> changed = new ArrayList<>(services);
					if (callback != null) mDispatcher.post(() -> callback.onServicesChanged(changed));
				}
				finish(op);
			}
		}
	}

	/**
	 * Name known by the system, read here once (a binder call) for a device created from its MAC address
	 */
	private void fetchSystemName() {
		BleDevice device = mDevice;
		if (device.getName() != null) return;
		try {
			String name = device.getDevice().getName();
			if (name != null) mDevice = device.withName(name);
		} catch (RuntimeException e) {
			BleLog.w("getName", e);
		}
	}

	void onServiceChanged(@NonNull BluetoothGatt gatt) {
		if (gatt != mGatt || mState != BleGattCallback.CONNECTED) return;
		mQueue.addFirst(new GattOps.Rediscover(mDispatcher));
		next();
	}

	public void setCallback(@Nullable BleGattCallback callback) {
		mCallback = callback;
	}

	/**
	 * Any thread
	 */
	@Nullable
	BleGattCallback callback() {
		return mCallback;
	}

	public void requestConnectionPriority(int priority) {
		BluetoothGatt gatt = mGatt;
		if (gatt == null) return;
		try {
			gatt.requestConnectionPriority(priority);
		} catch (RuntimeException e) {
			BleLog.w("requestConnectionPriority", e);
		}
	}

	/**
	 * @param destroy the thread ends right after the disconnection
	 */
	public void disconnect(boolean destroy) {
		mDestroyWhenDisconnected |= destroy;
		mAutoReconnect = false;
		mHandler.removeCallbacksAndMessages(mReconnectToken);
		if (mState == BleGattCallback.DISCONNECTED && mGatt == null) {
			// Between two rounds of an auto-reconnection: the app already got onDisconnected for the loss, and gets the end
			// of the reconnection when its onConnecting was told
			BleGattCallback callback = mCallback;
			if (mAnnounced && callback != null) mDispatcher.post(() -> callback.onDisconnected(true, BleError.CANCELLED));
			mAnnounced = false;
			mCallback = null;
			if (mDestroyWhenDisconnected) retire();
			else {
				mHandler.removeCallbacks(mRetireCheck);
				mHandler.postDelayed(mRetireCheck, RETIRE_AFTER_MS);
			}
			return;
		}
		boolean connecting = mState != BleGattCallback.CONNECTED;
		mLastStatus = connecting ? BleError.CANCELLED : BluetoothGatt.GATT_SUCCESS;
		linkDown(connecting);
	}

	/**
	 * Ends the operation in progress (an OTA at its next packet) and the waiting ones, with BleError.CANCELLED
	 */
	public void cancel() {
		GattOp current = mCurrent;
		// The rediscovery after a Service Changed is not an operation of the app: it goes on (the subscriptions depend on it)
		if (current != null && !(current instanceof GattOps.Rediscover)) {
			mCurrent = null;
			mBondWait = null;
			mHandler.removeCallbacksAndMessages(current);
			current.fail(BleError.CANCELLED);
		}
		ArrayDeque<GattOp> kept = new ArrayDeque<>();
		for (GattOp op; (op = mQueue.poll()) != null; ) {
			if (op instanceof GattOps.Rediscover) kept.add(op);
			else op.fail(BleError.CANCELLED);
		}
		mQueue.addAll(kept);
		if (mCurrent == null) next();
	}

	/**
	 * End of the connection, whatever the cause: every operation fails, the GATT client is closed
	 */
	private void linkDown(boolean connectFailed) {
		if (KnBle.DEBUG) BleLog.d("linkDown connectFailed=" + connectFailed);
		mDiscovering = false;
		if (mConnection != null) mHandler.removeCallbacksAndMessages(mConnection);
		mConnection = null;

		GattOp current = mCurrent;
		mCurrent = null;
		mBondWait = null;
		if (current != null) {
			mHandler.removeCallbacksAndMessages(current);
			current.fail(BleError.NOT_CONNECTED);
		}
		for (GattOp op; (op = mQueue.poll()) != null; ) op.fail(BleError.NOT_CONNECTED);
		mSubscriptions.clear();
		closeGatt();

		boolean wasActive = mState != BleGattCallback.DISCONNECTED;
		boolean silent = mReconnecting;
		mState = BleGattCallback.DISCONNECTED;
		mReconnecting = mBackgroundConnect = false;
		mMtu = DEFAULT_MTU;
		mServices = Collections.emptyList();
		mTxPhy = mRxPhy = BluetoothDevice.PHY_LE_1M;
		mLostRequests = 0;
		mConnectionParameters = null;

		BleGattCallback callback = mCallback;
		boolean reconnect = mAutoReconnect && !mDestroyWhenDisconnected && !mBondLost && callback != null;
		if (!reconnect) mCallback = null;
		int status = bondLostStatus(mLastStatus);
		// A round of an auto-reconnection is silent, unless the reconnection ends here (disconnect, destroy): its onConnecting
		// then gets its end
		boolean tell = wasActive && callback != null && (!silent || (!reconnect && mAnnounced));
		if (tell || !reconnect) mAnnounced = false;
		if (tell) mDispatcher.post(() -> callback.onDisconnected(connectFailed, status));

		if (reconnect) scheduleReconnection();
		else if (mDestroyWhenDisconnected) retire();
		else {
			mHandler.removeCallbacks(mRetireCheck);
			mHandler.postDelayed(mRetireCheck, RETIRE_AFTER_MS);
		}
	}

	/**
	 * Next round of the auto-reconnection, when Bluetooth is on (else at onBluetoothOn)
	 */
	private void scheduleReconnection() {
		mHandler.removeCallbacksAndMessages(mReconnectToken);
		if (!mEngine.isBluetoothEnabled()) {
			BleLog.d("auto-reconnection: waiting for bluetooth");
			return;
		}
		// The first background round at once, the next ones after a failure of the background connection
		long delay = mReconnectRound < RECONNECT_DELAYS_MS.length ? RECONNECT_DELAYS_MS[mReconnectRound]
				: mReconnectRound == RECONNECT_DELAYS_MS.length ? 0 : BACKGROUND_RETRY_MS;
		mReconnectRound++;
		BleLog.d("auto-reconnection round " + mReconnectRound + " in " + delay + " ms" + (mReconnectRound > RECONNECT_DELAYS_MS.length ? " (background)" : ""));
		postDelayed(this::reconnect, mReconnectToken, delay);
	}

	private void reconnect() {
		if (!mAutoReconnect || mCallback == null || mState != BleGattCallback.DISCONNECTED || mGatt != null) return;
		startConnection(true);
	}

	/**
	 * Ends an auto-reconnection round without telling the app
	 */
	private void stopReconnection() {
		if (mConnection != null) mHandler.removeCallbacksAndMessages(mConnection);
		mConnection = null;
		closeGatt();
		mState = BleGattCallback.DISCONNECTED;
		mReconnecting = mBackgroundConnect = false;
	}

	/**
	 * Bluetooth turning off: the connection ends (an auto-reconnection waits for Bluetooth to come back)
	 */
	void onBluetoothOff() {
		mHandler.removeCallbacksAndMessages(mReconnectToken);
		if (mState == BleGattCallback.DISCONNECTED && mGatt == null) {
			// Between two rounds of an auto-reconnection
			endAnnounced(BleError.BLUETOOTH_DISABLED);
			return;
		}
		mLastStatus = BleError.BLUETOOTH_DISABLED;
		boolean reconnecting = mReconnecting;
		linkDown(mState != BleGattCallback.CONNECTED);
		// A round of the auto-reconnection, silent in linkDown
		if (reconnecting) endAnnounced(BleError.BLUETOOTH_DISABLED);
	}

	/**
	 * An auto-reconnection told with onConnecting gets its end (as a connected link does when Bluetooth turns off): a
	 * new onConnecting when Bluetooth is back, as for Bluetooth classic
	 */
	private void endAnnounced(int status) {
		BleGattCallback callback = mCallback;
		if (!mAnnounced || callback == null) return;
		mAnnounced = false;
		mDispatcher.post(() -> callback.onDisconnected(true, status));
	}

	void onBluetoothOn() {
		if (!mAutoReconnect || mCallback == null || mState != BleGattCallback.DISCONNECTED || mGatt != null) return;
		BleLog.d("bluetooth on: auto-reconnection");
		mHandler.removeCallbacksAndMessages(mReconnectToken);
		// First round: told to the app with onConnecting
		mReconnectRound = 1;
		postDelayed(this::reconnect, mReconnectToken, BLUETOOTH_ON_RECONNECT_MS);
	}

	private void closeGatt() {
		BluetoothGatt gatt = mGatt;
		mGatt = null;
		mGattSnapshot = null;
		if (gatt == null) return;

		// Before close: the next connection discovers the services again (a reboot in DFU mode changes them)
		if (mEngine.settings().isRefreshOnDisconnect()) refresh(gatt);
		try {
			gatt.close();
		} catch (RuntimeException e) {
			BleLog.w("close", e);
		}
		mClosedAt = SystemClock.elapsedRealtime();
	}

	@SuppressWarnings({"JavaReflectionMemberAccess"})
	private static void refresh(@NonNull BluetoothGatt gatt) {
		try {
			if (sRefresh == null) sRefresh = BluetoothGatt.class.getMethod("refresh");
			sRefresh.invoke(gatt);
		} catch (Exception e) {
			BleLog.w("refresh", e);
		}
	}

	private void retireIfIdle() {
		if (mState == BleGattCallback.DISCONNECTED && mGatt == null && mCurrent == null && mQueue.isEmpty() && !mAutoReconnect) retire();
	}

	/**
	 * The thread ends: the messages already queued still run (a late connect goes to a new actor)
	 */
	private void retire() {
		if (!mRetired.compareAndSet(false, true)) return;
		BleLog.d("thread of the device ended");
		mEngine.onRetired(this);
		mThread.quitSafely();
	}

	// endregion

	// region GATT queue (thread of the actor)

	void enqueue(@NonNull GattOp op) {
		// An auto-reconnection may last: the operation fails now, the app retries on onConnectSuccess
		if (mState == BleGattCallback.DISCONNECTED || mReconnecting) {
			op.fail(BleError.NOT_CONNECTED);
			return;
		}
		// While connecting, the operation waits for the services
		mQueue.add(op);
		next();
	}

	private void next() {
		if (mInNext) return;
		mInNext = true;
		try {
			GattOp op;
			while (mCurrent == null && mState == BleGattCallback.CONNECTED && mGatt != null && (op = mQueue.poll()) != null) {
				mCurrent = op;
				op.timeout = op.timeoutMs(mEngine.settings().getOperationTimeout());
				op.deadline = SystemClock.elapsedRealtime() + op.timeout;
				ensureWatchdog();
				try {
					op.start(this, mGatt);
				} catch (SecurityException e) {
					op.fail(BleError.PERMISSION_DENIED);
					finish(op);
				} catch (RuntimeException e) {
					BleLog.w("start", e);
					op.fail(BleError.INTERNAL);
					finish(op);
				}
			}
		} finally {
			mInNext = false;
		}
	}

	/**
	 * End of the current operation (its result already given to the app)
	 */
	void finish(@NonNull GattOp op) {
		if (op != mCurrent) return;
		mHandler.removeCallbacksAndMessages(op);
		mCurrent = null;
		mLostRequests = 0;
		next();
	}

	/**
	 * Issues a GATT call of the current operation, again later while the stack is busy
	 */
	void issue(@NonNull GattOp op, @NonNull GattCall call) {
		attempt(op, call, 0);
	}

	private void attempt(@NonNull GattOp op, @NonNull GattCall call, long waitedMs) {
		BluetoothGatt gatt = mGatt;
		if (op != mCurrent || gatt == null) return;

		int result;
		op.awaiting = false;
		mInGattCall = true;
		try {
			result = call.call(gatt);
		} catch (SecurityException e) {
			result = BleError.PERMISSION_DENIED;
		} catch (RuntimeException e) {
			BleLog.w("GATT call", e);
			result = BleError.INTERNAL;
		} finally {
			mInGattCall = false;
		}

		if (result == CALL_OK) {
			// Its answer is awaited: an answer before this (a late one of a previous request) is not for it
			op.awaiting = true;
			return;
		}
		if (result == CALL_BUSY && waitedMs < BUSY_BUDGET_MS) {
			long delay = Math.max(2, Math.min(waitedMs, BUSY_MAX_DELAY_MS));
			if (KnBle.DEBUG) BleLog.d("stack busy, again in " + delay + " ms");
			postDelayed(() -> attempt(op, call, waitedMs + delay), op, delay);
			return;
		}

		op.fail(result == CALL_BUSY ? BleError.BUSY : result);
		if (result == CALL_BUSY) {
			// Busy for the whole budget: a request of the stack never answered (Android keeps its busy flag until the answer)
			int lost = mLostRequests + 1;
			finish(op);
			mLostRequests = lost;
			if (lost >= MAX_LOST_REQUESTS && mState == BleGattCallback.CONNECTED) {
				mLastStatus = BleError.TIMEOUT;
				linkDown(false);
			}
			return;
		}
		finish(op);
	}

	/**
	 * Delayed message removed with its token (an operation or a connection)
	 */
	void cancelDelayed(@NonNull Object token) {
		mHandler.removeCallbacksAndMessages(token);
	}

	void postDelayed(@NonNull Runnable r, @NonNull Object token, long delayMs) {
		Message m = Message.obtain(mHandler, r);
		m.obj = token;
		mHandler.sendMessageDelayed(m, delayMs);
	}

	private void ensureWatchdog() {
		if (mWatchdogScheduled) return;
		mWatchdogScheduled = true;
		mHandler.postDelayed(mWatchdog, WATCHDOG_PERIOD_MS);
	}

	/**
	 * Ends what the stack never answered: a connection, an operation
	 */
	private void watchdog() {
		mWatchdogScheduled = false;
		long now = SystemClock.elapsedRealtime();

		if (mState == BleGattCallback.CONNECTING && now > mConnectDeadline) {
			BleLog.d("connection timeout");
			mLastStatus = BleError.TIMEOUT;
			linkDown(true);
			return;
		}

		GattOp op = mCurrent;
		if (op != null && now > op.deadline) {
			if (KnBle.DEBUG) BleLog.d("timeout of " + op.getClass().getSimpleName());
			mCurrent = null;
			mHandler.removeCallbacksAndMessages(op);
			if (op == mBondWait) {
				// Not paired in time (the user did not answer): not a lost request
				mBondWait = null;
				op.fail(mBondStatus);
			} else if (op.onTimeout() && ++mLostRequests >= MAX_LOST_REQUESTS) {
				// The stack keeps losing the requests: the connection is dead
				mLastStatus = BleError.TIMEOUT;
				linkDown(false);
				return;
			}
			next();
		}

		// No wake-up during a background connection (it may last hours)
		if ((mState == BleGattCallback.CONNECTING && !mBackgroundConnect) || mCurrent != null) ensureWatchdog();
	}

	// endregion

	// region Pairing started by the stack (thread of the actor)

	/**
	 * Status of an operation that needs a bond (insufficient authentication or encryption, authentication failure): the
	 * stack starts the pairing by itself. The operation waits for its end, then runs once more.
	 * @return true when the operation waits (its result comes later), false when it must fail with this status
	 */
	boolean awaitBond(@NonNull GattOp op, int status) {
		if ((status != 5 && status != 15 && status != 137) || op.bondReplayed || op != mCurrent) return false;
		op.bondReplayed = true;
		mHandler.removeCallbacksAndMessages(op);
		if (mPairingCancelledAt != 0 && SystemClock.elapsedRealtime() - mPairingCancelledAt < BOND_TIMEOUT_MS) {
			// The user just refused the pairing (or Android refused the code of the app): not asked again
			int error = mPairingAbandonError;
			postDelayed(() -> {
				if (op != mCurrent) return;
				op.fail(error);
				finish(op);
			}, op, 0);
			return true;
		}
		mBondWait = op;
		mBondStatus = status;
		op.deadline = SystemClock.elapsedRealtime() + BOND_TIMEOUT_MS;

		int bond = bondState();
		BleLog.d("status " + status + ": waiting for the pairing (bond state " + bond + ")");
		// Already bonded: the stack just paired, or (Android 17+) it repairs a bond lost by the device without changing
		// the state first. The replay waits for the end of a repair a while there
		if (bond == BluetoothDevice.BOND_BONDED) {
			postDelayed(() -> replay(op), op, Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN ? BOND_START_GRACE_MS : BOND_SETTLE_MS);
		}
		else if (bond == BluetoothDevice.BOND_NONE) postDelayed(() -> pairingNotStarted(op), op, BOND_START_GRACE_MS);
		return true;
	}

	/**
	 * Android pairs this device (bond state BONDING, or a pairing request shown to the user): the operation in progress,
	 * whose answer waits for the end of the pairing (the stack retries it with encryption), gets the time of a pairing
	 */
	void onPairingActivity() {
		long deadline = SystemClock.elapsedRealtime() + BOND_TIMEOUT_MS;
		// A pairing asked by the device right after the connection: some stacks hold the discovery until its end
		if (mState == BleGattCallback.CONNECTING && !mBackgroundConnect && mConnection != null) {
			if (mConnectDeadline < deadline) mConnectDeadline = deadline;
			if (mDiscovering) {
				mHandler.removeCallbacks(mDiscoveryTimeout, mConnection);
				postDelayed(mDiscoveryTimeout, mConnection, BOND_TIMEOUT_MS);
			}
			BleLog.d("pairing in progress: the connection waits for it");
			return;
		}
		GattOp op = mCurrent;
		if (op == null) return;
		if (op.deadline < deadline) {
			op.deadline = deadline;
			BleLog.d("pairing in progress: " + op.getClass().getSimpleName() + " waits for it");
		}
	}

	/**
	 * The status an end tells: BOND_LOST once the bond is lost, unless the app or Bluetooth ended it
	 */
	private int bondLostStatus(int status) {
		if (!mBondLost || status == BluetoothGatt.GATT_SUCCESS || status == BleError.CANCELLED || status == BleError.BLUETOOTH_DISABLED) return status;
		return BleError.BOND_LOST;
	}

	/**
	 * ACTION_KEY_MISSING (Android 16+): the device, bonded, could not give its keys (reset, bond removed on its side,
	 * spoofed address). The connection in progress fails or ends by itself; KnBle makes no other attempt, ends an
	 * auto-reconnection (it would fail again, or ask the user again), fails the operation waiting for the bond.
	 */
	void onBondLost(int reason) {
		BleGattCallback callback = mCallback;
		boolean reconnecting = mAutoReconnect && callback != null;
		if (mState == BleGattCallback.DISCONNECTED && !reconnecting) return;
		BleLog.d("bond lost (reason " + reason + "): no other attempt");
		mBondLost = true;
		mHandler.removeCallbacksAndMessages(mReconnectToken);
		if (callback != null) mDispatcher.post(() -> callback.onBondLost(reason));
		GattOp op = mBondWait;
		if (op != null && op == mCurrent) {
			mBondWait = null;
			op.fail(BleError.BOND_LOST);
			finish(op);
		}
		if (mState == BleGattCallback.CONNECTING && mReconnecting) {
			// A round of the auto-reconnection: its onConnecting gets its end now
			mLastStatus = BleError.BOND_LOST;
			linkDown(true);
		} else if (mState == BleGattCallback.DISCONNECTED) {
			// Between two rounds
			endAnnounced(BleError.BOND_LOST);
			mCallback = null;
			mHandler.removeCallbacks(mRetireCheck);
			mHandler.postDelayed(mRetireCheck, RETIRE_AFTER_MS);
		}
	}

	/**
	 * ACTION_BOND_STATE_CHANGED of this device
	 */
	void onBondStateChanged(int state) {
		if (state == BluetoothDevice.BOND_BONDING) onPairingActivity();
		// Paired after all (createBond of the app, or the re-pairing of the system after a lost bond): the operations that
		// need the bond may ask for it again
		else if (state == BluetoothDevice.BOND_BONDED) {
			mPairingCancelledAt = 0;
			mBondLost = false;
		}
		GattOp op = mBondWait;
		if (op == null || op != mCurrent) return;
		if (state == BluetoothDevice.BOND_BONDED) {
			mHandler.removeCallbacksAndMessages(op);
			postDelayed(() -> replay(op), op, BOND_SETTLE_MS);
		} else if (state == BluetoothDevice.BOND_NONE) {
			BleLog.d("pairing failed");
			mBondWait = null;
			op.fail(mBondStatus);
			finish(op);
		}
	}

	/**
	 * The app cancelled the pairing (BlePairingRequest.cancel, error CANCELLED), or Android refused its code: the
	 * operation waiting for it fails at once with the error, KnBle does not ask for the pairing again for a while
	 */
	void onPairingAbandoned(int error) {
		mPairingCancelledAt = SystemClock.elapsedRealtime();
		mPairingAbandonError = error;
		GattOp op = mBondWait;
		if (op == null || op != mCurrent) return;
		BleLog.d("pairing abandoned: " + BleError.name(error));
		mBondWait = null;
		op.fail(error);
		finish(op);
	}

	/**
	 * Still no pairing a while after the status: the stack of this phone did not start it, asked here
	 */
	private void pairingNotStarted(@NonNull GattOp op) {
		if (op != mBondWait || bondState() != BluetoothDevice.BOND_NONE) return;
		boolean started = false;
		try {
			started = mDevice.getDevice().createBond();
		} catch (RuntimeException e) {
			BleLog.w("createBond", e);
		}
		BleLog.d("pairing not started by the stack, createBond: " + started);
		if (started) return;
		mBondWait = null;
		op.fail(mBondStatus);
		finish(op);
	}

	private void replay(@NonNull GattOp op) {
		BluetoothGatt gatt = mGatt;
		if (op != mCurrent || op != mBondWait || gatt == null) return;
		mBondWait = null;
		op.refreshDeadline();
		BleLog.d("bonded: " + op.getClass().getSimpleName() + " replayed");
		try {
			op.replay(this, gatt);
		} catch (SecurityException e) {
			op.fail(BleError.PERMISSION_DENIED);
			finish(op);
		} catch (RuntimeException e) {
			BleLog.w("replay", e);
			op.fail(BleError.INTERNAL);
			finish(op);
		}
	}

	private int bondState() {
		try {
			return mDevice.getDevice().getBondState();
		} catch (RuntimeException e) {
			BleLog.w("getBondState", e);
			return BluetoothDevice.BOND_NONE;
		}
	}

	// endregion

	// region GATT events (thread of the actor)

	void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
		GattOp op = mCurrent;
		if (gatt == mGatt && op != null) op.onCharacteristicRead(this, characteristic, value, status);
	}

	void onCharacteristicWrite(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
		GattOp op = mCurrent;
		if (gatt == mGatt && op != null) op.onCharacteristicWrite(this, characteristic, status);
	}

	void onDescriptorRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, @NonNull byte[] value, int status) {
		GattOp op = mCurrent;
		if (gatt == mGatt && op != null) op.onDescriptorRead(this, descriptor, value, status);
	}

	void onDescriptorWrite(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status) {
		GattOp op = mCurrent;
		if (gatt == mGatt && op != null) op.onDescriptorWrite(this, descriptor, status);
	}

	void onMtuChanged(@NonNull BluetoothGatt gatt, int mtu, int status) {
		if (gatt != mGatt) return;
		// Also when asked by the device or done by the stack itself
		if (status == BluetoothGatt.GATT_SUCCESS) mMtu = mtu;
		GattOp op = mCurrent;
		if (op != null) op.onMtuChanged(this, mtu, status);
	}

	void onPhy(@NonNull BluetoothGatt gatt, int txPhy, int rxPhy, int status, boolean read) {
		if (gatt != mGatt) return;
		// On a refusal the stack gives the requested values, not the ones in use
		if (status == BluetoothGatt.GATT_SUCCESS) {
			mTxPhy = txPhy;
			mRxPhy = rxPhy;
		}
		GattOp op = mCurrent;
		if (op != null) op.onPhy(this, txPhy, rxPhy, status, read);
	}

	void onReliableWriteCompleted(@NonNull BluetoothGatt gatt, int status) {
		GattOp op = mCurrent;
		if (gatt == mGatt && op != null) op.onReliableWriteCompleted(this, status);
	}

	void onConnectionUpdated(@NonNull BluetoothGatt gatt, int interval, int latency, int timeout, int status) {
		if (gatt != mGatt || status != BluetoothGatt.GATT_SUCCESS) return;
		BleConnectionParameters parameters = new BleConnectionParameters(interval, latency, timeout);
		mConnectionParameters = parameters;
		if (KnBle.DEBUG) BleLog.d("connection parameters: " + parameters);
		BleGattCallback callback = mCallback;
		if (callback != null && mState == BleGattCallback.CONNECTED) mDispatcher.post(() -> callback.onConnectionParametersChanged(parameters));
	}

	void onReadRemoteRssi(@NonNull BluetoothGatt gatt, int rssi, int status) {
		GattOp op = mCurrent;
		if (gatt == mGatt && op != null) op.onRssi(this, rssi, status);
	}

	void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
		if (gatt != mGatt) return;
		// A protocol run by KnBle (TI OAD) answers right here, on this thread
		GattOp op = mCurrent;
		if (op != null && op.onNotification(this, characteristic, value)) return;
		BleNotifyCallback callback = mSubscriptions.get(characteristic.getInstanceId());
		if (callback != null && callback != GattOps.NOTIFY_SINK) mDispatcher.post(() -> callback.onNotify(value));
	}

	// endregion

	// region For the operations (thread of the actor)

	int txPhy() {
		return mTxPhy;
	}

	int rxPhy() {
		return mRxPhy;
	}

	void subscribe(@NonNull BluetoothGattCharacteristic characteristic, @NonNull BleNotifyCallback callback) {
		mSubscriptions.put(characteristic.getInstanceId(), callback);
	}

	boolean isSubscribed(@NonNull BluetoothGattCharacteristic characteristic) {
		return mSubscriptions.get(characteristic.getInstanceId()) != null;
	}

	@Nullable
	BleNotifyCallback subscription(@NonNull BluetoothGattCharacteristic characteristic) {
		return mSubscriptions.get(characteristic.getInstanceId());
	}

	@Nullable
	BleNotifyCallback unsubscribe(@NonNull BluetoothGattCharacteristic characteristic) {
		int key = characteristic.getInstanceId();
		BleNotifyCallback callback = mSubscriptions.get(key);
		mSubscriptions.remove(key);
		return callback;
	}

	/**
	 * Subscriptions lost (services changed): each callback is told, the app subscribes again
	 */
	private void dropSubscriptions() {
		for (int i = 0; i < mSubscriptions.size(); i++) {
			BleNotifyCallback callback = mSubscriptions.valueAt(i);
			mDispatcher.post(callback::onNotifyDisabled);
		}
		mSubscriptions.clear();
	}

	// endregion

	// region Tests

	/**
	 * Holds the thread of the device until the latch: the GATT callbacks pile up meanwhile
	 */
	@androidx.annotation.VisibleForTesting
	boolean runOnThread(@NonNull Runnable r) {
		return mHandler.post(r);
	}

	// endregion
}
