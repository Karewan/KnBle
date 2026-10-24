package ovh.karewan.knble.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.ClassicOptions;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.KnBleSettings;
import ovh.karewan.knble.interfaces.BleBondCallback;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Pairings of the devices of KnBle: code given in advance, answered by the app (now or later), left to the dialog of
 * the system, cancelled; the other devices never touched; the pairing of a classic connection waited for.
 * "System" is a receiver of lower priority, as the one of the Settings app that shows the dialog.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PairingTest {
	private static final long TIMEOUT_MS = 5000;
	private static final String MAC = "AA:BB:CC:DD:EE:31";
	private static final String OTHER_MAC = "AA:BB:CC:DD:EE:32";

	private Context mContext;
	private BleDevice mDevice;
	private BluetoothDevice mBtDevice;
	private final AtomicInteger mSystemDialogs = new AtomicInteger();
	private final BroadcastReceiver mSystem = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mSystemDialogs.incrementAndGet();
		}
	};
	private final List<BluetoothSocket> mSockets = new CopyOnWriteArrayList<>();
	private volatile boolean mBlockConnect, mFailConnect;

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		BluetoothAdapter adapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(adapter).setEnabled(true);
		// The discovery that brings the dialog of the system over the app: tested apart
		assertTrue(KnBle.init(mContext, new KnBleSettings.Builder().setConnectTimeout(4000).setConnectAttempts(2).setPairingDialogInForeground(false).build()));
		mDevice = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(mDevice);
		mBtDevice = mDevice.getDevice();

		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
		filter.setPriority(0);
		mContext.registerReceiver(mSystem, filter, null, new Handler(Looper.getMainLooper()));

		ClassicLink.sSocketFactory = (device, byChannel) -> {
			BluetoothSocket s = Shadow.newInstanceOf(BluetoothSocket.class);
			if (mFailConnect) s.close();
			if (mBlockConnect) shadowOf(s).blockConnect();
			mSockets.add(s);
			return s;
		};
	}

	@After
	public void tearDown() {
		ClassicLink.sSocketFactory = null;
		for (BluetoothSocket s : mSockets) {
			try {
				shadowOf(s).getInputStreamFeeder().close();
			} catch (IOException ignored) {
			}
		}
		try {
			mContext.unregisterReceiver(mSystem);
		} catch (RuntimeException ignored) {
		}
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
		long until = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < until) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			Thread.sleep(5);
		}
	}

	private void pairingRequest(@NonNull BluetoothDevice device, int variant, int key) {
		Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).putExtra(BluetoothDevice.EXTRA_DEVICE, device)
				.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant);
		if (key != BlePairingRequest.NO_KEY) intent.putExtra(BluetoothDevice.EXTRA_PAIRING_KEY, key);
		mContext.sendOrderedBroadcast(intent, null);
	}

	private void bondState(int state, int previous) {
		mContext.sendBroadcast(new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice)
				.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state).putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous));
	}

	private byte[] pin() {
		return shadowOf(mBtDevice).getPin();
	}

	private PairingAgent agent() {
		BleEngine e = BleEngine.get();
		assertNotNull(e);
		return e.pairingForTests();
	}

	/**
	 * createBond started (the stack said yes), its pairing request receiver registered
	 */
	private void startBond(@NonNull String pin, @NonNull BleBondCallback callback) throws InterruptedException {
		shadowOf(mBtDevice).setCreatedBond(true);
		KnBle.createBond(mDevice, pin, callback);
		assertTrue(pumpUntil(() -> agent().isRegistered()));
		pumpFor(100);
	}

	private static class Bond implements BleBondCallback {
		final AtomicInteger bonded = new AtomicInteger();
		final AtomicInteger error = new AtomicInteger();
		final AtomicReference<BlePairingRequest> request = new AtomicReference<>();
		volatile boolean answer;

		@Override
		public void onBonded() {
			bonded.incrementAndGet();
		}

		@Override
		public void onBondFailed(int e) {
			error.set(e);
		}

		@Override
		public boolean onPairingRequest(@NonNull BlePairingRequest r) {
			request.set(r);
			return answer;
		}
	}

	// endregion

	@Test
	public void theCodeGivenInAdvanceAnswersWithoutTheDialog() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_CLASSIC);
		Bond bond = new Bond();
		startBond("1234", bond);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> pin() != null));
		assertArrayEquals("1234".getBytes(StandardCharsets.US_ASCII), pin());
		pumpFor(300);
		assertEquals("dialog of the system shown", 0, mSystemDialogs.get());
		assertEquals(PairingAgent.DECIDED_IN_ADVANCE, agent().lastDecision());
		// The app was not asked
		assertNull(bond.request.get());

		bondState(BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING);
		assertTrue(pumpUntil(() -> bond.bonded.get() == 1));
	}

	@Test
	public void theRepairingOfTheSystemIsTold() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_LE);
		Bond bond = new Bond();
		startBond("", bond);
		// Android 17: the system pairs again a device that lost its keys (numeric comparison)
		Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).putExtra(BluetoothDevice.EXTRA_DEVICE, mBtDevice)
				.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BlePairingRequest.VARIANT_PASSKEY_CONFIRMATION)
				.putExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 123456)
				.putExtra(PairingAgent.EXTRA_PAIRING_CONTEXT, PairingAgent.PAIRING_CONTEXT_REPAIRING);
		mContext.sendOrderedBroadcast(intent, null);
		assertTrue(pumpUntil(() -> bond.request.get() != null));
		assertTrue(bond.request.get().isRepairing());

		// A first pairing is not a re-pairing
		bond.request.set(null);
		pairingRequest(mBtDevice, BlePairingRequest.VARIANT_PASSKEY_CONFIRMATION, 654321);
		assertTrue(pumpUntil(() -> bond.request.get() != null));
		assertFalse(bond.request.get().isRepairing());
	}

	@Test
	public void aBlePasskeyGoesAsSixDigits() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_LE);
		startBond("1234", new Bond());
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> pin() != null));
		assertArrayEquals("001234".getBytes(StandardCharsets.US_ASCII), pin());
		assertEquals(0, mSystemDialogs.get());
	}

	@Test
	public void theAppAnswersLaterFromItsOwnDialog() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_CLASSIC);
		Bond bond = new Bond();
		bond.answer = true;
		startBond("", bond);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> bond.request.get() != null));
		BlePairingRequest r = bond.request.get();
		assertTrue(r.canAnswer());
		assertFalse(r.isPasskey());
		assertEquals(mDevice, r.getDevice());
		// The time the app has to answer from its own dialog
		assertTrue(r.getTimeLeftMs() > 0 && r.getTimeLeftMs() <= BlePairingRequest.ANSWER_TIMEOUT_MS);
		assertEquals(r.getDeadline() - android.os.SystemClock.elapsedRealtime(), r.getTimeLeftMs(), 50);
		// Robolectric ignores an abort after goAsync (Android does not): the decision of KnBle is checked
		assertTrue(pumpUntil(() -> agent().lastDecision() == PairingAgent.DECIDED_BY_APP));
		assertNull(pin());

		// The user typed it in the dialog of the app
		r.setPin("0000");
		assertTrue(pumpUntil(() -> pin() != null));
		assertArrayEquals("0000".getBytes(StandardCharsets.US_ASCII), pin());
		// Only the first answer counts
		r.cancel();
		pumpFor(200);
		assertEquals(0, bond.error.get());
	}

	@Test
	public void theDialogOfTheSystemWhenTheAppDoesNotAnswer() throws Exception {
		Bond bond = new Bond();
		startBond("", bond);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> mSystemDialogs.get() == 1));
		assertNotNull(bond.request.get());
		assertNull(pin());
	}

	@Test
	public void theOtherDevicesAreNeverTouched() throws Exception {
		Bond bond = new Bond();
		bond.answer = true;
		startBond("1234", bond);
		BluetoothDevice other = KnBle.getBleDeviceFromMac(OTHER_MAC).getDevice();
		pairingRequest(other, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> mSystemDialogs.get() == 1));
		assertNull(shadowOf(other).getPin());
		assertNull(bond.request.get());
	}

	@Test
	public void aNumberToConfirmStaysWithTheSystemAndTheAppIsTold() throws Exception {
		Bond bond = new Bond();
		bond.answer = true;
		startBond("1234", bond);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION, 123456);
		assertTrue(pumpUntil(() -> mSystemDialogs.get() == 1 && bond.request.get() != null));
		BlePairingRequest r = bond.request.get();
		assertFalse(r.canAnswer());
		assertEquals(BlePairingRequest.VARIANT_PASSKEY_CONFIRMATION, r.getVariant());
		assertEquals(123456, r.getPairingKey());
		// Nothing given, whatever the app does
		r.setPin("1234");
		pumpFor(200);
		assertNull(pin());
	}

	@Test
	public void android17RefusesTheCodesOfTheApps() throws Exception {
		// The pairing hardening of Android 17 (app targeting API 37): setPin needs BLUETOOTH_PRIVILEGED
		agent().setCanSetPinForTests(false);
		Bond bond = new Bond();
		bond.answer = true;
		startBond("1234", bond);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> mSystemDialogs.get() == 1 && bond.request.get() != null));
		assertFalse(bond.request.get().canAnswer());
		assertNull(pin());
	}

	@Test
	public void cancelEndsTheBondAtOnce() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_CLASSIC);
		Bond bond = new Bond();
		bond.answer = true;
		startBond("", bond);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> bond.request.get() != null));
		bond.request.get().cancel();
		assertTrue(pumpUntil(() -> bond.error.get() == BleError.CANCELLED));
		// Android 12 to 16: the negative answer of a dedicated bonding is an empty PIN
		assertNotNull(pin());
		assertEquals(0, pin().length);
		assertEquals(PairingAgent.DECIDED_BY_APP, agent().lastDecision());
	}

	@Test
	public void anAnswerToAnEndedPairingIsIgnored() throws Exception {
		shadowOf(mBtDevice).setType(BluetoothDevice.DEVICE_TYPE_CLASSIC);
		Bond first = new Bond();
		first.answer = true;
		startBond("", first);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> first.request.get() != null));
		// The user leaves the dialog of the app open, the pairing times out on the device side
		bondState(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING);
		assertTrue(pumpUntil(() -> first.error.get() == BleError.BOND_FAILED));

		// The app pairs again, then the old dialog is cancelled: the new pairing goes on
		Bond second = new Bond();
		KnBle.createBond(mDevice, second);
		pumpFor(200);
		first.request.get().cancel();
		first.request.get().setPin("0000");
		pumpFor(300);
		assertEquals(0, second.error.get());
		assertNull(pin());
		bondState(BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING);
		assertTrue(pumpUntil(() -> second.bonded.get() == 1));
	}

	@Test
	public void aCallbackIsAskedOnce() throws Exception {
		AtomicInteger asked = new AtomicInteger();
		Bond bond = new Bond() {
			@Override
			public boolean onPairingRequest(@NonNull BlePairingRequest r) {
				asked.incrementAndGet();
				return false;
			}
		};
		startBond("", bond);
		// The same object twice (an Activity used for several operations)
		KnBle.createBond(mDevice, bond);
		pumpFor(200);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION, 123456);
		assertTrue(pumpUntil(() -> mSystemDialogs.get() == 1));
		pumpFor(300);
		assertEquals(1, asked.get());
	}

	@Test
	public void aLambdaIsNotAskedAndTheSystemAnswersAtOnce() throws Exception {
		shadowOf(mBtDevice).setCreatedBond(true);
		KnBle.createBond(mDevice, () -> {});
		assertTrue(pumpUntil(() -> agent().isRegistered()));
		pumpFor(100);
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> mSystemDialogs.get() == 1));
		assertEquals(PairingAgent.DECIDED_SYSTEM, agent().lastDecision());
	}

	// region Classic connection

	private static final class Classic implements BleClassicCallback {
		final List<String> events = new CopyOnWriteArrayList<>();
		final AtomicReference<BlePairingRequest> request = new AtomicReference<>();
		volatile boolean answer;

		@Override
		public void onData(@NonNull byte[] data) {}

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
		public boolean onPairingRequest(@NonNull BlePairingRequest r) {
			request.set(r);
			return answer;
		}
	}

	@Test
	public void theClassicCallbackGivesThePin() throws Exception {
		mBlockConnect = true;
		Classic rec = new Classic();
		rec.answer = true;
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setChannelFallback(false).build(), false, rec);
		assertTrue(pumpUntil(() -> mSockets.size() == 1 && agent().isRegistered()));
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> rec.request.get() != null));
		assertFalse(rec.request.get().isPasskey());
		rec.request.get().setPin("1234");
		assertTrue(pumpUntil(() -> pin() != null));
		assertArrayEquals("1234".getBytes(StandardCharsets.US_ASCII), pin());
		shadowOf(mSockets.get(0)).unblockConnect();
		assertTrue(pumpUntil(() -> rec.events.contains("connected")));
		assertEquals(PairingAgent.DECIDED_BY_APP, agent().lastDecision());
	}

	@Test
	public void aCancelledPairingEndsTheClassicConnectionWithoutReconnecting() throws Exception {
		mBlockConnect = true;
		Classic rec = new Classic();
		rec.answer = true;
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setChannelFallback(false).build(), true, rec);
		assertTrue(pumpUntil(() -> mSockets.size() == 1 && agent().isRegistered()));
		pairingRequest(mBtDevice, BluetoothDevice.PAIRING_VARIANT_PIN, BlePairingRequest.NO_KEY);
		assertTrue(pumpUntil(() -> rec.request.get() != null));
		rec.request.get().cancel();
		assertTrue(pumpUntil(() -> rec.events.contains("disconnected true " + BleError.CANCELLED)));
		// No auto-reconnection: it would ask the user again
		pumpFor(1500);
		assertEquals(1, mSockets.size());
		assertEquals(List.of("connecting", "disconnected true " + BleError.CANCELLED), rec.events);
	}

	@Test
	public void anAttemptThatFailsDuringThePairingWaitsForIt() throws Exception {
		mBlockConnect = true;
		Classic rec = new Classic();
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setChannelFallback(false).build(), false, rec);
		assertTrue(pumpUntil(() -> mSockets.size() == 1));
		// The secure socket started a pairing, then gave up while the user is still typing (some stacks)
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDING);
		bondState(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE);
		pumpFor(200);
		mBlockConnect = false;
		mSockets.get(0).close();
		pumpFor(1500);
		assertEquals("no end while pairing: " + rec.events, List.of("connecting"), rec.events);

		// Paired: the next attempt connects
		mFailConnect = false;
		int before = mSockets.size();
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDED);
		bondState(BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING);
		assertTrue(pumpUntil(() -> rec.events.contains("connected")));
		assertTrue(mSockets.size() > before);
		assertEquals(List.of("connecting", "connected"), rec.events);
	}

	@Test
	public void aRefusedPairingDuringAnAutoReconnectionEndsItAndTellsTheApp() throws Exception {
		Classic rec = new Classic();
		KnBle.connectClassic(mDevice, new ClassicOptions.Builder().setChannelFallback(false).build(), true, rec);
		assertTrue(pumpUntil(() -> rec.events.contains("connected")));
		// The device drops the link: the auto-reconnection starts (its rounds are silent)
		mBlockConnect = true;
		shadowOf(mSockets.get(0)).getInputStreamFeeder().close();
		assertTrue(pumpUntil(() -> rec.events.contains("disconnected false " + BleError.CONNECTION_LOST)));
		assertTrue(pumpUntil(() -> mSockets.size() == 2));

		// The device lost its bond: the secure socket pairs again, the user refuses
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_BONDING);
		bondState(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE);
		pumpFor(200);
		shadowOf(mBtDevice).setBondState(BluetoothDevice.BOND_NONE);
		bondState(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING);
		mSockets.get(1).close();
		assertTrue(pumpUntil(() -> rec.events.contains("disconnected true " + BleError.BOND_FAILED)));
		// No more rounds: they would ask the user again
		pumpFor(2500);
		assertEquals(2, mSockets.size());
		assertEquals(List.of("connecting", "connected", "disconnected false " + BleError.CONNECTION_LOST, "connecting",
				"disconnected true " + BleError.BOND_FAILED), rec.events);
	}

	// endregion

	@Test
	public void pinEncoding() {
		assertArrayEquals("000042".getBytes(StandardCharsets.US_ASCII), BlePairingRequest.encode("42", true));
		assertArrayEquals("123456".getBytes(StandardCharsets.US_ASCII), BlePairingRequest.encode("123456", true));
		assertNull(BlePairingRequest.encode("1234567", true));
		assertNull(BlePairingRequest.encode("12a4", true));
		assertNull(BlePairingRequest.encode("", false));
		assertNull(BlePairingRequest.encode("12345678901234567", false));
		assertArrayEquals("abcd".getBytes(StandardCharsets.UTF_8), BlePairingRequest.encode("abcd", false));
	}
}
