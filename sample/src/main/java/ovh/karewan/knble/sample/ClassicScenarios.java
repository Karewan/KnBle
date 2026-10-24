package ovh.karewan.knble.sample;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.zip.CRC32;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.ClassicOptions;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicDiscoveryCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Bluetooth classic on the ESP32 bench of testbench/esp32-classic (SPP server "KnBleSpp" emulating a radio receiver):
 * discovery, connection, latency, throughput both ways, the receiver stream with commands, a frozen main thread, a lost
 * link, and the same transfers with a plain Android socket as the reference of what the phone can do.
 */
@SuppressLint("MissingPermission")
final class ClassicScenarios extends Scenarios {
	static final String NAME = "KnBleSpp";
	private static final long CONNECT_TIMEOUT = 30000;
	private static final long COMMAND_TIMEOUT = 5000;
	private static final int TRANSFER = 1024 * 1024;

	// Found by the discovery, kept for the next scenarios of the process
	@Nullable
	private static volatile BleDevice sDevice;
	// The runs from adb connect without pairing (no dialog), "Classic: secure connection" pairs
	private static final ClassicOptions INSECURE = new ClassicOptions.Builder().setSecure(false).build();
	// Options of the transfers: insecure for the ESP32 bench, secure for a bench that needs a bond (the Mac)
	private static volatile ClassicOptions sOptions = INSECURE;
	// PIN given in advance to the secure connections ("--es classicpin"), null: asked (PairingUi)
	@Nullable
	static volatile String sPin;

	/**
	 * The device of the scenarios: found by the discovery (name KnBleSpp), or given by its MAC ("--es classic")
	 */
	@Nullable
	static BleDevice target() {
		return sDevice;
	}

	// Service of the bench: SPP, or another UUID ("--es classicuuid")
	private static volatile java.util.UUID sUuid = ClassicOptions.SPP;

	static void setTarget(@Nullable String mac, boolean secure, @Nullable String uuid) {
		sDevice = mac == null ? null : KnBle.getBleDeviceFromMac(mac);
		sUuid = uuid == null ? ClassicOptions.SPP : java.util.UUID.fromString(uuid);
		sOptions = new ClassicOptions.Builder().setUuid(sUuid).setSecure(secure).build();
	}

	@NonNull
	private static ClassicOptions secure() {
		String pin = sPin;
		return new ClassicOptions.Builder().setUuid(sUuid).setPin(pin).build();
	}

	private final Rx mRx = new Rx();
	private volatile CountDownLatch mConnected = new CountDownLatch(1), mDisconnected = new CountDownLatch(1);
	private volatile int mLastStatus;
	private volatile long mDisconnectedAtNs;
	// Reason of the last onBondLost (Android 16+), -1 for none
	private volatile int mBondLostReason = -1;
	private final AtomicInteger mConnections = new AtomicInteger();

	private final BleClassicCallback mCallback = new BleClassicCallback() {
		@Override
		public void onData(@NonNull byte[] data) {
			mRx.feed(data);
		}

		@Override
		public void onConnected() {
			mConnections.incrementAndGet();
			mConnected.countDown();
		}

		@Override
		public boolean onPairingRequest(@NonNull BlePairingRequest request) {
			return PairingUi.onPairingRequest(request);
		}

		@Override
		public void onBondLost(int reason) {
			mBondLostReason = reason;
		}

		@Override
		public void onDisconnected(boolean connectFailed, int status) {
			mLastStatus = status;
			mDisconnectedAtNs = SystemClock.elapsedRealtimeNanos();
			mDisconnected.countDown();
			mConnected.countDown();
		}
	};

	ClassicScenarios(@NonNull Report report, @NonNull IntConsumer progress) {
		super(report, progress);
	}

	// region Receiver of the stream (main thread: onData)

	/**
	 * Text lines (answers and "$KNB" frames of the receiver), or a counted run of pattern bytes (STREAM)
	 */
	private static final class Rx {
		final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
		private final StringBuilder mLine = new StringBuilder();
		// Pattern bytes still expected, then back to the lines
		private long mCountLeft, mCountTotal, mCountErrors;
		volatile long countDoneNs;
		final AtomicInteger dataCalls = new AtomicInteger();
		volatile int maxChunk;
		// Frames of the receiver
		long frames, framesLost, framesBad, framesOutOfOrder, nextSeq = -1;

