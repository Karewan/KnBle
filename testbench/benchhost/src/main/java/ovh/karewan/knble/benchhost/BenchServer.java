package ovh.karewan.knble.benchhost;

import android.annotation.SuppressLint;
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
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32;

/**
 * The bench as a GATT server, with the GATT table and protocol of testbench/README.md (the ESP32-C3 firmware is the
 * reference): control, sink, stream, indicate, result, encrypted, notify and long characteristics (read blob, long and
 * reliable writes), the two services holding the same characteristic UUID, Silicon Labs AppLoader and TI OAD
 * emulations. Commands: statistics, stream, slow device, bootloader without flow control, advertising modes, indication,
 * disconnection, removal of the bond of the central. One central at a time.
 * <p>
 * What a phone cannot do, answered status 1: reboot in DFU mode with the same MAC (CMD_REBOOT), choose its pairing IO
 * capabilities (CMD_SECURITY: Android decides, numeric comparison between two phones), ask the central for connection
 * parameters (CMD_CONN_PARAMS). Neither can it hold the link layer: the slow device delays the response of a write with
 * response, but Android acknowledges a write without response before the app sees it. The advertising interval is
 * 100 ms at least, and the phone name goes in the scan response in every mode (no room left by the 128-bit UUID).
 * <p>
 * Everything runs on one thread, but the drain of the bootloader buffer; the notifications go one at a time, the next one
 * when Android says the previous one was sent (its only flow control on the server side).
 */
@SuppressLint("MissingPermission")
final class BenchServer {
	interface Log {
		void log(@NonNull String message);
	}

	private static final UUID SVC_BENCH = bench("0001"), CHR_CONTROL = bench("0002"), CHR_SINK = bench("0003"), CHR_STREAM = bench("0004"),
			CHR_INDICATE = bench("0005"), CHR_RESULT = bench("0006"), CHR_ENCRYPTED = bench("0007"), CHR_NOTIFY = bench("0008"),
			CHR_LONG = bench("0009");
	private static final UUID SVC_DUP_A = bench("0010"), CHR_DUP = bench("0011"), SVC_DUP_B = bench("0020");
	private static final UUID SVC_SLABS = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"),
			CHR_SLABS_CONTROL = UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063"),
			CHR_SLABS_DATA = UUID.fromString("984227f3-34fc-4045-a5d0-2c581f81a153");
	private static final UUID SVC_TI = UUID.fromString("f000ffc0-0451-4000-b000-000000000000"),
			CHR_TI_IDENTIFY = UUID.fromString("f000ffc1-0451-4000-b000-000000000000"),
			CHR_TI_BLOCK = UUID.fromString("f000ffc2-0451-4000-b000-000000000000"),
			CHR_TI_CONTROL = UUID.fromString("f000ffc5-0451-4000-b000-000000000000");
	private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	private static final byte CMD_RESET_STATS = 0x01, CMD_GET_STATS = 0x02, CMD_STREAM = 0x03, CMD_SINK_DELAY = 0x04, CMD_SINK_BUFFER = 0x05,
			CMD_ADV = 0x06, CMD_REBOOT = 0x07, CMD_INDICATE = 0x08, CMD_DISCONNECT_IN = 0x09, CMD_GET_INFO = 0x0A, CMD_SECURITY = 0x0B,
			CMD_DELETE_BONDS = 0x0C, CMD_CONN_PARAMS = 0x0D;
	private static final int ADV_NON_CONNECTABLE = 2, ADV_INTERVAL = 3, ADV_STOPPED = 4;
	private static final long ADV_MODE_DURATION_MS = 20000;
	private static final byte PROTO_NONE = 0, PROTO_SLABS = 1, PROTO_TI = 2, OTA_IDLE = 0, OTA_RUNNING = 1, OTA_DONE = 2;
	private static final byte TI_GET_BLOCK_SIZE = 0x01, TI_START = 0x03, TI_ENABLE_IMAGE = 0x04, TI_BLOCK_REQUEST = 0x12, TI_SUCCESS = 0x00,
			TI_DOWNLOAD_COMPLETE = 0x0E;
	// The longest attribute value
	private static final int MAX_VALUE = 512;

	// A notification (or an indication) waiting to be sent
	private record Outgoing(@NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, boolean indication) {}

	// A part of a long write or of a reliable write, kept until the central executes them
	private record Prepared(@NonNull Object attribute, int offset, @NonNull byte[] value) {}

	@NonNull
	private final Context mContext;
	@NonNull
	private final Log mLog;
	private final HandlerThread mThread = new HandlerThread("bench");
	private Handler mHandler;
	private BluetoothGattServer mServer;
	private final ArrayDeque<BluetoothGattService> mToAdd = new ArrayDeque<>();

