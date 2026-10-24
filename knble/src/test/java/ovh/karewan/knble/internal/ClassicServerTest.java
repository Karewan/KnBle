package ovh.karewan.knble.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.ClassicServerOptions;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.KnBleSettings;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicServerCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Bluetooth classic server: listening, clients as classic connections (stream, writes, end), refusal, limit of
 * clients, stop, Bluetooth off then on, replaced start. Robolectric does not end the input stream of a closed socket
 * (Android does): the tests close it themselves.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClassicServerTest {
	private static final long TIMEOUT_MS = 5000;
	private static final String MAC_1 = "AA:BB:CC:DD:EE:51", MAC_2 = "AA:BB:CC:DD:EE:52";

	private Context mContext;
	private BluetoothAdapter mAdapter;
	private final List<String> mServerEvents = new CopyOnWriteArrayList<>();
	private final List<BluetoothSocket> mClients = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		mAdapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(mAdapter).setEnabled(true);
		assertTrue(KnBle.init(mContext, new KnBleSettings.Builder().build()));
	}

	@After
	public void tearDown() {
		for (BluetoothSocket s : mClients) closeFeeder(s);
		BleEngine.resetForTests();
	}

	// region Helpers

	private static boolean pumpUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			if (condition.getAsBoolean()) return true;
			Thread.sleep(5);
		}
		return condition.getAsBoolean();
	}

	private static void pumpFor(long ms) throws InterruptedException {
		for (long done = 0; done < ms; done += 50) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			Thread.sleep(2);
		}
	}

	private static void closeFeeder(@NonNull BluetoothSocket s) {
		try {
			shadowOf(s).getInputStreamFeeder().close();
		} catch (IOException ignored) {
		}
	}

	private void bluetooth(boolean on) throws InterruptedException {
		shadowOf(mAdapter).setEnabled(on);
		mContext.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
				.putExtra(BluetoothAdapter.EXTRA_STATE, on ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF));
		pumpFor(100);
	}

	@Nullable
	private static BluetoothServerSocket serverSocket() {
		BleEngine e = BleEngine.get();
		return e == null ? null : e.classic().serverForTests().socketForTests();
	}

	/**
	 * The callback of one client connection
	 */
	private static final class Client implements BleClassicCallback {
		final List<String> events = new CopyOnWriteArrayList<>();
		final ByteArrayOutputStream data = new ByteArrayOutputStream();

		@Override
		public synchronized void onData(@NonNull byte[] d) {
			data.write(d, 0, d.length);
		}

		synchronized String text() {
			return data.toString(StandardCharsets.US_ASCII);
		}

		@Override
		public void onConnected() {
			events.add("connected");
		}

		@Override
		public void onDisconnected(boolean connectFailed, int status) {
			events.add("disconnected " + connectFailed + " " + status);
		}
	}

	/**
	 * A server whose clients get the callbacks of the list, in order (null: refused)
	 */
	private final class Server implements BleClassicServerCallback {
		final List<Client> clients = new ArrayList<>();
		final List<BleDevice> connected = new CopyOnWriteArrayList<>();
		private int mNext;

		Server(Client... clients) {
			for (Client c : clients) this.clients.add(c);
		}

		@Nullable
		@Override
		public BleClassicCallback onClientConnected(@NonNull BleDevice client) {
			connected.add(client);
			mServerEvents.add("client " + client.getMac());
			return mNext < clients.size() ? clients.get(mNext++) : null;
		}

		@Override
		public void onServerStarted() {
			mServerEvents.add("started");
		}

		@Override
		public void onServerFailed(int error) {
			mServerEvents.add("failed " + error);
		}
	}

	private BluetoothSocket clientConnects(@NonNull String mac) throws InterruptedException {
		BluetoothServerSocket server = serverSocket();
		assertNotNull("not listening", server);
		BluetoothDevice device = mAdapter.getRemoteDevice(mac);
		BluetoothSocket s = shadowOf(server).deviceConnected(device);
		mClients.add(s);
		return s;
	}

	private void started(@NonNull Server server) throws InterruptedException {
		KnBle.startClassicServer(new ClassicServerOptions.Builder().setSecure(false).build(), server);
		assertTrue(pumpUntil(() -> mServerEvents.contains("started") && serverSocket() != null));
	}

	// endregion

	@Test
	public void aClientIsAClassicConnection() throws Exception {
		Client client = new Client();
		started(new Server(client));
		assertTrue(KnBle.isClassicServerStarted());

		BluetoothSocket socket = clientConnects(MAC_1);
		// Sent at once by the client, before the app decides: nothing lost
		shadowOf(socket).getInputStreamFeeder().write("HELLO".getBytes(StandardCharsets.US_ASCII));
		assertTrue(pumpUntil(() -> client.events.contains("connected")));
		assertTrue(pumpUntil(() -> client.text().equals("HELLO")));
		BleDevice device = KnBle.getBleDeviceFromMac(MAC_1);
		assertNotNull(device);
		assertEquals(BleGattCallback.CONNECTED, KnBle.getClassicConnState(device));
		assertTrue(KnBle.getClassicConnectedDevices().contains(device));

		// Writes to the client, in order, with their results
		List<String> results = new CopyOnWriteArrayList<>();
		KnBle.writeClassic(device, "AB".getBytes(StandardCharsets.US_ASCII), new BleWriteCallback() {
			@Override
			public void onWriteSuccess() {
				results.add("ok");
			}

			@Override
			public void onWriteFailed(int error) {
				results.add("failed " + error);
			}
		});
		InputStream sink = shadowOf(socket).getOutputStreamSink();
		assertEquals('A', sink.read());
		assertEquals('B', sink.read());
		assertTrue(pumpUntil(() -> results.equals(List.of("ok"))));

		// The client goes away: told once, after its last bytes
		shadowOf(socket).getInputStreamFeeder().write("BYE".getBytes(StandardCharsets.US_ASCII));
		closeFeeder(socket);
		assertTrue(pumpUntil(() -> client.events.size() == 2));
		assertEquals("HELLOBYE", client.text());
		assertEquals(List.of("connected", "disconnected false " + BleError.CONNECTION_LOST), client.events);
		assertEquals(BleGattCallback.DISCONNECTED, KnBle.getClassicConnState(device));
	}

	@Test
	public void disconnectClassicEndsAClient() throws Exception {
		Client client = new Client();
		started(new Server(client));
		BluetoothSocket socket = clientConnects(MAC_1);
		assertTrue(pumpUntil(() -> client.events.contains("connected")));
		BleDevice device = KnBle.getBleDeviceFromMac(MAC_1);
		assertNotNull(device);
		KnBle.disconnectClassic(device);
		closeFeeder(socket);
		assertTrue(pumpUntil(() -> client.events.contains("disconnected false 0")));
		// Still listening
		assertTrue(KnBle.isClassicServerStarted());
	}

	@Test
	public void aClientRefusedByTheAppIsClosed() throws Exception {
		Server server = new Server();
		started(server);
		clientConnects(MAC_1);
		assertTrue(pumpUntil(() -> server.connected.size() == 1));
		pumpFor(200);
		BleDevice device = KnBle.getBleDeviceFromMac(MAC_1);
		assertNotNull(device);
		assertEquals(BleGattCallback.DISCONNECTED, KnBle.getClassicConnState(device));
		assertTrue(KnBle.getClassicConnectedDevices().isEmpty());
	}

	@Test
	public void clientsOverTheLimitAreRefused() throws Exception {
		Client first = new Client(), second = new Client();
		Server server = new Server(first, second);
		KnBle.startClassicServer(new ClassicServerOptions.Builder().setSecure(false).setMaxClients(1).build(), server);
		assertTrue(pumpUntil(() -> mServerEvents.contains("started") && serverSocket() != null));
		clientConnects(MAC_1);
		assertTrue(pumpUntil(() -> first.events.contains("connected")));
		clientConnects(MAC_2);
		pumpFor(300);
		// Refused by KnBle before the app is asked
		assertEquals(1, server.connected.size());
		assertTrue(second.events.isEmpty());
	}

	@Test
	public void stopEndsTheClientsAndNothingComesAfter() throws Exception {
		Client client = new Client();
		started(new Server(client));
		BluetoothSocket socket = clientConnects(MAC_1);
		assertTrue(pumpUntil(() -> client.events.contains("connected")));

		KnBle.stopClassicServer();
		assertFalse(KnBle.isClassicServerStarted());
		closeFeeder(socket);
		assertTrue(pumpUntil(() -> client.events.contains("disconnected false 0")));
		pumpFor(200);
		assertEquals(List.of("started", "client " + MAC_1), mServerEvents);
	}

	@Test
	public void bluetoothOffThenOnListensAgain() throws Exception {
		Client client = new Client();
		started(new Server(client));
		BluetoothServerSocket before = serverSocket();
		BluetoothSocket socket = clientConnects(MAC_1);
		assertTrue(pumpUntil(() -> client.events.contains("connected")));

		bluetooth(false);
		closeFeeder(socket);
		assertTrue(pumpUntil(() -> client.events.contains("disconnected false " + BleError.BLUETOOTH_DISABLED)));
		// Started, not listening
		assertTrue(KnBle.isClassicServerStarted());

		bluetooth(true);
		assertTrue(pumpUntil(() -> serverSocket() != null && serverSocket() != before));
		assertNotSame(before, serverSocket());
		// No second onServerStarted
		assertEquals(List.of("started", "client " + MAC_1), mServerEvents);
	}

	@Test
	public void startWithBluetoothOffFails() throws Exception {
		bluetooth(false);
		KnBle.startClassicServer(new Server());
		assertTrue(pumpUntil(() -> mServerEvents.contains("failed " + BleError.BLUETOOTH_DISABLED)));
		assertFalse(KnBle.isClassicServerStarted());
	}

	@Test
	public void aReplacedStartIsCancelled() throws Exception {
		List<String> first = new CopyOnWriteArrayList<>();
		KnBle.startClassicServer(new BleClassicServerCallback() {
			@Nullable
			@Override
			public BleClassicCallback onClientConnected(@NonNull BleDevice client) {
				return null;
			}

			@Override
			public void onServerStarted() {
				first.add("started");
			}

			@Override
			public void onServerFailed(int error) {
				first.add("failed " + error);
			}
		});
		started(new Server());
		pumpFor(200);
		assertEquals(List.of("failed " + BleError.CANCELLED), first);
	}
}
