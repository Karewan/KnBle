package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.ClassicOptions;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * One Bluetooth classic device: its RFCOMM connection, made again after a loss when asked. The state is confined to the
 * thread of the ClassicEngine, except the volatile snapshots. Each connection runs two threads of its own: "io"
 * connects the socket then writes the queue in order, "rx" reads. They only report to the thread of the engine, which
 * ends a connection by closing its socket.
 * <p>
 * Once a callback got onConnecting, exactly one onConnected or onDisconnected follows (then onDisconnected after
 * onConnected): a silent round of an auto-reconnection keeps it waiting, any other end tells it.
 */
@SuppressLint("MissingPermission")
final class ClassicLink {
	// A failed attempt is made again after this long, KnBleSettings.getConnectAttempts() times within getConnectTimeout()
	private static final long RETRY_DELAY_MS = 1000;
	// Auto-reconnection: after these delays, then every 10 s until disconnect()
	private static final long[] RECONNECT_DELAYS_MS = {1000, 2000, 5000, 10000};
	private static final long BLUETOOTH_ON_RECONNECT_MS = 1000;
	// A pairing (a code to type by the user) gets this long, out of the time of the connection
	private static final long PAIRING_TIMEOUT_MS = 60000;
	// Bytes of one read: what the stack holds is taken at once, a fast stream needs few wake-ups
	private static final int READ_BUFFER = 16384;
	// Bytes read but not yet given to the app: past this the reader waits, the RFCOMM flow control slows the device down
	@VisibleForTesting
	static final int PIPE_CAPACITY = 1024 * 1024;
	// At most this much per onData: a backlog comes in several calls, the Looper runs in between
	@VisibleForTesting
	static final int MAX_DATA = 256 * 1024;
	private static Method sChannelSocket, sInsecureChannelSocket;

	final long key;
	@NonNull
	private final ClassicEngine mEngine;
	@NonNull
	private final Dispatcher mDispatcher;
	@NonNull
	private volatile BleDevice mDevice;
	private volatile int mState = BleGattCallback.DISCONNECTED;
	@Nullable
	private volatile BleClassicCallback mCallback;
	// PIN of the options, read by the PairingAgent (thread of BleEngine)
	@Nullable
	private volatile String mPin;

	// Thread of the engine only
	@NonNull
	private ClassicOptions mOptions = new ClassicOptions.Builder().build();
	private boolean mAutoReconnect, mReconnecting, mBondingSeen, mByChannel;
	// The attempt failed while the user was still pairing: the next one waits for the end of the pairing
	private boolean mWaitingBond;
	// The app asked a connection while this one was closing (disconnect, write error, Bluetooth off): made by end()
	private boolean mConnectAfterEnd;
	// The classic server whose client this device is (its accepted connection), null for a connection of the app
	@Nullable
	private Object mServer;
	// ACTION_KEY_MISSING (Android 16+): the device lost its bond, no attempt nor auto-reconnection until a connectClassic
	// of the app or a new bond
	private boolean mBondLost;
	private int mReconnectRound, mRound;
	private long mDeadline;
	@Nullable
	private Connection mConn;
	// The callback told onConnecting (then onConnected): the one that gets the onDisconnected ending it, exactly once
	@Nullable
	private BleClassicCallback mTold;
	// Writes asked while connecting, given to the io thread once onConnected went to the app
	private final ArrayDeque<Write> mPending = new ArrayDeque<>();
	// Posted by end() after the onDisconnected of the connection: the connections asked then cancelled while it closed
	private final List<Runnable> mAfterEnd = new ArrayList<>(0);
	private final Object mReconnectToken = new Object();
	// The next round of a connection: not cancelled by another connect() of the app meanwhile
	private final Object mRetryToken = new Object();
	private final Object mBondWaitToken = new Object();

	private static final class Write {
		@NonNull
		final byte[] data;
		@Nullable
		final BleWriteCallback callback;

		Write(@NonNull byte[] data, @Nullable BleWriteCallback callback) {
			this.data = data;
			this.callback = callback;
		}
	}