		synchronized void expectPattern(long bytes) {
			mCountLeft = mCountTotal = bytes;
			mCountErrors = 0;
			countDoneNs = 0;
		}

		synchronized long patternErrors() {
			return mCountErrors;
		}

		synchronized void resetFrames() {
			frames = framesLost = framesBad = framesOutOfOrder = 0;
			nextSeq = -1;
		}

		synchronized void feed(@NonNull byte[] data) {
			dataCalls.incrementAndGet();
			if (data.length > maxChunk) maxChunk = data.length;
			int i = 0;
			while (i < data.length) {
				if (mCountLeft > 0) {
					long base = mCountTotal - mCountLeft;
					int n = (int) Math.min(mCountLeft, data.length - i);
					for (int k = 0; k < n; k++) {
						long p = base + k;
						if (data[i + k] != (byte) (p * 31 + (p >> 8))) mCountErrors++;
					}
					i += n;
					mCountLeft -= n;
					if (mCountLeft == 0) countDoneNs = SystemClock.elapsedRealtimeNanos();
					continue;
				}
				char c = (char) (data[i++] & 0xFF);
				if (c == '\r') continue;
				if (c != '\n') {
					mLine.append(c);
					continue;
				}
				String line = mLine.toString();
				mLine.setLength(0);
				if (line.startsWith("$KNB,")) frame(line);
				else lines.offer(line);
			}
		}

		private void frame(@NonNull String line) {
			frames++;
			int star = line.lastIndexOf('*');
			int comma = line.indexOf(',', 5);
			if (star < 0 || comma < 0) {
				framesBad++;
				return;
			}
			int x = 0;
			for (int k = 1; k < star; k++) x ^= line.charAt(k);
			try {
				if (Integer.parseInt(line.substring(star + 1), 16) != x) framesBad++;
				long seq = Long.parseLong(line.substring(5, comma));
				if (nextSeq >= 0 && seq > nextSeq) framesLost += seq - nextSeq;
				else if (nextSeq >= 0 && seq < nextSeq) framesOutOfOrder++;
				nextSeq = seq + 1;
			} catch (NumberFormatException e) {
				framesBad++;
			}
		}
	}

	// endregion

	// region Helpers (scenario thread)

