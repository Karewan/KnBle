package ovh.karewan.knble.sample;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;
import java.util.concurrent.CopyOnWriteArrayList;
import ovh.karewan.knble.struct.BleConnectionParameters;

/**
 * Synchronous access to the bench for the scenario thread (never the main thread). Each call waits for its KnBle
 * callback with a timeout: a callback that never comes is reported as TIMEOUT instead of blocking the scenario.
 */
final class BenchLink {
	static final long OP_TIMEOUT = 5000;
	static final long CONNECT_TIMEOUT = 20000;

	/**
	 * Result of an operation
	 */
	static final class Outcome<T> {
		static final int OK = 0, FAILED = 1, TIMEOUT = 2;

		final int status;
		@Nullable
		final T value;
		final long ms;

		Outcome(int status, @Nullable T value, long ms) {
			this.status = status;
			this.value = value;
			this.ms = ms;
		}

		boolean ok() {
			return status == OK;
		}

		@NonNull
		String label() {
			return status == OK ? "ok" : status == FAILED ? "failed" : "TIMEOUT";
		}
	}

	@NonNull
	final BleDevice device;
	private final LinkedBlockingQueue<byte[]> mAnswers = new LinkedBlockingQueue<>();
	private final AtomicInteger mConnectOutcome = new AtomicInteger();
	private volatile CountDownLatch mConnectLatch, mDisconnectLatch;
	private volatile List<BluetoothGattService> mServices = Collections.emptyList();
	volatile boolean lastConnectFailed;
	volatile long disconnectedAtNs;
	// Status of the last onDisconnected
	volatile int lastStatus;
	// Reason of the last onBondLost (Android 16+), -1 for none
	volatile int bondLostReason = -1;
	// Every onConnectionParametersChanged
	final List<BleConnectionParameters> parameters = new CopyOnWriteArrayList<>();

	BenchLink(@NonNull BleDevice device) {
		this.device = device;
	}

	private final BleGattCallback mGattCallback = new BleGattCallback() {
		@Override
		public void onConnecting() {}

		@Override
		public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
			mServices = services;
			mConnectOutcome.compareAndSet(0, 1);
			CountDownLatch l = mConnectLatch;
			if (l != null) l.countDown();
		}

		@Override
		public boolean onPairingRequest(@NonNull BlePairingRequest request) {
			return PairingUi.onPairingRequest(request);
		}

		@Override
		public void onBondLost(int reason) {
			bondLostReason = reason;
		}

		@Override
		public void onConnectionParametersChanged(@NonNull BleConnectionParameters p) {
			parameters.add(p);
		}