	/**
	 * An attempt, then the connection it made: its socket, its threads, its writes and its data
	 */
	private static final class Connection {
		final boolean byChannel;
		@NonNull
		final DataPipe pipe;
		@Nullable
		volatile BluetoothSocket socket;
		// Ended by KnBle: the threads stop at their next step
		volatile boolean closed;
		// Thread of the engine only
		boolean connected, timedOut;
		// Cause told to the app when the reader ends: CONNECTION_LOST unless KnBle or the app closed it
		int endStatus = BleError.CONNECTION_LOST;
		// The callback told the end of this connection by end(), null for none
		@Nullable
		BleClassicCallback endedFor;
		@Nullable
		Thread io;
		// Guarded by this: queued by the engine, taken one at a time by the io thread
		private final ArrayDeque<Write> mWrites = new ArrayDeque<>();
		// The write the io thread gives to the stream: its result is told by the io thread or by end(), never both
		@Nullable
		private Write mInFlight;

		Connection(boolean byChannel, @NonNull DataPipe pipe) {
			this.byChannel = byChannel;
			this.pipe = pipe;
		}

		void close() {
			closed = true;
			BluetoothSocket s = socket;
			if (s != null) closeQuietly(s);
			Thread t = io;
			if (t != null) t.interrupt();
			synchronized (this) {
				notifyAll();
			}
			// A reader waiting for the app goes on: its next read fails on the closed socket
			pipe.wake();
		}

		synchronized void queue(@NonNull Write w) {
			mWrites.add(w);
			notifyAll();
		}

		/**
		 * Thread io: the next write, now in flight, null once closed
		 */
		@Nullable
		synchronized Write take() throws InterruptedException {
			while (mWrites.isEmpty() && !closed) wait();
			if (closed) return null;
			mInFlight = mWrites.poll();
			return mInFlight;
		}

		/**
		 * Thread io: w went to the stream. Its success is posted under the lock, so before the failures and the
		 * onDisconnected of end(), unless end() took it first.
		 */
		synchronized void written(@NonNull Write w, @NonNull Dispatcher dispatcher) {
			if (mInFlight != w) return;
			mInFlight = null;
			BleWriteCallback callback = w.callback;
			if (callback != null) dispatcher.post(callback::onWriteSuccess);
		}

		/**
		 * Thread of the engine, once closed: the write in flight then the queued ones, in order, for their failure
		 */
		@NonNull
		synchronized List<Write> takeWrites() {
			List<Write> writes = new ArrayList<>(mWrites.size() + 1);
			if (mInFlight != null) writes.add(mInFlight);
			mInFlight = null;
			writes.addAll(mWrites);
			mWrites.clear();
			return writes;
		}
	}

	ClassicLink(@NonNull ClassicEngine engine, @NonNull BleDevice device) {
		mEngine = engine;
		mDispatcher = engine.dispatcher();
		mDevice = device;
		key = device.getMacLong();
	}

	// region Any thread

	int state() {
		return mState;
	}

	@NonNull
	BleDevice device() {
		return mDevice;
	}

	@Nullable
	BleClassicCallback callback() {
		return mCallback;
	}

	/**
	 * Thread of the engine: the server whose client this is, null for a connection of the app
	 */
	@Nullable
	Object server() {
		return mServer;
	}

	/**
	 * Thread of the engine: connected, connecting, or waiting for a round of an auto-reconnection
	 */
	boolean busy() {
		return mState != BleGattCallback.DISCONNECTED || (mAutoReconnect && mCallback != null);
	}

	/**
	 * PIN to give to a legacy pairing of this device: while connecting with ClassicOptions.setPin
	 */
	@Nullable
	String pairingPin() {
		return mState == BleGattCallback.CONNECTING ? mPin : null;
	}

	// endregion

	// region Connection (thread of the engine)

	void connect(@NonNull BleDevice device, @NonNull ClassicOptions options, boolean autoReconnect, @NonNull BleClassicCallback callback) {
		mEngine.cancelDelayed(mReconnectToken);
		// A client of the server taken by the app: its connection now (stopClassicServer no longer ends it)
		mServer = null;
		// The app tries again (paired again meanwhile, maybe)
		mBondLost = false;
		BleClassicCallback previous = mCallback;
		mDevice = device.getName() != null || mDevice.getName() == null ? device : mDevice;
		mOptions = options;
		mPin = options.getPin();
		mAutoReconnect = autoReconnect;
		mReconnectRound = 0;
		mCallback = callback;

		Connection c = mConn;
		if (mState == BleGattCallback.CONNECTED && c != null) {
			if (c.closed) {
				// Closing: its end is told first (after its last bytes), then the new connection starts
				if (mConnectAfterEnd && previous != null && previous != callback && previous != mTold) cancelAfterEnd(previous);
				mConnectAfterEnd = true;
				return;
			}
			mTold = callback;
			c.pipe.receiver = callback;
			mDispatcher.post(callback::onConnected);
			return;
		}
		if (mState == BleGattCallback.CONNECTING) {
			if (!mReconnecting) {
				// A connection of the app in progress (maybe between two rounds): it goes on, for this callback
				tellConnecting(callback);
				return;
			}
			// An auto-reconnection in progress: the connection of the app takes its place
			mConn = null;
			if (c != null) {
				mEngine.cancelDelayed(c);
				c.close();
			}
		}
		startConnection(false);
	}

