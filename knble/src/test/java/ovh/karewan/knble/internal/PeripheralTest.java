package ovh.karewan.knble.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
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
import org.robolectric.shadows.ShadowBluetoothGattServer;
import org.robolectric.shadows.ShadowBluetoothLeAdvertiser;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleAdvertiseCallback;
import ovh.karewan.knble.interfaces.BleServerCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.peripheral.BleAdvertiseData;
import ovh.karewan.knble.peripheral.BleServerService;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Peripheral mode: which links are centrals, disconnection of a central, no callback after a stop, replaced or
 * unanswered starts, subscriptions of the bonded centrals, value lengths, long writes, Bluetooth off
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(shadows = {PeripheralTest.RecordingServer.class, PeripheralTest.RecordingAdvertiser.class})
public class PeripheralTest {
	private static final long TIMEOUT_MS = 3000;
	private static final UUID SRV = UUID.fromString("4b420201-8d28-4316-88b3-6e0111311ac6");
	private static final UUID SRV_RW = UUID.fromString("4b420202-8d28-4316-88b3-6e0111311ac6");
	private static final UUID SRV_NOTIFY = UUID.fromString("4b420203-8d28-4316-88b3-6e0111311ac6");
	private static final UUID SRV_LATE = UUID.fromString("4b420204-8d28-4316-88b3-6e0111311ac6");
	private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	private static final String CENTRAL = "11:22:33:44:55:01", WATCH = "11:22:33:44:55:02";

	private Context mContext;
	private BluetoothAdapter mAdapter;

	/**
	 * The server of the system, with what KnBle asked it
	 */
	@Implements(BluetoothGattServer.class)
	public static class RecordingServer extends ShadowBluetoothGattServer {
		static final List<String> calls = new CopyOnWriteArrayList<>();
		final List<Integer> statuses = new CopyOnWriteArrayList<>();

		@Implementation
		protected boolean connect(BluetoothDevice device, boolean autoConnect) {
			calls.add("connect " + device.getAddress() + " " + autoConnect);
			return true;
		}

		@Override
		@Implementation
		protected void cancelConnection(BluetoothDevice device) {
			calls.add("cancel " + device.getAddress());
			super.cancelConnection(device);
		}

		@Override
		@Implementation
		protected boolean sendResponse(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
			statuses.add(status);
			return super.sendResponse(device, requestId, status, offset, value);
		}
	}

	/**
	 * Legacy advertising: the starts and stops asked, the test answers for the system
	 */
	@Implements(BluetoothLeAdvertiser.class)
	public static class RecordingAdvertiser extends ShadowBluetoothLeAdvertiser {
		static final List<AdvertiseCallback> started = new CopyOnWriteArrayList<>();
		static final List<AdvertiseCallback> stopped = new CopyOnWriteArrayList<>();

		@Implementation
		protected void startAdvertising(AdvertiseSettings settings, AdvertiseData data, AdvertiseData scanResponse, AdvertiseCallback callback) {
			started.add(callback);
		}