	private static boolean await(@NonNull CountDownLatch latch, long ms) {
		try {
			return latch.await(ms, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@Nullable
	private BleDevice device() {
		BleDevice d = sDevice;
		if (d == null) classicDiscovery();
		return sDevice;
	}

	/**
	 * Connected to the bench (connects when needed)
	 * @return ms of the connection, 0 when already connected, -1 on failure
	 */
	private long ensureConnected(@NonNull ClassicOptions options, boolean autoReconnect) {
		BleDevice d = device();
		if (d == null) return -1;
		if (KnBle.isClassicConnected(d)) return 0;
		mConnected = new CountDownLatch(1);
		mDisconnected = new CountDownLatch(1);
		int before = mConnections.get();
		long start = SystemClock.elapsedRealtimeNanos();
		KnBle.connectClassic(d, options, autoReconnect, mCallback);
		if (!await(mConnected, CONNECT_TIMEOUT) || mConnections.get() == before) {
			r.log("Classic connection failed: %s", BleError.name(mLastStatus));
			return -1;
		}
		return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000;
	}

	private void dropClassic() {
		BleDevice d = sDevice;
		if (d == null || KnBle.getClassicConnState(d) == ovh.karewan.knble.interfaces.BleGattCallback.DISCONNECTED) return;
		mDisconnected = new CountDownLatch(1);
		KnBle.disconnectClassic(d);
		await(mDisconnected, COMMAND_TIMEOUT);
	}

	private void send(@NonNull String line) {
		BleDevice d = sDevice;
		if (d != null) KnBle.writeClassic(d, (line + "\n").getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * The first answer line starting with the prefix, null on timeout
	 */
	@Nullable
	private String awaitLine(@NonNull String prefix, long timeoutMs) {
		long deadline = SystemClock.elapsedRealtime() + timeoutMs;
		try {
			while (true) {
				long left = deadline - SystemClock.elapsedRealtime();
				if (left <= 0) return null;
				String line = mRx.lines.poll(left, TimeUnit.MILLISECONDS);
				if (line == null) return null;
				if (line.startsWith(prefix)) return line;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	@Nullable
	private String command(@NonNull String line, @NonNull String answerPrefix) {
		mRx.lines.clear();
		send(line);
		return awaitLine(answerPrefix, COMMAND_TIMEOUT);
	}

	private static long ms(long fromNs) {
		return (SystemClock.elapsedRealtimeNanos() - fromNs) / 1_000_000;
	}

	@NonNull
	private static byte[] payload(int size) {
		return Bench.image(size, IMAGE_SEED);
	}

	// endregion

	// region Scenarios

	void classicDiscovery() {
		long start = SystemClock.elapsedRealtimeNanos();
		CountDownLatch finished = new CountDownLatch(1);
		AtomicLong foundMs = new AtomicLong(-1);
		AtomicInteger devices = new AtomicInteger();
		KnBle.startClassicDiscovery(new BleClassicDiscoveryCallback() {
			@Override
			public void onDeviceFound(@NonNull BleDevice device) {
				if (NAME.equals(device.getName()) && foundMs.get() < 0) {
					foundMs.set(ms(start));
					sDevice = device;
					r.log("Classic bench %s, RSSI %d", device.getMac(), device.getRssi());
				}
			}

			@Override
			public void onDiscoveryFinished(@NonNull List<BleDevice> list) {
				devices.set(list.size());
				finished.countDown();
			}

			@Override
			public void onDiscoveryFailed(int error) {
				r.log("Classic discovery failed: %s", BleError.name(error));
				finished.countDown();
			}
		});
		// Stopped as soon as the bench is there: the inquiry would slow down the connection
		long until = SystemClock.elapsedRealtime() + 20000;
		while (foundMs.get() < 0 && finished.getCount() > 0 && SystemClock.elapsedRealtime() < until) sleep(50);
		KnBle.stopClassicDiscovery();
		await(finished, 3000);
		r.result("classic_discovery", "found=%b foundMs=%d devices=%d totalMs=%d", foundMs.get() >= 0, foundMs.get(), devices.get(), ms(start));
	}

	void classicConnect() {
		dropClassic();
		long ms = ensureConnected(sOptions, false);
		String info = ms < 0 ? null : command("INFO", "INFO");
		r.result("classic_connect", "ok=%b ms=%d info=%s", ms >= 0, ms, info == null ? "-" : info.replace(' ', '_'));

		// Connections and disconnections in a row
		List<Long> times = new ArrayList<>();
		int failed = 0;
		for (int i = 0; i < 10; i++) {
			dropClassic();
			long t = ensureConnected(sOptions, false);
			if (t < 0) failed++;
			else times.add(t);
		}
		long sum = 0;
		for (long t : times) sum += t;
		r.result("classic_connect_cycles", "cycles=10 ok=%d failed=%d avgMs=%d p50Ms=%d maxMs=%d", times.size(), failed,
				times.isEmpty() ? 0 : sum / times.size(), percentile(times, 0.5), percentile(times, 1));
	}

	void classicSecureConnect() {
		dropClassic();
		r.log("Secure connection: accept the pairing on the phone if asked");
		long ms = ensureConnected(secure(), false);
		String info = ms < 0 ? null : command("INFO", "INFO");
		r.result("classic_secure_connect", "ok=%b ms=%d bonded=%b info=%s status=%s", ms >= 0 && info != null, ms,
				sDevice != null && KnBle.isBonded(sDevice), info == null ? "-" : info.replace(' ', '_'), BleError.name(mLastStatus));
		dropClassic();
	}

	/**
	 * The bench forces a pairing with a code (PAIR 1: authentication required, passkey on its serial port): secure
	 * connection, the user types the code in the dialog of the system, later than the connection timeout (the harness
	 * testbench/tools/type_passkey.py types it after 40 s). KnBle waits for the pairing, then the data flows. The bench
	 * goes back to the open mode at the end.
	 */
	void classicPairingWithCode() {
		dropClassic();
		if (ensureConnected(sOptions, false) < 0) return;
		BleDevice d = sDevice;
		mDisconnected = new CountDownLatch(1);
		if (command("PAIR 1", "PAIR") == null) return;
		// The bench reboots in the forced mode, its bonds removed: the phone forgets its own
		await(mDisconnected, COMMAND_TIMEOUT);
		KnBle.removeBond(d);
		sleep(4000);

		r.log("Pairing: type the passkey of the bench in the dialog of the phone");
		mConnected = new CountDownLatch(1);
		mDisconnected = new CountDownLatch(1);
		int before = mConnections.get();
		long start = SystemClock.elapsedRealtimeNanos();
		KnBle.connectClassic(d, secure(), false, mCallback);
		boolean connected = await(mConnected, 120000) && mConnections.get() > before;
		long ms = ms(start);
		String info = connected ? command("INFO", "INFO") : null;
		r.result("classic_pairing", "ok=%b ms=%d bonded=%b status=%s info=%s", connected && info != null, ms, KnBle.isBonded(d),
				BleError.name(mLastStatus), info == null ? "-" : info.replace(' ', '_'));

		// Back to the open mode
		if (connected) {
			mDisconnected = new CountDownLatch(1);
			command("PAIR 0", "PAIR");
			await(mDisconnected, COMMAND_TIMEOUT);
		}
		dropClassic();
		KnBle.removeBond(d);
		sleep(4000);
	}

	void classicLatency(int count) {
		if (ensureConnected(sOptions, false) < 0) return;
		// Out of sniff mode first
		command("PING 0", "PONG");
		List<Long> rtt = new ArrayList<>();
		int lost = 0;
		for (int i = 1; i <= count; i++) {
			long start = SystemClock.elapsedRealtimeNanos();
			String pong = command("PING " + i, "PONG " + i);
			if (pong == null) lost++;
			else rtt.add((SystemClock.elapsedRealtimeNanos() - start) / 1000);
		}
		r.result("classic_ping", "count=%d lost=%d p50Us=%d p95Us=%d maxUs=%d", count, lost, percentile(rtt, 0.5), percentile(rtt, 0.95), percentile(rtt, 1));
	}

	/**
	 * STREAM: the bench sends as fast as the link goes
	 * @return ms from the command to the last byte delivered to the app, -1 on failure
	 */
	private long classicReceive(@NonNull String name, int size, @Nullable Runnable during) {
		if (ensureConnected(sOptions, false) < 0) return -1;
		command("PING 0", "PONG");
		mRx.dataCalls.set(0);
		mRx.maxChunk = 0;
		mRx.expectPattern(size);
		mRx.lines.clear();
		long start = SystemClock.elapsedRealtimeNanos();
		send("STREAM " + size);
		if (during != null) during.run();
		String end = awaitLine("END", 60000);
		long doneNs = mRx.countDoneNs;
		long ms = doneNs == 0 ? -1 : (doneNs - start) / 1_000_000;
		long benchMs = end == null ? -1 : Long.parseLong(end.split(" ")[2]);
		long errors = mRx.patternErrors();
		r.result(name, "size=%d ms=%d kbps=%.1f benchMs=%d ok=%b patternErrors=%d dataCalls=%d maxChunk=%d", size, ms, kbps(size, ms), benchMs,
				end != null && errors == 0, errors, mRx.dataCalls.get(), mRx.maxChunk);
		return ms;
	}

	void classicReceive() {
		classicReceive("classic_receive", TRANSFER, null);
	}

	/**
	 * SINK: the phone sends, the bench counts and checks the CRC
	 */
	void classicSend(int chunk) {
		send("classic_send", chunk);
	}

	/**
	 * @return KB/s, 0 on failure
	 */
	private double send(@NonNull String name, int chunk) {
		if (ensureConnected(sOptions, false) < 0) return 0;
		byte[] data = payload(TRANSFER);
		CRC32 crc = new CRC32();
		crc.update(data);
		if (command("SINK " + data.length, "SINK READY") == null) {
			r.result(name, "chunk=%d status=no_ready", chunk);
			return 0;
		}
		long start = SystemClock.elapsedRealtimeNanos();
		BleDevice d = sDevice;
		for (int off = 0; off < data.length; off += chunk) {
			int n = Math.min(chunk, data.length - off);
			byte[] part = new byte[n];
			System.arraycopy(data, off, part, 0, n);
			KnBle.writeClassic(d, part);
		}
		long queuedUs = (SystemClock.elapsedRealtimeNanos() - start) / 1000;
		String answer = awaitLine("SINK " + data.length, 60000);
		long ms = ms(start);
		String[] p = answer == null ? new String[0] : answer.split(" ");
		boolean crcOk = p.length >= 4 && Long.parseLong(p[2], 16) == crc.getValue();
		r.result(name, "size=%d chunk=%d ms=%d kbps=%.1f benchMs=%s crcOk=%b callerUs=%d", data.length, chunk, ms, kbps(data.length, ms),
				p.length >= 4 ? p[3] : "-", crcOk, queuedUs);
		return crcOk ? kbps(data.length, ms) : 0;
	}

	/**
	 * The radio receiver: frames at a steady rate, PING commands meanwhile, nothing lost
	 */
	void classicReceiver(int seconds) {
		if (ensureConnected(sOptions, false) < 0) return;
		command("PING 0", "PONG");
		synchronized (mRx) {
			mRx.resetFrames();
		}
		mRx.lines.clear();
		send("RATE 20000 100");
		long until = SystemClock.elapsedRealtime() + seconds * 1000L;
		List<Long> rtt = new ArrayList<>();
		int lost = 0, i = 0;
		while (SystemClock.elapsedRealtime() < until) {
			i++;
			long start = SystemClock.elapsedRealtimeNanos();
			send("PING " + i);
			if (awaitLine("PONG " + i, COMMAND_TIMEOUT) == null) lost++;
			else rtt.add((SystemClock.elapsedRealtimeNanos() - start) / 1000);
			sleep(100);
		}
		send("STOP");
		String stop = awaitLine("STOP", COMMAND_TIMEOUT);
		sleep(200);
		long sent = stop == null ? -1 : Long.parseLong(stop.split(" ")[1]);
		synchronized (mRx) {
			r.result("classic_receiver", "seconds=%d sent=%d frames=%d lost=%d bad=%d outOfOrder=%d pings=%d pingsLost=%d pingP50Us=%d pingP95Us=%d pingMaxUs=%d",
					seconds, sent, mRx.frames, mRx.framesLost, mRx.framesBad, mRx.framesOutOfOrder, rtt.size() + lost, lost, percentile(rtt, 0.5),
					percentile(rtt, 0.95), percentile(rtt, 1));
		}
	}

	/**
	 * The main thread (where onData runs) frozen 3 s during a receive: the reader of KnBle goes on, the bench never waits
	 */
	void classicFrozenMain() {
		long ref = classicReceive("classic_frozen_main_ref", TRANSFER, null);
		classicReceive("classic_frozen_main", TRANSFER, () -> mMain.post(() -> SystemClock.sleep(3000)));
		r.log("Reference %d ms: with the main thread frozen 3 s, compare benchMs (the side of the device)", ref);
	}

	/**
	 * The bench closes the connection: onDisconnected(CONNECTION_LOST), then the auto-reconnection
	 */
	void classicLinkLoss() {
		dropClassic();
		if (ensureConnected(sOptions, true) < 0) return;
		int before = mConnections.get();
		mDisconnected = new CountDownLatch(1);
		if (command("DISC 300", "DISC") == null) return;
		long expected = SystemClock.elapsedRealtimeNanos() + 300_000_000L;
		boolean lost = await(mDisconnected, 15000);
		long latency = lost ? (mDisconnectedAtNs - expected) / 1_000_000 : -1;
		int status = mLastStatus;
		long start = SystemClock.elapsedRealtimeNanos();
		long until = SystemClock.elapsedRealtime() + 20000;
		while (mConnections.get() == before && SystemClock.elapsedRealtime() < until) sleep(20);
		boolean back = mConnections.get() > before;
		r.result("classic_link_loss", "lost=%b status=%s latencyMs=%d reconnected=%b reconnectMs=%d", lost, BleError.name(status), latency, back,
				back ? ms(start) : -1);
		dropClassic();
	}

	/**
	 * The bench in a pairing mode (PAIR n: it reboots, its bonds removed), the phone forgets its bond too
	 */
	private boolean pairMode(int mode) {
		dropClassic();
		if (ensureConnected(sOptions, false) < 0) return false;
		BleDevice d = sDevice;
		mDisconnected = new CountDownLatch(1);
		if (command("PAIR " + mode, "PAIR") == null) return false;
		await(mDisconnected, COMMAND_TIMEOUT);
		KnBle.removeBond(d);
		sleep(4000);
		return true;
	}

	/**
	 * Secure connection in the pairing mode of the bench, with what Android asked and who answered
	 * @param pin Given in advance (ClassicOptions.setPin), null: asked to PairingUi
	 */
	private boolean securePairing(@NonNull String name, @Nullable String pin, @Nullable String autoPin, long timeoutMs) {
		BleDevice d = sDevice;
		if (d == null) return false;
		String auto = PairingUi.sAutoPin;
		if (autoPin != null) PairingUi.sAutoPin = autoPin;
		PairingUi.sLast = null;
		PairingUi.sLastAnsweredByApp = false;
		mConnected = new CountDownLatch(1);
		mDisconnected = new CountDownLatch(1);
		int before = mConnections.get();
		long start = SystemClock.elapsedRealtimeNanos();
		KnBle.connectClassic(d, new ClassicOptions.Builder().setUuid(sUuid).setPin(pin).build(), false, mCallback);
		boolean connected = await(mConnected, timeoutMs) && mConnections.get() > before;
		long ms = ms(start);
		PairingUi.sAutoPin = auto;
		String info = connected ? command("INFO", "INFO") : null;
		BlePairingRequest q = PairingUi.sLast;
		r.result(name, "ok=%b ms=%d bonded=%b status=%s pinInAdvance=%b variant=%d canAnswer=%b answeredByApp=%b info=%s", connected && info != null, ms,
				KnBle.isBonded(d), BleError.name(mLastStatus), pin != null, q == null ? -1 : q.getVariant(), q != null && q.canAnswer(),
				PairingUi.sLastAnsweredByApp, info == null ? "-" : info.replace(' ', '_'));
		return connected;
	}

	/**
	 * Back to the open mode, both sides without bond
	 */
	private void openMode() {
		BleDevice d = sDevice;
		if (d != null && KnBle.isClassicConnected(d)) {
			mDisconnected = new CountDownLatch(1);
			command("PAIR 0", "PAIR");
			await(mDisconnected, COMMAND_TIMEOUT);
			KnBle.removeBond(d);
			sleep(4000);
		} else {
			pairMode(0);
		}
		dropClassic();
	}

	/**
	 * Legacy pairing (PAIR 3: no Secure Simple Pairing, PIN 1234): Android asks the PIN, the app types it
	 * (BlePairingRequest.setPin), no dialog of the system. On Android 17+ with an app targeting API 37, the dialog of the
	 * system asks it.
	 */
	void classicLegacyPinByApp() {
		if (!pairMode(3)) return;
		securePairing("classic_pairing_legacy_app", null, "1234", CONNECT_TIMEOUT);
		openMode();
	}

	/**
	 * Legacy pairing with the PIN given in advance (ClassicOptions.setPin): no request to the app, no dialog
	 */
	void classicLegacyPinInAdvance() {
		if (!pairMode(3)) return;
		securePairing("classic_pairing_legacy_advance", "1234", null, CONNECT_TIMEOUT);
		openMode();
	}

	/**
	 * Numeric comparison (PAIR 2): the dialog of the system asks the user to confirm, the app is told the number. Needs
	 * the user (confirm on the phone).
	 */
	void classicNumericComparison() {
		if (!pairMode(2)) return;
		r.log("Confirm the number in the pairing dialog of the phone");
		securePairing("classic_pairing_numeric", null, null, 120000);
		openMode();
	}

	/**
	 * The bench forgets the phone (UNBOND, at the disconnection) while the phone keeps its keys: Android 16+ tells
	 * onBondLost, KnBle ends the connection with BOND_LOST without auto-reconnection; before, what the stack does. Bonded
	 * first with the legacy PIN typed by the app (no user). A pairing asked again is refused: the scenario sees it.
	 */
	void classicLostBond() {
		BleDevice d = device();
		if (d == null) return;
		boolean paired;
		if (KnBle.isBonded(d)) {
			// The bond of the previous scenario (Android 17 + API 37: the app cannot remove it to pair again)
			dropClassic();
			paired = ensureConnected(new ClassicOptions.Builder().setUuid(sUuid).build(), false) >= 0;
		} else {
			if (!pairMode(3)) return;
			paired = securePairing("classic_lost_bond_pairing", null, "1234", CONNECT_TIMEOUT);
		}
		sleep(1000);
		boolean bonded = d != null && KnBle.isBonded(d);
		String forgot = paired ? command("UNBOND", "UNBOND") : null;
		dropClassic();
		sleep(2000);

		mBondLostReason = -1;
		mLastStatus = 0;
		String auto = PairingUi.sAutoPin;
		PairingUi.sAutoPin = "cancel";
		PairingUi.sLast = null;
		mConnected = new CountDownLatch(1);
		mDisconnected = new CountDownLatch(1);
		int before = mConnections.get();
		long start = SystemClock.elapsedRealtimeNanos();
		KnBle.connectClassic(d, new ClassicOptions.Builder().setUuid(sUuid).build(), true, mCallback);
		boolean connected = await(mConnected, CONNECT_TIMEOUT) && mConnections.get() > before;
		long ms = ms(start);
		sleep(3000);
		PairingUi.sAutoPin = auto;
		BlePairingRequest again = PairingUi.sLast;
		r.result("classic_lost_bond", "bonded=%b benchForgot=%s reconnected=%b pairingAskedAgain=%b repairing=%b bondLost=%d status=%s state=%d bondState=%d ms=%d",
				bonded, forgot == null ? "-" : forgot.replace(' ', '_'), connected, again != null, again != null && again.isRepairing(), mBondLostReason,
				BleError.name(mLastStatus), d == null ? -1 : KnBle.getClassicConnState(d), d == null ? -1 : KnBle.getBondState(d), ms);
		dropClassic();
		if (d != null && KnBle.isBonded(d)) KnBle.removeBond(d);
		sleep(2000);
		if (d != null && KnBle.isBonded(d)) r.log("The phone keeps its bond (Android 17 + API 37): forget %s in the Bluetooth settings of the phone", d.getMac());
		openMode();
	}

	/**
	 * The classic pairings without the user: the legacy PIN typed by the app or given in advance, the lost bond
	 */
	void classicPairingRunAll() {
		classicLegacyPinByApp();
		classicLegacyPinInAdvance();
		classicLostBond();
	}

	// endregion

	// region Reference: a plain Android socket, no KnBle

	/**
	 * The same STREAM and SINK with a BluetoothSocket read and written by this thread: what the phone can do
	 */
	void classicRawSocket() {
		rawTransfers("classic_raw");
	}

	/**
	 * @return KB/s of the receive and of the send, 0 on failure
	 */
	@NonNull
	private double[] rawTransfers(@NonNull String prefix) {
		double[] rates = new double[2];
		dropClassic();
		BleDevice d = device();
		if (d == null) return rates;
		BluetoothSocket socket = null;
		try {
			long start = SystemClock.elapsedRealtimeNanos();
			socket = sOptions.isSecure() ? d.getDevice().createRfcommSocketToServiceRecord(sUuid)
					: d.getDevice().createInsecureRfcommSocketToServiceRecord(sUuid);
			socket.connect();
			long connectMs = ms(start);
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			r.result(prefix + "_connect", "ms=%d", connectMs);

			out.write("PING 0\n".getBytes(StandardCharsets.US_ASCII));
			readLine(in);

			// Receive
			byte[] buffer = new byte[16384];
			start = SystemClock.elapsedRealtimeNanos();
			out.write(("STREAM " + TRANSFER + "\n").getBytes(StandardCharsets.US_ASCII));
			long got = 0, errors = 0;
			int reads = 0;
			while (got < TRANSFER) {
				int n = in.read(buffer, 0, (int) Math.min(buffer.length, TRANSFER - got));
				if (n < 0) break;
				for (int k = 0; k < n; k++) {
					long p = got + k;
					if (buffer[k] != (byte) (p * 31 + (p >> 8))) errors++;
				}
				got += n;
				reads++;
			}
			long ms = ms(start);
			String end = readLine(in);
			boolean rxOk = got == TRANSFER && errors == 0;
			r.result(prefix + "_receive", "size=%d ms=%d kbps=%.1f benchMs=%s ok=%b reads=%d", TRANSFER, ms, kbps(TRANSFER, ms),
					end == null ? "-" : end.split(" ")[2], rxOk, reads);
			if (rxOk) rates[0] = kbps(TRANSFER, ms);

			// Send, in chunks of 1 KB
			byte[] data = payload(TRANSFER);
			CRC32 crc = new CRC32();
			crc.update(data);
			out.write(("SINK " + TRANSFER + "\n").getBytes(StandardCharsets.US_ASCII));
			readLine(in);
			start = SystemClock.elapsedRealtimeNanos();
			for (int off = 0; off < data.length; off += 1024) out.write(data, off, Math.min(1024, data.length - off));
			String answer = readLine(in);
			ms = ms(start);
			String[] p = answer == null ? new String[0] : answer.split(" ");
			boolean txOk = p.length >= 4 && Long.parseLong(p[2], 16) == crc.getValue();
			r.result(prefix + "_send", "size=%d chunk=1024 ms=%d kbps=%.1f benchMs=%s crcOk=%b", TRANSFER, ms, kbps(TRANSFER, ms),
					p.length >= 4 ? p[3] : "-", txOk);
			if (txOk) rates[1] = kbps(TRANSFER, ms);
		} catch (IOException | RuntimeException e) {
			r.log("Raw socket: %s", e);
		} finally {
			try {
				if (socket != null) socket.close();
			} catch (IOException ignored) {
			}
		}
		// The bench sees the close a bit later
		sleep(500);
		return rates;
	}

	/**
	 * KnBle and the plain socket in turn, the order swapped every round: the cost of KnBle, if any
	 */
	void classicVersusRaw(int rounds) {
		List<Double> knbleRx = new ArrayList<>(), rawRx = new ArrayList<>(), knbleTx = new ArrayList<>(), rawTx = new ArrayList<>();
		for (int round = 1; round <= rounds; round++) {
			for (int k = 0; k < 2; k++) {
				boolean knble = (k == 0) == (round % 2 == 1);
				if (knble) {
					long ms = classicReceive("classic_vs_knble_receive", TRANSFER, null);
					knbleRx.add(ms > 0 ? kbps(TRANSFER, ms) : 0);
					knbleTx.add(send("classic_vs_knble_send", 1024));
					dropClassic();
					sleep(500);
				} else {
					double[] rates = rawTransfers("classic_vs_raw");
					rawRx.add(rates[0]);
					rawTx.add(rates[1]);
				}
			}
		}
		r.result("classic_vs_raw", "rounds=%d receiveKbps knble=%s raw=%s sendKbps knble=%s raw=%s", rounds, rates(knbleRx), rates(rawRx), rates(knbleTx),
				rates(rawTx));
	}

	@NonNull
	private static String rates(@NonNull List<Double> values) {
		StringBuilder sb = new StringBuilder();
		for (double v : values) sb.append(sb.length() == 0 ? "" : "/").append(String.format(Locale.US, "%.1f", v));
		return sb.toString();
	}

	@Nullable
	private static String readLine(@NonNull InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) >= 0) {
			if (c == '\n') return sb.toString();
			if (c != '\r') sb.append((char) c);
		}
		return null;
	}

	// endregion

	void classicRunAll() {
		classicDiscovery();
		classicConnect();
		classicLatency(200);
		classicReceive();
		classicSend(1024);
		classicSend(65536);
		classicReceiver(20);
		classicFrozenMain();
		classicLinkLoss();
		classicRawSocket();
		dropClassic();
		r.log("Classic: done (%s)", String.format(Locale.US, "%d connections", mConnections.get()));
	}
}