	/**
	 * onConnecting, unless this callback already waits for the end of this connection. The callback it replaces gets
	 * onDisconnected(true, CANCELLED): every onConnecting has its end.
	 */
	private void tellConnecting(@NonNull BleClassicCallback callback) {
		BleClassicCallback told = mTold;
		if (told == callback) return;
		if (told != null) mDispatcher.post(() -> told.onDisconnected(true, BleError.CANCELLED));
		mTold = callback;
		mDispatcher.post(callback::onConnecting);
	}

	/**
	 * A connection asked while the previous one was closing, then cancelled (disconnect, or another callback): told
	 * after the end of the previous one
	 */
	private void cancelAfterEnd(@NonNull BleClassicCallback callback) {
		mAfterEnd.add(() -> callback.onDisconnected(true, BleError.CANCELLED));
	}

	private void startConnection(boolean reconnection) {
		BleClassicCallback callback = mCallback;
		mEngine.cancelDelayed(mRetryToken);
		mState = BleGattCallback.CONNECTING;
		mReconnecting = reconnection;
		// An auto-reconnection is told once, at its first round: the next rounds find it told
		if (callback != null) tellConnecting(callback);
		mRound = 0;
		mDeadline = SystemClock.elapsedRealtime() + mEngine.engine().settings().getConnectTimeout();
		if (!mEngine.engine().isBluetoothEnabled()) {
			end(true, BleError.BLUETOOTH_DISABLED);
			return;
		}
		nextRound();
	}

	/**
	 * A round: by the UUID, then (when allowed) right away by the channel
	 */
	private void nextRound() {
		mRound++;
		attempt(mOptions.getChannel() > 0);
	}

	private void attempt(boolean byChannel) {
		mByChannel = byChannel;
		mBondingSeen = false;
		mWaitingBond = false;
		mEngine.cancelDelayed(mBondWaitToken);
		Connection c = new Connection(byChannel, new DataPipe(mDispatcher));
		mConn = c;
		Thread io = new Thread(() -> runIo(c), "KnBle-rfcomm-" + mDevice.getMac());
		c.io = io;
		io.start();
		// The socket has no timeout of its own: closed when the time of the connection is over
		mEngine.postDelayed(() -> onAttemptTimeout(c), c, Math.max(0, mDeadline - SystemClock.elapsedRealtime()));
	}

	/**
	 * A client of the classic server connected: the app gives the callback of the connection (onClientConnected), the
	 * reading starts once it has one, so nothing read is lost. Writes asked meanwhile wait for it.
	 * @param ask On the Looper of the callbacks: the callback of the connection, null to refuse the client
	 */
	void accept(@NonNull BleDevice device, @NonNull BluetoothSocket socket, @NonNull Object server, @NonNull Supplier<BleClassicCallback> ask) {
		mEngine.cancelDelayed(mReconnectToken);
		mDevice = device.getName() != null || mDevice.getName() == null ? device : mDevice;
		mServer = server;
		mAutoReconnect = false;
		mBondLost = false;
		mReconnectRound = 0;
		mCallback = null;
		mTold = null;
		mPin = null;
		mState = BleGattCallback.CONNECTING;
		mReconnecting = false;
		Connection c = new Connection(false, new DataPipe(mDispatcher));
		c.socket = socket;
		mConn = c;
		mDispatcher.post(() -> {
			BleClassicCallback decided = null;
			try {
				decided = ask.get();
			} finally {
				// Also when the app threw: the link does not stay connecting
				BleClassicCallback d = decided;
				mEngine.handler().post(() -> onAcceptDecision(c, d));
			}
		});
	}