		@Override
		public void onDisconnected(boolean connectFailed, int status) {
			lastStatus = status;
			lastConnectFailed = connectFailed;
			disconnectedAtNs = SystemClock.elapsedRealtimeNanos();
			mConnectOutcome.compareAndSet(0, 2);
			CountDownLatch c = mConnectLatch;
			if (c != null) c.countDown();
			CountDownLatch d = mDisconnectLatch;
			if (d != null) d.countDown();
		}
	};

	static long now() {
		return SystemClock.elapsedRealtimeNanos();
	}

	static long msSince(long startNs) {
		return (now() - startNs) / 1_000_000;
	}

	private static boolean await(@NonNull CountDownLatch latch, long timeoutMs) {
		try {
			return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static <T> Outcome<T> outcome(@NonNull CountDownLatch latch, long timeoutMs, @NonNull AtomicInteger status, @NonNull AtomicReference<T> value, long startNs) {
		if (!await(latch, timeoutMs)) return new Outcome<>(Outcome.TIMEOUT, null, msSince(startNs));
		return new Outcome<>(status.get(), value.get(), msSince(startNs));
	}

	boolean connected() {
		return KnBle.isConnected(device);
	}

	boolean hasService(@NonNull UUID uuid) {
		for (BluetoothGattService s : mServices) if (s.getUuid().equals(uuid)) return true;
		return false;
	}

	@NonNull
	List<BluetoothGattService> services() {
		return mServices;
	}

	int mtu() {
		return KnBle.getMtu(device);
	}

	/**
	 * Connects (KnBle.connect), then subscribes to the answers of the bench when it is in normal mode
	 */
	@NonNull
	Outcome<Void> connect(long timeoutMs) {
		long start = now();
		mConnectOutcome.set(0);
		CountDownLatch latch = new CountDownLatch(1);
		mConnectLatch = latch;
		KnBle.connect(device, mGattCallback);

		if (!await(latch, timeoutMs)) return new Outcome<>(Outcome.TIMEOUT, null, msSince(start));
		long ms = msSince(start);
		if (mConnectOutcome.get() != 1) return new Outcome<>(Outcome.FAILED, null, ms);

		if (hasService(Bench.SVC_BENCH)) {
			Outcome<Boolean> sub = enableNotify(Bench.SVC_BENCH, Bench.CHR_CONTROL, mAnswers::offer, OP_TIMEOUT);
			if (!sub.ok() || !Boolean.TRUE.equals(sub.value)) return new Outcome<>(Outcome.FAILED, null, ms);
		}
		return new Outcome<>(Outcome.OK, null, ms);
	}

	/**
	 * Calls KnBle.connect without waiting: the outcome is awaited by awaitConnect()
	 */
	void connectAsync() {
		mConnectOutcome.set(0);
		mConnectLatch = new CountDownLatch(1);
		KnBle.connect(device, mGattCallback);
	}

	@NonNull
	Outcome<Void> awaitConnect(long timeoutMs, long startNs) {
		CountDownLatch latch = mConnectLatch;
		if (latch == null || !await(latch, timeoutMs)) return new Outcome<>(Outcome.TIMEOUT, null, msSince(startNs));
		return new Outcome<>(mConnectOutcome.get() == 1 ? Outcome.OK : Outcome.FAILED, null, msSince(startNs));
	}

	@NonNull
	Outcome<Void> disconnect(long timeoutMs) {
		long start = now();
		if (KnBle.getDeviceConnState(device) == BleGattCallback.DISCONNECTED) return new Outcome<>(Outcome.OK, null, 0);
		CountDownLatch latch = new CountDownLatch(1);
		mDisconnectLatch = latch;
		KnBle.disconnect(device);
		return new Outcome<>(await(latch, timeoutMs) ? Outcome.OK : Outcome.TIMEOUT, null, msSince(start));
	}

	/**
	 * Waits for a disconnection coming from the device
	 */
	boolean awaitDisconnection(long timeoutMs) {
		if (KnBle.getDeviceConnState(device) == BleGattCallback.DISCONNECTED) return true;
		CountDownLatch latch = new CountDownLatch(1);
		mDisconnectLatch = latch;
		if (KnBle.getDeviceConnState(device) == BleGattCallback.DISCONNECTED) return true;
		return await(latch, timeoutMs);
	}

	@NonNull
	Outcome<byte[]> read(@NonNull UUID service, @NonNull UUID characteristic, long timeoutMs) {
		long start = now();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger status = new AtomicInteger();
		AtomicReference<byte[]> value = new AtomicReference<>();

		KnBle.read(device, service, characteristic, new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {
				value.set(data);
				latch.countDown();
			}

			@Override
			public void onReadFailed() {
				status.set(Outcome.FAILED);
				latch.countDown();
			}
		});
		return outcome(latch, timeoutMs, status, value, start);
	}

	@NonNull
	Outcome<byte[]> readDesc(@NonNull UUID service, @NonNull UUID characteristic, @NonNull UUID descriptor, long timeoutMs) {
		long start = now();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger status = new AtomicInteger();
		AtomicReference<byte[]> value = new AtomicReference<>();

		KnBle.readDesc(device, service, characteristic, descriptor, new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {
				value.set(data);
				latch.countDown();
			}

			@Override
			public void onReadFailed() {
				status.set(Outcome.FAILED);
				latch.countDown();
			}
		});
		return outcome(latch, timeoutMs, status, value, start);
	}

	@NonNull
	Outcome<Void> write(@NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] data, boolean noResponse, long timeoutMs) {
		long start = now();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger status = new AtomicInteger();

		KnBle.write(device, service, characteristic, data, noResponse, new BleWriteCallback() {
			@Override
			public void onWriteFailed() {
				status.set(Outcome.FAILED);
				latch.countDown();
			}

			@Override
			public void onWriteSuccess() {
				latch.countDown();
			}
		});
		return outcome(latch, timeoutMs, status, new AtomicReference<>(), start);
	}

	/**
	 * KnBle.write without waiting for its callback: for the answers sent from a KnBle callback, which must not block
	 */
	void writeAsync(@NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] data, boolean noResponse) {
		KnBle.write(device, service, characteristic, data, noResponse, new BleWriteCallback() {
			@Override
			public void onWriteFailed() {
				writeFailures.incrementAndGet();
			}

			@Override
			public void onWriteSuccess() {}
		});
	}

	final AtomicInteger writeFailures = new AtomicInteger();

	/**
	 * @return true when enabled, false when KnBle answered onNotifyDisabled
	 */
	@NonNull
	Outcome<Boolean> enableNotify(@NonNull UUID service, @NonNull UUID characteristic, @NonNull Consumer<byte[]> onData, long timeoutMs) {
		long start = now();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Boolean> enabled = new AtomicReference<>();

		KnBle.enableNotify(device, service, characteristic, new BleNotifyCallback() {
			@Override
			public void onNotifyEnabled() {
				enabled.compareAndSet(null, true);
				latch.countDown();
			}

			@Override
			public void onNotifyDisabled() {
				enabled.compareAndSet(null, false);
				latch.countDown();
			}

			@Override
			public void onNotify(@NonNull byte[] data) {
				onData.accept(data);
			}
		});
		return outcome(latch, timeoutMs, new AtomicInteger(), enabled, start);
	}

	void disableNotify(@NonNull UUID service, @NonNull UUID characteristic) {
		KnBle.disableNotify(device, service, characteristic);
	}

	/**
	 * Writes a command on Control and waits for its answer [cmd, status, payload]
	 */
	@NonNull
	Outcome<byte[]> command(@NonNull byte[] cmd, long timeoutMs) {
		long start = now();
		mAnswers.clear();
		Outcome<Void> w = write(Bench.SVC_BENCH, Bench.CHR_CONTROL, cmd, false, timeoutMs);
		if (!w.ok()) return new Outcome<>(w.status, null, w.ms);

		long deadline = start + timeoutMs * 1_000_000;
		try {
			while (true) {
				long left = deadline - now();
				if (left <= 0) return new Outcome<>(Outcome.TIMEOUT, null, msSince(start));
				byte[] a = mAnswers.poll(left, TimeUnit.NANOSECONDS);
				if (a == null) return new Outcome<>(Outcome.TIMEOUT, null, msSince(start));
				if (a.length >= 2 && a[0] == cmd[0]) return new Outcome<>(a[1] == 0 ? Outcome.OK : Outcome.FAILED, a, msSince(start));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Outcome<>(Outcome.TIMEOUT, null, msSince(start));
		}
	}

	@NonNull
	Outcome<Integer> requestMtu(int mtu, long timeoutMs) {
		long start = now();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Integer> value = new AtomicReference<>();
		KnBle.requestMtu(device, mtu, m -> {
			value.set(m);
			latch.countDown();
		});
		return outcome(latch, timeoutMs, new AtomicInteger(), value, start);
	}

	@NonNull
	Outcome<int[]> setPhy2M(long timeoutMs) {
		long start = now();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return new Outcome<>(Outcome.FAILED, null, 0);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<int[]> value = new AtomicReference<>();
		KnBle.setPreferredPhy(device, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED, (tx, rx) -> {
			value.set(new int[]{tx, rx});
			latch.countDown();
		});
		return outcome(latch, timeoutMs, new AtomicInteger(), value, start);
	}

	/**
	 * Fast transfer settings used by the OTA of the Nogema apps: priority HIGH, PHY 2M, MTU 517
	 * @return the MTU in use
	 */
	int prepareFastLink(@NonNull Report report) {
		KnBle.requestConnectionPriority(device, BluetoothGatt.CONNECTION_PRIORITY_HIGH);

		Outcome<int[]> phy = setPhy2M(3000);
		if (phy.ok() && phy.value != null) report.log("PHY: tx=%d rx=%d (%d ms)", phy.value[0], phy.value[1], phy.ms);
		else report.log("PHY 2M: %s (%d ms)%s", phy.label(), phy.ms, phy.status == Outcome.TIMEOUT ? ", callback never called: the GATT queue stays blocked" : "");

		Outcome<Integer> mtu = requestMtu(517, 3000);
		report.log("MTU: %s %s (%d ms)", mtu.label(), mtu.value == null ? "" : mtu.value, mtu.ms);
		return mtu();
	}

	/**
	 * KnBle.splittedWrite
	 * @return duration in ns
	 */
	@NonNull
	Outcome<Long> splittedWrite(@NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] data, int splitSize, boolean noResponse, long interval,
								@Nullable BiConsumer<Integer, Integer> progress, long timeoutMs) {
		long start = now();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger status = new AtomicInteger();
		AtomicReference<Long> duration = new AtomicReference<>();

		KnBle.splittedWrite(device, service, characteristic, data, splitSize, noResponse, interval, new BleSplittedWriteCallback() {
			@Override
			public void onWriteFailed() {
				status.set(Outcome.FAILED);
				latch.countDown();
			}

			@Override
			public void onWriteProgress(int current, int total) {
				if (progress != null) progress.accept(current, total);
			}

			@Override
			public void onWriteSuccess() {
				duration.set(now() - start);
				latch.countDown();
			}
		});
		return outcome(latch, timeoutMs, status, duration, start);
	}
}
