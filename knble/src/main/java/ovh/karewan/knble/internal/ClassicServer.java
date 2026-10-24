package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.ClassicServerOptions;
import ovh.karewan.knble.interfaces.BleClassicServerCallback;

/**
 * The Bluetooth classic server of the app: an RFCOMM server socket with an SDP record, listened to by a thread of its
 * own ("KnBle-rfcomm-server", the only blocking call: accept). Each client becomes a ClassicLink of the ClassicEngine,
 * the connection of any classic device. The state lives on the thread of the ClassicEngine. Each start and stop call
 * changes a generation at once, on the calling thread: nothing comes after stopClassicServer returned.
 */
@SuppressLint("MissingPermission")
final class ClassicServer {
	// Android closed the server socket with Bluetooth on (a stack restart): listened to again after this long, a few times
	private static final long RELISTEN_DELAY_MS = 1000;
	private static final int RELISTEN_ATTEMPTS = 3;
	private static final long BLUETOOTH_ON_DELAY_MS = 1000;

	@NonNull
	private final ClassicEngine mEngine;
	@NonNull
	private final Dispatcher mDispatcher;
	private final AtomicInteger mGeneration = new AtomicInteger();
	private final AtomicReference<Session> mLatest = new AtomicReference<>();
	// Generation of the last stop call
	private volatile int mStopped;
	// Thread of the engine
	@Nullable
	private Session mSession;
	// Any thread: started (also while Bluetooth is off, it listens again when it is back)
	@Nullable
	private volatile Session mStarted;
	private final Object mToken = new Object();

	/**
	 * A start call of the app
	 */
	static final class Session {
		@NonNull
		final ClassicServerOptions options;
		@NonNull
		final BleClassicServerCallback callback;
		final int gen;
		// The app got the result of the start (thread of the app only)
		boolean answered;
		// Thread of the engine
		boolean startedOnce;
		int failures;
		@Nullable
		volatile BluetoothServerSocket socket;
		@Nullable
		volatile Thread acceptThread;

		Session(@NonNull ClassicServerOptions options, @NonNull BleClassicServerCallback callback, int gen) {
			this.options = options;
			this.callback = callback;
			this.gen = gen;
		}
	}

	ClassicServer(@NonNull ClassicEngine engine) {
		mEngine = engine;
		mDispatcher = engine.dispatcher();
	}

	// region Any thread

	void start(@NonNull ClassicServerOptions options, @NonNull BleClassicServerCallback callback) {
		Session s = new Session(options, callback, mGeneration.incrementAndGet());
		Session replaced = mLatest.getAndSet(s);
		if (replaced != null && replaced.callback != callback) {
			// After the events of the replaced one already in the Dispatcher (dropped, the generation changed)
			mDispatcher.post(() -> {
				if (replaced.answered || mStopped > s.gen) return;
				replaced.answered = true;
				replaced.callback.onServerFailed(BleError.CANCELLED);
			});
		}
		mEngine.handler().post(() -> {
			// A newer start or stop came meanwhile: that one acts
			if (!current(s)) return;
			stopNow();
			mSession = s;
			listen(s, true);
		});
	}

	void stop() {
		int gen = mGeneration.incrementAndGet();
		mStopped = gen;
		mLatest.set(null);
		mStarted = null;
		mEngine.handler().post(() -> {
			if (mGeneration.get() == gen) stopNow();
		});
	}

	boolean isStarted() {
		return mStarted != null;
	}

	boolean current(@NonNull Session s) {
		return mGeneration.get() == s.gen;
	}

	/**
	 * The callback and the PIN for a pairing a client asks: while a secure server is started
	 */
	@Nullable
	Session pairingSession() {
		Session s = mStarted;
		return s != null && s.options.isSecure() ? s : null;
	}

	// endregion

	// region Thread of the engine