	private void onAcceptDecision(@NonNull Connection c, @Nullable BleClassicCallback decided) {
		if (c != mConn) {
			// Ended meanwhile (disconnectClassic, Bluetooth off, server stopped): the app knows the client, the end is told
			if (decided != null && decided != c.endedFor) {
				int status = c.endStatus;
				mDispatcher.post(() -> decided.onDisconnected(false, status));
			}
			return;
		}
		// A connectClassic of the app meanwhile (told onConnecting): its callback takes the connection
		BleClassicCallback app = mCallback;
		if (app != null && decided != null && decided != app) mDispatcher.post(() -> decided.onDisconnected(true, BleError.CANCELLED));
		BleClassicCallback callback = app != null ? app : decided;
		if (callback == null) {
			BleLog.d("classic server: client refused by the app");
			end(false, 0);
			return;
		}
		mCallback = callback;
		// Closed meanwhile without end (never by KnBle so far): no attempt of a connection to make again
		if (c.closed) {
			end(false, c.endStatus);
			return;
		}
		onSocketConnected(c);
	}

	private void onAttemptTimeout(@NonNull Connection c) {
		if (c != mConn || c.connected) return;
		c.timedOut = true;
		// connect() throws, the io thread reports the failure
		c.close();
	}

	private void onSocketConnected(@NonNull Connection c) {
		if (c != mConn) {
			c.close();
			return;
		}
		if (c.closed) {
			onAttemptFailed(c, null, 0);
			return;
		}
		mEngine.cancelDelayed(c);
		c.connected = true;
		mState = BleGattCallback.CONNECTED;
		if (mReconnecting) BleLog.d("classic auto-reconnection done");
		mReconnecting = false;
		mReconnectRound = 0;

		BluetoothSocket socket = c.socket;
		BleClassicCallback callback = mCallback;
		c.pipe.receiver = callback;
		if (callback != null) {
			mTold = callback;
			mDispatcher.post(callback::onConnected);
		}
		// A connection accepted by the server: no io thread yet, it only writes
		if (c.io == null && socket != null) {
			Thread io = new Thread(() -> writeLoop(c, socket), "KnBle-rfcomm-" + mDevice.getMac());
			c.io = io;
			io.start();
		}
		// After onConnected: the data and the results of the writes come after it
		for (Write w : mPending) c.queue(w);
		mPending.clear();
		if (socket != null) new Thread(() -> runReader(c, socket), "KnBle-rfcomm-rx-" + mDevice.getMac()).start();
	}

	private void onAttemptFailed(@NonNull Connection c, @Nullable Exception e, int error) {
		if (c != mConn) return;
		mEngine.cancelDelayed(c);
		mConn = null;
		if (mState != BleGattCallback.CONNECTING) return;
		if (KnBle.DEBUG) BleLog.d("classic attempt " + mRound + (c.byChannel ? " (channel)" : "") + " failed: " + e);

		if (error != 0) {
			end(true, error);
			return;
		}
		if (mBondLost) {
			end(true, BleError.BOND_LOST);
			return;
		}
		if (!mEngine.engine().checkBluetoothEnabled()) {
			end(true, BleError.BLUETOOTH_DISABLED);
			return;
		}
		if (c.timedOut || SystemClock.elapsedRealtime() >= mDeadline) {
			end(true, BleError.TIMEOUT);
			return;
		}
		int bond = bondState();
		// The socket gave up while the user is still typing the code (some stacks): the next attempt once paired
		if (bond == BluetoothDevice.BOND_BONDING) {
			BleLog.d("classic attempt failed during the pairing: waiting for it");
			mBondingSeen = mWaitingBond = true;
			scheduleBondWait();
			return;
		}
		// The pairing the secure socket started was refused or cancelled: another attempt would ask again
		if (mBondingSeen && bond != BluetoothDevice.BOND_BONDED) {
			end(true, BleError.BOND_FAILED);
			return;
		}
		if (!c.byChannel && mOptions.getChannel() == 0 && mOptions.isChannelFallback()) {
			attempt(true);
			return;
		}
		if (mRound >= mEngine.engine().settings().getConnectAttempts() || SystemClock.elapsedRealtime() + RETRY_DELAY_MS >= mDeadline) {
			end(true, BleError.REFUSED);
			return;
		}
		BleLog.d("classic connection: again in " + RETRY_DELAY_MS + " ms");
		mEngine.postDelayed(() -> {
			if (mState == BleGattCallback.CONNECTING && mConn == null) nextRound();
		}, mRetryToken, RETRY_DELAY_MS);
	}

	/**
	 * End of the connection made by c: the reader stopped (socket closed by the device, link lost, or closed by KnBle)
	 */
	private void onReaderEnded(@NonNull Connection c) {
		if (c != mConn) return;
		end(false, c.endStatus);
	}

