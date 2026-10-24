package ovh.karewan.knble.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.ClassicOptions;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.KnBleSettings;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicDiscoveryCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Bluetooth classic: RFCOMM connection, data stream, writes, attempts, end and auto-reconnection, discovery.
 * Robolectric does not end the input stream of a closed socket (Android does): the tests close it themselves.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClassicTest {
	private static final long TIMEOUT_MS = 5000;
	private static final String MAC = "AA:BB:CC:DD:EE:07";

	private Context mContext;
	private BleDevice mDevice;
	private final List<BluetoothSocket> mSockets = new CopyOnWriteArrayList<>();
	private final List<Boolean> mByChannel = new CopyOnWriteArrayList<>();
	private volatile boolean mFailConnect, mBlockConnect;

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		BluetoothAdapter adapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(adapter).setEnabled(true);
		assertTrue(KnBle.init(mContext, new KnBleSettings.Builder().setConnectTimeout(4000).setConnectAttempts(2).build()));
		mDevice = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(mDevice);

		ClassicLink.sSocketFactory = (device, byChannel) -> {
			// BluetoothDevice of Robolectric gives one socket for the whole process: a new one per attempt
			BluetoothSocket s = Shadow.newInstanceOf(BluetoothSocket.class);
			mByChannel.add(byChannel);
			if (mFailConnect) s.close();
			if (mBlockConnect) shadowOf(s).blockConnect();
			mSockets.add(s);
			return s;
		};
	}

	@After
	public void tearDown() {
		ClassicLink.sSocketFactory = null;
		for (BluetoothSocket s : mSockets) closeFeeder(s);
		BleEngine.resetForTests();
	}

	// region Helpers

	/**
	 * Runs the main looper and advances the clock until the condition (real time limit)
	 */
	private static boolean pumpUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			if (condition.getAsBoolean()) return true;
			Thread.sleep(5);
		}
		return condition.getAsBoolean();
	}

	/**
	 * Runs the main looper and advances the clock for this long
	 */
	private static void pumpFor(long ms) throws InterruptedException {
		for (long done = 0; done < ms; done += 50) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			Thread.sleep(2);
		}
	}

	private void bluetoothState(int state) throws InterruptedException {
		shadowOf(mContext.getSystemService(BluetoothManager.class).getAdapter()).setEnabled(state == BluetoothAdapter.STATE_ON);
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state));
		pumpFor(100);
	}

	private static BleWriteCallback logged(String name, List<String> log) {
		return new BleWriteCallback() {
			@Override
			public void onWriteSuccess() {
				log.add(name + " ok");
			}

			@Override
			public void onWriteFailed(int error) {
				log.add(name + " failed " + error);
			}
		};
	}

	private static void closeFeeder(@NonNull BluetoothSocket s) {
		try {
			shadowOf(s).getInputStreamFeeder().close();
		} catch (IOException ignored) {
		}
	}

	private static byte[] pattern(int length) {
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) b[i] = (byte) (i * 31 + (i >> 8));
		return b;
	}

	private static final class Recorder implements BleClassicCallback {
		final ByteArrayOutputStream received = new ByteArrayOutputStream();
		final List<String> events = new CopyOnWriteArrayList<>();
		final AtomicInteger dataCalls = new AtomicInteger();
		final AtomicInteger maxData = new AtomicInteger();

		@Override
		public void onData(@NonNull byte[] data) {
			synchronized (received) {
				received.write(data, 0, data.length);
			}
			dataCalls.incrementAndGet();
			maxData.accumulateAndGet(data.length, Math::max);
		}

		int size() {
			synchronized (received) {
				return received.size();
			}
		}

		byte[] bytes() {
			synchronized (received) {
				return received.toByteArray();
			}
		}

		@Override
		public void onConnected() {
			events.add("connected");
		}

		@Override
		public void onConnecting() {
			events.add("connecting");
		}

		@Override
		public void onDisconnected(boolean connectFailed, int status) {
			events.add("disconnected " + connectFailed + " " + status);
		}

		@Override
		public void onBondLost(int reason) {
			events.add("bond lost " + reason);
		}

		boolean has(String event) {
			return events.contains(event);
		}
	}

	private Recorder connect(boolean autoReconnect) throws InterruptedException {
		Recorder rec = new Recorder();
		KnBle.connectClassic(mDevice, autoReconnect, rec);
		assertTrue("not connected", pumpUntil(() -> rec.has("connected")));
		return rec;
	}

	private BluetoothSocket socket(int index) {
		return mSockets.get(index);
	}

	// endregion

	@Test
	public void dataComesInOrderAndMergedWhileTheAppIsBusy() throws Exception {
		Recorder rec = connect(false);
		assertEquals(BleGattCallback.CONNECTED, KnBle.getClassicConnState(mDevice));

		// 200 chunks fed while the main thread never runs: the reader never waits for the app
		byte[] all = pattern(200 * 1000);
		PipedOutputStream feeder = shadowOf(socket(0)).getInputStreamFeeder();
		for (int i = 0; i < 200; i++) feeder.write(all, i * 1000, 1000);
		feeder.flush();

		assertTrue(pumpUntil(() -> rec.size() == all.length));
		assertArrayEquals(all, rec.bytes());
		assertTrue("merged while the app was busy: " + rec.dataCalls.get(), rec.dataCalls.get() < 200);
	}

	@Test
	public void writesAskedWhileConnectingGoOutInOrderAfterOnConnected() throws Exception {
		Recorder rec = new Recorder();
		List<String> results = new CopyOnWriteArrayList<>();
		KnBle.connectClassic(mDevice, rec);
		KnBle.writeClassic(mDevice, "A".getBytes(StandardCharsets.US_ASCII), () -> results.add("A " + rec.events.size()));
		KnBle.writeClassic(mDevice, "BC".getBytes(StandardCharsets.US_ASCII), () -> results.add("BC"));
		assertTrue(pumpUntil(() -> rec.has("connected")));

		InputStream sink = shadowOf(socket(0)).getOutputStreamSink();
		byte[] out = new byte[3];
		int n = 0;
		while (n < 3) n += sink.read(out, n, 3 - n);
		assertEquals("ABC", new String(out, StandardCharsets.US_ASCII));
		assertTrue(pumpUntil(() -> results.size() == 2));
		// onConnected first, then the results in order
		assertEquals("A 2", results.get(0));
		assertEquals("BC", results.get(1));

		// While connected: written at once
		KnBle.writeClassic(mDevice, "D".getBytes(StandardCharsets.US_ASCII));
		assertEquals('D', sink.read());
	}

	@Test
	public void failedAttemptsTryTheChannelThenEnd() throws Exception {
		mFailConnect = true;
		Recorder rec = new Recorder();
		KnBle.connectClassic(mDevice, rec);
		assertTrue(pumpUntil(() -> rec.has("disconnected true " + BleError.REFUSED)));
		// Two rounds (setConnectAttempts(2)): the UUID, then right away channel 1
		assertEquals(List.of(false, true, false, true), new ArrayList<>(mByChannel));
		assertEquals(List.of("connecting", "disconnected true " + BleError.REFUSED), rec.events);
		assertEquals(BleGattCallback.DISCONNECTED, KnBle.getClassicConnState(mDevice));
	}

	@Test
	public void connectionTimesOut() throws Exception {
		mBlockConnect = true;
		Recorder rec = new Recorder();
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setChannelFallback(false).build(), false, rec);
		// The blocked connect() is unblocked by the close() of the socket at the end of setConnectTimeout(4000)
		assertTrue(pumpUntil(() -> rec.has("disconnected true " + BleError.TIMEOUT)));
	}

	@Test
	public void disconnectIsToldAfterTheLastBytes() throws Exception {
		Recorder rec = connect(false);
		PipedOutputStream feeder = shadowOf(socket(0)).getInputStreamFeeder();
		byte[] last = pattern(5000);
		feeder.write(last);
		feeder.flush();
		KnBle.disconnectClassic(mDevice);
		// What Android does when the socket closes
		feeder.close();
		assertTrue(pumpUntil(() -> rec.has("disconnected false 0")));
		assertArrayEquals(last, rec.bytes());

		AtomicInteger error = new AtomicInteger();
		KnBle.writeClassic(mDevice, new byte[]{1}, new BleWriteCallback() {
			@Override
			public void onWriteSuccess() {}

			@Override
			public void onWriteFailed(int e) {
				error.set(e);
			}
		});
		assertTrue(pumpUntil(() -> error.get() == BleError.NOT_CONNECTED));
	}

	@Test
	public void lostLinkIsToldThenMadeAgainWhenAsked() throws Exception {
		Recorder rec = connect(true);
		// The device closes its side
		closeFeeder(socket(0));
		assertTrue(pumpUntil(() -> rec.has("disconnected false " + BleError.CONNECTION_LOST)));

		// Auto-reconnection 1 s later: onConnecting once, then onConnected
		assertTrue(pumpUntil(() -> rec.events.stream().filter("connected"::equals).count() == 2));
		assertEquals(List.of("connecting", "connected", "disconnected false " + BleError.CONNECTION_LOST, "connecting", "connected"), rec.events);
		assertEquals(2, mSockets.size());

		KnBle.disconnectClassic(mDevice);
		closeFeeder(socket(1));
		assertTrue(pumpUntil(() -> rec.has("disconnected false 0")));
		assertEquals(BleGattCallback.DISCONNECTED, KnBle.getClassicConnState(mDevice));
	}

	@Test
	public void discoveryReportsEachDeviceOnceWithItsName() throws Exception {
		List<BleDevice> found = new CopyOnWriteArrayList<>();
		List<BleDevice> finished = new CopyOnWriteArrayList<>();
		AtomicInteger finishedCalls = new AtomicInteger();
		KnBle.startClassicDiscovery(new BleClassicDiscoveryCallback() {
			@Override
			public void onDeviceFound(@NonNull BleDevice device) {
				found.add(device);
			}

			@Override
			public void onDiscoveryFinished(@NonNull List<BleDevice> devices) {
				finished.addAll(devices);
				finishedCalls.incrementAndGet();
			}
		});
		assertTrue(pumpUntil(KnBle::isClassicDiscovering));

		BluetoothDevice d = mDevice.getDevice();
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_RSSI, (short) -60));
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_RSSI, (short) -61));
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_NAME_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, d).putExtra(BluetoothDevice.EXTRA_NAME, "RECEIVER"));
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));

		assertTrue(pumpUntil(() -> finishedCalls.get() == 1));
		// Found without a name, then again once its name came
		assertEquals(2, found.size());
		assertEquals("RECEIVER", found.get(1).getName());
		assertEquals(1, finished.size());
		assertEquals("RECEIVER", finished.get(0).getName());
		assertEquals(-61, finished.get(0).getRssi());
		assertTrue(!KnBle.isClassicDiscovering());
	}

	private void sendBondState(int state, int previous) {
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice.getDevice())
				.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state).putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous));
	}

	@Test
	public void theTimeOfThePairingIsNotTheTimeOfTheConnection() throws Exception {
		mBlockConnect = true;
		Recorder rec = new Recorder();
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setChannelFallback(false).build(), false, rec);
		assertTrue(pumpUntil(() -> mSockets.size() == 1));

		// The secure socket started a pairing: the user takes longer than setConnectTimeout(4000) to type the code
		sendBondState(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE);
		long until = System.currentTimeMillis() + 1500;
		while (System.currentTimeMillis() < until) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
			Thread.sleep(5);
		}
		assertEquals("no timeout while pairing: " + rec.events, BleGattCallback.CONNECTING, KnBle.getClassicConnState(mDevice));

		shadowOf(mDevice.getDevice()).setBondState(BluetoothDevice.BOND_BONDED);
		sendBondState(BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING);
		shadowOf(socket(0)).unblockConnect();
		assertTrue(pumpUntil(() -> rec.has("connected")));
		assertEquals(List.of("connecting", "connected"), rec.events);
	}

	@Test
	public void theLegacyPinIsGivenWhenAndroidAsks() throws Exception {
		mBlockConnect = true;
		Recorder rec = new Recorder();
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setPin("1234").build(), false, rec);
		assertTrue(pumpUntil(() -> mSockets.size() == 1));

		// Secure Simple Pairing: nothing given (the user confirms)
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice.getDevice())
				.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION));
		// Legacy pairing: the PIN of the options
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice.getDevice())
				.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PIN));
		assertTrue(pumpUntil(() -> shadowOf(mDevice.getDevice()).getPin() != null));
		assertArrayEquals("1234".getBytes(StandardCharsets.US_ASCII), shadowOf(mDevice.getDevice()).getPin());
		shadowOf(socket(0)).unblockConnect();
		assertTrue(pumpUntil(() -> rec.has("connected")));
	}

	@Test
	public void writeToAnUnknownDeviceFails() throws Exception {
		AtomicInteger error = new AtomicInteger();
		KnBle.writeClassic(mDevice, new byte[]{1}, new BleWriteCallback() {
			@Override
			public void onWriteSuccess() {}

			@Override
			public void onWriteFailed(int e) {
				error.set(e);
			}
		});
		assertTrue(pumpUntil(() -> error.get() == BleError.NOT_CONNECTED));
	}

	// region Review fixes

	@Test
	public void aSlowAppHoldsTheReaderWithoutLosingBytes() throws Exception {
		Recorder rec = connect(false);
		byte[] all = pattern(3 * 1024 * 1024);
		PipedOutputStream feeder = shadowOf(socket(0)).getInputStreamFeeder();
		AtomicLong written = new AtomicLong();
		// The writer stays alive until the end: the pipe of Robolectric breaks when its writer dies
		CountDownLatch end = new CountDownLatch(1);
		Thread writer = new Thread(() -> {
			try {
				for (int off = 0; off < all.length; off += 8192) {
					feeder.write(all, off, 8192);
					feeder.flush();
					written.addAndGet(8192);
				}
				end.await();
			} catch (IOException | InterruptedException ignored) {
			}
		});
		writer.start();
		try {
			// The main thread does not run: the reader stops once PIPE_CAPACITY bytes wait for the app
			long held = -1;
			for (int i = 0; i < 100; i++) {
				Thread.sleep(150);
				long now = written.get();
				if (now == held && now > 0) break;
				held = now;
			}
			assertTrue("read while the app was busy: " + held, held < all.length);
			assertTrue("held at " + held, held >= ClassicLink.PIPE_CAPACITY - 65536 && held <= ClassicLink.PIPE_CAPACITY + 65536);

			// The app catches up: every byte, in order, at most MAX_DATA per onData
			assertTrue(pumpUntil(() -> rec.size() == all.length));
			assertArrayEquals(all, rec.bytes());
			assertTrue("onData of " + rec.maxData.get(), rec.maxData.get() <= ClassicLink.MAX_DATA);
		} finally {
			end.countDown();
		}
	}

	@Test
	public void aSecondConnectBetweenTwoRoundsTakesOverTheConnection() throws Exception {
		mFailConnect = true;
		Recorder first = new Recorder();
		KnBle.connectClassic(mDevice, first);
		// First round: the UUID then channel 1, both refused; the next round in 1 s
		assertTrue(pumpUntil(() -> mSockets.size() == 2));
		// The engine handles the failures, the clock does not move
		Thread.sleep(300);

		mFailConnect = false;
		Recorder second = new Recorder();
		List<String> results = new CopyOnWriteArrayList<>();
		KnBle.connectClassic(mDevice, second);
		KnBle.writeClassic(mDevice, "A".getBytes(StandardCharsets.US_ASCII), logged("A", results));
		assertTrue("stuck: " + second.events, pumpUntil(() -> second.has("connected")));
		// The replaced callback gets the end of its onConnecting, the next round connects for the new one
		assertEquals(List.of("connecting", "disconnected true " + BleError.CANCELLED), first.events);
		assertEquals(List.of("connecting", "connected"), second.events);
		assertEquals(3, mSockets.size());
		assertEquals('A', shadowOf(socket(2)).getOutputStreamSink().read());
		assertTrue(pumpUntil(() -> results.equals(List.of("A ok"))));
	}

	@Test
	public void disconnectThenConnectAtOnceMakesANewConnection() throws Exception {
		Recorder rec = connect(false);
		// While the socket closes, before the reader tells its end
		KnBle.disconnectClassic(mDevice);
		KnBle.connectClassic(mDevice, rec);
		KnBle.writeClassic(mDevice, "B".getBytes(StandardCharsets.US_ASCII));
		closeFeeder(socket(0));

		assertTrue("events: " + rec.events, pumpUntil(() -> rec.events.size() == 5));
		assertEquals(List.of("connecting", "connected", "disconnected false 0", "connecting", "connected"), rec.events);
		assertEquals(BleGattCallback.CONNECTED, KnBle.getClassicConnState(mDevice));
		// The write asked after connectClassic goes to the new connection
		assertEquals('B', shadowOf(socket(1)).getOutputStreamSink().read());
	}

	@Test
	public void writeResultsComeInOrderBeforeOnDisconnected() throws Exception {
		Recorder rec = connect(false);
		List<String> log = rec.events;
		// W1 fills the pipe of the socket (1 KB): the io thread waits in its write
		KnBle.writeClassic(mDevice, pattern(65536), logged("W1", log));
		PipedInputStream sink = shadowOf(socket(0)).getOutputStreamSink();
		assertTrue(pumpUntil(() -> {
			try {
				return sink.available() == 1024;
			} catch (IOException e) {
				return false;
			}
		}));
		KnBle.writeClassic(mDevice, new byte[]{2}, logged("W2", log));
		KnBle.writeClassic(mDevice, new byte[]{3}, logged("W3", log));
		// The device drops the link
		closeFeeder(socket(0));

		assertTrue(pumpUntil(() -> rec.has("disconnected false " + BleError.CONNECTION_LOST)));
		pumpFor(200);
		assertEquals(List.of("connecting", "connected", "W1 failed " + BleError.NOT_CONNECTED, "W2 failed " + BleError.NOT_CONNECTED,
				"W3 failed " + BleError.NOT_CONNECTED, "disconnected false " + BleError.CONNECTION_LOST), log);
		sink.close();
		pumpFor(200);
		assertEquals(6, log.size());
	}

	@Test
	public void disconnectBetweenTwoRoundsOfTheAutoReconnectionIsTold() throws Exception {
		Recorder rec = connect(true);
		mFailConnect = true;
		closeFeeder(socket(0));
		assertTrue(pumpUntil(() -> rec.has("disconnected false " + BleError.CONNECTION_LOST)));
		// Round 1 (told with onConnecting): its attempts fail silently, the next round in 2 s
		assertTrue(pumpUntil(() -> mSockets.size() == 5));
		Thread.sleep(300);

		KnBle.disconnectClassic(mDevice);
		assertTrue(pumpUntil(() -> rec.has("disconnected true " + BleError.CANCELLED)));
		pumpFor(3000);
		assertEquals(5, mSockets.size());
		assertEquals(List.of("connecting", "connected", "disconnected false " + BleError.CONNECTION_LOST, "connecting",
				"disconnected true " + BleError.CANCELLED), rec.events);
	}

	@Test
	public void disconnectDuringARoundOfTheAutoReconnectionIsTold() throws Exception {
		Recorder rec = connect(true);
		mBlockConnect = true;
		closeFeeder(socket(0));
		assertTrue(pumpUntil(() -> rec.has("disconnected false " + BleError.CONNECTION_LOST)));
		assertTrue(pumpUntil(() -> mSockets.size() == 2 && rec.events.size() == 4));

		KnBle.disconnectClassic(mDevice);
		assertTrue(pumpUntil(() -> rec.has("disconnected true " + BleError.CANCELLED)));
		assertEquals(List.of("connecting", "connected", "disconnected false " + BleError.CONNECTION_LOST, "connecting",
				"disconnected true " + BleError.CANCELLED), rec.events);
		assertEquals(BleGattCallback.DISCONNECTED, KnBle.getClassicConnState(mDevice));
	}

	/**
	 * ACTION_KEY_MISSING of Android 16+ for the device
	 */
	private void keyMissing(int reason) {
		BluetoothDevice device = mContext.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(MAC);
		mContext.sendBroadcast(new Intent(BleEngine.ACTION_KEY_MISSING).putExtra(BluetoothDevice.EXTRA_DEVICE, device)
				.putExtra(BleEngine.EXTRA_BOND_LOSS_REASON, reason));
	}

	@Test
	public void aLostBondEndsTheConnectionWithoutReconnection() throws Exception {
		Recorder rec = connect(true);
		keyMissing(1);
		assertTrue(pumpUntil(() -> rec.has("bond lost 1")));
		// The link then ends (encryption failed): told BOND_LOST, no auto-reconnection
		closeFeeder(socket(0));
		assertTrue(pumpUntil(() -> rec.has("disconnected false " + BleError.BOND_LOST)));
		pumpFor(2500);
		assertEquals(1, mSockets.size());
		assertEquals(List.of("connecting", "connected", "bond lost 1", "disconnected false " + BleError.BOND_LOST), rec.events);

		// The app tries again (paired again): a connection
		KnBle.connectClassic(mDevice, rec);
		assertTrue(pumpUntil(() -> rec.events.stream().filter("connected"::equals).count() == 2));
	}

	@Test
	public void aLostBondDuringARoundOfTheAutoReconnectionEndsIt() throws Exception {
		Recorder rec = connect(true);
		mBlockConnect = true;
		closeFeeder(socket(0));
		assertTrue(pumpUntil(() -> mSockets.size() == 2 && rec.events.size() == 4));

		keyMissing(2);
		assertTrue(pumpUntil(() -> rec.has("disconnected true " + BleError.BOND_LOST)));
		pumpFor(3000);
		assertEquals(2, mSockets.size());
		assertEquals(List.of("connecting", "connected", "disconnected false " + BleError.CONNECTION_LOST, "connecting", "bond lost 2",
				"disconnected true " + BleError.BOND_LOST), rec.events);
	}

	@Test
	public void bluetoothOffDuringTheAutoReconnectionEndsItsOnConnecting() throws Exception {
		Recorder rec = connect(true);
		mBlockConnect = true;
		closeFeeder(socket(0));
		assertTrue(pumpUntil(() -> rec.has("disconnected false " + BleError.CONNECTION_LOST)));
		assertTrue(pumpUntil(() -> mSockets.size() == 2 && rec.events.size() == 4));

		bluetoothState(BluetoothAdapter.STATE_OFF);
		assertTrue(pumpUntil(() -> rec.has("disconnected true " + BleError.BLUETOOTH_DISABLED)));
		mBlockConnect = false;
		bluetoothState(BluetoothAdapter.STATE_ON);
		assertTrue(pumpUntil(() -> rec.events.stream().filter("connected"::equals).count() == 2));
		assertEquals(List.of("connecting", "connected", "disconnected false " + BleError.CONNECTION_LOST, "connecting",
				"disconnected true " + BleError.BLUETOOTH_DISABLED, "connecting", "connected"), rec.events);
	}

	/**
	 * cancelDiscovery() of Android: asynchronous, the inquiry runs until its DISCOVERY_FINISHED
	 */
	@Implements(BluetoothAdapter.class)
	public static class AsyncDiscoveryAdapter extends ShadowBluetoothAdapter {
		static volatile boolean sDiscovering;
		static final AtomicInteger sStarts = new AtomicInteger();

		@Implementation
		protected boolean startDiscovery() {
			sStarts.incrementAndGet();
			sDiscovering = true;
			return true;
		}

		@Implementation
		protected boolean cancelDiscovery() {
			return true;
		}

		@Implementation
		protected boolean isDiscovering() {
			return sDiscovering;
		}
	}

	private static final class Discovery implements BleClassicDiscoveryCallback {
		final List<BleDevice> found = new CopyOnWriteArrayList<>();
		final List<BleDevice> devices = new CopyOnWriteArrayList<>();
		final AtomicInteger finished = new AtomicInteger();

		@Override
		public void onDeviceFound(@NonNull BleDevice device) {
			found.add(device);
		}

		@Override
		public void onDiscoveryFinished(@NonNull List<BleDevice> list) {
			devices.addAll(list);
			finished.incrementAndGet();
		}
	}

	@Test
	@Config(shadows = AsyncDiscoveryAdapter.class)
	public void aNewDiscoveryWaitsForTheEndOfTheCancelledOne() throws Exception {
		AsyncDiscoveryAdapter.sDiscovering = false;
		AsyncDiscoveryAdapter.sStarts.set(0);
		Discovery first = new Discovery();
		Discovery second = new Discovery();
		KnBle.startClassicDiscovery(first);
		assertTrue(pumpUntil(() -> AsyncDiscoveryAdapter.sStarts.get() == 1));
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED));

		// Started again at once: the first one ends, the inquiry it cancelled still runs
		KnBle.startClassicDiscovery(second);
		assertTrue(pumpUntil(() -> first.finished.get() == 1));
		pumpFor(200);
		assertEquals(1, AsyncDiscoveryAdapter.sStarts.get());
		assertTrue(KnBle.isClassicDiscovering());

		// The end of the cancelled inquiry: not the end of the new discovery, which starts now
		AsyncDiscoveryAdapter.sDiscovering = false;
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
		assertTrue(pumpUntil(() -> AsyncDiscoveryAdapter.sStarts.get() == 2));
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice.getDevice())
				.putExtra(BluetoothDevice.EXTRA_RSSI, (short) -60));
		assertTrue(pumpUntil(() -> second.found.size() == 1));
		assertEquals(0, second.finished.get());

		AsyncDiscoveryAdapter.sDiscovering = false;
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
		assertTrue(pumpUntil(() -> second.finished.get() == 1));
		assertEquals(1, second.devices.size());
		assertEquals(1, first.finished.get());
	}

	@Test
	@Config(shadows = AsyncDiscoveryAdapter.class)
	public void aDiscoveryAfterStopStartsWithin2sWithoutTheBroadcast() throws Exception {
		AsyncDiscoveryAdapter.sDiscovering = false;
		AsyncDiscoveryAdapter.sStarts.set(0);
		Discovery first = new Discovery();
		Discovery second = new Discovery();
		KnBle.startClassicDiscovery(first);
		assertTrue(pumpUntil(() -> AsyncDiscoveryAdapter.sStarts.get() == 1));
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
		KnBle.stopClassicDiscovery();
		KnBle.startClassicDiscovery(second);
		assertTrue(pumpUntil(() -> first.finished.get() == 1));
		assertEquals(1, AsyncDiscoveryAdapter.sStarts.get());
		// No DISCOVERY_FINISHED: started anyway
		assertTrue(pumpUntil(() -> AsyncDiscoveryAdapter.sStarts.get() == 2));
		assertEquals(0, second.finished.get());
	}

	// endregion
}
