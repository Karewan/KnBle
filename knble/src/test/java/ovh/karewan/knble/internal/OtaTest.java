package ovh.karewan.knble.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.KnBleSettings;
import ovh.karewan.knble.OtaOptions;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleOtaCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.struct.BleProgress;

/**
 * The OTA protocols run by KnBle (Silicon Labs AppLoader, TI Enhanced OAD), link parameters, cancel, status of the
 * disconnection and Looper of the callbacks, against the Robolectric GATT shadow driven by a fake peripheral.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class OtaTest {
	private static final long TIMEOUT_MS = 5000;
	private static final String MAC = "AA:BB:CC:DD:EE:02";

	private BleDevice mDevice;
	private BluetoothDevice mBtDevice;
	private BluetoothGatt mGatt;
	private BluetoothGattService mSilabs, mTi;
	private BluetoothGattCharacteristic mSilabsControl, mSilabsData, mTiIdentify, mTiBlock, mTiControl;
	private final List<String> mEvents = new CopyOnWriteArrayList<>();
	// Writes seen by the fake peripheral, in order: "control 00", "data 512"...
	private final List<String> mWrites = new CopyOnWriteArrayList<>();
	private final ByteArrayOutputStream mReceived = new ByteArrayOutputStream();
	private boolean mWithSilabs = true;

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		Context context = ApplicationProvider.getApplicationContext();
		shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		shadowOf(context.getSystemService(BluetoothManager.class).getAdapter()).setEnabled(true);

		mSilabs = new BluetoothGattService(GattOps.SilabsOta.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		mSilabsControl = chr(GattOps.SilabsOta.CONTROL, BluetoothGattCharacteristic.PROPERTY_WRITE, false);
		mSilabsData = chr(GattOps.SilabsOta.DATA, BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, false);
		mSilabs.addCharacteristic(mSilabsControl);
		mSilabs.addCharacteristic(mSilabsData);

		mTi = new BluetoothGattService(GattOps.TiOad.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		int tiProps = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_NOTIFY;
		mTiIdentify = chr(GattOps.TiOad.IDENTIFY, tiProps, true);
		mTiBlock = chr(GattOps.TiOad.BLOCK, tiProps, true);
		mTiControl = chr(GattOps.TiOad.CONTROL, tiProps, true);
		mTi.addCharacteristic(mTiIdentify);
		mTi.addCharacteristic(mTiBlock);
		mTi.addCharacteristic(mTiControl);
	}

	@After
	public void tearDown() {
		BleEngine.resetForTests();
	}

	// region Helpers

	private static BluetoothGattCharacteristic chr(UUID uuid, int properties, boolean cccd) {
		BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid, properties, BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
		if (cccd) c.addDescriptor(new BluetoothGattDescriptor(GattOps.CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
		return c;
	}

	private static boolean pump(CountDownLatch latch) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			if (latch.await(10, TimeUnit.MILLISECONDS)) return true;
		}
		return false;
	}

	private void connect(KnBleSettings settings) throws InterruptedException {
		assertTrue(settings == null ? KnBle.init(ApplicationProvider.getApplicationContext()) : KnBle.init(ApplicationProvider.getApplicationContext(), settings));
		mDevice = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(mDevice);
		mBtDevice = mDevice.getDevice();

		CountDownLatch connected = new CountDownLatch(1);
		KnBle.connect(mDevice, new BleGattCallback() {
			@Override
			public void onConnectSuccess(@NonNull List<BluetoothGattService> services) {
				connected.countDown();
			}

			@Override
			public void onDisconnected(boolean connectFailed, int status) {
				mEvents.add("disconnected " + connectFailed + " " + status);
			}
		});
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (shadowOf(mBtDevice).getBluetoothGatts().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5);
		mGatt = shadowOf(mBtDevice).getBluetoothGatts().get(0);
		if (mWithSilabs) shadowOf(mGatt).addDiscoverableService(mSilabs);
		shadowOf(mGatt).addDiscoverableService(mTi);
		shadowOf(mGatt).allowCharacteristicNotification(mTiIdentify);
		shadowOf(mGatt).allowCharacteristicNotification(mTiBlock);
		shadowOf(mGatt).allowCharacteristicNotification(mTiControl);
		shadowOf(mBtDevice).simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
		assertTrue("not connected", pump(connected));
	}

	private static byte[] image(int size) {
		byte[] image = new byte[size];
		for (int i = 0; i < size; i++) image[i] = (byte) (i * 7 + 3);
		return image;
	}

	/**
	 * A TI OAD image: header with the length (offset 24) and the header length (offset 40)
	 */
	private static byte[] tiImage(int size) {
		byte[] image = image(size);
		for (int i = 0; i < 4; i++) image[24 + i] = (byte) (size >> (8 * i));
		image[40] = 44;
		image[41] = 0;
		return image;
	}

	private final class Ota implements BleOtaCallback {
		final CountDownLatch done = new CountDownLatch(1);
		final List<BleProgress> progress = new CopyOnWriteArrayList<>();
		volatile int error;
		volatile boolean success;
		volatile Looper looper;

		@Override
		public void onOtaSuccess() {
			looper = Looper.myLooper();
			success = true;
			done.countDown();
		}

		@Override
		public void onOtaProgress(@NonNull BleProgress p) {
			progress.add(p);
		}

		@Override
		public void onOtaFailed(int e) {
			looper = Looper.myLooper();
			error = e;
			done.countDown();
		}

		@Override
		public void onOtaRetry(int attempt) {
			mEvents.add("retry " + attempt);
		}
	}

	/**
	 * Fake peripheral: sees every write, and answers like the device (TI OAD notifications) when asked
	 */
	private interface Peripheral {
		void onWrite(@NonNull BluetoothGattCallback bridge, @NonNull BluetoothGattCharacteristic c, @NonNull byte[] value);
	}

	private void peripheral(Peripheral p) {
		BluetoothGattCallback bridge = shadowOf(mGatt).getGattCallback();
		shadowOf(mGatt).setGattCallback(new BluetoothGattCallback() {
			@Override
			public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
				byte[] value = shadowOf(mGatt).getLatestWrittenBytes();
				bridge.onCharacteristicWrite(gatt, c, status);
				if (value != null) p.onWrite(bridge, c, value);
			}

			@Override
			public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor d, int status) {
				bridge.onDescriptorWrite(gatt, d, status);
			}

			@Override
			public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
				bridge.onMtuChanged(gatt, mtu, status);
			}

			@Override
			public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
				bridge.onPhyUpdate(gatt, txPhy, rxPhy, status);
			}
		});
	}

	private void silabsPeripheral(int failFirstPackets) {
		int[] packets = {0};
		peripheral((bridge, c, value) -> {
			if (c.getUuid().equals(GattOps.SilabsOta.CONTROL)) {
				mWrites.add("control " + value[0]);
				if (value[0] == 0x00) mReceived.reset();
			} else if (c.getUuid().equals(GattOps.SilabsOta.DATA)) {
				packets[0]++;
				mReceived.write(value, 0, value.length);
			}
		});
		if (failFirstPackets > 0) {
			// The first attempt dies at its 3rd packet (refused by the stack): the retry starts again from 0x00
			BluetoothGattCallback current = shadowOf(mGatt).getGattCallback();
			int[] seen = {0};
			shadowOf(mGatt).setGattCallback(new BluetoothGattCallback() {
				@Override
				public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
					boolean data = c.getUuid().equals(GattOps.SilabsOta.DATA);
					if (data && ++seen[0] == failFirstPackets) current.onCharacteristicWrite(gatt, c, BluetoothGatt.GATT_FAILURE);
					else current.onCharacteristicWrite(gatt, c, status);
				}
			});
		}
	}

	private static void notify(BluetoothGattCallback bridge, BluetoothGatt gatt, BluetoothGattCharacteristic c, byte... value) {
		bridge.onCharacteristicChanged(gatt, c, value);
	}

	// endregion

	// region Silicon Labs

	@Test
	public void silabsOtaWritesStartImageThenEnd() throws Exception {
		connect(null);
		silabsPeripheral(0);
		byte[] image = image(5000);
		Ota ota = new Ota();
		KnBle.otaSilabs(mDevice, image, new OtaOptions.Builder().setPacketSize(244).build(), ota);
		assertTrue(pump(ota.done));

		assertTrue("error " + ota.error, ota.success);
		assertEquals("control 0", mWrites.get(0));
		assertEquals("control 3", mWrites.get(mWrites.size() - 1));
		assertArrayEquals(image, mReceived.toByteArray());
		BleProgress last = ota.progress.get(ota.progress.size() - 1);
		assertEquals(100, last.getPercent());
		assertEquals(image.length, last.getSentBytes());
		// MTU 23 (never raised here): packets without response of MTU - 3 bytes whatever the size asked (244 would be cut
		// by the stack and corrupt the image)
		assertEquals((image.length + 19) / 20, last.getTotalPackets());
		assertSame(Looper.getMainLooper(), ota.looper);
	}

	@Test
	public void silabsOtaStartsAgainSlowerAfterAFailure() throws Exception {
		connect(null);
		silabsPeripheral(3);
		byte[] image = image(3000);
		Ota ota = new Ota();
		KnBle.otaSilabs(mDevice, image, new OtaOptions.Builder().setPacketSize(244).setRetries(2).build(), ota);
		assertTrue(pump(ota.done));

		assertTrue("error " + ota.error, ota.success);
		assertTrue(mEvents.contains("retry 1"));
		// Again from the 0x00 command, the whole image received by the second attempt
		assertEquals(List.of("control 0", "control 0", "control 3"), mWrites);
		assertArrayEquals(image, mReceived.toByteArray());
	}

	@Test
	public void silabsOtaWithoutTheServiceFails() throws Exception {
		mWithSilabs = false;
		connect(null);
		Ota ota = new Ota();
		KnBle.otaSilabs(mDevice, image(100), ota);
		assertTrue(pump(ota.done));
		assertEquals(BleError.NOT_FOUND, ota.error);
	}

	// endregion

	// region TI OAD

	/**
	 * Fake TI target: block size, identify, then a request per block until the end
	 */
	private void tiPeripheral(int blockSize, int imageLength) {
		int dataSize = blockSize - 4;
		int blocks = (imageLength + dataSize - 1) / dataSize;
		peripheral((bridge, c, value) -> {
			BluetoothGatt gatt = mGatt;
			if (c.getUuid().equals(GattOps.TiOad.CONTROL)) {
				mWrites.add("control " + value[0]);
				switch (value[0]) {
					case 0x01 -> notify(bridge, gatt, mTiControl, (byte) 0x01, (byte) blockSize, (byte) (blockSize >> 8));
					case 0x03 -> notify(bridge, gatt, mTiControl, (byte) 0x12, (byte) 0x00, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
					case 0x04 -> notify(bridge, gatt, mTiControl, (byte) 0x04, (byte) 0x00);
					default -> {}
				}
			} else if (c.getUuid().equals(GattOps.TiOad.IDENTIFY)) {
				mWrites.add("identify " + value.length);
				notify(bridge, gatt, mTiIdentify, (byte) 0x00);
			} else if (c.getUuid().equals(GattOps.TiOad.BLOCK)) {
				int block = (int) GattOps.le32(value, 0);
				mReceived.write(value, 4, value.length - 4);
				if (block + 1 >= blocks) notify(bridge, gatt, mTiControl, (byte) 0x12, (byte) 0x0E);
				else {
					int next = block + 1;
					notify(bridge, gatt, mTiControl, (byte) 0x12, (byte) 0x00, (byte) next, (byte) (next >> 8), (byte) 0, (byte) 0);
				}
			}
		});
	}

	@Test
	public void tiOadAnswersEveryBlockRequest() throws Exception {
		connect(null);
		KnBle.requestMtu(mDevice, 247);
		byte[] image = tiImage(4000);
		tiPeripheral(244, image.length);
		Ota ota = new Ota();
		KnBle.otaTi(mDevice, image, ota);
		assertTrue(pump(ota.done));

		assertTrue("error " + BleError.name(ota.error), ota.success);
		assertEquals(List.of("control 1", "identify 22", "control 3", "control 4"), mWrites);
		assertArrayEquals(image, mReceived.toByteArray());
		BleProgress last = ota.progress.get(ota.progress.size() - 1);
		assertEquals(image.length, last.getSentBytes());
		assertEquals(17, last.getTotalPackets());
	}

	@Test
	public void tiOadRefusesATruncatedImage() throws Exception {
		connect(null);
		byte[] image = tiImage(4000);
		Ota ota = new Ota();
		KnBle.otaTi(mDevice, java.util.Arrays.copyOf(image, 3000), ota);
		assertTrue(pump(ota.done));
		assertEquals(BleError.INVALID_IMAGE, ota.error);
	}

	@Test
	public void tiOadNeedsABlockThatFitsTheMtu() throws Exception {
		connect(null);
		// MTU 23 (no request): the identify (22 bytes) and a block of 244 do not fit
		byte[] image = tiImage(1000);
		tiPeripheral(244, image.length);
		Ota ota = new Ota();
		KnBle.otaTi(mDevice, image, ota);
		assertTrue(pump(ota.done));
		assertEquals(BleError.NOT_SUPPORTED, ota.error);
	}

	// endregion

	// region Link, cancel, status, Looper

	@Test
	public void fastTransferAlwaysCallsBack() throws Exception {
		connect(null);
		CountDownLatch done = new CountDownLatch(1);
		int[] values = new int[3];
		KnBle.requestFastTransfer(mDevice, (mtu, tx, rx) -> {
			values[0] = mtu;
			values[1] = tx;
			values[2] = rx;
			done.countDown();
		});
		assertTrue(pump(done));
		assertEquals(517, values[0]);
		assertEquals(BluetoothGatt.CONNECTION_PRIORITY_HIGH, shadowOf(mGatt).getConnectionPriority());
	}

	@Test
	public void cancelEndsTheOtaAndTheQueue() throws Exception {
		connect(null);
		// Interval of 1 s between packets: the OTA is still running when cancelled
		silabsPeripheral(0);
		Ota ota = new Ota();
		KnBle.otaSilabs(mDevice, image(5000), new OtaOptions.Builder().setPacketSize(244).setPacketInterval(1000).build(), ota);
		Ota queued = new Ota();
		KnBle.otaSilabs(mDevice, image(100), queued);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
		KnBle.cancel(mDevice);
		assertTrue(pump(ota.done));
		assertTrue(pump(queued.done));
		assertEquals(BleError.CANCELLED, ota.error);
		assertEquals(BleError.CANCELLED, queued.error);
	}

	@Test
	public void disconnectionGivesItsStatus() throws Exception {
		connect(null);
		shadowOf(mBtDevice).simulateGattConnectionChange(8, BluetoothProfile.STATE_DISCONNECTED);
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (mEvents.isEmpty() && System.currentTimeMillis() < deadline) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
		assertEquals(List.of("disconnected false 8"), mEvents);
	}

	@Test
	public void callbacksOnTheLooperOfTheSettings() throws Exception {
		HandlerThread thread = new HandlerThread("app");
		thread.start();
		try {
			connect(new KnBleSettings.Builder().setCallbackLooper(thread.getLooper()).build());
			silabsPeripheral(0);
			Ota ota = new Ota();
			KnBle.otaSilabs(mDevice, image(1000), ota);
			assertTrue(ota.done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || pump(ota.done));
			assertTrue(ota.success);
			assertSame(thread.getLooper(), ota.looper);
		} finally {
			thread.quitSafely();
		}
	}

	// endregion

}