	/**
	 * The io thread could not write: the connection is lost, the reader ends it
	 */
	private void onWriteError(@NonNull Connection c) {
		if (c != mConn) return;
		c.close();
	}

	void disconnect() {
		mAutoReconnect = false;
		mEngine.cancelDelayed(mReconnectToken);
		Connection c = mConn;
		if (mState == BleGattCallback.CONNECTED && c != null) {
			if (mConnectAfterEnd) {
				// The connection asked while this one closes is cancelled: its writes fail after the ones before them
				mConnectAfterEnd = false;
				for (Write w : mPending) c.queue(w);
				mPending.clear();
				BleClassicCallback callback = mCallback;
				if (callback != null && callback != mTold) cancelAfterEnd(callback);
			}
			// Told when the reader ends: after the last bytes it read
			c.endStatus = 0;
			c.close();
			return;
		}
		// Connecting, or between two rounds of an auto-reconnection (told when the app got onConnecting)
		end(true, BleError.CANCELLED);
	}

	/**
	 * End of the connection, whatever the cause: the writes fail (in order), the app is told unless a round of the
	 * auto-reconnection follows, then the connection asked meanwhile, the next round or the end of the link
	 */
	private void end(boolean connectFailed, int requested) {
		// Once the bond is lost, an end not asked by the app nor Bluetooth turned off is its consequence
		int status = mBondLost && requested != 0 && requested != BleError.CANCELLED && requested != BleError.BLUETOOTH_DISABLED
				? BleError.BOND_LOST : requested;
		Connection c = mConn;
		mConn = null;
		List<Write> failed = new ArrayList<>();
		if (c != null) {
			c.endStatus = status;
			mEngine.cancelDelayed(c);
			c.close();
			failed.addAll(c.takeWrites());
		}
		mEngine.cancelDelayed(mRetryToken);
		mWaitingBond = false;
		mEngine.cancelDelayed(mBondWaitToken);

		boolean silent = mReconnecting;
		mState = BleGattCallback.DISCONNECTED;
		mReconnecting = false;
		boolean again = mConnectAfterEnd;
		mConnectAfterEnd = false;
		// The writes of the connection asked meanwhile wait for it
		if (!again) {
			failed.addAll(mPending);
			mPending.clear();
		}
		for (Write w : failed) fail(w);

		BleClassicCallback callback = mCallback;
		// A refused pairing or a missing permission: the next rounds would fail the same way (and ask the user again)
		if (status == BleError.PERMISSION_DENIED || status == BleError.BOND_FAILED || status == BleError.CANCELLED || status == BleError.BOND_LOST) {
			mAutoReconnect = false;
		}
		boolean reconnect = !again && mAutoReconnect && callback != null;
		boolean nextRound = reconnect && mEngine.engine().isBluetoothEnabled();
		// A failed round of the auto-reconnection followed by another is silent: still connecting for the app
		BleClassicCallback told = mTold;
		if (told != null && !(silent && nextRound)) {
			mTold = null;
			if (c != null) c.endedFor = told;
			DataPipe pipe = c != null ? c.pipe : null;
			mDispatcher.post(() -> {
				// The last bytes read come first
				if (pipe != null) pipe.flush();
				told.onDisconnected(connectFailed, status);
			});
		}
		for (Runnable r : mAfterEnd) mDispatcher.post(r);
		mAfterEnd.clear();

		if (again) {
			startConnection(false);
		} else if (!reconnect) {
			mCallback = null;
			mEngine.onRetired(this);
		} else if (nextRound) {
			scheduleReconnection();
		}
	}

	private void scheduleReconnection() {
		mEngine.cancelDelayed(mReconnectToken);
		long delay = mReconnectRound < RECONNECT_DELAYS_MS.length ? RECONNECT_DELAYS_MS[mReconnectRound] : RECONNECT_DELAYS_MS[RECONNECT_DELAYS_MS.length - 1];
		mReconnectRound++;
		BleLog.d("classic auto-reconnection round " + mReconnectRound + " in " + delay + " ms");
		mEngine.postDelayed(this::reconnect, mReconnectToken, delay);
	}

	private void reconnect() {
		if (!mAutoReconnect || mCallback == null || mState != BleGattCallback.DISCONNECTED) return;
		startConnection(true);
	}

