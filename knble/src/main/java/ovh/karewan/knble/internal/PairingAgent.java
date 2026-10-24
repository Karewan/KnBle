package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.Utils;
import ovh.karewan.knble.interfaces.BleBondCallback;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicServerCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Pairings of the devices KnBle works with (createBond, a classic connection, a BLE connection, a client of a secure
 * classic server). Android sends its
 * ACTION_PAIRING_REQUEST as an ordered broadcast, received here first (thread of the engine, never blocked), before the
 * receiver of the system dialog:
 * <ol>
 * <li>a code given in advance is answered at once, without dialog;</li>
 * <li>else the callbacks of the app that handle pairings are told (their Looper): one may answer the code itself, the
 * broadcast is then aborted (no dialog of the system);</li>
 * <li>else the dialog of the system asks the user. Android shows it over the app only in some cases (a classic
 * discovery less than 60 s ago, the app that called createBond in the foreground on Android 14+...), else as a
 * notification the user may never see: while the app is in the foreground, KnBle starts a very short discovery
 * first, so the dialog shows.</li>
 * </ol>
 * The devices KnBle does not work with are never touched. An app can only give the codes Android asks for with setPin
 * (PIN, passkey), never confirm a number (BLUETOOTH_PRIVILEGED), and from Android 17 not at all when it targets API 37+.
 */
@SuppressLint("MissingPermission")
final class PairingAgent {
	// The callbacks decide within this time, else the dialog of the system (Android waits 10 s for a foreground broadcast)
	private static final long DECISION_TIMEOUT_MS = 8000;
	// The dialog of the system goes over the app when a discovery ran less than 60 s ago (Settings of Android)
	private static final long DISCOVERY_GRACE_MS = 50000;
	// Wait for the discovery to start before the system decides, then end it (it slows down the radio)
	private static final long DISCOVERY_START_WAIT_MS = 1500, DISCOVERY_POLL_MS = 20, DISCOVERY_STOP_AFTER_MS = 600;
	// EXTRA_PAIRING_ALGORITHM of Android 17+: BR/EDR legacy (a PIN), else LE legacy or secure connections (a passkey)
	private static final String EXTRA_PAIRING_ALGORITHM = "android.bluetooth.device.extra.PAIRING_ALGORITHM";
	// BluetoothDevice.EXTRA_PAIRING_CONTEXT and PAIRING_CONTEXT_REPAIRING (Android 17): the system pairs again a device that
	// lost its keys
	static final String EXTRA_PAIRING_CONTEXT = "android.bluetooth.device.extra.PAIRING_CONTEXT";
	static final int PAIRING_CONTEXT_REPAIRING = 2;
	private static final int ALGORITHM_LE_LEGACY = 0, ALGORITHM_BREDR_LEGACY = 1, ALGORITHM_SC = 3;
	private static final int UNKNOWN = 0, YES = 1, NO = 2;
	// Decisions, for the tests (Robolectric ignores an abort after goAsync)
	static final int DECIDED_SYSTEM = 1, DECIDED_IN_ADVANCE = 2, DECIDED_BY_APP = 3;
	// Callbacks of the app that override onPairingRequest (the others are not asked: no trip through the main thread)
	private static final ConcurrentHashMap<Class<?>, Boolean> sHandlesPairing = new ConcurrentHashMap<>();

	private interface AppAnswer {
		boolean onPairingRequest(@NonNull BlePairingRequest request);
	}

	@NonNull
	private final BleEngine mEngine;
	// Thread of the engine
	private boolean mRegistered;
	// Any thread (KnBle.getAdapterInfo): the probe gives the same answer each time
	private volatile int mCanSetPin = UNKNOWN;
	// elapsedRealtime of the last discovery started here for a dialog
	private long mDialogDiscoveryAt;
	// Current pairing request of each device: the answers to an older one (a dialog left open) are ignored
	private final HashMap<Long, Integer> mCurrent = new HashMap<>();
	private int mSequence;
	private volatile int mLastDecision;

