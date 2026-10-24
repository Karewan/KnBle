package ovh.karewan.knble.sample;

import android.app.PendingIntent;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BleReliableWrite;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleAdvertiseCallback;
import ovh.karewan.knble.interfaces.BleAssociateCallback;
import ovh.karewan.knble.interfaces.BleBackgroundScanCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleRequestCallback;
import ovh.karewan.knble.interfaces.BleServerCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.peripheral.BleAdvertiseData;
import ovh.karewan.knble.peripheral.BleServerService;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.struct.BleConnectionParameters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The features of KnBle 4.0 on real devices: request, connection parameters, reliable write and auto-reconnection on
 * the bench, then the phone as a peripheral (advertising and GATT server of KnBle) tested by a second phone.
 */
final class FeatureScenarios extends Scenarios {
	// GATT server of the "KnBle peripheral" scenario
	static final UUID SVC_PERIPHERAL = Bench.bench("0201");
	static final UUID CHR_P_VALUE = Bench.bench("0202");
	static final UUID CHR_P_COMMAND = Bench.bench("0203");
	static final UUID CHR_P_STREAM = Bench.bench("0204");
	private static final byte P_CMD_STREAM = 0x01;

	FeatureScenarios(@NonNull Report report, @NonNull IntConsumer progress) {
		super(report, progress);
	}

	private static boolean await(@NonNull CountDownLatch latch, long ms) {
		try {
			return latch.await(ms, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@NonNull
	private static String params(BleConnectionParameters p) {
		return p == null ? "none" : String.format(java.util.Locale.US, "%.2fms/%d/%dms", p.getIntervalMs(), p.getLatency(), p.getSupervisionTimeoutMs());
	}

	// region Bench

	/**
	 * The long characteristic (512 bytes): read blob and prepared writes when the MTU is small, one request after the MTU
	 * exchange. Read, written with another value, read back, then its first value again.
	 */
	void longCharacteristic() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		if (!link.hasService(Bench.SVC_BENCH) || KnBle.getCharacteristic(link.device, Bench.SVC_BENCH, Bench.CHR_LONG) == null) {
			r.result("long_characteristic", "supported=false");
			return;
		}
		int mtu = KnBle.getMtu(link.device);
		BenchLink.Outcome<byte[]> first = link.read(Bench.SVC_BENCH, Bench.CHR_LONG, BenchLink.OP_TIMEOUT);
		boolean firstOk = first.ok() && Arrays.equals(first.value, Bench.longPattern(0));
		byte[] other = Bench.longPattern(7);
		BenchLink.Outcome<Void> w = link.write(Bench.SVC_BENCH, Bench.CHR_LONG, other, false, BenchLink.OP_TIMEOUT);
		BenchLink.Outcome<byte[]> back = link.read(Bench.SVC_BENCH, Bench.CHR_LONG, BenchLink.OP_TIMEOUT);
		boolean backOk = w.ok() && back.ok() && Arrays.equals(back.value, other);
		link.write(Bench.SVC_BENCH, Bench.CHR_LONG, Bench.longPattern(0), false, BenchLink.OP_TIMEOUT);
		r.result("long_characteristic", "mtu=%d read=%s length=%d valueOk=%b write=%s readBackOk=%b readMs=%d writeMs=%d", mtu, first.label(),
				first.value == null ? -1 : first.value.length, firstOk, w.label(), backOk, first.ms, w.ms);
	}

	/**
	 * The bench asks other connection parameters (30 to 50 ms): onConnectionParametersChanged without a request of the
	 * app. ESP32-C3 only.
	 */
	void deviceConnectionParameters() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		link.parameters.clear();
		long start = System.nanoTime();
		BenchLink.Outcome<byte[]> c = link.command(Bench.command(Bench.CMD_CONN_PARAMS, (short) 24, (short) 40, (short) 0, (short) 400), BenchLink.OP_TIMEOUT);
		if (!c.ok()) {
			r.result("device_connection_parameters", "supported=false");
			return;
		}
		BleConnectionParameters told = null;
		long until = System.nanoTime() + 10_000_000_000L;
		while (told == null && System.nanoTime() < until) {
			for (BleConnectionParameters p : link.parameters) if (p.getIntervalMs() >= 29 && p.getIntervalMs() <= 51) told = p;
			if (told == null) sleep(50);
		}
		r.result("device_connection_parameters", "asked=30-50ms told=%b params=%s ms=%d", told != null, params(told), (System.nanoTime() - start) / 1_000_000);
		KnBle.requestConnectionPriority(link.device, BluetoothGatt.CONNECTION_PRIORITY_HIGH);
	}

	/**
	 * Background scan (Android 8+): the system delivers the results to the PendingIntent of ScanReceiver, the bench found
	 * by a hardware filter. The bench is disconnected first (the ESP32 does not advertise while connected).
	 */
	void backgroundScan(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			r.result("background_scan", "supported=false");
			return;
		}
		BenchLink link = findBench();
		if (link == null) return;
		link.disconnect(BenchLink.OP_TIMEOUT);
		sleep(500);
		ScanFilters filters = sPcBench ? new ScanFilters.Builder().addServiceUuid(Bench.SVC_BENCH).build()
				: new ScanFilters.Builder().addDeviceName(Bench.NAME).build();
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
		PendingIntent intent = PendingIntent.getBroadcast(context, 1, new Intent(context, ScanReceiver.class), flags);
		ScanReceiver.reset();
		AtomicInteger error = new AtomicInteger(-1);
		CountDownLatch started = new CountDownLatch(1);
		long start = android.os.SystemClock.elapsedRealtime();
		KnBle.startBackgroundScan(intent, filters, new ScanSettings.Builder().build(), new BleBackgroundScanCallback() {
			@Override
			public void onBackgroundScanStarted() {
				error.set(0);
				started.countDown();
			}

			@Override
			public void onBackgroundScanFailed(int e) {
				error.set(e);
				started.countDown();
			}
		});
		await(started, 5000);
		long until = start + 20000;
		while (ScanReceiver.sDevices.get() == 0 && android.os.SystemClock.elapsedRealtime() < until) sleep(100);
		KnBle.stopBackgroundScan(intent);
		long first = ScanReceiver.sFirstAt.get();
		r.result("background_scan", "started=%b error=%d found=%b firstMs=%d deliveries=%d devices=%d", error.get() == 0, error.get(),
				ScanReceiver.sDevices.get() > 0, first == 0 ? -1 : first - start, ScanReceiver.sDeliveries.get(), ScanReceiver.sDevices.get());
	}