	void onBluetoothOff() {
		mEngine.cancelDelayed(mReconnectToken);
		Connection c = mConn;
		if (mState == BleGattCallback.CONNECTED && c != null) {
			c.endStatus = BleError.BLUETOOTH_DISABLED;
			c.close();
		} else if (mState == BleGattCallback.CONNECTING) {
			end(true, BleError.BLUETOOTH_DISABLED);
		} else {
			// Between two rounds of an auto-reconnection: it waits for Bluetooth, its onConnecting gets its end
			BleClassicCallback told = mTold;
			mTold = null;
			if (told != null) mDispatcher.post(() -> told.onDisconnected(true, BleError.BLUETOOTH_DISABLED));
		}
	}

	void onBluetoothOn() {
		if (!mAutoReconnect || mCallback == null || mState != BleGattCallback.DISCONNECTED) return;
		mEngine.cancelDelayed(mReconnectToken);
		// First round: told to the app with onConnecting
		mReconnectRound = 1;
		mEngine.postDelayed(this::reconnect, mReconnectToken, BLUETOOTH_ON_RECONNECT_MS);
	}

	/**
	 * The pairing of a secure connection: its time (the user may type a code) does not count in the time of the
	 * connection, which starts again once bonded
	 */
	void onBondStateChanged(int state) {
		// Paired again (the re-pairing of the system after a lost bond): the attempts may go on
		if (state == BluetoothDevice.BOND_BONDED) mBondLost = false;
		// A client of the server waiting for the app: already paired, it has no attempt to time
		if (mState != BleGattCallback.CONNECTING || mServer != null) return;
		if (state == BluetoothDevice.BOND_BONDING) {
			mBondingSeen = true;
			mDeadline = SystemClock.elapsedRealtime() + PAIRING_TIMEOUT_MS;
		} else if (state == BluetoothDevice.BOND_BONDED && mBondingSeen) {
			mDeadline = SystemClock.elapsedRealtime() + mEngine.engine().settings().getConnectTimeout();
			if (mWaitingBond && mConn == null) {
				BleLog.d("classic pairing done: next attempt");
				attempt(mByChannel);
				return;
			}
		} else if (state == BluetoothDevice.BOND_NONE && mBondingSeen && mWaitingBond && mConn == null) {
			end(true, BleError.BOND_FAILED);
			return;
		} else {
			return;
		}
		Connection c = mConn;
		if (c == null) {
			if (mWaitingBond) scheduleBondWait();
			return;
		}
		if (c.connected) return;
		mEngine.cancelDelayed(c);
		mEngine.postDelayed(() -> onAttemptTimeout(c), c, Math.max(0, mDeadline - SystemClock.elapsedRealtime()));
	}

	/**
	 * Between two attempts, waiting for the end of a pairing: the connection ends at its deadline
	 */
	private void scheduleBondWait() {
		mEngine.cancelDelayed(mBondWaitToken);
		mEngine.postDelayed(() -> {
			if (mWaitingBond && mState == BleGattCallback.CONNECTING && mConn == null) end(true, BleError.TIMEOUT);
		}, mBondWaitToken, Math.max(0, mDeadline - SystemClock.elapsedRealtime()));
	}

	/**
	 * ACTION_KEY_MISSING (Android 16+): the device, bonded, could not give its keys. The connection in progress fails or
	 * ends by itself (or succeeds, when the user accepts the re-pairing of the system); no other attempt, no
	 * auto-reconnection (it would fail again, or ask the user again).
	 */
	void onBondLost(int reason) {
		BleClassicCallback callback = mCallback;
		boolean reconnecting = mAutoReconnect && callback != null;
		if (mState == BleGattCallback.DISCONNECTED && !reconnecting) return;
		BleLog.d("classic bond lost (reason " + reason + "): no other attempt");
		mBondLost = true;
		mEngine.cancelDelayed(mReconnectToken);
		if (callback != null) mDispatcher.post(() -> callback.onBondLost(reason));
		if (mState == BleGattCallback.CONNECTING && mReconnecting) {
			// A round of the auto-reconnection: ends now, its onConnecting gets its end
			end(true, BleError.BOND_LOST);
		} else if (mState == BleGattCallback.DISCONNECTED) {
			// Between two rounds: the auto-reconnection ends
			mAutoReconnect = false;
			BleClassicCallback told = mTold;
			mTold = null;
			if (told != null) mDispatcher.post(() -> told.onDisconnected(true, BleError.BOND_LOST));
			mCallback = null;
			mEngine.onRetired(this);
		}
	}

