package ovh.karewan.knble.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BleReliableWrite;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleBondCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleServerCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.peripheral.BleServerService;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.scan.ScanSettings;
import ovh.karewan.knble.struct.BleConnectionParameters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The features of 4.0 around the GATT queue: Bluetooth state, connection parameters, services changed,
 * auto-reconnection, request, reliable write, bonds, background scan, GATT server, companion association
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FeaturesTest {
	private static final long TIMEOUT_MS = 3000;
	private static final String MAC = "AA:BB:CC:DD:EE:02";
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
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_ADVERTISE);
		mAdapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(mAdapter).setEnabled(true);

		assertTrue(KnBle.init(mContext));
		mDevice = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(mDevice);
		mBtDevice = mDevice.getDevice();

		mService = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		mRw = new BluetoothGattCharacteristic(CHR_RW, BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
				BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
		mNotify = new BluetoothGattCharacteristic(CHR_NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
		mNotify.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
		mService.addCharacteristic(mRw);
		mService.addCharacteristic(mNotify);
	}

	@After
	public void tearDown() {
		BleEngine.resetForTests();
	}

	// region Helpers

	private static void advance(long ms) {
		for (long done = 0; done < ms; done += 50) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			try {
				Thread.sleep(2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static boolean pump(CountDownLatch latch) throws InterruptedException {
		return pumpUntil(() -> latch.getCount() == 0, TIMEOUT_MS);
	}

	/**
	 * Runs the main looper and advances the clock until the condition (real time limit)
	 */
	private static boolean pumpUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			if (condition.getAsBoolean()) return true;
			Thread.sleep(5);
		}
		return condition.getAsBoolean();
	}

	private class Recorder implements BleGattCallback {
		final CountDownLatch connected = new CountDownLatch(1);
		volatile CountDownLatch reconnected = new CountDownLatch(1);
		volatile List<BluetoothGattService> changed;
		volatile BleConnectionParameters parameters;
		int connections;

		@Override
		public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
			mEvents.add("connected");
			if (++connections == 1) connected.countDown();
			else reconnected.countDown();
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
		public void onServicesChanged(@NonNull List<BluetoothGattService> services) {
			changed = services;
		}

		@Override
		public void onConnectionParametersChanged(@NonNull BleConnectionParameters p) {
			parameters = p;
		}
	}

	private BluetoothGatt awaitNewClient(int previousClients) throws InterruptedException {
		assertTrue("connectGatt not called", pumpUntil(() -> shadowOf(mBtDevice).getBluetoothGatts().size() > previousClients, TIMEOUT_MS));
		BluetoothGatt gatt = shadowOf(mBtDevice).getBluetoothGatts().get(previousClients);
		shadowOf(gatt).addDiscoverableService(mService);
		shadowOf(gatt).allowCharacteristicNotification(mNotify);
		return gatt;
	}

	private Recorder connect(boolean autoReconnect) throws InterruptedException {
		int previous = shadowOf(mBtDevice).getBluetoothGatts().size();
		Recorder rec = new Recorder();
		KnBle.connect(mDevice, autoReconnect, rec);
		mGatt = awaitNewClient(previous);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue("not connected", pump(rec.connected));
		return rec;
	}

	private void bluetoothState(int state) {
		shadowOf(mAdapter).setEnabled(state == BluetoothAdapter.STATE_ON);
		Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
		intent.putExtra(BluetoothAdapter.EXTRA_STATE, state);
		mContext.sendBroadcast(intent);
		advance(100);
	}

	private void bondState(int previous, int state) {
		shadowOf(mBtDevice).setBondState(state);
		Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice);
		intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state);
		intent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous);
		mContext.sendBroadcast(intent);
		advance(100);
	}

	private static final class Write implements BleWriteCallback {
		final CountDownLatch done = new CountDownLatch(1);
		volatile Integer error;

		@Override
		public void onWriteSuccess() {
			error = 0;
			done.countDown();
		}

		@Override
		public void onWriteFailed(int e) {
			error = e;
			done.countDown();
		}
	}

	// endregion

	// region Connection

	@Test
	public void bluetoothStateListenersAreToldOncePerChange() throws Exception {
		List<Boolean> states = new CopyOnWriteArrayList<>();
		KnBle.addBluetoothStateListener(states::add);
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		bluetoothState(BluetoothAdapter.STATE_OFF);
		bluetoothState(BluetoothAdapter.STATE_TURNING_ON);
		bluetoothState(BluetoothAdapter.STATE_ON);
		assertEquals(List.of(false, true), states);
	}

	@Test
	public void connectionParametersComeFromTheHiddenCallback() throws Exception {
		Recorder rec = connect(false);
		assertEquals(null, KnBle.getConnectionParameters(mDevice));

		// The method the stack calls on the callback (not in the public SDK)
		BluetoothGattCallback bridge = shadowOf(mGatt).getGattCallback();
		Method m = bridge.getClass().getMethod("onConnectionUpdated", BluetoothGatt.class, int.class, int.class, int.class, int.class);
		m.invoke(bridge, mGatt, 6, 0, 500, BluetoothGatt.GATT_SUCCESS);
		assertTrue(pumpUntil(() -> rec.parameters != null, TIMEOUT_MS));

		BleConnectionParameters p = KnBle.getConnectionParameters(mDevice);
		assertNotNull(p);
		assertEquals(7.5, p.getIntervalMs(), 0.001);
		assertEquals(5000, p.getSupervisionTimeoutMs());
		assertEquals(7.5, rec.parameters.getIntervalMs(), 0.001);
	}

	@Test
	public void serviceChangedIndicationGivesTheNewServices() throws Exception {
		Recorder rec = connect(false);
		shadowOf(mGatt).getGattCallback().onServiceChanged(mGatt);
		assertTrue("onServicesChanged not called", pumpUntil(() -> rec.changed != null, TIMEOUT_MS));
		assertEquals(SVC, rec.changed.get(0).getUuid());
	}

	@Test
	public void autoReconnectionAfterALossUntilDisconnect() throws Exception {
		Recorder rec = connect(true);
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();

		// Link lost (supervision timeout): told once, then KnBle connects again by itself
		shadowOf(mBtDevice).simulateGattConnectionChange(8, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected false 8"), TIMEOUT_MS));

		// An operation during the reconnection fails at once
		CountDownLatch readFailed = new CountDownLatch(1);
		KnBle.read(mDevice, SVC, CHR_RW, new BleReadCallback() {
			@Override
			public void onReadSuccess(@NonNull byte[] data) {}

			@Override
			public void onReadFailed(int error) {
				if (error == BleError.NOT_CONNECTED) readFailed.countDown();
			}
		});
		assertTrue(pump(readFailed));

		mGatt = awaitNewClient(clients);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue("not reconnected", pump(rec.reconnected));
		assertEquals(List.of("connecting", "connected", "disconnected false 8", "connecting", "connected"), mEvents);

		// disconnect() ends it: no new connection
		KnBle.disconnect(mDevice);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected false 0"), TIMEOUT_MS));
		int after = shadowOf(mBtDevice).getBluetoothGatts().size();
		advance(3000);
		assertEquals(after, shadowOf(mBtDevice).getBluetoothGatts().size());
	}

	@Test
	public void autoReconnectionWaitsForBluetooth() throws Exception {
		connect(true);
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		assertTrue(pumpUntil(() -> mEvents.contains("disconnected false " + BleError.BLUETOOTH_DISABLED), TIMEOUT_MS));
		int clients = shadowOf(mBtDevice).getBluetoothGatts().size();
		advance(3000);
		assertEquals("connected while bluetooth is off", clients, shadowOf(mBtDevice).getBluetoothGatts().size());

		bluetoothState(BluetoothAdapter.STATE_ON);
		awaitNewClient(clients);
	}

	// endregion

	// region Operations

	@Test
	public void requestTakesTheNotificationThatAnswersTheWrite() throws Exception {
		connect(false);
		byte[] command = {0x01, 0x02};
		CountDownLatch answered = new CountDownLatch(1);
		byte[][] response = new byte[1][];
		KnBle.request(mDevice, SVC, CHR_RW, CHR_NOTIFY, command, r -> {
			response[0] = r;
			answered.countDown();
		});
		// Subscribed by the request, then written
		assertTrue("not written", pumpUntil(() -> java.util.Arrays.equals(command, shadowOf(mGatt).getLatestWrittenBytes()), TIMEOUT_MS));

		shadowOf(mGatt).getGattCallback().onCharacteristicChanged(mGatt, mNotify, new byte[]{0x01, 0x00});
		assertTrue(pump(answered));
		assertArrayEquals(new byte[]{0x01, 0x00}, response[0]);
	}

	@Test
	public void requestWithoutAnswerTimesOut() throws Exception {
		connect(false);
		CountDownLatch failed = new CountDownLatch(1);
		int[] error = new int[1];
		KnBle.request(mDevice, SVC, CHR_RW, CHR_NOTIFY, new byte[]{0x01}, new ovh.karewan.knble.interfaces.BleRequestCallback() {
			@Override
			public void onResponse(@NonNull byte[] r) {}

			@Override
			public void onRequestFailed(int e) {
				error[0] = e;
				failed.countDown();
			}
		});
		assertTrue(pumpUntil(() -> failed.getCount() == 0, 15000));
		assertEquals(BleError.TIMEOUT, error[0]);
	}

	@Test
	public void reliableWriteWritesThenExecutes() throws Exception {
		connect(false);
		shadowOf(mGatt).setBeginReliableWriteResult(true);
		Write w = new Write();
		KnBle.reliableWrite(mDevice, new BleReliableWrite().add(SVC, CHR_RW, new byte[]{0x0A}).add(SVC, CHR_RW, new byte[]{0x0B}), w);
		assertTrue(pump(w.done));
		assertEquals(Integer.valueOf(0), w.error);
		assertArrayEquals(new byte[]{0x0B}, shadowOf(mGatt).getLatestWrittenBytes());
	}

	@Test
	public void reliableWriteRefusedByTheStackFails() throws Exception {
		connect(false);
		shadowOf(mGatt).setBeginReliableWriteResult(false);
		Write w = new Write();
		KnBle.reliableWrite(mDevice, new BleReliableWrite().add(SVC, CHR_RW, new byte[]{0x0A}), w);
		assertTrue(pump(w.done));
		assertEquals(Integer.valueOf(BleError.REFUSED), w.error);
	}

	// endregion

	// region Bonds

	@Test
	public void createBondFollowsThePairing() throws Exception {
		shadowOf(mAdapter).setBondedDevices(Collections.emptySet());
		assertFalse(KnBle.isBonded(mDevice));
		shadowOf(mBtDevice).setCreatedBond(true);

		CountDownLatch bonded = new CountDownLatch(1);
		KnBle.createBond(mDevice, bonded::countDown);
		advance(200);
		bondState(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING);
		assertEquals(BluetoothDevice.BOND_BONDING, KnBle.getBondState(mDevice));
		bondState(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED);
		assertTrue(pump(bonded));
		assertTrue(KnBle.isBonded(mDevice));
		assertEquals(1, KnBle.getBondedDevices().size());
	}

	@Test
	public void createBondRefusedByTheUserFails() throws Exception {
		shadowOf(mBtDevice).setCreatedBond(true);
		CountDownLatch failed = new CountDownLatch(1);
		int[] error = new int[1];
		KnBle.createBond(mDevice, new BleBondCallback() {
			@Override
			public void onBonded() {}

			@Override
			public void onBondFailed(int e) {
				error[0] = e;
				failed.countDown();
			}
		});
		advance(200);
		bondState(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING);
		bondState(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE);
		assertTrue(pump(failed));
		assertEquals(BleError.BOND_FAILED, error[0]);
	}

	@Test
	public void createBondOfABondedDeviceSucceedsAtOnce() throws Exception {
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDED);
		CountDownLatch bonded = new CountDownLatch(1);
		KnBle.createBond(mDevice, bonded::countDown);
		assertTrue(pump(bonded));
	}

	// endregion

	// region Background scan

	private static byte[] record(String name) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] n = name.getBytes(StandardCharsets.UTF_8);
		out.write(2);
		out.write(0x01);
		out.write(0x06);
		out.write(n.length + 1);
		out.write(0x09);
		out.write(n, 0, n.length);
		return out.toByteArray();
	}

	private ScanResult result(String mac, String name, int rssi) throws Exception {
		android.bluetooth.le.ScanRecord record = (android.bluetooth.le.ScanRecord) android.bluetooth.le.ScanRecord.class
				.getMethod("parseFromBytes", byte[].class).invoke(null, (Object) record(name));
		return new ScanResult(mAdapter.getRemoteDevice(mac), 0x1B, 1, 0, 0xFF, 127, rssi, 0, record, SystemClock.elapsedRealtimeNanos());
	}

	@Test
	public void backgroundScanResultsAreParsedAndFiltered() throws Exception {
		Intent intent = new Intent();
		ArrayList<ScanResult> results = new ArrayList<>();
		results.add(result("AA:BB:CC:DD:EE:10", "PTI-1", -60));
		results.add(result("AA:BB:CC:DD:EE:11", "Other", -60));
		results.add(result("AA:BB:CC:DD:EE:12", "PTI-2", -95));
		intent.putParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, results);

		ScanFilters filters = new ScanFilters.Builder().addDeviceNameStartsWith("PTI").setMinRssi(-80).build();
		List<BleDevice> devices = KnBle.parseBackgroundScan(intent, filters);
		assertEquals(1, devices.size());
		assertEquals("PTI-1", devices.get(0).getName());
		assertEquals(3, KnBle.parseBackgroundScan(intent, null).size());
		assertEquals(ovh.karewan.knble.interfaces.BleScanCallback.NO_ERROR, KnBle.getBackgroundScanError(intent));
	}

	@Test
	public void backgroundScanStartsWithThePendingIntent() throws Exception {
		PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, new Intent("scan"), PendingIntent.FLAG_MUTABLE);
		CountDownLatch started = new CountDownLatch(1);
		KnBle.startBackgroundScan(pi, new ScanFilters.Builder().addDeviceName("PTI").build(),
				new ScanSettings.Builder().setNeverForLocation(true).build(), started::countDown);
		assertTrue(pump(started));
		KnBle.stopBackgroundScan(pi);
		advance(200);
	}

	// endregion

	// region GATT server

	private static final UUID SRV = UUID.fromString("4b420101-8d28-4316-88b3-6e0111311ac6");
	private static final UUID SRV_RW = UUID.fromString("4b420102-8d28-4316-88b3-6e0111311ac6");
	private static final UUID SRV_NOTIFY = UUID.fromString("4b420103-8d28-4316-88b3-6e0111311ac6");

	private final class Server implements BleServerCallback {
		final CountDownLatch started = new CountDownLatch(1);
		final List<String> events = new CopyOnWriteArrayList<>();
		final List<byte[]> writes = new CopyOnWriteArrayList<>();

		@Override
		public void onServerStarted() {
			started.countDown();
		}

		@Override
		public void onServerFailed(int error) {
			events.add("failed " + error);
		}

		@Override
		public void onCentralConnected(@NonNull BleDevice central) {
			events.add("connected " + central.getMac());
		}

		@Override
		public void onCentralDisconnected(@NonNull BleDevice central) {
			events.add("disconnected " + central.getMac());
		}

		@Override
		public void onWrite(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, @NonNull byte[] value) {
			writes.add(value);
		}

		@Override
		public void onSubscriptionChanged(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, boolean subscribed) {
			events.add("subscribed " + subscribed);
		}
	}

	/**
	 * A started server: Robolectric does not call onServiceAdded, the test does
	 */
	private BluetoothGattServer startServer(Server callback) throws Exception {
		KnBle.startServer(callback, new BleServerService(SRV)
				.addCharacteristic(SRV_RW, BleServerService.READ | BleServerService.WRITE, "hello".getBytes(StandardCharsets.UTF_8))
				.addCharacteristic(SRV_NOTIFY, BleServerService.NOTIFY));
		BleEngine engine = BleEngine.get();
		assertNotNull(engine);
		BluetoothGattServer[] server = new BluetoothGattServer[1];
		assertTrue(pumpUntil(() -> {
			try {
				server[0] = engine.peripheral().serverForTests();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return server[0] != null;
		}, TIMEOUT_MS));
		BluetoothGattServerCallback cb = shadowOf(server[0]).getGattServerCallback();
		cb.onServiceAdded(BluetoothGatt.GATT_SUCCESS, server[0].getService(SRV) != null ? server[0].getService(SRV) : new BluetoothGattService(SRV, 0));
		assertTrue("server not started", pump(callback.started));
		assertTrue(KnBle.isServerStarted());
		return server[0];
	}

	@NonNull
	private static BluetoothGattCharacteristic serverChr(BluetoothGattServer server, UUID uuid) {
		BluetoothGattService s = server.getService(SRV);
		assertNotNull(s);
		BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
		assertNotNull(c);
		return c;
	}

	@Test
	public void serverAnswersReadsAndStoresWrites() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice central = mAdapter.getRemoteDevice("11:22:33:44:55:66");

		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);

		// A central of this server at its first request (the server is told of every LE link of the phone)
		BluetoothGattCharacteristic rw = serverChr(server, SRV_RW);
		cb.onCharacteristicReadRequest(central, 1, 0, rw);
		assertTrue(pumpUntil(() -> !shadowOf(server).getResponses().isEmpty(), TIMEOUT_MS));
		assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), shadowOf(server).getResponses().get(0));
		assertTrue(pumpUntil(() -> callback.events.contains("connected 11:22:33:44:55:66"), TIMEOUT_MS));
		assertEquals(1, KnBle.getConnectedCentrals().size());

		cb.onCharacteristicWriteRequest(central, 2, rw, false, true, 0, new byte[]{1, 2, 3});
		assertTrue(pumpUntil(() -> callback.writes.size() == 1, TIMEOUT_MS));
		assertArrayEquals(new byte[]{1, 2, 3}, callback.writes.get(0));

		// Long write: prepared parts, applied on execute
		shadowOf(server).clearResponses();
		cb.onCharacteristicWriteRequest(central, 3, rw, true, true, 0, new byte[]{9, 9});
		cb.onCharacteristicWriteRequest(central, 4, rw, true, true, 2, new byte[]{8});
		cb.onExecuteWrite(central, 5, true);
		assertTrue(pumpUntil(() -> callback.writes.size() == 2, TIMEOUT_MS));
		assertArrayEquals(new byte[]{9, 9, 8}, callback.writes.get(1));

		shadowOf(server).clearResponses();
		cb.onCharacteristicReadRequest(central, 6, 0, rw);
		assertTrue(pumpUntil(() -> !shadowOf(server).getResponses().isEmpty(), TIMEOUT_MS));
		assertArrayEquals(new byte[]{9, 9, 8}, shadowOf(server).getResponses().get(0));

		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> callback.events.contains("disconnected 11:22:33:44:55:66"), TIMEOUT_MS));
		assertTrue(KnBle.getConnectedCentrals().isEmpty());
	}

	@Test
	public void serverNotificationsNeedASubscription() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice central = mAdapter.getRemoteDevice("11:22:33:44:55:77");
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);

		Write nobody = new Write();
		KnBle.notifyCentrals(SRV, SRV_NOTIFY, new byte[]{1}, nobody);
		assertTrue(pump(nobody.done));
		assertEquals(Integer.valueOf(BleError.NOT_CONNECTED), nobody.error);

		// Subscribed: a value over MTU - 3 is refused before the stack
		BluetoothGattDescriptor cccd = serverChr(server, SRV_NOTIFY).getDescriptor(CCCD);
		cb.onDescriptorWriteRequest(central, 1, cccd, false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		assertTrue(pumpUntil(() -> callback.events.contains("subscribed true"), TIMEOUT_MS));
		Write tooLarge = new Write();
		KnBle.notifyCentrals(SRV, SRV_NOTIFY, new byte[30], tooLarge);
		assertTrue(pump(tooLarge.done));
		assertEquals(Integer.valueOf(BleError.DATA_TOO_LARGE), tooLarge.error);

		// Indications on a notify-only characteristic: refused
		shadowOf(server).clearResponses();
		cb.onDescriptorWriteRequest(central, 2, cccd, false, true, 0, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
		advance(200);
		assertTrue(callback.events.contains("subscribed true"));
		assertFalse(callback.events.contains("subscribed false"));
	}

	@Test
	public void serverOpensAgainWhenBluetoothComesBack() throws Exception {
		Server callback = new Server();
		BluetoothGattServer first = startServer(callback);
		BluetoothDevice central = mAdapter.getRemoteDevice("11:22:33:44:55:88");
		shadowOf(first).getGattServerCallback().onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		shadowOf(first).getGattServerCallback().onCharacteristicReadRequest(central, 1, 0, serverChr(first, SRV_RW));
		assertTrue(pumpUntil(() -> callback.events.contains("connected 11:22:33:44:55:88"), TIMEOUT_MS));

		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		assertTrue(pumpUntil(() -> callback.events.contains("disconnected 11:22:33:44:55:88"), TIMEOUT_MS));
		assertFalse(KnBle.isServerStarted());
		assertTrue(shadowOf(first).isClosed());

		bluetoothState(BluetoothAdapter.STATE_ON);
		BleEngine engine = BleEngine.get();
		assertNotNull(engine);
		BluetoothGattServer[] again = new BluetoothGattServer[1];
		assertTrue(pumpUntil(() -> {
			try {
				again[0] = engine.peripheral().serverForTests();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return again[0] != null && again[0] != first;
		}, TIMEOUT_MS));
		KnBle.stopServer();
		advance(200);
		assertTrue(shadowOf(again[0]).isClosed());
	}

	// endregion

	// region Companion

	@Test
	public void associateOpensTheActivityOfKnBle() {
		KnBle.associate(mContext, new ScanFilters.Builder().addDeviceNameStartsWith("PTI").build(), true, d -> {});
		Intent started = shadowOf((Application) mContext).getNextStartedActivity();
		assertNotNull(started);
		assertEquals(AssociateActivity.class.getName(), started.getComponent().getClassName());
	}

	// endregion
}
