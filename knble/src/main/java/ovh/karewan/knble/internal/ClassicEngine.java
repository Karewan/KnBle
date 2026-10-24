package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.ClassicOptions;
import ovh.karewan.knble.ClassicServerOptions;
import ovh.karewan.knble.Utils;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicDiscoveryCallback;
import ovh.karewan.knble.interfaces.BleClassicServerCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Bluetooth classic (BR/EDR): discovery, RFCOMM connections of the app and of the clients of its server (ClassicServer). Their state lives on the thread "KnBle-classic", which
 * never blocks. Each connection has two threads of its own for the calls of its socket, the only blocking calls of
 * KnBle: one connects then writes, in order, the other reads. Nothing waits for them: a connection ends by the close()
 * of its socket, which unblocks both.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@SuppressLint("MissingPermission")
public final class ClassicEngine {
	// The system ends an inquiry after about 12 s: past this, the discovery ends without its broadcast
	private static final long DISCOVERY_WATCHDOG_MS = 30000;
	// cancelDiscovery() is asynchronous: a new discovery waits this long at most for the end of the cancelled one
	private static final long CANCEL_WAIT_MS = 2000;

	@NonNull
	private final BleEngine mEngine;
	@NonNull
	private final Dispatcher mDispatcher;
	private final ConcurrentHashMap<Long, ClassicLink> mLinks = new ConcurrentHashMap<>();
	@NonNull
	private final ClassicServer mServer;
	@Nullable
	private volatile Handler mHandler;
	@Nullable
	private HandlerThread mThread;
	private volatile boolean mDiscovering;
	// elapsedRealtime of the last discovery of KnBle (running or ended), 0 for none
	private volatile long mLastDiscoveryAt;
	// elapsedRealtime of the last cancelDiscovery() of KnBle not followed by DISCOVERY_FINISHED yet, 0 for none
	private volatile long mCancelledAt;

	// Discovery (thread of the engine)
	@Nullable
	private BleClassicDiscoveryCallback mDiscoveryCallback;
	private final LinkedHashMap<Long, BleDevice> mFound = new LinkedHashMap<>();
	// The discovery started by this session began: a DISCOVERY_FINISHED before it is the one of the previous discovery
	private boolean mDiscoveryStarted, mReceiverRegistered;
	// Asked while the discovery KnBle just cancelled still runs: started at its DISCOVERY_FINISHED, which is not its end
	private boolean mStartWaiting;
	private final Object mDiscoveryToken = new Object();