		@Implementation
		protected void stopAdvertising(AdvertiseCallback callback) {
			stopped.add(callback);
		}
	}

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		RecordingServer.calls.clear();
		RecordingAdvertiser.started.clear();
		RecordingAdvertiser.stopped.clear();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_ADVERTISE);
		mAdapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(mAdapter).setEnabled(true);
		assertTrue(KnBle.init(mContext));
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

	private static PeripheralEngine peripheral() {
		BleEngine engine = BleEngine.get();
		assertNotNull(engine);
		return engine.peripheral();
	}

	/**
	 * What was posted to the thread of the peripheral ran (its callbacks for the app wait in the Dispatcher: the main
	 * looper is not run)
	 */
	private static BluetoothGattServer flush() throws InterruptedException {
		return peripheral().serverForTests();
	}

	private void bluetoothState(int state) {
		shadowOf(mAdapter).setEnabled(state == BluetoothAdapter.STATE_ON);
		Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
		intent.putExtra(BluetoothAdapter.EXTRA_STATE, state);
		mContext.sendBroadcast(intent);
		advance(100);
	}

	private static final class Server implements BleServerCallback {
		final List<String> events = new CopyOnWriteArrayList<>();
		final List<byte[]> writes = new CopyOnWriteArrayList<>();

		@Override
		public void onServerStarted() {
			events.add("started");
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
			events.add("write");
			writes.add(value);
		}

		@Override
		public void onSubscriptionChanged(@NonNull BleDevice central, @NonNull UUID service, @NonNull UUID characteristic, boolean subscribed) {
			events.add("subscribed " + central.getMac() + " " + subscribed);
		}

		@Override
		public void onMtuChanged(@NonNull BleDevice central, int mtu) {
			events.add("mtu " + central.getMac() + " " + mtu);
		}
	}

	private static final class Advertiser implements BleAdvertiseCallback {
		final List<String> events = new CopyOnWriteArrayList<>();

		@Override
		public void onAdvertisingStarted() {
			events.add("started");
		}

		@Override
		public void onAdvertisingFailed(int error) {
			events.add("failed " + error);
		}

		@Override
		public void onAdvertisingStopped() {
			events.add("stopped");
		}
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

	@NonNull
	private static BleServerService service() {
		return new BleServerService(SRV)
				.addCharacteristic(SRV_RW, BleServerService.READ | BleServerService.WRITE, "hello".getBytes(StandardCharsets.UTF_8))
				.addCharacteristic(SRV_NOTIFY, BleServerService.READ | BleServerService.NOTIFY, new byte[]{7});
	}

	/**
	 * A new server of the system opened by KnBle, its services added (Robolectric does not call onServiceAdded)
	 */
	private static BluetoothGattServer awaitServer(BluetoothGattServer previous, Server callback) throws InterruptedException {
		BluetoothGattServer[] server = new BluetoothGattServer[1];
		assertTrue("server not opened", pumpUntil(() -> {
			try {
				server[0] = flush();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return server[0] != null && server[0] != previous;
		}));
		BluetoothGattService added = server[0].getService(SRV);
		assertNotNull(added);
		shadowOf(server[0]).getGattServerCallback().onServiceAdded(BluetoothGatt.GATT_SUCCESS, added);
		assertTrue("server not started", pumpUntil(() -> callback.events.contains("started")));
		return server[0];
	}

	private BluetoothGattServer startServer(Server callback) throws InterruptedException {
		KnBle.startServer(callback, service());
		return awaitServer(null, callback);
	}

	@NonNull
	private static BluetoothGattCharacteristic chr(BluetoothGattServer server, UUID uuid) {
		BluetoothGattService s = server.getService(SRV);
		assertNotNull(s);
		BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
		assertNotNull(c);
		return c;
	}

	private static RecordingServer recording(BluetoothGattServer server) {
		return Shadow.extract(server);
	}

	/**
	 * The last response of the server, once given
	 */
	private static byte[] response(BluetoothGattServer server, int before) throws InterruptedException {
		assertTrue("no response", pumpUntil(() -> shadowOf(server).getResponses().size() > before));
		List<byte[]> responses = shadowOf(server).getResponses();
		return responses.get(responses.size() - 1);
	}

	private static int status(BluetoothGattServer server, int before) throws InterruptedException {
		RecordingServer r = recording(server);
		assertTrue("no response", pumpUntil(() -> r.statuses.size() > before));
		return r.statuses.get(r.statuses.size() - 1);
	}

	// endregion

	// region Centrals

	@Test
	public void onlyALinkWithARequestIsACentral() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice watch = mAdapter.getRemoteDevice(WATCH);
		BluetoothDevice central = mAdapter.getRemoteDevice(CENTRAL);

		// Every LE link of the phone and its MTU reach every server: not a central of this one
		cb.onConnectionStateChange(watch, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onMtuChanged(watch, 185);
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onMtuChanged(central, 247);
		flush();
		advance(200);
		assertEquals(List.of("started"), callback.events);
		assertTrue(KnBle.getConnectedCentrals().isEmpty());

		// Its first request: a central, with the MTU exchanged before
		cb.onDescriptorWriteRequest(central, 1, chr(server, SRV_NOTIFY).getDescriptor(CCCD), false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		assertTrue(pumpUntil(() -> callback.events.size() == 4));
		assertEquals(List.of("started", "connected " + CENTRAL, "mtu " + CENTRAL + " 247", "subscribed " + CENTRAL + " true"), callback.events);
		assertEquals(1, KnBle.getConnectedCentrals().size());
		assertEquals(CENTRAL, KnBle.getConnectedCentrals().get(0).getMac());

		cb.onConnectionStateChange(watch, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
		assertTrue(pumpUntil(() -> callback.events.contains("disconnected " + CENTRAL)));
		advance(100);
		assertFalse(callback.events.contains("disconnected " + WATCH));
		assertTrue(KnBle.getConnectedCentrals().isEmpty());
	}

	@Test
	public void disconnectionHoldsTheLinkBeforeCancellingIt() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice watch = mAdapter.getRemoteDevice(WATCH);
		BluetoothDevice central = mAdapter.getRemoteDevice(CENTRAL);
		cb.onConnectionStateChange(watch, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onCharacteristicReadRequest(central, 1, 0, chr(server, SRV_RW));
		assertTrue(pumpUntil(() -> KnBle.getConnectedCentrals().size() == 1));

		// cancelConnection alone does nothing on a link the central opened
		KnBle.disconnectCentral(KnBle.getConnectedCentrals().get(0));
		flush();
		assertEquals(List.of("connect " + CENTRAL + " false", "cancel " + CENTRAL), RecordingServer.calls);

		// The centrals of this server only, not the watch
		RecordingServer.calls.clear();
		KnBle.stopServer();
		flush();
		assertEquals(List.of("connect " + CENTRAL + " false", "cancel " + CENTRAL), RecordingServer.calls);
		assertTrue(shadowOf(server).isClosed());
	}

	// endregion

	// region No callback after a stop

	@Test
	public void noServerCallbackAfterStopOrReplacement() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice central = mAdapter.getRemoteDevice(CENTRAL);
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onCharacteristicReadRequest(central, 1, 0, chr(server, SRV_RW));
		assertTrue(pumpUntil(() -> callback.events.contains("connected " + CENTRAL)));

		// A write handled, its onWrite waiting for the main thread, then a new server: not given to the old callback
		cb.onCharacteristicWriteRequest(central, 2, chr(server, SRV_RW), false, true, 0, new byte[]{1});
		flush();
		Server second = new Server();
		KnBle.startServer(second, service());
		BluetoothGattServer secondServer = awaitServer(server, second);
		advance(200);
		assertFalse(callback.events.contains("write"));
		assertFalse(callback.events.contains("failed " + BleError.CANCELLED));
		assertFalse(callback.events.contains("disconnected " + CENTRAL));

		// Same with stopServer
		cb = shadowOf(secondServer).getGattServerCallback();
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onCharacteristicWriteRequest(central, 3, chr(secondServer, SRV_RW), false, true, 0, new byte[]{2});
		flush();
		KnBle.stopServer();
		advance(200);
		assertEquals(List.of("started"), second.events);
	}

	@Test
	public void noAdvertisingCallbackAfterStop() throws Exception {
		Advertiser callback = new Advertiser();
		KnBle.startAdvertising(new BleAdvertiseData.Builder().addServiceUuid(SRV).build(), callback);
		assertTrue(pumpUntil(() -> RecordingAdvertiser.started.size() == 1));

		// Started, onAdvertisingStarted waiting for the main thread: dropped by stopAdvertising
		RecordingAdvertiser.started.get(0).onStartSuccess(null);
		flush();
		KnBle.stopAdvertising();
		advance(200);
		assertTrue(callback.events.isEmpty());
		assertFalse(KnBle.isAdvertising());
		assertEquals(RecordingAdvertiser.started, RecordingAdvertiser.stopped);
	}

	// endregion

	// region Starts

	@Test
	public void replacedStartIsCancelled() throws Exception {
		BleAdvertiseData data = new BleAdvertiseData.Builder().addServiceUuid(SRV).build();
		Advertiser first = new Advertiser(), second = new Advertiser(), third = new Advertiser();
		KnBle.startAdvertising(data, first);
		KnBle.startAdvertising(data, second);
		assertTrue(pumpUntil(() -> first.events.contains("failed " + BleError.CANCELLED)));
		// Both starts handled: the last one asked to the system is the second
		flush();
		AdvertiseCallback last = RecordingAdvertiser.started.get(RecordingAdvertiser.started.size() - 1);
		last.onStartSuccess(null);
		assertTrue(pumpUntil(() -> second.events.contains("started")));

		// Replaced once started: nothing
		KnBle.startAdvertising(data, third);
		advance(200);
		assertEquals(List.of("started"), second.events);
		assertEquals(List.of("failed " + BleError.CANCELLED), first.events);

		// Replaced then stopped: nothing after the stop
		Advertiser fourth = new Advertiser(), fifth = new Advertiser();
		KnBle.startAdvertising(data, fourth);
		KnBle.startAdvertising(data, fifth);
		KnBle.stopAdvertising();
		advance(200);
		assertTrue(fourth.events.isEmpty());
		assertTrue(fifth.events.isEmpty());

		// The server too
		Server a = new Server(), b = new Server();
		KnBle.startServer(a, service());
		KnBle.startServer(b, service());
		assertTrue(pumpUntil(() -> a.events.contains("failed " + BleError.CANCELLED)));
		awaitServer(null, b);
		assertEquals(List.of("failed " + BleError.CANCELLED), a.events);
	}

	@Test
	public void advertisingStartWithoutAnswerTimesOut() throws Exception {
		Advertiser callback = new Advertiser();
		KnBle.startAdvertising(new BleAdvertiseData.Builder().addServiceUuid(SRV).build(), callback);
		assertTrue(pumpUntil(() -> RecordingAdvertiser.started.size() == 1));
		advance(5200);
		assertTrue(pumpUntil(() -> callback.events.contains("failed " + BleError.TIMEOUT)));
		// Stopped in case it starts late, and its late start ignored
		assertEquals(RecordingAdvertiser.started, RecordingAdvertiser.stopped);
		RecordingAdvertiser.started.get(0).onStartSuccess(null);
		flush();
		advance(200);
		assertEquals(List.of("failed " + BleError.TIMEOUT), callback.events);
		assertFalse(KnBle.isAdvertising());
	}

	// endregion

	// region Bluetooth off

	@Test
	public void bluetoothOffStopsTheAdvertising() throws Exception {
		Advertiser callback = new Advertiser();
		KnBle.startAdvertising(new BleAdvertiseData.Builder().addServiceUuid(SRV).build(), callback);
		assertTrue(pumpUntil(() -> RecordingAdvertiser.started.size() == 1));
		AdvertiseCallback first = RecordingAdvertiser.started.get(0);
		first.onStartSuccess(null);
		assertTrue(pumpUntil(() -> callback.events.contains("started")));

		// The stack may keep it on air (BLE_ON): stopped all the same, started again with Bluetooth
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		assertTrue(pumpUntil(() -> callback.events.contains("stopped")));
		assertEquals(List.of(first), RecordingAdvertiser.stopped);
		bluetoothState(BluetoothAdapter.STATE_OFF);
		bluetoothState(BluetoothAdapter.STATE_ON);
		advance(1200);
		assertTrue(pumpUntil(() -> RecordingAdvertiser.started.size() == 2));

		// A start not answered when Bluetooth turns off: that is its result, no restart
		KnBle.stopAdvertising();
		Advertiser pending = new Advertiser();
		KnBle.startAdvertising(new BleAdvertiseData.Builder().addServiceUuid(SRV).build(), pending);
		assertTrue(pumpUntil(() -> RecordingAdvertiser.started.size() == 3));
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		assertTrue(pumpUntil(() -> pending.events.contains("failed " + BleError.BLUETOOTH_DISABLED)));
		bluetoothState(BluetoothAdapter.STATE_ON);
		advance(1200);
		assertEquals(3, RecordingAdvertiser.started.size());
	}

	@Test
	public void servicesAreCopiedAtStart() throws Exception {
		Server callback = new Server();
		BleServerService service = service();
		KnBle.startServer(callback, service);
		BluetoothGattServer first = awaitServer(null, callback);

		// Changed by the app afterwards: the server opened again after Bluetooth off and on has the services of the start
		service.addCharacteristic(SRV_LATE, BleServerService.READ, new byte[]{1});
		callback.events.clear();
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		bluetoothState(BluetoothAdapter.STATE_ON);
		advance(1200);
		BluetoothGattServer again = awaitServer(first, callback);
		assertNotNull(again.getService(SRV).getCharacteristic(SRV_RW));
		assertNull(again.getService(SRV).getCharacteristic(SRV_LATE));
	}

	// endregion

	// region Subscriptions, values, long writes

	@Test
	public void bondedCentralKeepsItsSubscriptions() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice bonded = mAdapter.getRemoteDevice(CENTRAL);
		shadowOf(bonded).setBondState(BluetoothDevice.BOND_BONDED);
		BluetoothDevice other = mAdapter.getRemoteDevice(WATCH);
		BluetoothGattDescriptor cccd = chr(server, SRV_NOTIFY).getDescriptor(CCCD);

		for (BluetoothDevice d : List.of(bonded, other)) {
			cb.onConnectionStateChange(d, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
			cb.onDescriptorWriteRequest(d, 1, cccd, false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			cb.onConnectionStateChange(d, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
		}
		assertTrue(pumpUntil(() -> callback.events.contains("disconnected " + WATCH)));
		callback.events.clear();

		// Back: the bonded central is a central at once, still subscribed (it does not write the CCCD again)
		cb.onConnectionStateChange(bonded, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onConnectionStateChange(other, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue(pumpUntil(() -> callback.events.size() == 2));
		advance(100);
		assertEquals(List.of("connected " + CENTRAL, "subscribed " + CENTRAL + " true"), callback.events);
		int before = shadowOf(server).getResponses().size();
		cb.onDescriptorReadRequest(bonded, 2, 0, cccd);
		assertArrayEquals(new byte[]{1, 0}, response(server, before));
		cb.onDescriptorReadRequest(other, 3, 0, cccd);
		assertArrayEquals(new byte[]{0, 0}, response(server, before + 1));

		// Bluetooth off and on: kept too
		callback.events.clear();
		bluetoothState(BluetoothAdapter.STATE_TURNING_OFF);
		bluetoothState(BluetoothAdapter.STATE_ON);
		advance(1200);
		BluetoothGattServer again = awaitServer(server, callback);
		shadowOf(again).getGattServerCallback().onConnectionStateChange(bonded, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue(pumpUntil(() -> callback.events.contains("subscribed " + CENTRAL + " true")));
	}

	@Test
	public void valuesOver512BytesAreRefused() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> new BleServerService(SRV).addCharacteristic(SRV_RW, BleServerService.READ, new byte[513]));
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice central = mAdapter.getRemoteDevice(CENTRAL);
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		cb.onMtuChanged(central, 517);
		cb.onDescriptorWriteRequest(central, 1, chr(server, SRV_NOTIFY).getDescriptor(CCCD), false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		assertTrue(pumpUntil(() -> callback.events.contains("subscribed " + CENTRAL + " true")));

		assertThrows(IllegalArgumentException.class, () -> KnBle.setServerValue(SRV, SRV_RW, new byte[513]));
		// MTU 517 allows 514 bytes, an attribute 512: refused before the stack (which throws), and not stored
		Write tooLarge = new Write();
		KnBle.notifyCentrals(SRV, SRV_NOTIFY, new byte[513], tooLarge);
		assertTrue(pumpUntil(() -> tooLarge.done.getCount() == 0));
		assertEquals(Integer.valueOf(BleError.DATA_TOO_LARGE), tooLarge.error);
		int before = shadowOf(server).getResponses().size();
		cb.onCharacteristicReadRequest(central, 2, 0, chr(server, SRV_NOTIFY));
		assertArrayEquals(new byte[]{7}, response(server, before));
	}

	@Test
	public void longWritesAreBoundedAndChecked() throws Exception {
		Server callback = new Server();
		BluetoothGattServer server = startServer(callback);
		BluetoothGattServerCallback cb = shadowOf(server).getGattServerCallback();
		BluetoothDevice central = mAdapter.getRemoteDevice(CENTRAL);
		BluetoothGattCharacteristic rw = chr(server, SRV_RW);
		RecordingServer r = recording(server);
		cb.onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);

		// A central that never executes: its queue is bounded
		for (int i = 0; i < 8; i++) {
			cb.onCharacteristicWriteRequest(central, i, rw, true, true, 0, new byte[512]);
			assertEquals(BluetoothGatt.GATT_SUCCESS, status(server, i));
		}
		cb.onCharacteristicWriteRequest(central, 8, rw, true, true, 0, new byte[1]);
		assertEquals(0x09, status(server, 8));
		cb.onExecuteWrite(central, 9, false);
		assertEquals(BluetoothGatt.GATT_SUCCESS, status(server, 9));

		// A part past the end of the value: the whole execute fails, nothing written
		cb.onCharacteristicWriteRequest(central, 10, rw, true, true, 0, new byte[]{1, 2});
		cb.onCharacteristicWriteRequest(central, 11, rw, true, true, 5, new byte[]{3});
		cb.onExecuteWrite(central, 12, true);
		assertEquals(BluetoothGatt.GATT_INVALID_OFFSET, status(server, 12));
		int before = shadowOf(server).getResponses().size();
		cb.onCharacteristicReadRequest(central, 13, 0, rw);
		assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), response(server, before));
		assertFalse(callback.events.contains("write"));

		// The queue is free again after an execute
		cb.onCharacteristicWriteRequest(central, 14, rw, true, true, 0, new byte[]{1, 2});
		cb.onCharacteristicWriteRequest(central, 15, rw, true, true, 2, new byte[]{3});
		cb.onExecuteWrite(central, 16, true);
		assertTrue(pumpUntil(() -> callback.writes.size() == 1));
		assertArrayEquals(new byte[]{1, 2, 3}, callback.writes.get(0));
		assertEquals(17, r.statuses.size());
	}

	@Test
	public void advertiseDataRefusesUnknownModeAndPower() {
		BleAdvertiseData.Builder b = new BleAdvertiseData.Builder();
		for (int bad : Arrays.asList(-1, 3, 99)) assertThrows(IllegalArgumentException.class, () -> b.setMode(bad));
		for (int bad : Arrays.asList(-1, 4, 99)) assertThrows(IllegalArgumentException.class, () -> b.setTxPower(bad));
		BleAdvertiseData data = b.setMode(BleAdvertiseData.MODE_LOW_LATENCY).setTxPower(BleAdvertiseData.TX_POWER_ULTRA_LOW).build();
		assertEquals(BleAdvertiseData.MODE_LOW_LATENCY, data.getMode());
		assertEquals(BleAdvertiseData.TX_POWER_ULTRA_LOW, data.getTxPower());
	}

	// endregion
}