	/**
	 * The app cancelled the pairing (BlePairingRequest.cancel, error CANCELLED) or Android refused its code: the
	 * connection that needed it ends, without auto-reconnection (it would ask the user again)
	 */
	void onPairingAbandoned(int error) {
		if (mState != BleGattCallback.CONNECTING) return;
		BleLog.d("classic pairing abandoned: " + BleError.name(error));
		if (error != BleError.CANCELLED && error != BleError.PERMISSION_DENIED && error != BleError.BOND_FAILED) error = BleError.BOND_FAILED;
		end(true, error);
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

	// region Writes (thread of the engine)

	void write(@NonNull byte[] data, @Nullable BleWriteCallback callback) {
		Write w = new Write(data, callback);
		Connection c = mConn;
		if (mConnectAfterEnd || (mState == BleGattCallback.CONNECTING && !mReconnecting)) mPending.add(w);
		// Closing: failed by end() after the writes before it
		else if (mState == BleGattCallback.CONNECTED && c != null) c.queue(w);
		else fail(w);
	}

	private void fail(@NonNull Write w) {
		BleWriteCallback callback = w.callback;
		if (callback != null) mDispatcher.post(() -> callback.onWriteFailed(BleError.NOT_CONNECTED));
	}

	// endregion

	// region Threads of a connection

	/**
	 * Thread "io": connects the socket, then writes the queue in order until the connection ends
	 */
	private void runIo(@NonNull Connection c) {
		BluetoothSocket socket = null;
		try {
			// A discovery in progress makes the connection fail or very slow
			mEngine.cancelDiscovery();
			socket = createSocket(c.byChannel);
			c.socket = socket;
			if (c.closed) throw new IOException("closed before connect");
			socket.connect();
		} catch (IOException | RuntimeException e) {
			if (socket != null) closeQuietly(socket);
			int error = e instanceof SecurityException ? BleError.PERMISSION_DENIED : 0;
			mEngine.handler().post(() -> onAttemptFailed(c, e, error));
			return;
		}
		mEngine.handler().post(() -> onSocketConnected(c));
		writeLoop(c, socket);
	}

	/**
	 * Thread "io" once connected: writes the queue in order until the connection ends
	 */
	private void writeLoop(@NonNull Connection c, @NonNull BluetoothSocket s) {
		try {
			OutputStream out = s.getOutputStream();
			Write w;
			while ((w = c.take()) != null) {
				out.write(w.data);
				c.written(w, mDispatcher);
			}
		} catch (InterruptedException e) {
			// Ended by KnBle
		} catch (IOException | RuntimeException e) {
			// The write in flight stays taken: end() fails it before the queued ones
			if (!c.closed) mEngine.handler().post(() -> onWriteError(c));
		}
	}

	/**
	 * Thread "rx": reads until the socket closes. The chunks go to the app in order through the DataPipe.
	 */
	private void runReader(@NonNull Connection c, @NonNull BluetoothSocket socket) {
		byte[] buffer = new byte[READ_BUFFER];
		DataPipe pipe = c.pipe;
		try {
			InputStream in = socket.getInputStream();
			int n;
			while ((n = in.read(buffer)) >= 0) {
				if (n == 0) continue;
				pipe.push(Arrays.copyOf(buffer, n));
				// The app is PIPE_CAPACITY behind: no read until it catches up, the flow control holds the device
				pipe.awaitRoom(c);
			}
		} catch (IOException e) {
			if (KnBle.DEBUG && !c.closed) BleLog.d("classic read ended: " + e.getMessage());
		}
		mEngine.handler().post(() -> onReaderEnded(c));
	}

	@VisibleForTesting
	interface SocketFactory {
		@NonNull
		BluetoothSocket create(@NonNull BluetoothDevice device, boolean byChannel) throws IOException;
	}

	// Sockets of the tests
	@VisibleForTesting
	@Nullable
	static volatile SocketFactory sSocketFactory;

	@NonNull
	private BluetoothSocket createSocket(boolean byChannel) throws IOException {
		BluetoothDevice device = mDevice.getDevice();
		SocketFactory factory = sSocketFactory;
		if (factory != null) return factory.create(device, byChannel);
		boolean secure = mOptions.isSecure();
		if (!byChannel) {
			return secure ? device.createRfcommSocketToServiceRecord(mOptions.getUuid()) : device.createInsecureRfcommSocketToServiceRecord(mOptions.getUuid());
		}
		int channel = mOptions.getChannel() > 0 ? mOptions.getChannel() : 1;
		try {
			// Hidden but kept by Android (greylist): the socket of a channel, without the SDP query
			Method m = secure ? sChannelSocket : sInsecureChannelSocket;
			if (m == null) {
				m = BluetoothDevice.class.getMethod(secure ? "createRfcommSocket" : "createInsecureRfcommSocket", int.class);
				if (secure) sChannelSocket = m;
				else sInsecureChannelSocket = m;
			}
			return (BluetoothSocket) m.invoke(device, channel);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException io) throw io;
			if (cause instanceof RuntimeException re) throw re;
			throw new IOException("channel socket", e);
		} catch (ReflectiveOperationException e) {
			throw new IOException("channel socket not available", e);
		}
	}

