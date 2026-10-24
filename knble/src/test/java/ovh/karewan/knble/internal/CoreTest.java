package ovh.karewan.knble.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BleReliableWrite;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.KnBleSettings;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleRequestCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The contract of the connection core: every onConnecting gets its end, an operation asked right after
 * destroyDevice + connect waits for the new connection, a request the device does not answer is not a dead link, a
 * failed subscription keeps the previous one, the callbacks before init come on the main thread.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CoreTest {
	private static final long TIMEOUT_MS = 8000;
	private static final String MAC = "AA:BB:CC:DD:EE:41";
	private static final UUID SVC = UUID.fromString("4b420001-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_RW = UUID.fromString("4b420003-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CHR_NOTIFY = UUID.fromString("4b420008-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	private Context mContext;
	private BluetoothAdapter mAdapter;
	private BleDevice mDevice;
	private BluetoothDevice mBtDevice;
	private BluetoothGatt mGatt;
	private BluetoothGattService mService;
	private BluetoothGattCharacteristic mRw, mNotify;
	private final List<String> mEvents = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		mAdapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(mAdapter).setEnabled(true);
		mBtDevice = mAdapter.getRemoteDevice(MAC);

		mService = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		mRw = new BluetoothGattCharacteristic(CHR_RW, BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
				BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
		mNotify = new BluetoothGattCharacteristic(CHR_NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
		mNotify.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
		mService.addCharacteristic(mRw);
		mService.addCharacteristic(mNotify);
		setValue(mRw, "RW");
	}

	@SuppressWarnings("deprecation")
	private static void setValue(@NonNull BluetoothGattCharacteristic c, @NonNull String value) {
		c.setValue(value.getBytes(StandardCharsets.UTF_8));
	}

	private void init() {
		assertTrue(KnBle.init(mContext, new KnBleSettings.Builder().setOperationTimeout(1000).build()));
		mDevice = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(mDevice);
	}

	@After
	public void tearDown() {
		BleEngine.resetForTests();
	}

	// region Helpers

	private static boolean pumpUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			if (condition.getAsBoolean()) return true;
			Thread.sleep(3);
		}
		return condition.getAsBoolean();
	}

	private static void pumpFor(long ms) throws InterruptedException {
		long until = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < until) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			Thread.sleep(3);
		}
	}

	private final class Recorder implements BleGattCallback {
		final AtomicInteger connected = new AtomicInteger();
		volatile List<BluetoothGattService> services;

		@Override
		public void onConnectSuccess(@NonNull List<BluetoothGattService> s) {
			mEvents.add("connected");
			services = s;
			connected.incrementAndGet();
		}

		@Override
		public void onConnecting() {
			mEvents.add("connecting");
		}

		@Override
		public void onDisconnected(boolean connectFailed, int status) {
			mEvents.add("disconnected " + connectFailed + " " + status);
		}

		@Override
		public void onBondLost(int reason) {
			mEvents.add("bond lost " + reason);
		}
	}

	private BluetoothGatt awaitClient(int previous) throws InterruptedException {
		assertTrue("connectGatt not called", pumpUntil(() -> shadowOf(mBtDevice).getBluetoothGatts().size() > previous));
		BluetoothGatt gatt = shadowOf(mBtDevice).getBluetoothGatts().get(previous);
		shadowOf(gatt).addDiscoverableService(mService);
		shadowOf(gatt).allowCharacteristicNotification(mNotify);
		return gatt;
	}

	private Recorder connect(boolean autoReconnect) throws InterruptedException {
		int previous = shadowOf(mBtDevice).getBluetoothGatts().size();
		Recorder rec = new Recorder();
		KnBle.connect(mDevice, autoReconnect, rec);
		mGatt = awaitClient(previous);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue("not connected", pumpUntil(() -> rec.connected.get() > 0));
		return rec;
	}

	private static final class Result implements BleReadCallback, BleWriteCallback, BleRequestCallback {
		volatile Integer error;
		volatile byte[] value;

		@Override
		public void onReadSuccess(@NonNull byte[] data) {
			value = data;
			error = 0;
		}

		@Override
		public void onReadFailed(int e) {
			error = e;
		}

		@Override
		public void onWriteSuccess() {
			error = 0;
		}

		@Override
		public void onWriteFailed(int e) {
			error = e;
		}

		@Override
		public void onResponse(@NonNull byte[] data) {
			value = data;
			error = 0;
		}

		@Override
		public void onRequestFailed(int e) {
			error = e;
		}
	}

	// endregion

	@Test
	public void requestsTheDeviceNeverAnswersDoNotEndTheConnection() throws Exception {
		init();
		connect(false);
		// Written (the stack answers) but never answered by a notification: twice, more than the lost requests allowed
		for (int i = 0; i < 2; i++) {
			Result r = new Result();
			KnBle.request(mDevice, SVC, CHR_RW, CHR_NOTIFY, new byte[]{0x01}, r);
			assertTrue(pumpUntil(() -> r.error != null));
			assertEquals(Integer.valueOf(BleError.TIMEOUT), r.error);
		}
		assertEquals(BleGattCallback.CONNECTED, KnBle.getDeviceConnState(mDevice));
		assertFalse(mEvents.toString(), mEvents.stream().anyMatch(e -> e.startsWith("disconnected")));
	}

	@Test
	public void aReliableWriteValueOverMtuMinus5IsRefused() throws Exception {
		init();
		connect(false);
		shadowOf(mGatt).setBeginReliableWriteResult(true);
		Result r = new Result();
		// MTU 23: one Prepare Write carries 18 bytes, a longer value would be cut by the stack then applied
		KnBle.reliableWrite(mDevice, new BleReliableWrite().add(SVC, CHR_RW, new byte[19]), r);
		assertTrue(pumpUntil(() -> r.error != null));
		assertEquals(Integer.valueOf(BleError.DATA_TOO_LARGE), r.error);
	}

	@Test
	public void aFailedSubscriptionAgainKeepsThePreviousOne() throws Exception {
		init();
		connect(false);
		List<String> first = new CopyOnWriteArrayList<>();
		AtomicInteger firstEnabled = new AtomicInteger(), secondFailed = new AtomicInteger();
		KnBle.enableNotify(mDevice, SVC, CHR_NOTIFY, new BleNotifyCallback() {
			@Override
			public void onNotify(@NonNull byte[] data) {
				first.add(new String(data, StandardCharsets.UTF_8));
			}

			@Override
			public void onNotifyEnabled() {
				firstEnabled.incrementAndGet();
			}
		});
		assertTrue(pumpUntil(() -> firstEnabled.get() == 1));

		// The device refuses the write of the CCCD for the second subscription
		BluetoothGattCallback bridge = shadowOf(mGatt).getGattCallback();
		shadowOf(mGatt).setGattCallback(new BluetoothGattCallback() {
			@Override
			public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
				bridge.onDescriptorWrite(gatt, descriptor, 3);
			}
		});
		KnBle.enableNotify(mDevice, SVC, CHR_NOTIFY, new BleNotifyCallback() {
			@Override
			public void onNotify(@NonNull byte[] data) {}

			@Override
			public void onNotifyFailed(int error) {
				secondFailed.incrementAndGet();
			}
		});
		assertTrue(pumpUntil(() -> secondFailed.get() == 1));

		// The device still notifies: the first callback keeps getting the values
		bridge.onCharacteristicChanged(mGatt, mNotify, "still".getBytes(StandardCharsets.UTF_8));
		assertTrue(pumpUntil(() -> first.contains("still")));
	}

	@Test
	public void anOperationAskedRightAfterDestroyThenConnectWaitsForTheNewConnection() throws Exception {
		init();
		connect(false);
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		Recorder second = new Recorder();
		Result read = new Result();
		// In a row on the main thread: the old thread of the device gets the read after its end
		KnBle.destroyDevice(mDevice);
		KnBle.connect(mDevice, second);
		KnBle.read(mDevice, SVC, CHR_RW, read);

		mGatt = awaitClient(clients);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue(pumpUntil(() -> second.connected.get() == 1));
		assertTrue(pumpUntil(() -> read.error != null));
		assertEquals("read not given to the new connection: " + BleError.name(read.error), Integer.valueOf(0), read.error);
	}

	@Test
	public void disconnectDuringAnAutoReconnectionEndsItsOnConnecting() throws Exception {
		init();
		connect(true);
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		// Link lost: told, then the first round of the auto-reconnection (onConnecting)
		shadowOf(mBtDevice).simulateGattConnectionChange(8, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected false 8")));
		assertTrue(pumpUntil(() -> shadowOf(mBtDevice).getBluetoothGatts().size() > clients));
		assertTrue(pumpUntil(() -> mEvents.stream().filter("connecting"::equals).count() == 2));

		KnBle.disconnect(mDevice);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected true " + BleError.CANCELLED)));
		pumpFor(500);
		assertEquals(List.of("connecting", "connected", "disconnected false 8", "connecting", "disconnected true " + BleError.CANCELLED), mEvents);
	}

	@Test
	public void bluetoothOffDuringAnAutoReconnectionEndsItsOnConnecting() throws Exception {
		init();
		connect(true);
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		shadowOf(mBtDevice).simulateGattConnectionChange(8, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> shadowOf(mBtDevice).getBluetoothGatts().size() > clients));
		assertTrue(pumpUntil(() -> mEvents.stream().filter("connecting"::equals).count() == 2));

		// As a connected link: the end of its onConnecting, then a new onConnecting once Bluetooth is back
		bluetooth(false);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected true " + BleError.BLUETOOTH_DISABLED)));
		bluetooth(true);
		assertTrue(pumpUntil(() -> mEvents.stream().filter("connecting"::equals).count() == 3));
		pumpFor(300);
		assertEquals(List.of("connecting", "connected", "disconnected false 8", "connecting", "disconnected true " + BleError.BLUETOOTH_DISABLED,
				"connecting"), mEvents);
	}

	/**
	 * ACTION_KEY_MISSING of Android 16+ for the device
	 */
	private void keyMissing(int reason) {
		mContext.sendBroadcast(new Intent(BleEngine.ACTION_KEY_MISSING).putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice)
				.putExtra(BleEngine.EXTRA_BOND_LOSS_REASON, reason));
	}

	@Test
	public void aLostBondEndsTheConnectionWithoutReconnection() throws Exception {
		init();
		connect(true);
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		keyMissing(3);
		assertTrue(pumpUntil(() -> mEvents.contains("bond lost 3")));
		// The link then ends (encryption failed): told BOND_LOST, no auto-reconnection
		shadowOf(mBtDevice).simulateGattConnectionChange(5, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected false " + BleError.BOND_LOST)));
		pumpFor(3500);
		assertEquals(clients, shadowOf(mBtDevice).getBluetoothGatts().size());
		assertEquals(List.of("connecting", "connected", "bond lost 3", "disconnected false " + BleError.BOND_LOST), mEvents);
	}

	@Test
	public void aLostBondDuringAnAutoReconnectionEndsIt() throws Exception {
		init();
		connect(true);
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		shadowOf(mBtDevice).simulateGattConnectionChange(8, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> shadowOf(mBtDevice).getBluetoothGatts().size() > clients));
		assertTrue(pumpUntil(() -> mEvents.stream().filter("connecting"::equals).count() == 2));

		keyMissing(0);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected true " + BleError.BOND_LOST)));
		pumpFor(3500);
		assertEquals(clients + 1, shadowOf(mBtDevice).getBluetoothGatts().size());
		assertEquals(List.of("connecting", "connected", "disconnected false 8", "connecting", "bond lost 0", "disconnected true " + BleError.BOND_LOST),
				mEvents);
	}

	private void bluetooth(boolean on) {
		shadowOf(mAdapter).setEnabled(on);
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
				.putExtra(BluetoothAdapter.EXTRA_STATE, on ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF));
	}

	@Test
	public void theServicesGivenToTheAppCanBeChanged() throws Exception {
		init();
		Recorder rec = connect(false);
		// 3.x gave the mutable list of the stack: apps sort it or remove from it
		rec.services.add(new BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY));
		rec.services.removeIf(s -> s.getUuid().equals(SVC));
		assertNotNull(KnBle.getService(mDevice, SVC));
	}

	@Test
	public void theCallbacksBeforeInitComeOnTheMainThread() throws Exception {
		BleDevice device = new BleDevice(mBtDevice);
		Result read = new Result();
		AtomicInteger mtu = new AtomicInteger();
		KnBle.read(device, SVC, CHR_RW, read);
		KnBle.requestMtu(device, 247, mtu::set);
		// Not inside the call of the app
		assertEquals(null, read.error);
		assertEquals(0, mtu.get());
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(Integer.valueOf(BleError.NOT_CONNECTED), read.error);
		assertEquals(23, mtu.get());
	}

	@Test
	public void theBondedDevicesHaveTheNameOfTheSystem() throws Exception {
		shadowOf(mBtDevice).setName("SENSOR-1");
		shadowOf(mAdapter).setBondedDevices(Set.of(mBtDevice));
		init();
		assertTrue(pumpUntil(() -> !KnBle.getBondedDevices().isEmpty() && "SENSOR-1".equals(KnBle.getBondedDevices().get(0).getName())));
		assertEquals("SENSOR-1", KnBle.getBleDeviceFromMac(MAC.toLowerCase()).getName());
		assertTrue(KnBle.isBonded(mDevice));
	}
}