	// Thread of the bench only
	@Nullable
	private BluetoothDevice mDevice;
	private int mMtu = 23;
	private BluetoothGattCharacteristic mControl, mStream, mIndicate, mResult, mTiIdentify, mTiBlock, mTiControl;
	// CCCD values written by the central
	private final HashMap<BluetoothGattDescriptor, Integer> mCccd = new HashMap<>();
	// Prepared writes of each central, applied by onExecuteWrite
	private final HashMap<BluetoothDevice, List<Prepared>> mPrepared = new HashMap<>();
	// Value of the long characteristic, replaced by each write
	private byte[] mLong = longPattern();
	private final Object mDisconnectToken = new Object();
	// Notifications waiting, one in flight at most
	private final ArrayDeque<Outgoing> mNotifications = new ArrayDeque<>();
	private boolean mNotifying;
	private final Runnable mPump = this::pump;

	// Advertising: mode of CMD_ADV, counter of the manufacturer data
	private BluetoothLeAdvertiser mAdvertiser;
	@Nullable
	private AdvertisingSetCallback mAdvCallback;
	@Nullable
	private AdvertisingSet mAdvSet;
	private int mAdvMode, mAdvCounter;
	private long mAdvParam;
	private boolean mAdvName;
	private final Runnable mApplyAdvertising = this::applyAdvertising, mAdvTick = this::advTick, mAdvResume = this::advResume;

	// Stream
	private long mStreamLeft, mStreamSeq, mStreamCount, mStreamRetries;
	private int mStreamSize, mStreamInterval;
	private boolean mStreamWaiting;

	// Sink: every written byte goes through it (sink, Silicon Labs data, TI blocks). Guarded by this: the drain thread consumes too
	private final CRC32 mCrc = new CRC32();
	private long mBytes, mWrites, mDropped, mFirstMs, mLastMs, mMaxGapMs, mGaps50;
	// Slow device (CMD_SINK_DELAY), bootloader without flow control (CMD_SINK_BUFFER: mBuffered writes wait for the drain)
	private long mSinkDelayUs;
	private int mSinkMode, mSinkDepth = 8;
	private volatile int mBuffered;
	private volatile long mDrainUs = 2000;
	private final LinkedBlockingQueue<byte[]> mSinkQueue = new LinkedBlockingQueue<>();
	private final Thread mDrain = new Thread(this::drain, "drain");

	// OTA result
	private byte mOtaProtocol = PROTO_NONE, mOtaState = OTA_IDLE;
	private long mOtaStartMs, mOtaEndMs;

	// TI OAD emulation
	private int mTiBlockSize;
	private long mTiImageLen, mTiBlockCount, mTiNext;
	private boolean mTiActive;

	BenchServer(@NonNull Context context, @NonNull Log log) {
		mContext = context.getApplicationContext();
		mLog = log;
	}

	private static UUID bench(String id) {
		return UUID.fromString("4b42" + id + "-8d28-4316-88b3-6e0111311ac6");
	}

	void start() {
		mThread.start();
		mHandler = new Handler(mThread.getLooper());
		mDrain.start();
		mHandler.post(this::open);
	}

	void stop() {
		mHandler.post(() -> {
			mHandler.removeCallbacksAndMessages(null);
			if (mAdvCallback != null) mAdvertiser.stopAdvertisingSet(mAdvCallback);
			if (mServer != null) mServer.close();
			mDrain.interrupt();
			mThread.quitSafely();
		});
	}

	// region Setup