	// Thread of the engine
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent != null && BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) onPairingRequest(this, intent);
		}
	};

	PairingAgent(@NonNull BleEngine engine) {
		mEngine = engine;
	}

	/**
	 * Thread of the engine, before the first pairing KnBle may run
	 */
	void register() {
		if (mRegistered) return;
		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
		// Ahead of the receiver of the system dialog (Settings): highest priority an app may use
		filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
		try {
			mEngine.context().registerReceiver(mReceiver, filter, null, mEngine.handler());
			mRegistered = true;
		} catch (RuntimeException e) {
			BleLog.w("register pairing receiver", e);
		}
	}

	void unregisterForTests() {
		if (!mRegistered) return;
		try {
			mEngine.context().unregisterReceiver(mReceiver);
		} catch (RuntimeException ignored) {
		}
		mRegistered = false;
	}

	/**
	 * Thread of the engine: a pairing that ended makes its requests stale
	 */
	void onBondStateChanged(long key, int state) {
		if (state != BluetoothDevice.BOND_BONDING) mCurrent.remove(key);
	}

	@SuppressWarnings("deprecation")
	private void onPairingRequest(@NonNull BroadcastReceiver receiver, @NonNull Intent intent) {
		BluetoothDevice device = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
				: intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
		if (device == null) return;
		int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
		int pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BlePairingRequest.NO_KEY);
		boolean repairing = intent.getIntExtra(EXTRA_PAIRING_CONTEXT, -1) == PAIRING_CONTEXT_REPAIRING;

		long key = Utils.macToLong(device.getAddress());
		BleEngine.BondRequest bond = mEngine.bondRequest(key);
		ClassicLink link = mEngine.classic().link(key);
		boolean classic = link != null && link.state() != BleGattCallback.DISCONNECTED;
		DeviceActor actor = mEngine.actor(key);
		boolean le = actor != null && actor.state() != BleGattCallback.DISCONNECTED;
		// A device KnBle does not work with, while a secure classic server listens: most likely a client of it, which
		// pairs before its connection is accepted
		ClassicServer.Session server = bond == null && !classic && !le ? mEngine.classic().serverPairing() : null;
		// Not a device of KnBle: untouched, the dialog of the system at once
		if (bond == null && !classic && !le && server == null) return;
		// The operation or the connection in progress waits for the user (some stacks give no BONDING state first)
		if (le) actor.submit(actor::onPairingActivity);
		if (classic) mEngine.classic().onBondStateChanged(key, BluetoothDevice.BOND_BONDING);

		boolean codeAsked = variant == BlePairingRequest.VARIANT_PIN || variant == BlePairingRequest.VARIANT_PIN_16_DIGITS;
		boolean passkey = codeAsked && isPasskey(intent, variant, classic || server != null, bond, le);
		boolean answerable = codeAsked && canSetPin();
		int id = ++mSequence;
		mCurrent.put(key, id);
		BleLog.d("pairing request variant " + variant + (codeAsked ? passkey ? " (passkey)" : " (PIN)" : "") + (answerable ? "" : ", answered by the user")
				+ (repairing ? ", re-pairing of the system" : ""));

		// A code given in advance
		String pin = bond != null && bond.pin != null ? bond.pin : classic ? link.pairingPin() : server != null ? server.options.getPin() : null;
		if (answerable && pin != null) {
			byte[] bytes = BlePairingRequest.encode(pin, passkey);
			if (bytes != null && giveCode(key, device, bytes)) {
				BleLog.d("pairing: code given in advance");
				mLastDecision = DECIDED_IN_ADVANCE;
				if (receiver.isOrderedBroadcast()) receiver.abortBroadcast();
				return;
			}
			BleLog.d("pairing: the code given in advance is not a valid " + (passkey ? "passkey" : "PIN"));
			answerable = mCanSetPin == YES;
		}

		// The callbacks that handle pairings, each once (an object may be several of them)
		List<Object> callbacks = new ArrayList<>();
		if (bond != null) for (BleBondCallback cb : bond.callbacks) addHandler(callbacks, cb);
		if (classic) addHandler(callbacks, link.callback());
		if (le) addHandler(callbacks, actor.callback());
		if (server != null) addHandler(callbacks, server.callback);
		List<AppAnswer> handlers = new ArrayList<>();
		for (Object cb : callbacks) {
			if (cb instanceof BleBondCallback b) handlers.add(b::onPairingRequest);
			else if (cb instanceof BleClassicCallback c) handlers.add(c::onPairingRequest);
			else if (cb instanceof BleGattCallback g) handlers.add(g::onPairingRequest);
			else if (cb instanceof BleClassicServerCallback v) handlers.add(v::onPairingRequest);
		}
		boolean showDialog = needsDiscoveryForDialog(bond);
		mLastDecision = DECIDED_SYSTEM;
		if (handlers.isEmpty() && !showDialog) return;

		// The broadcast waits for the decision; the thread of the engine does not
		BroadcastReceiver.PendingResult pending = receiver.goAsync();
		AtomicBoolean finished = new AtomicBoolean(), aborted = new AtomicBoolean();
		Handler h = mEngine.handler();
		Runnable guard = () -> {
			if (finished.compareAndSet(false, true)) {
				BleLog.d("pairing: no decision in time, dialog of the system");
				pending.finish();
			}
		};
		h.postDelayed(guard, DECISION_TIMEOUT_MS);
		// To the dialog of the system, shown over the app when needed
		Runnable toSystem = () -> {
			if (finished.get()) return;
			if (showDialog) startDiscoveryThen(pending, finished, guard);
			else if (finished.compareAndSet(false, true)) {
				h.removeCallbacks(guard);
				pending.finish();
			}
		};
		BleDevice d = new BleDevice(device);
		boolean canAnswer = answerable;

		if (!canAnswer || handlers.isEmpty()) {
			// The app is told (a hint for the user, the number to compare), the system asks the user
			if (!handlers.isEmpty()) {
				BlePairingRequest request = request(d, key, id, variant, passkey, pairingKey, false, repairing, aborted);
				mEngine.dispatcher().post(() -> {
					for (AppAnswer handler : handlers) handler.onPairingRequest(request);
				});
			}
			toSystem.run();
			return;
		}
		mEngine.dispatcher().post(() -> {
			// Too late (the main thread was busy): the dialog of the system already asks the user
			boolean late = finished.get();
			BlePairingRequest request = request(d, key, id, variant, passkey, pairingKey, !late, repairing, aborted);
			boolean handled = false;
			try {
				for (AppAnswer handler : handlers) {
					if (handler.onPairingRequest(request) && !late) {
						handled = true;
						break;
					}
				}
			} finally {
				if (!handled) {
					if (!late) h.post(toSystem);
				} else if (finished.compareAndSet(false, true)) {
					h.removeCallbacks(guard);
					BleLog.d("pairing: answered by the app");
					mLastDecision = DECIDED_BY_APP;
					aborted.set(true);
					try {
						pending.abortBroadcast();
					} catch (RuntimeException e) {
						BleLog.w("abortBroadcast", e);
					}
					pending.finish();
				}
			}
		});
	}

	/**
	 * A callback of the app that overrides onPairingRequest, not twice
	 */
	private static void addHandler(@NonNull List<Object> callbacks, @Nullable Object cb) {
		if (cb == null) return;
		for (Object c : callbacks) if (c == cb) return;
		Boolean handles = sHandlesPairing.computeIfAbsent(cb.getClass(), type -> {
			try {
				Method m = type.getMethod("onPairingRequest", BlePairingRequest.class);
				// The default method of the interface (a lambda, or not overridden): the dialog of the system
				return !m.getDeclaringClass().isInterface();
			} catch (NoSuchMethodException e) {
				return false;
			}
		});
		if (handles) callbacks.add(cb);
	}

	/**
	 * A BLE passkey (6 digits) or a classic PIN: the algorithm given by Android 17+, else by what KnBle does with the
	 * device
	 */
	private static boolean isPasskey(@NonNull Intent intent, int variant, boolean classic, @Nullable BleEngine.BondRequest bond, boolean le) {
		if (variant != BlePairingRequest.VARIANT_PIN) return false;
		int algorithm = intent.getIntExtra(EXTRA_PAIRING_ALGORITHM, -1);
		if (algorithm == ALGORITHM_BREDR_LEGACY) return false;
		if (algorithm == ALGORITHM_LE_LEGACY || algorithm == ALGORITHM_SC) return true;
		return !classic && (bond != null ? bond.le : le);
	}

	@NonNull
	private BlePairingRequest request(@NonNull BleDevice d, long key, int id, int variant, boolean passkey, int pairingKey, boolean canAnswer,
									  boolean repairing, @NonNull AtomicBoolean aborted) {
		BluetoothDevice device = d.getDevice();
		return new BlePairingRequest(d, variant, passkey, pairingKey, canAnswer, repairing, new BlePairingRequest.Responder() {
			@Override
			public void setPin(@NonNull byte[] code) {
				mEngine.handler().post(() -> {
					if (!isCurrent(key, id)) {
						BleLog.d("pairing: code of an ended pairing, ignored");
						return;
					}
					boolean given = giveCode(key, device, code);
					BleLog.d("pairing: code of the app given " + given);
					// Refused by Android (the dialog of the system was not shown): the operations fail at once
					if (!given && mCanSetPin == NO) abandon(key, device, passkey, variant, aborted.get(), BleError.PERMISSION_DENIED);
				});
			}

			@Override
			public void cancel() {
				mEngine.handler().post(() -> {
					if (isCurrent(key, id)) abandon(key, device, passkey, variant, aborted.get(), BleError.CANCELLED);
					else BleLog.d("pairing: cancel of an ended pairing, ignored");
				});
			}
		});
	}

	private boolean isCurrent(long key, int id) {
		Integer current = mCurrent.get(key);
		return current != null && current == id;
	}

	// region Dialog of the system over the app

	/**
	 * Thread of the engine: the app is in the foreground, the screen on, and Android would put the dialog in a
	 * notification (no discovery lately, not a createBond of the app on Android 14+)
	 */
	private boolean needsDiscoveryForDialog(@Nullable BleEngine.BondRequest bond) {
		if (!mEngine.settings().isPairingDialogInForeground()) return false;
		// Android 14+ shows the dialog over the app that started the pairing
		if (bond != null && bond.started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
		try {
			ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
			ActivityManager.getMyMemoryState(info);
			if (info.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return false;
			PowerManager pm = (PowerManager) mEngine.context().getSystemService(Context.POWER_SERVICE);
			if (pm != null && !pm.isInteractive()) return false;
			// A discovery of KnBle (or one started here) ran lately: Android already shows the dialog
			long now = SystemClock.elapsedRealtime();
			long last = Math.max(mDialogDiscoveryAt, mEngine.classic().lastDiscoveryAt());
			if (last != 0 && now - last < DISCOVERY_GRACE_MS) return false;
			return !mEngine.adapter().isDiscovering();
		} catch (RuntimeException e) {
			// No scan permission (BLUETOOTH_SCAN, location before Android 12): as the system decides
			BleLog.w("pairing dialog", e);
			return false;
		}
	}

	/**
	 * Thread of the engine: a discovery starts, the broadcast goes on to the system once it runs (Android then shows the
	 * dialog over the app), the discovery ends right after
	 */
	private void startDiscoveryThen(@NonNull BroadcastReceiver.PendingResult pending, @NonNull AtomicBoolean finished, @NonNull Runnable guard) {
		BluetoothAdapter adapter = mEngine.adapter();
		boolean started;
		try {
			started = adapter.startDiscovery();
		} catch (RuntimeException e) {
			BleLog.w("startDiscovery", e);
			started = false;
		}
		BleLog.d("pairing: discovery for the dialog of the system " + started);
		if (started) mDialogDiscoveryAt = SystemClock.elapsedRealtime();
		long until = SystemClock.elapsedRealtime() + DISCOVERY_START_WAIT_MS;
		Handler h = mEngine.handler();
		boolean ok = started;
		Runnable poll = new Runnable() {
			@Override
			public void run() {
				boolean discovering;
				try {
					discovering = ok && adapter.isDiscovering();
				} catch (RuntimeException e) {
					discovering = false;
				}
				if (ok && !discovering && SystemClock.elapsedRealtime() < until && !finished.get()) {
					h.postDelayed(this, DISCOVERY_POLL_MS);
					return;
				}
				if (finished.compareAndSet(false, true)) {
					h.removeCallbacks(guard);
					pending.finish();
				}
				// The discovery was only for the dialog (unless the app started one meanwhile)
				if (ok) h.postDelayed(() -> {
					if (mEngine.classic().isDiscovering()) return;
					try {
						adapter.cancelDiscovery();
					} catch (RuntimeException e) {
						BleLog.w("cancelDiscovery", e);
					}
				}, DISCOVERY_STOP_AFTER_MS);
			}
		};
		poll.run();
	}

	// endregion

	// region Answers (thread of the engine)

	/**
	 * Binder call. setPin is deprecated by Android 17: refused to the apps targeting API 37+ (canSetPin checks it).
	 */
	@SuppressWarnings("deprecation")
	static boolean setPin(@NonNull BluetoothDevice device, @NonNull byte[] pin) {
		try {
			return device.setPin(pin);
		} catch (RuntimeException e) {
			BleLog.w("setPin", e);
			return false;
		}
	}

	/**
	 * setPin, a refusal of Android (SecurityException) remembered: the next requests go to the dialog of the system
	 */
	@SuppressWarnings("deprecation")
	private boolean giveCode(long key, @NonNull BluetoothDevice device, @NonNull byte[] code) {
		try {
			return device.setPin(code);
		} catch (SecurityException e) {
			BleLog.w("setPin refused by Android", e);
			mCanSetPin = NO;
			return false;
		} catch (RuntimeException e) {
			BleLog.w("setPin", e);
			return false;
		}
	}

	/**
	 * Android 17 (pairing hardening): BluetoothDevice.setPin needs BLUETOOTH_PRIVILEGED for an app targeting API 37+.
	 * Asked once to the stack with a device that is not pairing: a SecurityException when refused (checked before the
	 * pairing state by Android 17), else no effect.
	 */
	boolean canSetPin() {
		if (mCanSetPin != UNKNOWN) return mCanSetPin == YES;
		boolean can = probeSetPin(mEngine.context(), mEngine.adapter());
		mCanSetPin = can ? YES : NO;
		if (!can) BleLog.d("pairing codes refused by Android to the apps targeting API 37+: the user types them in the dialog of the system");
		return can;
	}

	/**
	 * Any thread, a binder call on Android 17+ for an app targeting API 37+ (else none)
	 */
	@SuppressWarnings("deprecation")
	static boolean probeSetPin(@NonNull Context c, @Nullable BluetoothAdapter adapter) {
		if (Build.VERSION.SDK_INT < 37 || c.getApplicationInfo().targetSdkVersion < 37
				|| c.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
			return true;
		}
		if (adapter == null) return false;
		try {
			adapter.getRemoteDevice("00:00:00:00:00:00").setPin(new byte[]{'0'});
		} catch (SecurityException e) {
			return false;
		} catch (RuntimeException e) {
			BleLog.w("setPin check", e);
		}
		return true;
	}

	/**
	 * The app gave up the pairing (CANCELLED), or Android refused its code (PERMISSION_DENIED): the pairing ends as
	 * soon as Android allows it, the operations that needed it fail at once with the error
	 * @param aborted the dialog of the system was not shown (the app answers): an empty PIN is the negative answer of a
	 *                code asked for a classic createBond
	 */
	private void abandon(long key, @NonNull BluetoothDevice device, boolean passkey, int variant, boolean aborted, int error) {
		mCurrent.remove(key);
		BleEngine.BondRequest bond = mEngine.bondRequest(key);
		boolean byApp = bond != null && bond.started;
		boolean ended = false;
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
			// Android 7 to 11: the hidden cancelBondProcess (BLUETOOTH_ADMIN)
			ended = cancelBondProcessHidden(device);
		} else if (Build.VERSION.SDK_INT >= 37 && byApp) {
			// Android 17: allowed to the app that started the pairing
			try {
				ended = device.cancelBondProcess();
			} catch (RuntimeException | LinkageError e) {
				BleLog.w("cancelBondProcess", e);
			}
		}
		// Android 12 to 16: an empty PIN makes the stack answer no to a PIN request of a dedicated bonding (createBond),
		// only while it waits for the code (no effect on a confirmation, nor on the pairing of a secure socket or BLE)
		boolean pinAsked = variant == BlePairingRequest.VARIANT_PIN || variant == BlePairingRequest.VARIANT_PIN_16_DIGITS;
		if (!ended && aborted && pinAsked && !passkey && byApp && mCanSetPin == YES) ended = setPin(device, new byte[0]);
		BleLog.d("pairing abandoned (" + BleError.name(error) + "), ended by Android at once: " + ended);
		mEngine.endBond(key, error);
		mEngine.classic().onPairingAbandoned(key, error);
		DeviceActor actor = mEngine.actor(key);
		if (actor != null) actor.submit(() -> actor.onPairingAbandoned(error));
	}

	@SuppressWarnings("JavaReflectionMemberAccess")
	private static boolean cancelBondProcessHidden(@NonNull BluetoothDevice device) {
		try {
			Method m = BluetoothDevice.class.getMethod("cancelBondProcess");
			return Boolean.TRUE.equals(m.invoke(device));
		} catch (Exception e) {
			BleLog.w("cancelBondProcess", e);
			return false;
		}
	}

	// endregion

	@VisibleForTesting
	int lastDecision() {
		return mLastDecision;
	}

	@VisibleForTesting
	boolean isRegistered() {
		return mRegistered;
	}

	@VisibleForTesting
	void setCanSetPinForTests(boolean can) {
		mCanSetPin = can ? YES : NO;
	}
}