	static void closeQuietly(@NonNull BluetoothSocket socket) {
		try {
			socket.close();
		} catch (IOException | RuntimeException e) {
			BleLog.w("socket close", e);
		}
	}

	// endregion

	/**
	 * Bytes of the reader of one connection to the app, in order, never dropped. At most one message waits on the
	 * Looper of the callbacks: it gives what was read meanwhile, merged, at most MAX_DATA per onData. The reader never
	 * waits for the Looper, unless the app is PIPE_CAPACITY behind: it then stops reading until the app catches up, and
	 * the RFCOMM flow control slows the device down.
	 */
	private static final class DataPipe {
		@NonNull
		private final Dispatcher mDispatcher;
		private final ConcurrentLinkedQueue<byte[]> mChunks = new ConcurrentLinkedQueue<>();
		// Bytes in mChunks
		private final AtomicInteger mBytes = new AtomicInteger();
		private final AtomicBoolean mScheduled = new AtomicBoolean();
		private final Runnable mDrain = this::drain;
		// The reader, while it waits for room
		@Nullable
		private volatile Thread mWaiting;
		// Receiver of the data, read on the Looper of the callbacks: kept after the end, the last bytes still reach it
		@Nullable
		volatile BleClassicCallback receiver;

		DataPipe(@NonNull Dispatcher dispatcher) {
			mDispatcher = dispatcher;
		}

		/**
		 * Thread rx
		 */
		void push(@NonNull byte[] chunk) {
			mChunks.offer(chunk);
			mBytes.addAndGet(chunk.length);
			if (mScheduled.compareAndSet(false, true)) mDispatcher.post(mDrain);
		}

		/**
		 * Thread rx: waits while PIPE_CAPACITY bytes wait for the app, until it catches up or the connection closes
		 */
		void awaitRoom(@NonNull Connection c) {
			if (mBytes.get() < PIPE_CAPACITY) return;
			// Published before the checks: a drain or a close() after them sees it and wakes this thread
			mWaiting = Thread.currentThread();
			try {
				while (mBytes.get() >= PIPE_CAPACITY && !c.closed) LockSupport.park(this);
			} finally {
				mWaiting = null;
			}
		}

		void wake() {
			Thread t = mWaiting;
			if (t != null) LockSupport.unpark(t);
		}

		/**
		 * Looper of the callbacks: one onData, the rest in the next message (the other callbacks run in between)
		 */
		private void drain() {
			mScheduled.set(false);
			if (deliver() && mScheduled.compareAndSet(false, true)) mDispatcher.post(mDrain);
		}

		/**
		 * Looper of the callbacks, before onDisconnected: everything left, the reader has ended
		 */
		void flush() {
			while (deliver()) {
				// Until empty
			}
		}

		/**
		 * One onData of the first chunks (a single chunk without copy)
		 * @return true when bytes are left
		 */
		private boolean deliver() {
			byte[] data = mChunks.poll();
			if (data == null) return false;
			// Only this thread takes: what peek() sees is what poll() returns
			byte[] next = mChunks.peek();
			if (next != null && data.length + next.length <= MAX_DATA) {
				List<byte[]> chunks = new ArrayList<>();
				chunks.add(data);
				int length = data.length;
				while (next != null && length + next.length <= MAX_DATA) {
					mChunks.poll();
					chunks.add(next);
					length += next.length;
					next = mChunks.peek();
				}
				data = new byte[length];
				int offset = 0;
				for (byte[] chunk : chunks) {
					System.arraycopy(chunk, 0, data, offset, chunk.length);
					offset += chunk.length;
				}
			}
			if (mBytes.addAndGet(-data.length) < PIPE_CAPACITY) wake();
			BleClassicCallback callback = receiver;
			if (callback != null) callback.onData(data);
			return !mChunks.isEmpty();
		}
	}
}
