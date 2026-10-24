package ovh.karewan.knble.sample;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The threading contract of KnBle 4 (no blocking in the lib, of the lib by the app, of the app by the lib) and the
 * reproductions of the 3.0.5 bugs found by the audit. On 3.0.5 most of them are expected to fail: they are the
 * measurements the 4.0 must improve, the same scenarios are run again on it.
 */
final class ContractScenarios extends Scenarios {
	private static final int CONTRACT_SIZE = 128 * 1024;
	// A descriptor the bench does not have
	private static final UUID MISSING_DESCRIPTOR = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb");

	ContractScenarios(@NonNull Report report, @NonNull IntConsumer progress) {
		super(report, progress);
	}

	// region No blocking

	/**
	 * Duration of a transfer to the sink measured by the bench, from its first to its last packet: what the radio did,
	 * whatever the phone delivers late to the app (its main thread may be the one blocked)
	 * @return ms, -1 on failure
	 */
	private long transfer(@NonNull BenchLink link, BiConsumer<Integer, Integer> progress) {
		int split = splitSize(link.mtu());
		byte[] image = Bench.image(CONTRACT_SIZE, IMAGE_SEED);
		link.command(Bench.command(Bench.CMD_RESET_STATS), BenchLink.OP_TIMEOUT);
		long start = BenchLink.now();
		BenchLink.Outcome<Long> w = link.splittedWrite(Bench.SVC_BENCH, Bench.CHR_SINK, image, split, true, 0, progress, 300000);
		if (!w.ok() || awaitDelivered(link, CONTRACT_SIZE, start, 120000) < 0) return -1;
		long[] s = sinkStats(link);
		return s == null ? -1 : s[4];
	}