	// On the thread of the engine (registered with its Handler)
	private final BroadcastReceiver mDiscoveryReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent == null ? null : intent.getAction();
			if (action == null) return;
			if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				onDiscoveryEnded();
				return;
			}
			if (mDiscoveryCallback == null) return;
			switch (action) {
				case BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
					if (!mStartWaiting) mDiscoveryStarted = true;
				}
				case BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> onFound(intent, BluetoothDevice.ACTION_FOUND.equals(action));
				default -> {}
			}
		}
	};

	ClassicEngine(@NonNull BleEngine engine, @NonNull Dispatcher dispatcher) {
		mEngine = engine;
		mDispatcher = dispatcher;
		mServer = new ClassicServer(this);
	}

	@NonNull
	BleEngine engine() {
		return mEngine;
	}

	@NonNull
	Dispatcher dispatcher() {
		return mDispatcher;
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
				HandlerThread thread = new HandlerThread("KnBle-classic");
				thread.start();
				mThread = thread;
				mHandler = new Handler(thread.getLooper());
			}
			return mHandler;
		}
	}

	void postDelayed(@NonNull Runnable r, @NonNull Object token, long delayMs) {
		Message m = Message.obtain(handler(), r);
		m.obj = token;
		handler().sendMessageDelayed(m, delayMs);
	}

	void cancelDelayed(@NonNull Object token) {
		handler().removeCallbacksAndMessages(token);
	}

	// region Any thread

	public boolean isDiscovering() {
		return mDiscovering;
	}

	long lastDiscoveryAt() {
		return mDiscovering ? SystemClock.elapsedRealtime() : mLastDiscoveryAt;
	}

	public void startDiscovery(@NonNull BleClassicDiscoveryCallback callback) {
		handler().post(() -> handleStartDiscovery(callback));
	}

	public void stopDiscovery() {
		if (mHandler != null) handler().post(() -> finishDiscovery(true));
	}

	/**
	 * Stops a running discovery (it makes the connections fail). Asynchronous: its DISCOVERY_FINISHED comes later.
	 */
	void cancelDiscovery() {
		mCancelledAt = SystemClock.elapsedRealtime();
		try {
			mEngine.adapter().cancelDiscovery();
		} catch (RuntimeException e) {
			BleLog.w("cancelDiscovery", e);
		}
	}

	public void connect(@NonNull BleDevice device, @NonNull ClassicOptions options, boolean autoReconnect, @NonNull BleClassicCallback callback) {
		// A secure connection may need a pairing: its code is given by the PairingAgent
		mEngine.ensurePairingAgent();
		handler().post(() -> {
			ClassicLink link = mLinks.get(device.key());
			if (link == null) {
				link = new ClassicLink(this, device);
				mLinks.put(device.key(), link);
			}
			link.connect(device, options, autoReconnect, callback);
		});
	}

	/**
	 * Through the thread of the engine: in order with the connection and its end, each callback exactly once
	 */
	public void write(@NonNull BleDevice device, @NonNull byte[] data, @Nullable BleWriteCallback callback) {
		handler().post(() -> {
			ClassicLink link = mLinks.get(device.key());
			if (link != null) link.write(data, callback);
			else if (callback != null) mDispatcher.post(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
		});
	}

	public void disconnect(@NonNull BleDevice device) {
		if (mHandler == null) return;
		handler().post(() -> {
			ClassicLink link = mLinks.get(device.key());
			if (link != null) link.disconnect();
		});
	}

	public void disconnectAll() {
		if (mHandler == null) return;
		handler().post(() -> {
			for (ClassicLink link : mLinks.values()) link.disconnect();
		});
	}

	public int state(@NonNull BleDevice device) {
		ClassicLink link = mLinks.get(device.key());
		return link == null ? BleGattCallback.DISCONNECTED : link.state();
	}

	@NonNull
	public List<BleDevice> connectedDevices() {
		List<BleDevice> devices = new ArrayList<>();
		for (ClassicLink link : mLinks.values()) if (link.state() == BleGattCallback.CONNECTED) devices.add(link.device());
		return devices;
	}

	// region Server

	public void startServer(@NonNull ClassicServerOptions options, @NonNull BleClassicServerCallback callback) {
		// A secure server: its clients may pair first, the PairingAgent gives the PIN of the options
		if (options.isSecure()) mEngine.ensurePairingAgent();
		mServer.start(options, callback);
	}

	public void stopServer() {
		mServer.stop();
	}

	public boolean isServerStarted() {
		return mServer.isStarted();
	}

	/**
	 * Any thread: the server whose clients may ask a pairing now (a secure one, started)
	 */
	@Nullable
	ClassicServer.Session serverPairing() {
		return mServer.pairingSession();
	}

	/**
	 * Thread of the engine: a client accepted by the server becomes the link of its device
	 */
	void accept(@NonNull ClassicServer.Session s, @NonNull BluetoothSocket client) {
		BluetoothDevice device = null;
		try {
			device = client.getRemoteDevice();
		} catch (RuntimeException e) {
			BleLog.w("getRemoteDevice", e);
		}
		if (device == null) {
			ClassicLink.closeQuietly(client);
			return;
		}
		long key = Utils.macToLong(device.getAddress());
		int clients = 0;
		for (ClassicLink l : mLinks.values()) if (l.server() == s && l.state() != BleGattCallback.DISCONNECTED) clients++;
		if (clients >= s.options.getMaxClients()) {
			BleLog.d("classic server: client refused, " + clients + " clients already");
			ClassicLink.closeQuietly(client);
			return;
		}
		ClassicLink link = mLinks.get(key);
		if (link != null && link.busy()) {
			// One link per device: the app already connects to it (or reconnects)
			BleLog.d("classic server: client refused, a connection to this device is in progress");
			ClassicLink.closeQuietly(client);
			return;
		}
		BleDevice d = clientDevice(device);
		if (link == null) {
			link = new ClassicLink(this, d);
			mLinks.put(key, link);
		}
		BleLog.d("classic server: client connected");
		link.accept(d, client, s, () -> mServer.current(s) ? s.callback.onClientConnected(d) : null);
	}

	/**
	 * The name known by KnBle (bonded), else the one of the system (a binder call, on this thread)
	 */
	@NonNull
	private BleDevice clientDevice(@NonNull BluetoothDevice device) {
		BleDevice d = new BleDevice(device);
		String name = mEngine.knownName(d);
		if (name == null) {
			try {
				name = device.getName();
			} catch (RuntimeException e) {
				BleLog.w("getName", e);
			}
		}
		return name == null ? d : new BleDevice(device, name, 0, null, System.currentTimeMillis(), true, 0, 0, BleDevice.TX_POWER_UNKNOWN);
	}

	/**
	 * Thread of the engine: the clients of a stopped server end
	 */
	void disconnectClients(@NonNull ClassicServer.Session s) {
		for (ClassicLink link : mLinks.values()) if (link.server() == s) link.disconnect();
	}

	// endregion

	void onRetired(@NonNull ClassicLink link) {
		mLinks.remove(link.key, link);
	}

	/**
	 * Any thread
	 */
	@Nullable
	ClassicLink link(long key) {
		return mLinks.get(key);
	}

	/**
	 * The pairing of this device was cancelled by the app or its code refused by Android: a connection waiting for it ends
	 */
	void onPairingAbandoned(long key, int error) {
		if (mHandler == null) return;
		handler().post(() -> {
			ClassicLink link = mLinks.get(key);
			if (link != null) link.onPairingAbandoned(error);
		});
	}

	// endregion

	// region State of Bluetooth and bonds (from the receivers of BleEngine)

	void onBluetoothOff() {
		if (mHandler == null) return;
		handler().post(() -> {
			finishDiscovery(false);
			mServer.onBluetoothOff();
			for (ClassicLink link : mLinks.values()) link.onBluetoothOff();
		});
	}

	void onBluetoothOn() {
		if (mHandler == null) return;
		handler().post(() -> {
			mServer.onBluetoothOn();
			for (ClassicLink link : mLinks.values()) link.onBluetoothOn();
		});
	}

	void onBondLost(long key, int reason) {
		if (mHandler == null) return;
		handler().post(() -> {
			ClassicLink link = mLinks.get(key);
			if (link != null) link.onBondLost(reason);
		});
	}

	void onBondStateChanged(long key, int state) {
		if (mHandler == null) return;
		handler().post(() -> {
			ClassicLink link = mLinks.get(key);
			if (link != null) link.onBondStateChanged(state);
		});
	}

	// endregion

	// region Discovery (thread of the engine)

	private void handleStartDiscovery(@NonNull BleClassicDiscoveryCallback callback) {
		// A discovery running: it ends (onDiscoveryFinished), the new one starts
		if (mDiscoveryCallback != null) finishDiscovery(true);
		if (!mEngine.checkBluetoothEnabled()) {
			mDispatcher.post(() -> callback.onDiscoveryFailed(BleError.BLUETOOTH_DISABLED));
			return;
		}
		registerReceiver();
		mFound.clear();
		mDiscoveryCallback = callback;
		mDiscovering = true;
		mLastDiscoveryAt = SystemClock.elapsedRealtime();

		// The discovery KnBle just cancelled may still run: its DISCOVERY_FINISHED would end this one at once
		long cancelledAt = mCancelledAt;
		if (cancelledAt != 0 && SystemClock.elapsedRealtime() - cancelledAt < CANCEL_WAIT_MS && adapterDiscovering()) {
			BleLog.d("classic discovery: waiting for the end of the cancelled one");
			mStartWaiting = true;
			postDelayed(() -> {
				mStartWaiting = false;
				beginDiscovery();
			}, mDiscoveryToken, CANCEL_WAIT_MS);
			return;
		}
		beginDiscovery();
	}

	private boolean adapterDiscovering() {
		try {
			return mEngine.adapter().isDiscovering();
		} catch (RuntimeException e) {
			BleLog.w("isDiscovering", e);
			return false;
		}
	}

	private void beginDiscovery() {
		BleClassicDiscoveryCallback callback = mDiscoveryCallback;
		if (callback == null) return;
		int error = 0;
		try {
			BluetoothAdapter adapter = mEngine.adapter();
			// Already running (another app): its broadcasts end this one too
			mDiscoveryStarted = adapter.isDiscovering();
			if (!adapter.startDiscovery()) error = BleError.REFUSED;
		} catch (SecurityException e) {
			BleLog.w("startDiscovery", e);
			error = BleError.PERMISSION_DENIED;
		} catch (RuntimeException e) {
			BleLog.w("startDiscovery", e);
			error = BleError.INTERNAL;
		}
		if (error != 0) {
			mDiscoveryCallback = null;
			mDiscovering = false;
			mDiscoveryStarted = false;
			int e = error;
			mDispatcher.post(() -> callback.onDiscoveryFailed(e));
			return;
		}
		mLastDiscoveryAt = SystemClock.elapsedRealtime();
		postDelayed(() -> finishDiscovery(true), mDiscoveryToken, DISCOVERY_WATCHDOG_MS);
	}

	/**
	 * DISCOVERY_FINISHED: the end of the cancelled discovery a start waits for, else of the running one
	 */
	private void onDiscoveryEnded() {
		mCancelledAt = 0;
		if (mStartWaiting) {
			mStartWaiting = false;
			cancelDelayed(mDiscoveryToken);
			beginDiscovery();
		} else if (mDiscoveryCallback != null && mDiscoveryStarted) {
			finishDiscovery(false);
		}
	}

	private void registerReceiver() {
		if (mReceiverRegistered) return;
		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
		mEngine.context().registerReceiver(mDiscoveryReceiver, filter, null, handler());
		mReceiverRegistered = true;
	}

	@SuppressWarnings("deprecation")
	private void onFound(@NonNull Intent intent, boolean found) {
		BluetoothDevice device = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
				: intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
		if (device == null) return;
		BleDevice d = new BleDevice(device);
		BleDevice known = mFound.get(d.key());
		// A name change of a device this discovery did not find
		if (!found && known == null) return;

		String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
		if (name == null && known != null) name = known.getName();
		int rssi = found ? intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0) : known.getRssi();
		BleDevice updated = new BleDevice(device, name, rssi, null, System.currentTimeMillis(), true, 0, 0, BleDevice.TX_POWER_UNKNOWN);
		mFound.put(d.key(), updated);

		// Told once, and again when the name comes later
		boolean tell = known == null || (known.getName() == null && name != null) || (name != null && !name.equals(known.getName()));
		BleClassicDiscoveryCallback callback = mDiscoveryCallback;
		if (tell && callback != null) mDispatcher.post(() -> callback.onDeviceFound(updated));
	}

	private void finishDiscovery(boolean cancel) {
		BleClassicDiscoveryCallback callback = mDiscoveryCallback;
		if (callback == null) return;
		cancelDelayed(mDiscoveryToken);
		mDiscoveryCallback = null;
		mDiscovering = false;
		mDiscoveryStarted = false;
		// Not started yet (waiting for the end of a cancelled one): nothing to cancel
		boolean started = !mStartWaiting;
		mStartWaiting = false;
		mLastDiscoveryAt = SystemClock.elapsedRealtime();
		if (cancel && started) cancelDiscovery();
		List<BleDevice> devices = new ArrayList<>(mFound.values());
		mDispatcher.post(() -> callback.onDiscoveryFinished(devices));
	}

	// endregion

	// region Tests

	@VisibleForTesting
	@NonNull
	ClassicServer serverForTests() {
		return mServer;
	}

	@VisibleForTesting
	void quitForTests() {
		HandlerThread thread = mThread;
		if (thread != null) thread.quitSafely();
	}

	// endregion
}