	/**
	 * Companion device association (Android 8+): the dialog of the system lists the bench, the user picks it. Then the
	 * association is removed. Needs the user.
	 */
	void companionAssociate(@NonNull android.app.Activity activity) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			r.result("companion_associate", "supported=false");
			return;
		}
		BenchLink link = findBench();
		if (link != null) link.disconnect(BenchLink.OP_TIMEOUT);
		ScanFilters filters = sPcBench ? new ScanFilters.Builder().addServiceUuid(Bench.SVC_BENCH).build()
				: new ScanFilters.Builder().addDeviceName(Bench.NAME).build();
		r.log("Pick the bench in the dialog of the system");
		CountDownLatch done = new CountDownLatch(1);
		AtomicInteger error = new AtomicInteger(-1);
		BleDevice[] associated = new BleDevice[1];
		long start = android.os.SystemClock.elapsedRealtime();
		mMain.post(() -> KnBle.associate(activity, filters, true, new BleAssociateCallback() {
			@Override
			public void onAssociated(@NonNull BleDevice device) {
				associated[0] = device;
				error.set(0);
				done.countDown();
			}

			@Override
			public void onAssociationFailed(int e) {
				error.set(e);
				done.countDown();
			}
		}));
		await(done, 90000);
		// Listed by getAssociatedDevices (read off the main thread)
		AtomicBoolean listedFlag = new AtomicBoolean();
		CountDownLatch read = new CountDownLatch(1);
		KnBle.getAssociatedDevices(devices -> {
			listedFlag.set(associated[0] != null && devices.contains(associated[0]));
			read.countDown();
		});
		await(read, 5000);
		boolean listed = listedFlag.get();
		if (associated[0] != null) KnBle.disassociate(associated[0]);
		r.result("companion_associate", "associated=%b error=%s listed=%b ms=%d", associated[0] != null, error.get() <= 0 ? String.valueOf(error.get()) : BleError.name(error.get()),
				listed, android.os.SystemClock.elapsedRealtime() - start);
	}

	/**
	 * request (write answered by a notification) timed, connection parameters before and after the HIGH priority,
	 * reliable write to the sink
	 */
	void benchFeatures() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		BleDevice device = link.device;

		String before = params(KnBle.getConnectionParameters(device));

		// request: the GET_INFO command answered on Control
		int count = 20;
		long total = 0, worst = 0;
		int ok = 0;
		for (int i = 0; i < count; i++) {
			CountDownLatch done = new CountDownLatch(1);
			AtomicInteger error = new AtomicInteger(1);
			long start = BenchLink.now();
			KnBle.request(device, Bench.SVC_BENCH, Bench.CHR_CONTROL, Bench.CHR_CONTROL, Bench.command(Bench.CMD_GET_INFO), new BleRequestCallback() {
				@Override
				public void onResponse(@NonNull byte[] response) {
					error.set(response.length >= 2 && response[0] == Bench.CMD_GET_INFO ? 0 : -100);
					done.countDown();
				}

				@Override
				public void onRequestFailed(int e) {
					error.set(e);
					done.countDown();
				}
			});
			boolean answered = await(done, 5000);
			long ms = BenchLink.msSince(start);
			if (answered && error.get() == 0) {
				ok++;
				total += ms;
				worst = Math.max(worst, ms);
			} else {
				r.log("request %d: %s", i, answered ? BleError.name(error.get()) : "no callback");
			}
		}

		KnBle.requestConnectionPriority(device, BluetoothGatt.CONNECTION_PRIORITY_HIGH);
		sleep(1500);
		String after = params(KnBle.getConnectionParameters(device));

		// Reliable write: two characteristics (both feed the sink of the bench) queued then executed together. One
		// characteristic twice at offset 0 is refused by the device (prepared writes not contiguous). Each value is at most
		// MTU - 5 (KnBle refuses more: Android would cut it): MTU 247 first. The statistics take 30 bytes too: more than a
		// notification carries with MTU 23.
		link.requestMtu(247, 3000);
		BenchLink.Outcome<byte[]> reset = link.command(Bench.command(Bench.CMD_RESET_STATS), BenchLink.OP_TIMEOUT);
		if (!reset.ok()) r.log("reset stats: %s", reset.label());
		byte[] a = new byte[100], b = new byte[60];
		Arrays.fill(a, (byte) 0x11);
		Arrays.fill(b, (byte) 0x22);
		CountDownLatch written = new CountDownLatch(1);
		AtomicInteger reliable = new AtomicInteger(1);
		KnBle.reliableWrite(device, new BleReliableWrite().add(Bench.SVC_BENCH, Bench.CHR_SINK, a).add(Bench.SVC_SLABS_OTA, Bench.CHR_SLABS_DATA, b), new BleWriteCallback() {
			@Override
			public void onWriteSuccess() {
				reliable.set(0);
				written.countDown();
			}

			@Override
			public void onWriteFailed(int e) {
				reliable.set(e);
				written.countDown();
			}
		});
		boolean reliableDone = await(written, 10000);
		long[] stats = sinkStats(link);

		r.result("bench_features", "requests=%d/%d avgMs=%.1f maxMs=%d params=%s paramsHigh=%s reliable=%s sinkBytes=%d",
				ok, count, ok == 0 ? 0 : total / (double) ok, worst, before, after,
				!reliableDone ? "no callback" : reliable.get() == 0 ? "OK" : BleError.name(reliable.get()), stats == null ? -1 : stats[0]);
	}

	/**
	 * Auto-reconnection: the bench disconnects by itself, KnBle connects again without the app
	 */
	void autoReconnect() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		BleDevice device = link.device;

		CountDownLatch lost = new CountDownLatch(1), back = new CountDownLatch(1);
		AtomicLong lostAt = new AtomicLong(), backAt = new AtomicLong();
		AtomicInteger status = new AtomicInteger(), connecting = new AtomicInteger();
		AtomicBoolean first = new AtomicBoolean(true);
		KnBle.connect(device, true, new BleGattCallback() {
			@Override
			public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
				// The first one: already connected
				if (first.getAndSet(false)) return;
				backAt.set(BenchLink.now());
				back.countDown();
			}

			@Override
			public void onConnecting() {
				connecting.incrementAndGet();
			}

			@Override
			public void onDisconnected(boolean connectFailed, int s) {
				status.set(s);
				lostAt.set(BenchLink.now());
				lost.countDown();
			}
		});
		sleep(300);

		// Written without waiting for the answer: the bench disconnects 500 ms later
		link.writeAsync(Bench.SVC_BENCH, Bench.CHR_CONTROL, Bench.command(Bench.CMD_DISCONNECT_IN, 500), false);
		boolean seenLoss = await(lost, 15000);
		boolean reconnected = seenLoss && await(back, 30000);
		long ms = reconnected ? (backAt.get() - lostAt.get()) / 1_000_000 : -1;
		r.result("auto_reconnect", "loss=%b status=%d reconnected=%b afterMs=%d onConnecting=%d", seenLoss, status.get(), reconnected, ms, connecting.get());

		// Back to the connection of the other scenarios
		KnBle.disconnect(device);
		sleep(1500);
	}

	// endregion

	// region Peripheral

	/**
	 * This phone as a peripheral for durationMs: advertising, GATT server of KnBle. A central writes
	 * [0x01, count u32, size u16] to the command characteristic: the server queues that many notifications at once
	 * ([seq u32][pattern]), KnBle sends them one after the other.
	 */
	void peripheral(long durationMs) {
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger notified = new AtomicInteger(), failedNotifications = new AtomicInteger();
		AtomicLong streamStart = new AtomicLong();
		BleServerCallback server = new BleServerCallback() {
			@Override
			public void onServerStarted() {
				r.log("Server started");
				started.countDown();
			}

			@Override
			public void onServerFailed(int error) {
				r.log("Server failed: %s", BleError.name(error));
				started.countDown();
			}

			@Override
			public void onCentralConnected(@NonNull BleDevice central) {
				r.log("Central connected: %s", central.getMac());
			}

			@Override
			public void onCentralDisconnected(@NonNull BleDevice central) {
				r.log("Central disconnected: %s", central.getMac());
			}

			@Override
			public void onMtuChanged(@NonNull BleDevice central, int mtu) {
				r.log("MTU %d", mtu);
			}

			@Override
			public void onSubscriptionChanged(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, boolean subscribed) {
				r.log("Subscription %s", subscribed);
			}

			@Override
			public void onWrite(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] value) {
				if (CHR_P_VALUE.equals(characteristic)) {
					r.log("Value written: %d bytes", value.length);
					return;
				}
				if (!CHR_P_COMMAND.equals(characteristic) || value.length < 7 || value[0] != P_CMD_STREAM) return;
				int count = (int) Bench.le32(value, 1);
				int size = (value[5] & 0xFF) | (value[6] & 0xFF) << 8;
				r.log("Stream of %d x %d bytes", count, size);
				streamStart.set(BenchLink.now());
				for (int i = 0; i < count; i++) {
					byte[] packet = new byte[Math.max(4, size)];
					Bench.putLe32(packet, 0, i);
					for (int j = 4; j < packet.length; j++) packet[j] = (byte) (i + j);
					boolean last = i == count - 1;
					KnBle.notifyCentral(central, SVC_PERIPHERAL, CHR_P_STREAM, packet, new BleWriteCallback() {
						@Override
						public void onWriteSuccess() {
							notified.incrementAndGet();
							if (last) report();
						}

						@Override
						public void onWriteFailed(int error) {
							if (failedNotifications.incrementAndGet() == 1) r.log("Notification failed: %s", BleError.name(error));
							if (last) report();
						}

						private void report() {
							long ms = BenchLink.msSince(streamStart.get());
							r.result("peripheral_stream", "sent=%d failed=%d ms=%d kbps=%.1f", notified.get(), failedNotifications.get(), ms,
									kbps((long) notified.get() * size, ms));
						}
					});
				}
			}
		};

		KnBle.startServer(server, new BleServerService(SVC_PERIPHERAL)
				.addCharacteristic(CHR_P_VALUE, BleServerService.READ | BleServerService.WRITE, "KnBle peripheral".getBytes())
				.addCharacteristic(CHR_P_COMMAND, BleServerService.WRITE | BleServerService.WRITE_NO_RESPONSE)
				.addCharacteristic(CHR_P_STREAM, BleServerService.NOTIFY));
		if (!await(started, 10000) || !KnBle.isServerStarted()) {
			r.result("peripheral", "server=false");
			KnBle.stopServer();
			return;
		}

		CountDownLatch advertising = new CountDownLatch(1);
		AtomicInteger advError = new AtomicInteger();
		KnBle.startAdvertising(new BleAdvertiseData.Builder()
				.addServiceUuid(SVC_PERIPHERAL)
				.setMode(BleAdvertiseData.MODE_LOW_LATENCY)
				.setTxPower(BleAdvertiseData.TX_POWER_HIGH)
				.build(), new BleAdvertiseCallback() {
			@Override
			public void onAdvertisingStarted() {
				r.log("Advertising");
				advertising.countDown();
			}

			@Override
			public void onAdvertisingFailed(int error) {
				advError.set(error);
				advertising.countDown();
			}
		});
		await(advertising, 10000);
		r.result("peripheral", "server=true advertising=%b error=%s", KnBle.isAdvertising(), BleError.name(advError.get()));
		if (!KnBle.isAdvertising()) {
			KnBle.stopServer();
			return;
		}

		r.log("Peripheral for %d s", durationMs / 1000);
		sleep(durationMs);
		KnBle.stopAdvertising();
		KnBle.stopServer();
		r.log("Peripheral stopped");
	}

	/**
	 * Central of the peripheral scenario run by another phone: long write and read (before the MTU exchange), MTU 517,
	 * subscription, stream of notifications checked in order
	 */
	void peripheralCentral(int count) {
		BleDevice device = scanFor(new ScanFilters.Builder().addServiceUuid(SVC_PERIPHERAL).build(), d -> true, 20000);
		if (device == null) {
			r.result("peripheral_central", "found=false");
			return;
		}
		BenchLink link = new BenchLink(device);
		BenchLink.Outcome<Void> c = link.connect(BenchLink.CONNECT_TIMEOUT);
		if (!c.ok()) {
			r.result("peripheral_central", "found=true connect=%s", c.label());
			return;
		}
		BenchLink.Outcome<byte[]> initial = link.read(SVC_PERIPHERAL, CHR_P_VALUE, BenchLink.OP_TIMEOUT);

		// 100 bytes with MTU 23: a long write (prepared writes), read back with read blobs
		byte[] value = new byte[100];
		for (int i = 0; i < value.length; i++) value[i] = (byte) i;
		BenchLink.Outcome<Void> w = link.write(SVC_PERIPHERAL, CHR_P_VALUE, value, false, BenchLink.OP_TIMEOUT);
		BenchLink.Outcome<byte[]> readBack = link.read(SVC_PERIPHERAL, CHR_P_VALUE, BenchLink.OP_TIMEOUT);
		boolean longOk = w.ok() && readBack.ok() && Arrays.equals(value, readBack.value);

		int mtu = link.prepareFastLink(r);
		int size = Math.min(mtu - 3, 509);

		AtomicInteger received = new AtomicInteger(), outOfOrder = new AtomicInteger();
		AtomicLong expected = new AtomicLong(), firstAt = new AtomicLong(), lastAt = new AtomicLong();
		CountDownLatch all = new CountDownLatch(1);
		BenchLink.Outcome<Boolean> sub = link.enableNotify(SVC_PERIPHERAL, CHR_P_STREAM, v -> {
			long now = BenchLink.now();
			if (received.get() == 0) firstAt.set(now);
			lastAt.set(now);
			if (v.length >= 4 && Bench.le32(v, 0) != expected.getAndIncrement()) outOfOrder.incrementAndGet();
			if (received.incrementAndGet() == count) all.countDown();
		}, BenchLink.OP_TIMEOUT);
		sleep(300);

		long start = BenchLink.now();
		link.write(SVC_PERIPHERAL, CHR_P_COMMAND, Bench.command(P_CMD_STREAM, count, (short) size), false, BenchLink.OP_TIMEOUT);
		boolean complete = await(all, 60000);
		long ms = (lastAt.get() - start) / 1_000_000;
		BleConnectionParameters p = KnBle.getConnectionParameters(device);

		r.result("peripheral_central", "initial=%s longWrite=%b mtu=%d subscribed=%b received=%d/%d outOfOrder=%d ms=%d kbps=%.1f params=%s",
				initial.ok() && initial.value != null ? new String(initial.value) : initial.label(), longOk, mtu, sub.ok(), received.get(), count,
				outOfOrder.get(), ms, complete ? kbps((long) count * size, ms) : 0, params(p));
		link.disconnect(BenchLink.OP_TIMEOUT);
	}

	// endregion
}
