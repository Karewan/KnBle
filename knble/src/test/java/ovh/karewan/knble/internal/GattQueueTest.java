package ovh.karewan.knble.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * GATT queue of a device against the Robolectric GATT shadow (a fake peripheral answering synchronously, which the
 * actor must not re-enter). The "3.0.5 bug" tests reproduce the bugs found by the audit: they failed on 3.0.5.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GattQueueTest {
	private static final long TIMEOUT_MS = 3000;
	private static final String MAC = "AA:BB:CC:DD:EE:01";
	private static final UUID SVC_A = UUID.fromString("4b420010-8d28-4316-88b3-6e0111311ac6");
	private static final UUID SVC_B = UUID.fromString("4b420020-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_DUP = UUID.fromString("4b420011-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_RW = UUID.fromString("4b420003-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_NOTIFY = UUID.fromString("4b420008-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_INDICATE = UUID.fromString("4b420005-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_REFUSED = UUID.fromString("4b420009-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	private static final UUID MISSING = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb");

	private BleDevice mDevice;
	private BluetoothDevice mBtDevice;
	private BluetoothGatt mGatt;
	private BluetoothGattService mServiceA, mServiceB;
	private BluetoothGattCharacteristic mRw, mNotify, mIndicate;
	// Callbacks of the test, connection ones included, in the order the app got them
	private final List<String> mEvents = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() {
		BleEngine.resetForTests();

		Context context = ApplicationProvider.getApplicationContext();
		shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		shadowOf(context.getSystemService(BluetoothManager.class).getAdapter()).setEnabled(true);

		assertTrue(KnBle.init(context));
		mDevice = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(mDevice);
		mBtDevice = mDevice.getDevice();

		mServiceA = new BluetoothGattService(SVC_A, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		mServiceB = new BluetoothGattService(SVC_B, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		mServiceA.addCharacteristic(characteristic(CHR_DUP, BluetoothGattCharacteristic.PROPERTY_READ, "A", false));
		mServiceB.addCharacteristic(characteristic(CHR_DUP, BluetoothGattCharacteristic.PROPERTY_READ, "B", false));
		mRw = characteristic(CHR_RW, BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE
				| BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, "RW", false);
		mNotify = characteristic(CHR_NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, "", true);
		mIndicate = characteristic(CHR_INDICATE, BluetoothGattCharacteristic.PROPERTY_INDICATE, "", true);
		mServiceA.addCharacteristic(mRw);
		mServiceA.addCharacteristic(mNotify);
		mServiceA.addCharacteristic(mIndicate);
		// Readable for KnBle, refused by the fake peripheral (no read permission): the stack answers false forever
		BluetoothGattCharacteristic refused = new BluetoothGattCharacteristic(CHR_REFUSED, BluetoothGattCharacteristic.PROPERTY_READ, 0);
		mServiceA.addCharacteristic(refused);
	}

	@After
	public void tearDown() {
		BleEngine.resetForTests();
	}

	@SuppressWarnings("deprecation")
	private static BluetoothGattCharacteristic characteristic(UUID uuid, int properties, String value, boolean cccd) {
		BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid, properties, BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
		c.setValue(value.getBytes(StandardCharsets.UTF_8));
		if (cccd) c.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
		return c;
	}

	/**
	 * Runs the main looper (the callbacks of the app) and advances the clock (the delayed messages of the threads of
	 * KnBle) until the latch
	 */
	private static boolean pump(CountDownLatch latch) throws InterruptedException {
		return pump(latch, TIMEOUT_MS);
	}

	private static boolean pump(CountDownLatch latch, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			if (latch.await(10, TimeUnit.MILLISECONDS)) return true;
		}
		return false;
	}

	private void connect() throws InterruptedException {
		connect(null);
	}

	/**
	 * @param pairing Answer of the app to the pairing requests, null for the default (the dialog of the system)
	 */
	private void connect(@Nullable Function<BlePairingRequest, Boolean> pairing) throws InterruptedException {
		int previousClients = shadowOf(mBtDevice).getBluetoothGatts().size();
		CountDownLatch connected = new CountDownLatch(1);
		KnBle.connect(mDevice, new BleGattCallback() {
			@Override
			public void onConnecting() {}

			@Override
			public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
				connected.countDown();
			}

			@Override
			public void onDisconnected(boolean connectFailed) {
				mEvents.add("disconnected");
			}

			@Override
			public boolean onPairingRequest(@NonNull BlePairingRequest request) {
				return pairing != null && pairing.apply(request);
			}
		});

		// A new GATT client for this connection (after the pause that follows a close())
		mGatt = awaitNewClient(previousClients);
		shadowOf(mGatt).addDiscoverableService(mServiceA);
		shadowOf(mGatt).addDiscoverableService(mServiceB);
		shadowOf(mGatt).allowCharacteristicNotification(mNotify);
		shadowOf(mGatt).allowCharacteristicNotification(mIndicate);

		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue("not connected", pump(connected));
	}

	/**
	 * The GATT client created after the previous ones, the clock advancing meanwhile
	 */
	private BluetoothGatt awaitNewClient(int previousClients) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (shadowOf(mBtDevice).getBluetoothGatts().size() <= previousClients && System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			Thread.sleep(5);
		}
		assertTrue("connectGatt not called", shadowOf(mBtDevice).getBluetoothGatts().size() > previousClients);
		return shadowOf(mBtDevice).getBluetoothGatts().get(previousClients);
	}

	private static final class Read implements BleReadCallback {
		final CountDownLatch done = new CountDownLatch(1);
		volatile String value;
		volatile boolean failed;
		volatile Looper looper;

		@Override
		public void onReadSuccess(@NonNull byte[] data) {
			looper = Looper.myLooper();
			value = new String(data, StandardCharsets.UTF_8);
			done.countDown();
		}

		@Override
		public void onReadFailed() {
			looper = Looper.myLooper();
			failed = true;
			done.countDown();
		}
	}

	private Read read(UUID service, UUID characteristic) throws InterruptedException {
		Read r = new Read();
		KnBle.read(mDevice, service, characteristic, r);
		pump(r.done);
		return r;
	}

	/**
	 * Holds the thread of the device until the returned latch is released: the GATT callbacks and the requests pile up
	 */
	private CountDownLatch holdDeviceThread() throws InterruptedException {
		CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
		BleEngine engine = BleEngine.get();
		assertNotNull(engine);
		assertTrue(engine.runOnDeviceThread(mDevice, () -> {
			held.countDown();
			try {
				release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		assertTrue(held.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
		return release;
	}

	private abstract static class Notify implements BleNotifyCallback {
		final CountDownLatch answered = new CountDownLatch(1);
		volatile Boolean enabled;

		@Override
		public void onNotifyEnabled() {
			enabled = true;
			answered.countDown();
		}

		@Override
		public void onNotifyDisabled() {
			if (enabled == null) enabled = false;
			answered.countDown();
		}
	}

	// region Behaviour

	@Test
	public void readDeliversTheValueOnTheMainThread() throws Exception {
		connect();
		Read r = read(SVC_A, CHR_RW);
		assertEquals("RW", r.value);
		assertSame(Looper.getMainLooper(), r.looper);
	}

	@Test
	public void operationsRunInOrder() throws Exception {
		connect();
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(3);
		BleReadCallback first = new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {
				order.add("read1");
				done.countDown();
			}

			@Override
			public void onReadFailed() {}
		};
		BleWriteCallback second = new BleWriteCallback() {
			@Override
			public void onWriteFailed() {}

			@Override
			public void onWriteSuccess() {
				order.add("write");
				done.countDown();
			}
		};
		BleReadCallback third = new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {
				order.add("read2");
				done.countDown();
			}

			@Override
			public void onReadFailed() {}
		};

		KnBle.read(mDevice, SVC_A, CHR_RW, first);
		KnBle.write(mDevice, SVC_A, CHR_RW, new byte[]{1}, false, second);
		KnBle.read(mDevice, SVC_A, CHR_RW, third);
		assertTrue(pump(done));
		assertEquals(List.of("read1", "write", "read2"), order);
	}

	@Test
	public void splittedWriteSendsEveryPacketWithCoalescedProgress() throws Exception {
		connect();
		CountDownLatch done = new CountDownLatch(1);
		List<Integer> progress = new CopyOnWriteArrayList<>();
		List<String> events = new CopyOnWriteArrayList<>();

		KnBle.splittedWrite(mDevice, SVC_A, CHR_RW, new byte[1000], 20, true, 0, new BleSplittedWriteCallback() {
			@Override
			public void onWriteFailed() {
				events.add("failed");
				done.countDown();
			}

			@Override
			public void onWriteProgress(int current, int total) {
				assertEquals(50, total);
				progress.add(current);
				events.add("progress");
			}

			@Override
			public void onWriteSuccess() {
				events.add("success");
				done.countDown();
			}
		});

		assertTrue(pump(done));
		assertEquals("success", events.get(events.size() - 1));
		// At most one event per 100 ms, the last one with every packet sent, before the success
		assertTrue(progress.size() <= 50);
		assertEquals(Integer.valueOf(50), progress.get(progress.size() - 1));
	}

	@Test
	public void notificationsAreDelivered() throws Exception {
		connect();
		CountDownLatch received = new CountDownLatch(1);
		byte[][] value = new byte[1][];
		Notify n = new Notify() {
			@Override
			public void onNotify(@NonNull byte[] data) {
				value[0] = data;
				received.countDown();
			}
		};
		KnBle.enableNotify(mDevice, SVC_A, CHR_NOTIFY, n);
		assertTrue(pump(n.answered));
		assertEquals(Boolean.TRUE, n.enabled);
		assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, cccdValue(mNotify));

		byte[] v = {1, 2, 3};
		shadowOf(mGatt).getGattCallback().onCharacteristicChanged(mGatt, mNotify, v);
		assertTrue(pump(received));
		assertArrayEquals(v, value[0]);
	}

	@SuppressWarnings("deprecation")
	private static byte[] cccdValue(BluetoothGattCharacteristic c) {
		return c.getDescriptor(CCCD).getValue();
	}

	/**
	 * Requests sent before the disconnection but not answered yet (the fake peripheral answers through a message queued
	 * behind the disconnection) fail once each, before onDisconnected. A request sent after it fails too.
	 */
	@Test
	public void disconnectionFailsThePendingOperationsBeforeOnDisconnected() throws Exception {
		connect();
		CountDownLatch done = new CountDownLatch(3);
		BleReadCallback cb = new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {
				mEvents.add("success");
				done.countDown();
			}

			@Override
			public void onReadFailed() {
				mEvents.add("failed");
				done.countDown();
			}
		};

		CountDownLatch release = holdDeviceThread();
		KnBle.read(mDevice, SVC_A, CHR_RW, cb);
		KnBle.read(mDevice, SVC_A, CHR_RW, cb);
		KnBle.disconnect(mDevice);
		KnBle.read(mDevice, SVC_A, CHR_RW, cb);
		release.countDown();

		assertTrue(pump(done));
		assertEquals(List.of("failed", "failed", "disconnected", "failed"), mEvents);
	}

	@Test
	public void refusedRequestFailsAfterTheBusyBudgetAndTheQueueGoesOn() throws Exception {
		connect();
		Read refused = new Read();
		KnBle.read(mDevice, SVC_A, CHR_REFUSED, refused);
		Read next = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, next);
		// 5 s of busy budget on the clock of the test: more real time when the machine is loaded
		assertTrue(pump(refused.done, 15000));
		assertTrue(refused.failed);
		assertTrue(pump(next.done));
		assertEquals("RW", next.value);
	}

	@Test
	public void requestMtuAlwaysCallsBack() throws Exception {
		connect();
		CountDownLatch done = new CountDownLatch(1);
		int[] mtu = new int[1];
		KnBle.requestMtu(mDevice, 247, m -> {
			mtu[0] = m;
			done.countDown();
		});
		assertTrue(pump(done));
		assertEquals(247, mtu[0]);
		assertEquals(247, KnBle.getMtu(mDevice));
	}

	@Test
	public void staleCallbackOfAClosedConnectionIsIgnored() throws Exception {
		connect();
		BluetoothGatt old = mGatt;
		BluetoothGattCallback stack = shadowOf(old).getGattCallback();
		KnBle.disconnect(mDevice);
		connect();

		// The previous GATT client reports a disconnection after the new connection
		stack.onConnectionStateChange(old, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
		Read r = read(SVC_A, CHR_RW);
		assertEquals("RW", r.value);
		assertTrue(KnBle.isConnected(mDevice));
	}

	// endregion

	// region Android and phone robustness

	/**
	 * Connection callbacks of the app, in order
	 */
	private final class Recorder implements BleGattCallback {
		final CountDownLatch connected = new CountDownLatch(1), disconnected = new CountDownLatch(1);

		@Override
		public void onConnecting() {
			mEvents.add("connecting");
		}

		@Override
		public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
			mEvents.add("connected");
			connected.countDown();
		}

		@Override
		public void onDisconnected(boolean connectFailed) {
			mEvents.add("disconnected " + connectFailed);
			disconnected.countDown();
		}
	}

	private void addServices(BluetoothGatt gatt) {
		shadowOf(gatt).addDiscoverableService(mServiceA);
		shadowOf(gatt).addDiscoverableService(mServiceB);
	}

	@Test
	public void connectionFailedWith133IsMadeAgain() throws Exception {
		Recorder rec = new Recorder();
		KnBle.connect(mDevice, rec);
		BluetoothGatt first = awaitNewClient(0);
		shadowOf(mBtDevice).simulateGattConnectionChange(133, BluetoothProfile.STATE_DISCONNECTED);

		BluetoothGatt second = awaitNewClient(1);
		assertTrue("the failed client is not closed", shadowOf(first).isClosed());
		addServices(second);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue("not connected on the second attempt", pump(rec.connected));
		assertEquals(List.of("connecting", "connected"), mEvents);
		assertEquals("RW", read(SVC_A, CHR_RW).value);
	}

	@Test
	public void pauseAfterCloseOnlyOnPhonesThatNeedIt() throws Exception {
		connect();
		KnBle.disconnect(mDevice);
		// Reconnection at once: no pause by default
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		Recorder rec = new Recorder();
		KnBle.connect(mDevice, rec);
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (shadowOf(mBtDevice).getBluetoothGatts().size() <= clients && System.currentTimeMillis() < deadline) Thread.sleep(5);
		assertTrue("connectGatt waited without advancing the clock", shadowOf(mBtDevice).getBluetoothGatts().size() > clients);
		assertFalse(DeviceActor.waitsAfterClose());

		// This phone fails (133) a connection made right after the close(): the pause is learnt
		BluetoothGatt last = shadowOf(mBtDevice).getBluetoothGatts().get(clients);
		BluetoothGattCallback stack = shadowOf(last).getGattCallback();
		stack.onConnectionStateChange(last, 133, BluetoothProfile.STATE_DISCONNECTED);
		long until = System.currentTimeMillis() + TIMEOUT_MS;
		while (!DeviceActor.waitsAfterClose() && System.currentTimeMillis() < until) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
		assertTrue(DeviceActor.waitsAfterClose());
	}

	@Test
	public void connectionGivesUpAfterThreeAttempts() throws Exception {
		Recorder rec = new Recorder();
		KnBle.connect(mDevice, rec);
		for (int attempt = 0; attempt < 3; attempt++) {
			awaitNewClient(attempt);
			shadowOf(mBtDevice).simulateGattConnectionChange(133, BluetoothProfile.STATE_DISCONNECTED);
		}
		assertTrue(pump(rec.disconnected));
		// No fourth attempt
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertEquals(3, shadowOf(mBtDevice).getBluetoothGatts().size());
		assertEquals(List.of("connecting", "disconnected true"), mEvents);
		assertEquals(133, BleEngine.get().actor(mDevice).lastStatus());
	}

	@Test
	public void emptyServiceTableIsDiscoveredAgainOnANewConnection() throws Exception {
		Recorder rec = new Recorder();
		KnBle.connect(mDevice, rec);
		awaitNewClient(0);
		// No service: the stack of some phones ends the first discovery like this
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);

		BluetoothGatt second = awaitNewClient(1);
		addServices(second);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue(pump(rec.connected));
		assertEquals(List.of("connecting", "connected"), mEvents);
	}

	/**
	 * The fake peripheral answers the first read of a characteristic with a status (a pairing needed)
	 */
	private void firstReadAnswers(int status) {
		BluetoothGattCallback bridge = shadowOf(mGatt).getGattCallback();
		boolean[] first = {true};
		shadowOf(mGatt).setGattCallback(new BluetoothGattCallback() {
			@Override
			public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int s) {
				bridge.onCharacteristicRead(gatt, characteristic, value, first[0] ? status : s);
				first[0] = false;
			}

			@Override
			@SuppressWarnings("deprecation")
			public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int s) {
				bridge.onCharacteristicRead(gatt, characteristic, first[0] ? status : s);
				first[0] = false;
			}
		});
	}

	private void bondStateChanged(int state) {
		shadowOf(mBtDevice).setBondState(state);
		Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice);
		intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state);
		ApplicationProvider.getApplicationContext().sendBroadcast(intent);
	}

	@Test
	public void operationNeedingABondIsReplayedOncePaired() throws Exception {
		connect();
		firstReadAnswers(5);
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDING);
		Read r = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, r);
		assertFalse("the read ended before the pairing", pump(r.done, 300));

		bondStateChanged(BluetoothDevice.BOND_BONDED);
		assertTrue(pump(r.done));
		assertEquals("RW", r.value);
	}

	@Test
	public void operationFailsWhenThePairingFails() throws Exception {
		connect();
		firstReadAnswers(15);
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDING);
		Read r = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, r);
		assertFalse(pump(r.done, 300));

		bondStateChanged(BluetoothDevice.BOND_NONE);
		assertTrue(pump(r.done));
		assertTrue(r.failed);
		// The queue goes on
		assertEquals("RW", read(SVC_A, CHR_RW).value);
	}

	@Test
	public void thePasskeyAskedByTheStackIsGivenByTheGattCallback() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_LE);
		connect(request -> {
			request.setPin("123456");
			return true;
		});
		firstReadAnswers(5);
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDING);
		Read r = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, r);
		// The stack asks the passkey of the device
		Context context = ApplicationProvider.getApplicationContext();
		context.sendOrderedBroadcast(new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice)
				.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PIN), null);
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (shadowOf(mBtDevice).getPin() == null && System.currentTimeMillis() < deadline) pump(r.done, 50);
		assertArrayEquals("123456".getBytes(StandardCharsets.US_ASCII), shadowOf(mBtDevice).getPin());

		bondStateChanged(BluetoothDevice.BOND_BONDED);
		assertTrue(pump(r.done));
		assertEquals("RW", r.value);
	}

	@Test
	public void aPairingCancelledByTheUserFailsTheOperationAndIsNotAskedAgain() throws Exception {
		connect(request -> {
			request.cancel();
			return true;
		});
		firstReadAnswers(5);
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDING);
		Read r = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, r);
		assertFalse(pump(r.done, 300));
		Context context = ApplicationProvider.getApplicationContext();
		context.sendOrderedBroadcast(new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice)
				.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.PAIRING_VARIANT_PIN), null);
		// Failed at once, not after the 30 s of the pairing
		assertTrue(pump(r.done));
		assertTrue(r.failed);

		// The device ends the pairing: the next read that needs it fails at once, KnBle does not ask again
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_NONE);
		firstReadAnswers(5);
		shadowOf(mBtDevice).setCreatedBond(true);
		Read again = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, again);
		assertTrue(pump(again.done, 1500));
		assertTrue(again.failed);
	}

	@Test
	public void pairingNotStartedByTheStackIsAskedThenFails() throws Exception {
		connect();
		firstReadAnswers(5);
		shadowOf(mBtDevice).setCreatedBond(false);
		Read r = new Read();
		KnBle.read(mDevice, SVC_A, CHR_RW, r);
		assertTrue(pump(r.done));
		assertTrue(r.failed);
	}

	// endregion

	// region 3.0.5 bugs

	@Test
	public void disableNotifyWithoutEnableKeepsTheQueueRunning() throws Exception {
		connect();
		KnBle.disableNotify(mDevice, SVC_A, CHR_NOTIFY);
		Read next = read(SVC_A, CHR_RW);
		assertEquals("the GATT queue is blocked", "RW", next.value);
	}

	@Test
	public void readMissingDescriptorKeepsTheQueueRunning() throws Exception {
		connect();
		Read desc = new Read();
		KnBle.readDesc(mDevice, SVC_A, CHR_RW, MISSING, desc);
		assertTrue(pump(desc.done));
		assertTrue(desc.failed);
		Read next = read(SVC_A, CHR_RW);
		assertEquals("the GATT queue is blocked", "RW", next.value);
	}

	@Test
	public void refusedNotificationKeepsTheQueueRunning() throws Exception {
		connect();
		shadowOf(mGatt).disallowCharacteristicNotification(mNotify);
		Notify n = new Notify() {
			@Override
			public void onNotify(@NonNull byte[] data) {}
		};
		KnBle.enableNotify(mDevice, SVC_A, CHR_NOTIFY, n);
		assertTrue(pump(n.answered));
		assertEquals(Boolean.FALSE, n.enabled);
		Read next = read(SVC_A, CHR_RW);
		assertEquals("the GATT queue is blocked", "RW", next.value);
	}

	@Test
	public void sameCharacteristicUuidInTwoServices() throws Exception {
		connect();
		assertEquals("A", read(SVC_A, CHR_DUP).value);
		assertEquals("the characteristic of service A was read", "B", read(SVC_B, CHR_DUP).value);
	}

	@Test
	public void indicateOnlyCharacteristicIsSubscribedWithTheIndicationValue() throws Exception {
		connect();
		Notify n = new Notify() {
			@Override
			public void onNotify(@NonNull byte[] data) {}
		};
		KnBle.enableNotify(mDevice, SVC_A, CHR_INDICATE, n);
		assertTrue(pump(n.answered));
		assertEquals(Boolean.TRUE, n.enabled);
		assertArrayEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, cccdValue(mIndicate));
	}

	@Test
	public void notificationValuesAreNotOverwritten() throws Exception {
		connect();
		CountDownLatch received = new CountDownLatch(2);
		List<String> values = new CopyOnWriteArrayList<>();
		Notify n = new Notify() {
			@Override
			public void onNotify(@NonNull byte[] data) {
				values.add(new String(data, StandardCharsets.UTF_8));
				received.countDown();
			}
		};
		KnBle.enableNotify(mDevice, SVC_A, CHR_NOTIFY, n);
		assertTrue(pump(n.answered));

		// Two notifications in a row while the thread of the device is busy, as the stack delivers them
		CountDownLatch release = holdDeviceThread();
		BluetoothGattCallback stack = shadowOf(mGatt).getGattCallback();
		for (String v : new String[]{"first", "second"}) {
			byte[] b = v.getBytes(StandardCharsets.UTF_8);
			stack.onCharacteristicChanged(mGatt, mNotify, b);
		}
		release.countDown();

		assertTrue(pump(received));
		assertEquals(List.of("first", "second"), values);
	}

	/**
	 * Lost on 3.0.5 when the thread of the device was busy at that moment: held here to make it deterministic
	 */
	@Test
	public void connectRightAfterDisconnectIsNotLost() throws Exception {
		connect();
		CountDownLatch connecting = new CountDownLatch(1);
		CountDownLatch release = holdDeviceThread();
		KnBle.disconnect(mDevice);
		KnBle.connect(mDevice, new BleGattCallback() {
			@Override
			public void onConnecting() {
				connecting.countDown();
			}

			@Override
			public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {}

			@Override
			public void onDisconnected(boolean connectFailed) {}
		});
		release.countDown();
		assertTrue("the connect() was dropped by the disconnection", pump(connecting));
	}

	@Test
	public void operationOnUnknownDeviceCallsBack() throws Exception {
		BleDevice unknown = KnBle.getBleDeviceFromMac("aa:bb:cc:dd:ee:99");
		assertNotNull("lower case MAC refused", unknown);
		Read r = new Read();
		KnBle.read(unknown, SVC_A, CHR_RW, r);
		assertTrue("no callback at all", pump(r.done));
		assertTrue(r.failed);
	}

	// endregion
}