	private void listen(@NonNull Session s, boolean first) {
		if (mSession != s || s.socket != null) return;
		if (!mEngine.engine().checkBluetoothEnabled()) {
			// Started again when Bluetooth is back (onBluetoothOn)
			if (first) fail(s, BleError.BLUETOOTH_DISABLED);
			return;
		}
		BluetoothServerSocket socket;
		try {
			BluetoothAdapter adapter = mEngine.engine().adapter();
			ClassicServerOptions o = s.options;
			socket = o.isSecure() ? adapter.listenUsingRfcommWithServiceRecord(o.getName(), o.getUuid())
					: adapter.listenUsingInsecureRfcommWithServiceRecord(o.getName(), o.getUuid());
		} catch (SecurityException e) {
			BleLog.w("classic server", e);
			fail(s, BleError.PERMISSION_DENIED);
			return;
		} catch (IOException | RuntimeException e) {
			BleLog.w("classic server", e);
			if (first) fail(s, BleError.REFUSED);
			else relistenLater(s);
			return;
		}
		if (socket == null) {
			if (first) fail(s, BleError.REFUSED);
			else relistenLater(s);
			return;
		}
		s.socket = socket;
		s.failures = 0;
		BleLog.d("classic server listening");
		Thread t = new Thread(() -> runAccept(s, socket), "KnBle-rfcomm-server");
		s.acceptThread = t;
		t.start();
		if (!s.startedOnce) {
			s.startedOnce = true;
			mStarted = s;
			tell(s, true, BleClassicServerCallback::onServerStarted);
		}
	}

	private void relistenLater(@NonNull Session s) {
		if (++s.failures > RELISTEN_ATTEMPTS) {
			fail(s, BleError.INTERNAL);
			return;
		}
		mEngine.postDelayed(() -> listen(s, false), mToken, RELISTEN_DELAY_MS);
	}

	/**
	 * Thread of the server: one client after the other, until the socket closes
	 */
	private void runAccept(@NonNull Session s, @NonNull BluetoothServerSocket socket) {
		try {
			while (true) {
				BluetoothSocket client = socket.accept();
				if (client != null) mEngine.handler().post(() -> onAccepted(s, client));
			}
		} catch (IOException | RuntimeException e) {
			mEngine.handler().post(() -> onListenEnded(s, socket));
		}
	}

	private void onAccepted(@NonNull Session s, @NonNull BluetoothSocket client) {
		if (mSession != s) {
			ClassicLink.closeQuietly(client);
			return;
		}
		mEngine.accept(s, client);
	}

	/**
	 * The server socket closed: by KnBle, by Bluetooth turned off, or by the stack
	 */
	private void onListenEnded(@NonNull Session s, @NonNull BluetoothServerSocket socket) {
		if (mSession != s || s.socket != socket) return;
		s.socket = null;
		if (mEngine.engine().checkBluetoothEnabled()) {
			BleLog.d("classic server socket closed by Android: listening again");
			relistenLater(s);
		}
	}

	void onBluetoothOff() {
		Session s = mSession;
		if (s == null) return;
		mEngine.cancelDelayed(mToken);
		close(s);
	}

	void onBluetoothOn() {
		Session s = mSession;
		if (s == null || s.socket != null) return;
		mEngine.cancelDelayed(mToken);
		s.failures = 0;
		mEngine.postDelayed(() -> listen(s, false), mToken, BLUETOOTH_ON_DELAY_MS);
	}

	private void stopNow() {
		Session s = mSession;
		if (s == null) return;
		mSession = null;
		mEngine.cancelDelayed(mToken);
		close(s);
		// Its clients end too (onDisconnected(false, 0) after their last bytes)
		mEngine.disconnectClients(s);
		BleLog.d("classic server stopped");
	}

	private void fail(@NonNull Session s, int error) {
		BleLog.d("classic server failed: " + BleError.name(error));
		if (mSession == s) mSession = null;
		if (mStarted == s) mStarted = null;
		mEngine.cancelDelayed(mToken);
		close(s);
		tell(s, true, cb -> cb.onServerFailed(error));
	}

	private static void close(@NonNull Session s) {
		BluetoothServerSocket socket = s.socket;
		s.socket = null;
		Thread t = s.acceptThread;
		s.acceptThread = null;
		if (socket != null) {
			try {
				// Ends the accept in progress
				socket.close();
			} catch (IOException | RuntimeException e) {
				BleLog.w("classic server close", e);
			}
		}
		// The close is enough on Android, not on every stack
		if (t != null) t.interrupt();
	}

	/**
	 * An event for the app, dropped if a start or a stop came before its turn
	 */
	void tell(@NonNull Session s, boolean result, @NonNull Consumer<BleClassicServerCallback> event) {
		mDispatcher.post(() -> {
			if (!current(s)) return;
			if (result) s.answered = true;
			event.accept(s.callback);
		});
	}

	// endregion

	// region Tests

	@VisibleForTesting
	@Nullable
	BluetoothServerSocket socketForTests() {
		Session s = mStarted;
		return s == null ? null : s.socket;
	}

	// endregion
}