	/**
	 * A progress callback of the app blocks for 5 s: does the transfer wait for it?
	 */
	void blockingCallback() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);

		long ref = transfer(link, null);
		AtomicBoolean slept = new AtomicBoolean();
		long blocked = transfer(link, (current, total) -> {
			if (current >= total / 10 && slept.compareAndSet(false, true)) SystemClock.sleep(5000);
		});

		long extra = blocked - ref;
		r.log("Reference %d ms, with a 5 s blocking callback %d ms: %s", ref, blocked,
				extra > 4000 ? "the transfer waits for the app (blocked by the app)" : "the transfer goes on");
		r.result("contract_blocking_callback", "size=%d refMs=%d blockedMs=%d extraMs=%d blockedByApp=%b", CONTRACT_SIZE, ref, blocked, extra, extra > 4000);
	}

	/**
	 * The main thread of the app freezes for 5 s during a transfer whose progress goes to the UI (like the apps)
	 */
	void frozenMainThread() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.prepareFastLink(r);

		long ref = transfer(link, null);
		AtomicBoolean frozen = new AtomicBoolean();
		AtomicInteger calls = new AtomicInteger();
		BiConsumer<Integer, Integer> ui = uiProgress(calls);
		long withFreeze = transfer(link, (current, total) -> {
			if (current >= total / 10 && frozen.compareAndSet(false, true)) mMain.post(() -> SystemClock.sleep(5000));
			ui.accept(current, total);
		});

		long extra = withFreeze - ref;
		r.log("Reference %d ms, main thread frozen 5 s %d ms: %s", ref, withFreeze, extra > 4000 ? "the transfer waits for the main thread" : "the transfer goes on");
		r.result("contract_frozen_main", "size=%d refMs=%d frozenMs=%d extraMs=%d blockedByMain=%b", CONTRACT_SIZE, ref, withFreeze, extra, extra > 4000);
	}

	private interface Call {
		void run();
	}

	private void measure(@NonNull String name, int n, @NonNull Call call) {
		List<Long> ns = new ArrayList<>(n);
		onMain(() -> {
			for (int i = 0; i < n; i++) {
				long t = SystemClock.elapsedRealtimeNanos();
				call.run();
				ns.add(SystemClock.elapsedRealtimeNanos() - t);
			}
		});
		long sum = 0;
		for (long v : ns) sum += v;
		r.result("api_latency", "call=%s n=%d avgUs=%.1f p99Us=%.1f maxUs=%.1f", name, n, sum / 1000.0 / Math.max(1, n),
				percentile(ns, 0.99) / 1000.0, percentile(ns, 1) / 1000.0);
	}

	/**
	 * Time spent in the public methods of KnBle when the app calls them on the main thread
	 */
	void apiLatency() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		BleDevice d = link.device;

		measure("isConnected", 2000, () -> KnBle.isConnected(d));
		measure("getDeviceConnState", 2000, () -> KnBle.getDeviceConnState(d));
		measure("getMtu", 2000, () -> KnBle.getMtu(d));
		measure("getConnectedDevices", 2000, KnBle::getConnectedDevices);
		measure("getScannedDevices", 2000, KnBle::getScannedDevices);
		measure("isScanning", 2000, KnBle::isScanning);
		measure("isBluetoothEnabled", 2000, KnBle::isBluetoothEnabled);
		measure("BleDevice.getName", 500, d::getName);
		measure("getBluetoothGatt", 2000, () -> KnBle.getBluetoothGatt(d));

		CountDownLatch reads = new CountDownLatch(200);
		BleReadCallback cb = new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {
				reads.countDown();
			}

			@Override
			public void onReadFailed() {
				reads.countDown();
			}
		};
		measure("read (enqueue)", 200, () -> KnBle.read(d, Bench.SVC_DUP_A, Bench.CHR_DUP, cb));
		try {
			reads.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		KnBle.setScanFilter(new ScanFilters.Builder().addMacAddress(d.getMac()).build());
		for (int i = 0; i < 3; i++) {
			measure("startScan", 1, () -> KnBle.startScan(new SilentScan()));
			sleep(1500);
			measure("stopScan", 1, KnBle::stopScan);
			sleep(500);
		}
		KnBle.setScanFilter(new ScanFilters.Builder().build());
	}

	/**
	 * Reads posted from the main thread while the previous ones complete on the KnBle thread: each one must get
	 * exactly one callback with its own value
	 */
	void enqueueRace() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		BleDevice d = link.device;
		int n = 300;
		AtomicIntegerArray callbacks = new AtomicIntegerArray(n);
		AtomicInteger wrong = new AtomicInteger(), failed = new AtomicInteger();

		for (int i = 0; i < n; i++) {
			int id = i;
			mMain.postDelayed(() -> KnBle.read(d, Bench.SVC_DUP_A, Bench.CHR_DUP, new BleReadCallback() {
				@Override
				public void onReadSuccess(@NonNull byte[] data) {
					callbacks.incrementAndGet(id);
					if (!"A".equals(Bench.text(data))) wrong.incrementAndGet();
				}

				@Override
				public void onReadFailed() {
					callbacks.incrementAndGet(id);
					failed.incrementAndGet();
				}
			}), i * 2L);
		}

		sleep(n * 2L + 20000);
		int missing = 0, duplicated = 0;
		for (int i = 0; i < n; i++) {
			if (callbacks.get(i) == 0) missing++;
			else if (callbacks.get(i) > 1) duplicated++;
		}
		r.result("contract_enqueue_race", "reads=%d missing=%d duplicated=%d wrongValue=%d failed=%d", n, missing, duplicated, wrong.get(), failed.get());
	}

	// endregion

	// region 3.0.5 bugs

	/**
	 * disableNotify on a characteristic never subscribed: is the next operation still executed?
	 */
	void disableNotifyWithoutEnable() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.disableNotify(Bench.SVC_BENCH, Bench.CHR_NOTIFY);
		BenchLink.Outcome<byte[]> next = link.read(Bench.SVC_DUP_A, Bench.CHR_DUP, BenchLink.OP_TIMEOUT);
		r.result("bug_disable_notify_without_enable", "nextRead=%s queueBlocked=%b", next.label(), next.status == BenchLink.Outcome.TIMEOUT);
		recover(link, next.status == BenchLink.Outcome.TIMEOUT);
	}

	/**
	 * readDesc on a descriptor that does not exist: callback, then is the next operation still executed?
	 */
	void readMissingDescriptor() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		BenchLink.Outcome<byte[]> desc = link.readDesc(Bench.SVC_BENCH, Bench.CHR_CONTROL, MISSING_DESCRIPTOR, BenchLink.OP_TIMEOUT);
		BenchLink.Outcome<byte[]> next = link.read(Bench.SVC_DUP_A, Bench.CHR_DUP, BenchLink.OP_TIMEOUT);
		r.result("bug_read_missing_descriptor", "readDesc=%s nextRead=%s queueBlocked=%b", desc.label(), next.label(), next.status == BenchLink.Outcome.TIMEOUT);
		recover(link, next.status == BenchLink.Outcome.TIMEOUT);
	}

	/**
	 * enableNotify on an indicate-only characteristic: the CCCD must be written with the indication value
	 */
	void indicateOnly() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		CountDownLatch received = new CountDownLatch(1);
		BenchLink.Outcome<Boolean> en = link.enableNotify(Bench.SVC_BENCH, Bench.CHR_INDICATE, data -> {
			if ("IND-OK".equals(Bench.text(data))) received.countDown();
		}, BenchLink.OP_TIMEOUT);

		boolean got = false;
		if (en.ok() && Boolean.TRUE.equals(en.value)) {
			link.command(Bench.command(Bench.CMD_INDICATE), BenchLink.OP_TIMEOUT);
			try {
				got = received.await(3, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			link.disableNotify(Bench.SVC_BENCH, Bench.CHR_INDICATE);
		}
		r.result("bug_indicate_only", "enable=%s enabled=%s indicationReceived=%b", en.label(), en.value, got);
	}

	/**
	 * The same characteristic UUID in two services: each read must reach its own service
	 */
	void sameUuidTwoServices() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		// A fresh connection: the 3.0.5 caches only live as long as the connection
		link.disconnect(BenchLink.OP_TIMEOUT);
		link = ensureConnected();
		if (link == null) return;
		BenchLink.Outcome<byte[]> a = link.read(Bench.SVC_DUP_A, Bench.CHR_DUP, BenchLink.OP_TIMEOUT);
		BenchLink.Outcome<byte[]> b = link.read(Bench.SVC_DUP_B, Bench.CHR_DUP, BenchLink.OP_TIMEOUT);
		String va = Bench.text(a.value), vb = Bench.text(b.value);
		r.result("bug_same_uuid_two_services", "serviceA=%s serviceB=%s ok=%b", va, vb, "A".equals(va) && "B".equals(vb));
	}

	/**
	 * disconnect() then connect() right away, without waiting: is the new connection made? Lost on 3.0.5 when the
	 * thread of the device is busy at that moment (a race: the unit test makes it deterministic)
	 */
	void disconnectThenConnect() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		CountDownLatch connected = new CountDownLatch(1);
		long start = BenchLink.now();
		KnBle.disconnect(link.device);
		KnBle.connect(link.device, new BleGattCallback() {
			@Override
			public void onConnecting() {}

			@Override
			public void onConnectSuccess(@NonNull List<android.bluetooth.BluetoothGattService> services) {
				connected.countDown();
			}

			@Override
			public void onDisconnected(boolean connectFailed) {}
		});

		boolean ok = false;
		try {
			ok = connected.await(20, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		r.result("bug_disconnect_then_connect", "reconnected=%b ms=%d connectLost=%b", ok, BenchLink.msSince(start), !ok);

		// The callback of this connection is not the one of BenchLink: disconnect without it, the next scenario reconnects
		KnBle.disconnect(link.device);
		long deadline = SystemClock.elapsedRealtime() + 5000;
		while (KnBle.getDeviceConnState(link.device) != BleGattCallback.DISCONNECTED && SystemClock.elapsedRealtime() < deadline) sleep(50);
	}

	/**
	 * Read of a characteristic that needs encryption: the phone pairs (Just Works, a system dialog may ask)
	 */
	void encryptedRead() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		r.log("Accept the pairing request if the phone asks");
		BenchLink.Outcome<byte[]> e = link.read(Bench.SVC_BENCH, Bench.CHR_ENCRYPTED, 40000);
		r.result("encrypted_read", "status=%s value=%s ms=%d bondState=%d", e.label(), Bench.text(e.value), e.ms, link.device.getDevice().getBondState());
	}

	/**
	 * A blocked GATT queue only recovers with a disconnection on 3.0.5
	 */
	private void recover(@NonNull BenchLink link, boolean blocked) {
		if (!blocked) return;
		r.log("Queue blocked: disconnecting to recover");
		link.disconnect(BenchLink.OP_TIMEOUT);
	}

	// endregion

	private static final class SilentScan implements ovh.karewan.knble.interfaces.BleScanCallback {
		@Override
		public void onScanStarted() {}

		@Override
		public void onScanFailed(int error) {}

		@Override
		public void onScanResult(@NonNull BleDevice bleDevice) {}

		@Override
		public void onDeviceUpdated(@NonNull BleDevice bleDevice) {}

		@Override
		public void onScanFinished(@NonNull List<BleDevice> scanResult) {}
	}
}