	private void open() {
		BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
		BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
		if (adapter == null || !adapter.isEnabled()) {
			mLog.log("Bluetooth off or absent");
			return;
		}
		mAdvertiser = adapter.getBluetoothLeAdvertiser();
		if (mAdvertiser == null) {
			mLog.log("No peripheral role on this phone");
			return;
		}
		// The name of the phone goes in the scan response when it fits
		String name = adapter.getName();
		mAdvName = name != null && name.getBytes(StandardCharsets.UTF_8).length <= 29;
		mServer = manager.openGattServer(mContext, mCallback);
		if (mServer == null) {
			mLog.log("openGattServer failed");
			return;
		}

		int write = BluetoothGattCharacteristic.PERMISSION_WRITE, read = BluetoothGattCharacteristic.PERMISSION_READ;
		BluetoothGattService bench = new BluetoothGattService(SVC_BENCH, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		mControl = notifying(CHR_CONTROL, BluetoothGattCharacteristic.PROPERTY_WRITE, write);
		bench.addCharacteristic(mControl);
		bench.addCharacteristic(new BluetoothGattCharacteristic(CHR_SINK,
				BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, write));
		mStream = notifying(CHR_STREAM, 0, 0);
		bench.addCharacteristic(mStream);
		// Indicate only: a CCCD value of notifications is refused
		mIndicate = new BluetoothGattCharacteristic(CHR_INDICATE, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0);
		mIndicate.addDescriptor(cccd());
		bench.addCharacteristic(mIndicate);
		mResult = new BluetoothGattCharacteristic(CHR_RESULT, BluetoothGattCharacteristic.PROPERTY_READ, read);
		bench.addCharacteristic(mResult);
		// Read with encryption, like the ESP32 bench: the central pairs with this phone first
		bench.addCharacteristic(new BluetoothGattCharacteristic(CHR_ENCRYPTED, BluetoothGattCharacteristic.PROPERTY_READ,
				BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED));
		bench.addCharacteristic(notifying(CHR_NOTIFY, 0, 0));
		bench.addCharacteristic(new BluetoothGattCharacteristic(CHR_LONG,
				BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE, read | write));

		// The same characteristic UUID in two services
		BluetoothGattService dupA = new BluetoothGattService(SVC_DUP_A, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		dupA.addCharacteristic(new BluetoothGattCharacteristic(CHR_DUP, BluetoothGattCharacteristic.PROPERTY_READ, read));
		BluetoothGattService dupB = new BluetoothGattService(SVC_DUP_B, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		dupB.addCharacteristic(new BluetoothGattCharacteristic(CHR_DUP, BluetoothGattCharacteristic.PROPERTY_READ, read));

		BluetoothGattService slabs = new BluetoothGattService(SVC_SLABS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		slabs.addCharacteristic(new BluetoothGattCharacteristic(CHR_SLABS_CONTROL, BluetoothGattCharacteristic.PROPERTY_WRITE, write));
		slabs.addCharacteristic(new BluetoothGattCharacteristic(CHR_SLABS_DATA,
				BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, write));

		BluetoothGattService ti = new BluetoothGattService(SVC_TI, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		int tiProps = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
		mTiIdentify = notifying(CHR_TI_IDENTIFY, tiProps, write);
		mTiBlock = notifying(CHR_TI_BLOCK, tiProps, write);
		mTiControl = notifying(CHR_TI_CONTROL, tiProps, write);
		ti.addCharacteristic(mTiIdentify);
		ti.addCharacteristic(mTiBlock);
		ti.addCharacteristic(mTiControl);

		// One service at a time: the next one once Android added the previous one
		mToAdd.add(bench);
		mToAdd.add(dupA);
		mToAdd.add(dupB);
		mToAdd.add(slabs);
		mToAdd.add(ti);
		addNextService();
	}

	@NonNull
	private static BluetoothGattCharacteristic notifying(@NonNull UUID uuid, int properties, int permissions) {
		BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid, properties | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
				permissions | BluetoothGattCharacteristic.PERMISSION_READ);
		c.addDescriptor(cccd());
		return c;
	}

	@NonNull
	private static BluetoothGattDescriptor cccd() {
		return new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
	}

	@NonNull
	private static byte[] longPattern() {
		byte[] b = new byte[MAX_VALUE];
		for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 31 + (i >> 8));
		return b;
	}

	private void addNextService() {
		BluetoothGattService service = mToAdd.poll();
		if (service == null) {
			applyAdvertising();
			mHandler.postDelayed(mAdvTick, 500);
			return;
		}
		if (!mServer.addService(service)) mLog.log("addService refused: " + service.getUuid());
	}

	// endregion

	// region Advertising

	/**
	 * Advertising in the current mode, never while a central is connected (like the ESP32): started again at the disconnection
	 */
	private void applyAdvertising() {
		mHandler.removeCallbacks(mApplyAdvertising);
		if (mAdvCallback != null) {
			mAdvertiser.stopAdvertisingSet(mAdvCallback);
			mAdvCallback = null;
			mAdvSet = null;
		}
		if (mAdvMode == ADV_STOPPED || mDevice != null) return;

		// Units of 0.625 ms: 100 ms by default, the shortest interval Android allows
		long interval = mAdvMode == ADV_INTERVAL ? Math.max(AdvertisingSetParameters.INTERVAL_MIN, Math.min(mAdvParam * 8 / 5, 16384))
				: AdvertisingSetParameters.INTERVAL_LOW;
		AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder()
				.setLegacyMode(true)
				.setConnectable(mAdvMode != ADV_NON_CONNECTABLE)
				.setScannable(true)
				.setInterval((int) interval)
				.setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
				.build();
		// The 128-bit UUID leaves no room for the name: it goes in the scan response, in every mode
		AdvertiseData response = new AdvertiseData.Builder().setIncludeDeviceName(mAdvName).build();
		AdvertisingSetCallback callback = new AdvertisingSetCallback() {
			@Override
			public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
				if (mAdvCallback != this) return;
				if (status != ADVERTISE_SUCCESS) {
					mLog.log("Advertising failed: " + status + ", again in 2 s");
					mAdvCallback = null;
					mHandler.postDelayed(mApplyAdvertising, 2000);
					return;
				}
				mAdvSet = set;
				mLog.log("Advertising the bench service, mode " + mAdvMode);
			}
		};
		mAdvCallback = callback;
		mAdvertiser.startAdvertisingSet(parameters, advertiseData(), response, null, null, callback, mHandler);
	}

	/**
	 * The bench service UUID (the sample finds the bench by it), then company 0xFFFF (test) with the counter and the mode
	 */
	@NonNull
	private AdvertiseData advertiseData() {
		return new AdvertiseData.Builder()
				.addServiceUuid(new ParcelUuid(SVC_BENCH))
				.addManufacturerData(0xFFFF, new byte[]{(byte) mAdvCounter, (byte) (mAdvCounter >> 8), (byte) mAdvMode})
				.build();
	}

	// End of a special mode (CMD_ADV)
	private void advResume() {
		mAdvMode = 0;
		mLog.log("Advertising back to normal");
		applyAdvertising();
	}

	// The counter of the manufacturer data changes every 500 ms
	private void advTick() {
		mAdvCounter = (mAdvCounter + 1) & 0xFFFF;
		if (mAdvSet != null) mAdvSet.setAdvertisingData(advertiseData());
		mHandler.postDelayed(mAdvTick, 500);
	}

	// endregion

	// region GATT server (binder threads: everything goes to the thread of the bench)

	private final BluetoothGattServerCallback mCallback = new BluetoothGattServerCallback() {
		@Override
		public void onServiceAdded(int status, BluetoothGattService service) {
			mHandler.post(() -> {
				if (status != BluetoothGatt.GATT_SUCCESS) mLog.log("Service " + service.getUuid() + " not added: " + status);
				addNextService();
			});
		}

		@Override
		public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
			mHandler.post(() -> {
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					// Also called back when the bench takes the link to close it (CMD_DISCONNECT_IN)
					if (device.equals(mDevice)) return;
					mDevice = device;
					mMtu = 23;
					mCccd.clear();
					mLog.log("Connected: " + device.getAddress());
					applyAdvertising();
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					mPrepared.remove(device);
					mLog.log("Disconnected: " + device.getAddress() + " status " + status);
					if (!device.equals(mDevice)) return;
					mDevice = null;
					mNotifications.clear();
					mNotifying = false;
					mStreamLeft = 0;
					mTiActive = false;
					mHandler.removeCallbacksAndMessages(mDisconnectToken);
					applyAdvertising();
				}
			});
		}

		@Override
		public void onMtuChanged(BluetoothDevice device, int mtu) {
			mHandler.post(() -> {
				mMtu = mtu;
				mLog.log("MTU " + mtu);
			});
		}

		@Override
		public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite,
												 boolean responseNeeded, int offset, byte[] value) {
			byte[] v = value == null ? new byte[0] : value;
			mHandler.post(() -> onWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, v));
		}

		@Override
		public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite,
											 boolean responseNeeded, int offset, byte[] value) {
			byte[] v = value == null ? new byte[0] : value;
			mHandler.post(() -> onWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, v));
		}

		@Override
		public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
			mHandler.post(() -> onExecuteWriteRequest(device, requestId, execute));
		}

		@Override
		public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
			mHandler.post(() -> respondRead(device, requestId, offset, readValue(device, characteristic)));
		}

		@Override
		public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
			mHandler.post(() -> {
				int cccd = mCccd.getOrDefault(descriptor, 0);
				respondRead(device, requestId, offset, new byte[]{(byte) cccd, (byte) (cccd >> 8)});
			});
		}

		@Override
		public void onNotificationSent(BluetoothDevice device, int status) {
			mHandler.post(() -> {
				mNotifying = false;
				pump();
			});
		}
	};

	/**
	 * A write to a characteristic or a descriptor. A prepared part is kept and echoed, applied by onExecuteWrite
	 */
	private void onWriteRequest(@NonNull BluetoothDevice device, int requestId, @NonNull Object attribute, boolean prepared, boolean responseNeeded,
								int offset, @NonNull byte[] v) {
		if (prepared) {
			mPrepared.computeIfAbsent(device, d -> new ArrayList<>()).add(new Prepared(attribute, offset, v));
			if (responseNeeded) mServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
			return;
		}
		int status = check(attribute, v);
		if (status == BluetoothGatt.GATT_SUCCESS) slowDown(attribute);
		if (responseNeeded) mServer.sendResponse(device, requestId, status, 0, null);
		if (status == BluetoothGatt.GATT_SUCCESS) apply(device, attribute, v);
	}

	/**
	 * Applies (execute) or drops the prepared writes of the central
	 */
	private void onExecuteWriteRequest(@NonNull BluetoothDevice device, int requestId, boolean execute) {
		List<Prepared> parts = mPrepared.remove(device);
		LinkedHashMap<Object, byte[]> values = new LinkedHashMap<>();
		int status = execute && parts != null ? assemble(parts, values) : BluetoothGatt.GATT_SUCCESS;
		if (status == BluetoothGatt.GATT_SUCCESS) for (Object attribute : values.keySet()) slowDown(attribute);
		mServer.sendResponse(device, requestId, status, 0, null);
		if (status != BluetoothGatt.GATT_SUCCESS) {
			mLog.log("Prepared writes refused: status " + status);
			return;
		}
		for (Map.Entry<Object, byte[]> e : values.entrySet()) apply(device, e.getKey(), e.getValue());
	}

	/**
	 * The value of each attribute from its prepared parts, checked like NimBLE on the ESP32 does: parts sorted by offset,
	 * contiguous from 0 (two parts at offset 0 for one characteristic are refused), 512 bytes at most
	 * @return the ATT status
	 */
	private static int assemble(@NonNull List<Prepared> parts, @NonNull Map<Object, byte[]> values) {
		LinkedHashMap<Object, List<Prepared>> byAttribute = new LinkedHashMap<>();
		for (Prepared p : parts) byAttribute.computeIfAbsent(p.attribute(), a -> new ArrayList<>()).add(p);
		for (Map.Entry<Object, List<Prepared>> e : byAttribute.entrySet()) {
			e.getValue().sort(Comparator.comparingInt(Prepared::offset));
			ByteArrayOutputStream value = new ByteArrayOutputStream();
			for (Prepared p : e.getValue()) {
				if (p.offset() != value.size()) return BluetoothGatt.GATT_INVALID_OFFSET;
				if (value.size() + p.value().length > MAX_VALUE) return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
				value.write(p.value(), 0, p.value().length);
			}
			byte[] v = value.toByteArray();
			int status = check(e.getKey(), v);
			if (status != BluetoothGatt.GATT_SUCCESS) return status;
			values.put(e.getKey(), v);
		}
		return BluetoothGatt.GATT_SUCCESS;
	}

	/**
	 * ATT status of a write: refused when longer than an attribute holds, or a CCCD value the characteristic cannot send
	 * (like NimBLE: notifications on the indicate only characteristic)
	 */
	private static int check(@NonNull Object attribute, @NonNull byte[] v) {
		if (v.length > MAX_VALUE) return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
		if (!(attribute instanceof BluetoothGattDescriptor d) || !CCCD.equals(d.getUuid())) return BluetoothGatt.GATT_SUCCESS;
		if (v.length != 2) return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
		int properties = d.getCharacteristic().getProperties();
		int allowed = ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? 1 : 0)
				| ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ? 2 : 0);
		return (getLe16(v, 0) & ~allowed) != 0 ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED : BluetoothGatt.GATT_SUCCESS;
	}

	/**
	 * A slow device (CMD_SINK_DELAY): each sink write is answered and processed that much later
	 */
	private void slowDown(@NonNull Object attribute) {
		if (mSinkDelayUs == 0 || mSinkMode == 1 || !(attribute instanceof BluetoothGattCharacteristic c)) return;
		UUID uuid = c.getUuid();
		if (CHR_SINK.equals(uuid) || CHR_SLABS_DATA.equals(uuid) || CHR_TI_BLOCK.equals(uuid)) pause(mSinkDelayUs);
	}

	private void apply(@NonNull BluetoothDevice device, @NonNull Object attribute, @NonNull byte[] v) {
		if (attribute instanceof BluetoothGattDescriptor d) {
			if (CCCD.equals(d.getUuid())) mCccd.put(d, getLe16(v, 0));
			return;
		}
		UUID uuid = ((BluetoothGattCharacteristic) attribute).getUuid();
		if (CHR_SINK.equals(uuid) || CHR_SLABS_DATA.equals(uuid)) sinkWrite(v, 0);
		else if (CHR_CONTROL.equals(uuid)) onControl(device, v);
		else if (CHR_LONG.equals(uuid)) mLong = v;
		else if (CHR_SLABS_CONTROL.equals(uuid)) onSlabsControl(v);
		else if (CHR_TI_CONTROL.equals(uuid)) onTiControl(v);
		else if (CHR_TI_IDENTIFY.equals(uuid)) onTiIdentify(v);
		else if (CHR_TI_BLOCK.equals(uuid)) onTiBlock(v);
	}

	@NonNull
	private byte[] readValue(@NonNull BluetoothDevice device, @NonNull BluetoothGattCharacteristic c) {
		UUID uuid = c.getUuid();
		if (CHR_RESULT.equals(uuid)) return otaResult();
		if (CHR_LONG.equals(uuid)) return mLong;
		if (CHR_DUP.equals(uuid)) return ascii(SVC_DUP_A.equals(c.getService().getUuid()) ? "A" : "B");
		if (CHR_ENCRYPTED.equals(uuid)) {
			mLog.log("Encrypted characteristic read by " + device.getAddress());
			return ascii("SECURE-OK");
		}
		return new byte[0];
	}

	/**
	 * Read blob: the value from the offset, Android cuts it to the MTU
	 */
	private void respondRead(@NonNull BluetoothDevice device, int requestId, int offset, @NonNull byte[] value) {
		if (offset > value.length) mServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
		else mServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(value, offset, value.length));
	}

	// endregion

	// region Notifications

	private void notify(@NonNull BluetoothGattCharacteristic c, @NonNull byte[] value) {
		mNotifications.add(new Outgoing(c, value, false));
		pump();
	}

	/**
	 * Sends the next notification (answers first, then the stream) when none is in flight
	 */
	private void pump() {
		BluetoothDevice device = mDevice;
		if (mNotifying || device == null) return;

		Outgoing next = mNotifications.peek();
		boolean stream = false;
		if (next == null) {
			if (mStreamLeft <= 0 || mStreamWaiting) return;
			next = new Outgoing(mStream, streamPacket(), false);
			stream = true;
		}
		if (!send(device, next)) {
			// Android busy: again a bit later
			if (stream) mStreamRetries++;
			mHandler.removeCallbacks(mPump);
			mHandler.postDelayed(mPump, 2);
			return;
		}
		mNotifying = true;
		if (!stream) {
			mNotifications.poll();
			return;
		}

		mStreamSeq++;
		mStreamLeft--;
		if (mStreamLeft == 0) {
			byte[] p = new byte[8];
			le32(p, 0, mStreamCount);
			le32(p, 4, mStreamRetries);
			answer(CMD_STREAM, 0, p);
		} else if (mStreamInterval > 0) {
			mStreamWaiting = true;
			mHandler.postDelayed(() -> {
				mStreamWaiting = false;
				pump();
			}, mStreamInterval);
		}
	}

	/**
	 * An indication is sent once the previous one was confirmed: onNotificationSent comes with the confirmation
	 */
	@SuppressWarnings("deprecation")
	private boolean send(@NonNull BluetoothDevice device, @NonNull Outgoing o) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				return mServer.notifyCharacteristicChanged(device, o.characteristic(), o.indication(), o.value()) == BluetoothStatusCodes.SUCCESS;
			}
			o.characteristic().setValue(o.value());
			return mServer.notifyCharacteristicChanged(device, o.characteristic(), o.indication());
		} catch (RuntimeException e) {
			return false;
		}
	}

	@NonNull
	private byte[] streamPacket() {
		byte[] b = new byte[mStreamSize];
		le32(b, 0, mStreamSeq);
		for (int i = 4; i < mStreamSize; i++) b[i] = (byte) (mStreamSeq + i);
		return b;
	}

	private void answer(byte cmd, int status, @Nullable byte[] payload) {
		byte[] b = new byte[2 + (payload == null ? 0 : payload.length)];
		b[0] = cmd;
		b[1] = (byte) status;
		if (payload != null) System.arraycopy(payload, 0, b, 2, payload.length);
		notify(mControl, b);
	}

	private int cccd(@NonNull BluetoothGattCharacteristic c) {
		Integer value = mCccd.get(c.getDescriptor(CCCD));
		return value == null ? 0 : value;
	}

	// endregion

	// region Sink and commands

	private synchronized void sinkReset() {
		mBuffered -= mSinkQueue.drainTo(new ArrayList<>());
		mBytes = mWrites = mDropped = mGaps50 = 0;
		mFirstMs = mLastMs = mMaxGapMs = 0;
		mCrc.reset();
	}

	private void sinkWrite(@NonNull byte[] data, int offset) {
		if (mSinkMode == 1) sinkBuffer(Arrays.copyOfRange(data, offset, data.length));
		else sinkConsume(data, offset);
	}

	/**
	 * Bootloader without flow control: the write is acknowledged at once and kept in a buffer of mSinkDepth writes, what
	 * does not fit is lost
	 */
	private synchronized void sinkBuffer(@NonNull byte[] data) {
		if (mBuffered >= mSinkDepth) {
			mDropped++;
			return;
		}
		mBuffered++;
		mSinkQueue.add(data);
	}

	private synchronized void sinkConsume(@NonNull byte[] data, int offset) {
		long now = SystemClock.elapsedRealtime();
		if (mWrites == 0) mFirstMs = now;
		else {
			long gap = now - mLastMs;
			if (gap > mMaxGapMs) mMaxGapMs = gap;
			if (gap >= 50) mGaps50++;
		}
		mLastMs = now;
		mWrites++;
		mBytes += data.length - offset;
		mCrc.update(data, offset, data.length - offset);
	}

	/**
	 * Drain thread of the bootloader buffer: one write every mDrainUs
	 */
	private void drain() {
		try {
			while (true) {
				byte[] data = mSinkQueue.take();
				synchronized (this) {
					sinkConsume(data, 0);
					mBuffered--;
				}
				pause(mDrainUs);
			}
		} catch (InterruptedException ignored) {
			// stop()
		}
	}

	@NonNull
	private synchronized byte[] sinkStats() {
		byte[] p = new byte[28];
		le32(p, 0, mBytes);
		le32(p, 4, mWrites);
		le32(p, 8, mDropped);
		le32(p, 12, mCrc.getValue());
		le32(p, 16, mLastMs - mFirstMs);
		le32(p, 20, mMaxGapMs);
		le32(p, 24, mGaps50);
		return p;
	}

	private void onControl(@NonNull BluetoothDevice device, @NonNull byte[] v) {
		if (v.length == 0) return;
		byte cmd = v[0];
		switch (cmd) {
			case CMD_RESET_STATS -> {
				sinkReset();
				answer(cmd, 0, null);
			}
			case CMD_GET_STATS -> answer(cmd, 0, sinkStats());
			case CMD_STREAM -> {
				if (v.length < 9) {
					answer(cmd, 2, null);
					return;
				}
				mStreamCount = getLe32(v, 1);
				mStreamSize = Math.max(4, Math.min(getLe16(v, 5), Math.min(mMtu - 3, MAX_VALUE)));
				mStreamInterval = getLe16(v, 7);
				mStreamLeft = mStreamCount;
				mStreamSeq = 0;
				mStreamRetries = 0;
				mStreamWaiting = false;
				pump();
			}
			case CMD_SINK_DELAY -> {
				if (v.length < 5) {
					answer(cmd, 2, null);
					return;
				}
				mSinkDelayUs = getLe32(v, 1);
				answer(cmd, 0, null);
			}
			case CMD_SINK_BUFFER -> {
				if (v.length < 8) {
					answer(cmd, 2, null);
					return;
				}
				mSinkMode = v[1];
				int depth = getLe16(v, 2);
				mSinkDepth = depth == 0 || depth > 64 ? 64 : depth;
				mDrainUs = getLe32(v, 4);
				answer(cmd, 0, null);
			}
			case CMD_ADV -> {
				if (v.length < 6) {
					answer(cmd, 2, null);
					return;
				}
				mAdvMode = v[1] & 0xFF;
				mAdvParam = getLe32(v, 2);
				// Every special mode goes back to normal by itself: a non connectable bench could not be reset
				mHandler.removeCallbacks(mAdvResume);
				if (mAdvMode != 0) mHandler.postDelayed(mAdvResume, mAdvMode == ADV_STOPPED ? mAdvParam : ADV_MODE_DURATION_MS);
				mLog.log("Advertising mode " + mAdvMode + ", param " + mAdvParam + ", once disconnected");
				answer(cmd, 0, null);
				applyAdvertising();
			}
			case CMD_INDICATE -> {
				// To a central subscribed to the indications only, like NimBLE: the answer after its confirmation
				if ((cccd(mIndicate) & 2) != 0) mNotifications.add(new Outgoing(mIndicate, ascii("IND-OK"), true));
				answer(cmd, 0, null);
			}
			case CMD_DISCONNECT_IN -> {
				long ms = v.length >= 5 ? getLe32(v, 1) : 0;
				answer(cmd, 0, null);
				mHandler.postAtTime(() -> disconnect(device), mDisconnectToken, SystemClock.uptimeMillis() + ms);
			}
			// Version, boot mode, MTU, pairing mode 2 (numeric comparison: what Android does with another phone), bonds with this central
			case CMD_GET_INFO -> answer(cmd, 0, new byte[]{1, 0, (byte) mMtu, (byte) (mMtu >> 8), 2,
					(byte) (device.getBondState() == BluetoothDevice.BOND_BONDED ? 1 : 0)});
			case CMD_DELETE_BONDS -> deleteBond(device);
			// A phone cannot: reboot in DFU mode with the same MAC, choose its pairing IO capabilities, ask for connection parameters
			case CMD_REBOOT, CMD_SECURITY, CMD_CONN_PARAMS -> answer(cmd, 1, null);
			default -> answer(cmd, 1, null);
		}
	}

	/**
	 * Closes the link of the central: Android only drops a link held by the server app, connect() takes the one the
	 * central opened
	 */
	private void disconnect(@NonNull BluetoothDevice device) {
		if (!device.equals(mDevice)) return;
		mLog.log("Disconnecting " + device.getAddress());
		mServer.connect(device, false);
		mServer.cancelConnection(device);
	}

	/**
	 * The phone forgets the bond of the central (BluetoothDevice.removeBond, hidden, allowed on Android 12): status 0 and
	 * one bond when bonded, else status 1. The answer leaves first, the removal drops the link
	 */
	private void deleteBond(@NonNull BluetoothDevice device) {
		Method removeBond = removeBondMethod();
		boolean bonded = removeBond != null && device.getBondState() == BluetoothDevice.BOND_BONDED;
		answer(CMD_DELETE_BONDS, bonded ? 0 : 1, new byte[]{(byte) (bonded ? 1 : 0)});
		if (!bonded) {
			mLog.log("No bond to remove for " + device.getAddress());
			return;
		}
		mHandler.postDelayed(() -> {
			try {
				mLog.log("Bond of " + device.getAddress() + (Boolean.TRUE.equals(removeBond.invoke(device)) ? " removed" : ": removeBond refused"));
			} catch (ReflectiveOperationException e) {
				mLog.log("removeBond failed: " + e);
			}
		}, 300);
	}

	@Nullable
	private static Method removeBondMethod() {
		try {
			return BluetoothDevice.class.getMethod("removeBond");
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	// endregion

	// region OTA

	private void otaStart(byte protocol) {
		sinkReset();
		mOtaProtocol = protocol;
		mOtaState = OTA_RUNNING;
		mOtaStartMs = SystemClock.elapsedRealtime();
	}

	private void otaEnd(byte protocol) {
		otaFinish(protocol, SystemClock.elapsedRealtime() + 3000);
	}

	/**
	 * Without flow control, the buffer drains before the result is final (3 s at most)
	 */
	private void otaFinish(byte protocol, long untilMs) {
		if (mBuffered > 0 && SystemClock.elapsedRealtime() < untilMs) {
			mHandler.postDelayed(() -> otaFinish(protocol, untilMs), 2);
			return;
		}
		mOtaProtocol = protocol;
		mOtaState = OTA_DONE;
		mOtaEndMs = SystemClock.elapsedRealtime();
		synchronized (this) {
			mLog.log("OTA end, protocol " + protocol + ", " + mBytes + " bytes, crc " + Long.toHexString(mCrc.getValue()) + ", dropped " + mDropped + ", "
					+ (mOtaEndMs - mOtaStartMs) + " ms");
		}
	}

	@NonNull
	private synchronized byte[] otaResult() {
		byte[] r = new byte[18];
		r[0] = mOtaProtocol;
		r[1] = mOtaState;
		le32(r, 2, mBytes);
		le32(r, 6, mCrc.getValue());
		le32(r, 10, mDropped);
		le32(r, 14, mOtaState == OTA_IDLE ? 0 : (mOtaState == OTA_DONE ? mOtaEndMs : SystemClock.elapsedRealtime()) - mOtaStartMs);
		return r;
	}

	private void onSlabsControl(@NonNull byte[] v) {
		if (v.length == 0) return;
		if (v[0] == 0x00) otaStart(PROTO_SLABS);
		else if (v[0] == 0x03) otaEnd(PROTO_SLABS);
	}

	private void onTiControl(@NonNull byte[] v) {
		if (v.length == 0) return;
		switch (v[0]) {
			case TI_GET_BLOCK_SIZE -> {
				mTiBlockSize = Math.min(mMtu - 3, 244);
				notify(mTiControl, new byte[]{TI_GET_BLOCK_SIZE, (byte) mTiBlockSize, (byte) (mTiBlockSize >> 8)});
			}
			case TI_START -> {
				if (mTiBlockSize <= 4 || mTiImageLen == 0) {
					notify(mTiControl, new byte[]{TI_START, 0x01});
					return;
				}
				otaStart(PROTO_TI);
				mTiNext = 0;
				mTiActive = true;
				tiRequestBlock(0);
			}
			case TI_ENABLE_IMAGE -> {
				notify(mTiControl, new byte[]{TI_ENABLE_IMAGE, TI_SUCCESS});
				otaEnd(PROTO_TI);
			}
			default -> {}
		}
	}

	private void tiRequestBlock(long block) {
		byte[] b = new byte[6];
		b[0] = TI_BLOCK_REQUEST;
		b[1] = TI_SUCCESS;
		le32(b, 2, block);
		notify(mTiControl, b);
	}

	private void onTiIdentify(@NonNull byte[] v) {
		byte status = 0x01;
		if (v.length >= 18 && mTiBlockSize > 4) {
			mTiImageLen = getLe32(v, 14);
			long dataSize = mTiBlockSize - 4;
			mTiBlockCount = (mTiImageLen + dataSize - 1) / dataSize;
			if (mTiImageLen > 0) status = TI_SUCCESS;
		}
		notify(mTiIdentify, new byte[]{status});
	}

	private void onTiBlock(@NonNull byte[] v) {
		if (!mTiActive || v.length <= 4) return;
		long block = getLe32(v, 0);
		// A block out of order is asked again, like the TI target does
		if (block != mTiNext) {
			tiRequestBlock(mTiNext);
			return;
		}
		sinkWrite(v, 4);
		mTiNext++;
		if (mTiNext >= mTiBlockCount) {
			mTiActive = false;
			notify(mTiControl, new byte[]{TI_BLOCK_REQUEST, TI_DOWNLOAD_COMPLETE});
		} else {
			tiRequestBlock(mTiNext);
		}
	}

	// endregion

	/**
	 * Holds the calling thread for us microseconds
	 */
	private static void pause(long us) {
		long end = System.nanoTime() + us * 1000;
		for (long left = us * 1000; left > 0; left = end - System.nanoTime()) LockSupport.parkNanos(left);
	}

	@NonNull
	private static byte[] ascii(@NonNull String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private static void le32(@NonNull byte[] b, int p, long v) {
		for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i));
	}

	private static long getLe32(@NonNull byte[] b, int p) {
		return (b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16 | (b[p + 3] & 0xFFL) << 24;
	}

	private static int getLe16(@NonNull byte[] b, int p) {
		return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
	}
}
