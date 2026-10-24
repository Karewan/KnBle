package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.UUID;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.OtaOptions;
import ovh.karewan.knble.interfaces.BleFastTransferCallback;
import ovh.karewan.knble.interfaces.BleMtuChangedCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BleOtaCallback;
import ovh.karewan.knble.interfaces.BlePhyValueCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleReadRssiCallback;
import ovh.karewan.knble.interfaces.BleRequestCallback;
import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleProgress;

/**
 * The operations of the GATT queue. All of them run on the thread of their DeviceActor.
 */
@SuppressLint("MissingPermission")
final class GattOps {
	static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	// Subscription made by a request when the app had none: its other notifications are dropped
	static final BleNotifyCallback NOTIFY_SINK = value -> {};

	private GattOps() {}

	// region GATT calls

	@SuppressWarnings("deprecation")
	static int writeCharacteristic(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int writeType) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return status(gatt.writeCharacteristic(characteristic, value, writeType));
		// Before Android 13 a refusal says nothing about its cause: asked again while it lasts
		characteristic.setWriteType(writeType);
		return characteristic.setValue(value) && gatt.writeCharacteristic(characteristic) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY;
	}

	@SuppressWarnings("deprecation")
	static int writeDescriptor(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, @NonNull byte[] value) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return status(gatt.writeDescriptor(descriptor, value));
		return descriptor.setValue(value) && gatt.writeDescriptor(descriptor) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY;
	}

	/**
	 * Android 13+ status of a write: only "busy" is worth asking again
	 */
	private static int status(int code) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return DeviceActor.CALL_BUSY;
		return switch (code) {
			case BluetoothStatusCodes.SUCCESS -> DeviceActor.CALL_OK;
			// The stack of the Bluetooth process restarting also ends up there
			case BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> DeviceActor.CALL_BUSY;
			case BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> BleError.PERMISSION_DENIED;
			case BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> BleError.BLUETOOTH_DISABLED;
			case BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED -> BleError.NOT_SUPPORTED;
			default -> BleError.REFUSED;
		};
	}

	/**
	 * Write type the characteristic allows, the one asked by the app when it can
	 */
	static int writeType(int properties, boolean noResponse) {
		boolean canNoResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
		boolean canWrite = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
		if (noResponse && !canNoResponse) noResponse = false;
		else if (!noResponse && !canWrite) noResponse = true;
		return noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
	}

	private static boolean canWrite(int properties) {
		return (properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
	}

	/**
	 * Subscription on the phone side and value of the CCCD: notifications, else indications. Null when not possible.
	 */
	@Nullable
	static byte[] subscribeValue(@NonNull BluetoothGattCharacteristic chr) {
		int properties = chr.getProperties();
		if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) return BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
		if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) return BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
		return null;
	}

	static long le32(@NonNull byte[] b, int offset) {
		return (b[offset] & 0xFFL) | (b[offset + 1] & 0xFFL) << 8 | (b[offset + 2] & 0xFFL) << 16 | (b[offset + 3] & 0xFFL) << 24;
	}

	// endregion

	static final class Read extends GattOp {
		private final CharTarget mTarget;
		private final BleReadCallback mCallback;
		private BluetoothGattCharacteristic mChr;

		Read(@NonNull Dispatcher d, @NonNull CharTarget target, @NonNull BleReadCallback callback) {
			super(d);
			mTarget = target;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mChr = mTarget.resolve(gatt);
			if (mChr == null || (mChr.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
				fail(mChr == null ? BleError.NOT_FOUND : BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			actor.issue(this, g -> g.readCharacteristic(mChr) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
		}

		@Override
		void onCharacteristicRead(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
			if (!answer(same(characteristic, mChr))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
			} else if (done()) mDispatcher.post(() -> mCallback.onReadSuccess(value));
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onReadFailed(error));
		}
	}

	static final class Write extends GattOp {
		private final CharTarget mTarget;
		private final byte[] mData;
		private final boolean mNoResponse;
		private final BleWriteCallback mCallback;
		private BluetoothGattCharacteristic mChr;

		Write(@NonNull Dispatcher d, @NonNull CharTarget target, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
			super(d);
			mTarget = target;
			mData = data;
			mNoResponse = noResponse;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mChr = mTarget.resolve(gatt);
			if (mChr == null || !canWrite(mChr.getProperties())) {
				fail(mChr == null ? BleError.NOT_FOUND : BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			int type = writeType(mChr.getProperties(), mNoResponse);
			actor.issue(this, g -> writeCharacteristic(g, mChr, mData, type));
		}

		@Override
		void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			if (!answer(same(characteristic, mChr))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
			} else if (done()) mDispatcher.post(mCallback::onWriteSuccess);
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onWriteFailed(error));
		}
	}

	/**
	 * Packets of data on one characteristic, each one sent when the stack took the previous one: the only flow control
	 * Android gives for the writes without response. Hot path of the OTA: no allocation per packet (one reused buffer,
	 * the stack copies it during the call), the next packet goes before the progress for the app.
	 */
	abstract static class PacketWriter extends GattOp implements DeviceActor.GattCall {
		private final Runnable mSendNext = this::sendNext;
		@NonNull
		final ProgressReporter mProgress;
		DeviceActor mActor;
		BluetoothGattCharacteristic mDataChr;
		private byte[] mData;
		private long mInterval;
		private int mSyncEvery, mWriteType, mPacketType, mOffset, mSent, mPackets;
		private boolean mCanSync;
		private byte[] mBuffer, mPacket;
		// A progress at the last packet (SilabsOta reports its end once the device answered instead)
		boolean mReportLastPacket = true;

		PacketWriter(@NonNull Dispatcher d, @NonNull ProgressReporter.Sink sink) {
			super(d);
			mProgress = new ProgressReporter(d, sink);
		}

		/**
		 * Sends the data from its first byte
		 * @param split 0 or less: the largest packet of the connection (512 bytes at most, the size limit of an attribute)
		 */
		final void startPackets(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic chr, @NonNull byte[] data, int split, boolean noResponse,
								long interval, int syncEvery) {
			mActor = actor;
			mDataChr = chr;
			mData = data;
			mInterval = interval;
			mSyncEvery = syncEvery;
			mWriteType = writeType(chr.getProperties(), noResponse);
			mCanSync = (chr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
			// 512 bytes at most (an attribute); a write without response is cut by the stack beyond MTU - 3: never more
			if (split <= 0) split = actor.mtu() - 3;
			split = Math.min(split, 512);
			if (mWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) split = Math.min(split, actor.mtu() - 3);
			split = Math.max(1, split);
			mPackets = data.length == 0 ? 1 : (data.length + split - 1) / split;
			mBuffer = new byte[Math.min(split, data.length)];
			mOffset = 0;
			mSent = 0;
			mProgress.start();
			sendNext();
		}

		final void sendNext() {
			int length = Math.min(mBuffer.length, mData.length - mOffset);
			mPacket = length == mBuffer.length ? mBuffer : new byte[length];
			System.arraycopy(mData, mOffset, mPacket, 0, length);
			// One packet in N with response: Android never keeps more than N packets waiting
			mPacketType = mSyncEvery > 0 && mCanSync && (mSent + 1) % mSyncEvery == 0 ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : mWriteType;
			refreshDeadline();
			mActor.issue(this, this);
		}

		@Override
		public final int call(@NonNull BluetoothGatt gatt) {
			return writeCharacteristic(gatt, mDataChr, mPacket, mPacketType);
		}

		/**
		 * Answer of the stack to a packet
		 * @return false when it was not about a packet
		 */
		final boolean onPacketWritten(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			if (!answer(same(characteristic, mDataChr))) return false;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (!actor.awaitBond(this, status)) packetsFailed(status);
				return true;
			}

			mOffset += mPacket.length;
			mSent++;
			if (mSent >= mPackets) {
				if (mReportLastPacket) mProgress.update(mOffset, mData.length, mSent, mPackets);
				packetsDone();
				return true;
			}

			if (mInterval > 0) {
				// The pause is not a wait for the device: the watchdog gives it its time
				deadline = SystemClock.elapsedRealtime() + mInterval + timeout;
				actor.postDelayed(mSendNext, this, mInterval);
			} else {
				sendNext();
			}
			// After the next packet (the hot path), unless that one already ended the operation
			if (!ended()) mProgress.update(mOffset, mData.length, mSent, mPackets);
			return true;
		}

		/**
		 * The packet refused for lack of bond goes again, the packets already written stay written
		 */
		@Override
		void replay(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			sendNext();
		}

		/**
		 * Last progress at the real end of the delivery (the device answered after the last packet): the rate of the
		 * whole transfer, not the one of the data Android took in advance
		 */
		final void reportDelivered() {
			mProgress.update(mData.length, mData.length, mPackets, mPackets);
		}

		abstract void packetsDone();

		abstract void packetsFailed(int status);
	}

	static final class SplittedWrite extends PacketWriter {
		private final CharTarget mTarget;
		private final byte[] mData;
		private final int mSplitSize;
		private final boolean mNoResponse;
		private final long mInterval;
		private final BleSplittedWriteCallback mCallback;

		SplittedWrite(@NonNull Dispatcher d, @NonNull CharTarget target, @NonNull byte[] data, int splitSize, boolean noResponse, long interval,
					  @NonNull BleSplittedWriteCallback callback) {
			super(d, callback::onWriteProgress);
			mTarget = target;
			mData = data;
			mSplitSize = splitSize;
			mNoResponse = noResponse;
			mInterval = interval;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			BluetoothGattCharacteristic chr = mTarget.resolve(gatt);
			if (chr == null || !canWrite(chr.getProperties())) {
				fail(chr == null ? BleError.NOT_FOUND : BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			startPackets(actor, chr, mData, mSplitSize, mNoResponse, mInterval, 0);
		}

		@Override
		void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			onPacketWritten(actor, characteristic, status);
		}

		@Override
		void packetsDone() {
			if (done()) mDispatcher.post(mCallback::onWriteSuccess);
			mActor.finish(this);
		}

		@Override
		void packetsFailed(int status) {
			fail(status);
			mActor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onWriteFailed(error));
		}
	}

	/**
	 * Silicon Labs AppLoader OTA (BGM111, BGM13P, BGM210P, and the ESP32 cloning it): control 0x00, the image in packets
	 * without response on the data characteristic, control 0x03. Control writes are with response: the 0x03 one is
	 * answered once the device got every packet Android was still holding, so the success is the end of the delivery.
	 */
	static final class SilabsOta extends PacketWriter {
		static final UUID SERVICE = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0");
		static final UUID CONTROL = UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063");
		static final UUID DATA = UUID.fromString("984227f3-34fc-4045-a5d0-2c581f81a153");
		private static final byte START = 0x00, END = 0x03;
		// The answer to 0x03 waits for the delivery of what Android holds (hundreds of KB on some phones) and for the
		// check of the image by the device
		private static final long END_TIMEOUT_MS = 60000;
		private static final int PHASE_START = 0, PHASE_DATA = 1, PHASE_END = 2;

		private final byte[] mImage;
		private final OtaOptions mOptions;
		private final BleOtaCallback mCallback;
		private BluetoothGattCharacteristic mControl;
		private int mPhase, mAttempt;
		private long mDefaultTimeout;

		SilabsOta(@NonNull Dispatcher d, @NonNull byte[] image, @NonNull OtaOptions options, @NonNull BleOtaCallback callback) {
			super(d, callback::onOtaProgress);
			mImage = image;
			mOptions = options;
			mCallback = callback;
			// The 100 % once the device answered 0x03 (reportDelivered), not when Android took the last packet
			mReportLastPacket = false;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			mDefaultTimeout = timeout;
			BluetoothGattService service = gatt.getService(SERVICE);
			mControl = service == null ? null : service.getCharacteristic(CONTROL);
			mDataChr = service == null ? null : service.getCharacteristic(DATA);
			if (mControl == null || mDataChr == null) {
				fail(BleError.NOT_FOUND);
				actor.finish(this);
				return;
			}
			control(START);
		}

		private void control(byte command) {
			mPhase = command == START ? PHASE_START : PHASE_END;
			timeout = command == END ? Math.max(END_TIMEOUT_MS, mDefaultTimeout) : mDefaultTimeout;
			refreshDeadline();
			byte[] value = {command};
			mActor.issue(this, g -> writeCharacteristic(g, mControl, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT));
		}

		@Override
		void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			if (mPhase == PHASE_DATA) {
				onPacketWritten(actor, characteristic, status);
				return;
			}
			if (!answer(same(characteristic, mControl))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (!actor.awaitBond(this, status)) failed(status);
				return;
			}

			if (mPhase == PHASE_START) {
				mPhase = PHASE_DATA;
				timeout = mDefaultTimeout;
				long interval = mOptions.getPacketInterval() + mAttempt * mOptions.getRetryIntervalStep();
				startPackets(actor, mDataChr, mImage, mOptions.getPacketSize(), true, interval, mOptions.getSyncEvery());
			} else {
				reportDelivered();
				if (done()) mDispatcher.post(mCallback::onOtaSuccess);
				actor.finish(this);
			}
		}

		@Override
		void replay(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			if (mPhase == PHASE_DATA) sendNext();
			else control(mPhase == PHASE_START ? START : END);
		}

		@Override
		void packetsDone() {
			control(END);
		}

		@Override
		void packetsFailed(int status) {
			failed(status);
		}

		/**
		 * A write failed while the device is still connected: again from the beginning, slower, if allowed
		 */
		private void failed(int status) {
			if (mAttempt < mOptions.getRetries()) {
				mAttempt++;
				int attempt = mAttempt;
				BleLog.d("Silicon Labs OTA: " + BleError.name(status) + ", attempt " + attempt);
				mDispatcher.post(() -> mCallback.onOtaRetry(attempt));
				bondReplayed = false;
				control(START);
				return;
			}
			fail(status);
			mActor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onOtaFailed(error));
		}
	}

	/**
	 * TI Enhanced OAD (CC13xx/CC26xx): the device asks for each block by a notification on the control point, answered
	 * here on the thread of the device by a single write without response (no trip through the main thread per block).
	 * Block size, image identify, start, blocks, enable image, under a watchdog refreshed by each answer.
	 */
	static final class TiOad extends GattOp {
		static final UUID SERVICE = UUID.fromString("f000ffc0-0451-4000-b000-000000000000");
		static final UUID IDENTIFY = UUID.fromString("f000ffc1-0451-4000-b000-000000000000");
		static final UUID BLOCK = UUID.fromString("f000ffc2-0451-4000-b000-000000000000");
		static final UUID CONTROL = UUID.fromString("f000ffc5-0451-4000-b000-000000000000");
		private static final byte CMD_GET_BLOCK_SIZE = 0x01, CMD_START = 0x03, CMD_ENABLE_IMAGE = 0x04, RSP_BLOCK_REQUEST = 0x12;
		private static final byte STATUS_SUCCESS = 0x00, STATUS_DOWNLOAD_COMPLETE = 0x0E;
		// Image header (read by the device from the first block too), identify made of a part of it, block number of a block
		static final int HEADER_LEN = 44, IDENTIFY_LEN = 22, BLOCK_NUMBER_LEN = 4;

		private final byte[] mImage;
		private final BleOtaCallback mCallback;
		private final ProgressReporter mProgress;
		private DeviceActor mActor;
		private BluetoothGattCharacteristic mIdentify, mBlock, mControl;
		// Characteristics to subscribe to, in this order
		private BluetoothGattCharacteristic[] mSubscriptions;
		private BluetoothGattDescriptor mCccd;
		private int mSubscribed, mBlockSize, mBlockCount;
		private boolean mTransferring;

		TiOad(@NonNull Dispatcher d, @NonNull byte[] image, @NonNull BleOtaCallback callback) {
			super(d);
			mImage = image;
			mCallback = callback;
			mProgress = new ProgressReporter(d, callback::onOtaProgress);
		}

		/**
		 * The length declared by the header must be the one of the image: a truncated file is never sent
		 */
		static boolean validImage(@NonNull byte[] image) {
			if (image.length < HEADER_LEN) return false;
			int headerLength = (image[40] & 0xFF) | (image[41] & 0xFF) << 8;
			return le32(image, 24) == image.length && headerLength >= HEADER_LEN;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			if (!validImage(mImage)) {
				fail(BleError.INVALID_IMAGE);
				actor.finish(this);
				return;
			}
			BluetoothGattService service = gatt.getService(SERVICE);
			mIdentify = service == null ? null : service.getCharacteristic(IDENTIFY);
			mBlock = service == null ? null : service.getCharacteristic(BLOCK);
			mControl = service == null ? null : service.getCharacteristic(CONTROL);
			if (mIdentify == null || mBlock == null || mControl == null) {
				fail(BleError.NOT_FOUND);
				actor.finish(this);
				return;
			}
			// The block requests come on the control point, the TI reference client subscribes to the block one anyway
			mSubscriptions = new BluetoothGattCharacteristic[]{mControl, mIdentify, mBlock};
			mSubscribed = 0;
			subscribeNext(gatt);
		}

		private void subscribeNext(@NonNull BluetoothGatt gatt) {
			if (mSubscribed >= mSubscriptions.length) {
				command(CMD_GET_BLOCK_SIZE);
				return;
			}
			BluetoothGattCharacteristic chr = mSubscriptions[mSubscribed];
			byte[] value = subscribeValue(chr);
			mCccd = chr.getDescriptor(CCCD);
			if (value == null || mCccd == null || !gatt.setCharacteristicNotification(chr, true)) {
				fail(value == null || mCccd == null ? BleError.NOT_SUPPORTED : BleError.REFUSED);
				mActor.finish(this);
				return;
			}
			refreshDeadline();
			mActor.issue(this, g -> writeDescriptor(g, mCccd, value));
		}

		@Override
		void onDescriptorWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, int status) {
			if (!answer(same(descriptor, mCccd))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (!actor.awaitBond(this, status)) failed(status);
				return;
			}
			mSubscribed++;
			BluetoothGatt gatt = actor.gatt();
			if (gatt != null) subscribeNext(gatt);
		}

		@Override
		void replay(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			if (mSubscribed < mSubscriptions.length) subscribeNext(gatt);
			else failed(BleError.REFUSED);
		}

		private void command(byte command) {
			write(mControl, new byte[]{command});
		}

		private void write(@NonNull BluetoothGattCharacteristic chr, @NonNull byte[] value) {
			refreshDeadline();
			mActor.issue(this, g -> writeCharacteristic(g, chr, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE));
		}

		@Override
		void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			if (!answer(same(characteristic, mControl) || same(characteristic, mIdentify) || same(characteristic, mBlock))) return;
			if (status == BluetoothGatt.GATT_SUCCESS) return;
			if (!actor.awaitBond(this, status)) failed(status);
		}

		@Override
		boolean onNotification(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
			if (same(characteristic, mControl)) onControlPoint(value);
			else if (same(characteristic, mIdentify)) onIdentify(value);
			else return same(characteristic, mBlock);
			return true;
		}

		private void onControlPoint(@NonNull byte[] data) {
			if (data.length == 0) return;
			switch (data[0]) {
				case CMD_GET_BLOCK_SIZE -> {
					if (data.length < 3) {
						failed(BleError.REFUSED);
						return;
					}
					mBlockSize = (data[1] & 0xFF) | (data[2] & 0xFF) << 8;
					// The identify and each block go in a single write (ATT header: 3 bytes)
					int mtu = mActor.mtu();
					if (mtu - 3 < IDENTIFY_LEN || mBlockSize <= BLOCK_NUMBER_LEN || mBlockSize > mtu - 3) {
						BleLog.d("TI OAD: block size " + mBlockSize + " for MTU " + mtu);
						failed(BleError.NOT_SUPPORTED);
						return;
					}
					int dataSize = mBlockSize - BLOCK_NUMBER_LEN;
					mBlockCount = (mImage.length + dataSize - 1) / dataSize;
					write(mIdentify, identify(mImage));
				}
				case CMD_START -> {
					if (data.length > 1 && data[1] != STATUS_SUCCESS) failed(data[1] & 0xFF);
				}
				case RSP_BLOCK_REQUEST -> {
					if (data.length > 1 && data[1] == STATUS_DOWNLOAD_COMPLETE) {
						mTransferring = false;
						command(CMD_ENABLE_IMAGE);
					} else if (data.length > 5 && data[1] == STATUS_SUCCESS) {
						sendBlock(le32(data, 2));
					} else {
						failed(data.length > 1 ? data[1] & 0xFF : BleError.REFUSED);
					}
				}
				case CMD_ENABLE_IMAGE -> {
					if (data.length > 1 && data[1] == STATUS_SUCCESS) {
						if (done()) mDispatcher.post(mCallback::onOtaSuccess);
						mActor.finish(this);
					} else {
						failed(data.length > 1 ? data[1] & 0xFF : BleError.REFUSED);
					}
				}
				default -> {}
			}
		}

		private void onIdentify(@NonNull byte[] data) {
			if (data.length > 0 && data[0] == STATUS_SUCCESS) {
				mTransferring = true;
				mProgress.start();
				command(CMD_START);
			} else {
				BleLog.d("TI OAD: image refused by the device");
				failed(BleError.INVALID_IMAGE);
			}
		}

		/**
		 * Image identify, from the header: the device checks it can take the image before any block
		 */
		@NonNull
		static byte[] identify(@NonNull byte[] image) {
			byte[] packet = new byte[IDENTIFY_LEN];
			// Image id
			System.arraycopy(image, 0, packet, 0, 8);
			// BIM version, header version
			packet[8] = image[12];
			packet[9] = image[13];
			// Copy status, CRC status, image type, image number
			System.arraycopy(image, 16, packet, 10, 4);
			// Image length
			System.arraycopy(image, 24, packet, 14, 4);
			// Software version
			System.arraycopy(image, 32, packet, 18, 4);
			return packet;
		}

		/**
		 * The block the device asked for: its number, then its share of the image, header included
		 */
		private void sendBlock(long block) {
			if (!mTransferring || block < 0 || block >= mBlockCount) {
				failed(BleError.REFUSED);
				return;
			}
			int dataSize = mBlockSize - BLOCK_NUMBER_LEN;
			int offset = (int) block * dataSize;
			int length = Math.min(dataSize, mImage.length - offset);
			byte[] packet = new byte[BLOCK_NUMBER_LEN + length];
			for (int i = 0; i < BLOCK_NUMBER_LEN; i++) packet[i] = (byte) (block >> (8 * i));
			System.arraycopy(mImage, offset, packet, BLOCK_NUMBER_LEN, length);
			write(mBlock, packet);
			mProgress.update(offset + length, mImage.length, (int) block + 1, mBlockCount);
		}

		@Override
		boolean onTimeout() {
			// The device stopped asking for blocks: not a request lost by the stack
			fail(BleError.TIMEOUT);
			return false;
		}

		private void failed(int status) {
			if (KnBle.DEBUG) BleLog.d("TI OAD failed: " + BleError.name(status));
			fail(status);
			mActor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onOtaFailed(error));
		}
	}

	/**
	 * Link parameters for a fast transfer, in this order: priority HIGH, PHY 2M (1M kept when refused), MTU 517. The
	 * callback always comes, with the values in use.
	 */
	static final class FastTransfer extends GattOp {
		private static final int MTU = 517;
		// Some stacks never answer a PHY or MTU request
		private static final long STEP_TIMEOUT_MS = 3000;
		private static final int STEP_PHY = 1, STEP_MTU = 2, STEP_DONE = 3;
		private final BleFastTransferCallback mCallback;
		private final Runnable mNextStep = this::nextStep;
		private DeviceActor mActor;
		private int mStep;

		FastTransfer(@NonNull Dispatcher d, @NonNull BleFastTransferCallback callback) {
			super(d);
			mCallback = callback;
		}

		@Override
		long timeoutMs(long defaultMs) {
			return 2 * STEP_TIMEOUT_MS + 1000;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			try {
				gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
			} catch (RuntimeException e) {
				BleLog.w("requestConnectionPriority", e);
			}
			mStep = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 0 : STEP_PHY;
			nextStep();
		}

		@SuppressLint("NewApi")
		private void nextStep() {
			mActor.cancelDelayed(this);
			mStep++;
			if (mStep >= STEP_DONE) {
				fail(BleError.CANCELLED);
				mActor.finish(this);
				return;
			}
			// The step ends with its answer or this timeout
			mActor.postDelayed(mNextStep, this, STEP_TIMEOUT_MS);
			if (mStep == STEP_PHY) {
				mActor.issue(this, g -> {
					g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED);
					return DeviceActor.CALL_OK;
				});
			} else {
				mActor.issue(this, g -> g.requestMtu(MTU) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
			}
		}

		@Override
		void onPhy(@NonNull DeviceActor actor, int txPhy, int rxPhy, int status, boolean read) {
			if (mStep == STEP_PHY && !read && answer(true)) nextStep();
		}

		@Override
		void onMtuChanged(@NonNull DeviceActor actor, int mtu, int status) {
			if (mStep == STEP_MTU && answer(true)) nextStep();
		}

		@Override
		boolean onTimeout() {
			fail(BleError.TIMEOUT);
			return false;
		}

		/**
		 * Every end (the last step, a refusal, a timeout) gives the values in use
		 */
		@Override
		void onFailure(int error) {
			DeviceActor a = mActor;
			int mtu = a != null ? a.mtu() : 23;
			int tx = a != null ? a.txPhy() : BluetoothDevice.PHY_LE_1M, rx = a != null ? a.rxPhy() : BluetoothDevice.PHY_LE_1M;
			mDispatcher.post(() -> mCallback.onFastTransfer(mtu, tx, rx));
		}
	}

	static final class EnableNotify extends GattOp {
		private final CharTarget mTarget;
		private final BleNotifyCallback mCallback;
		private DeviceActor mActor;
		private BluetoothGattCharacteristic mChr;
		private BluetoothGattDescriptor mCccd;
		// Subscription of the app before this one (a subscription again with another callback): kept when this one fails
		@Nullable
		private BleNotifyCallback mPrevious;

		EnableNotify(@NonNull Dispatcher d, @NonNull CharTarget target, @NonNull BleNotifyCallback callback) {
			super(d);
			mTarget = target;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			mChr = mTarget.resolve(gatt);
			byte[] value = mChr == null ? null : subscribeValue(mChr);
			mCccd = mChr == null ? null : mChr.getDescriptor(CCCD);

			if (mChr == null || value == null || mCccd == null) {
				fail(mChr == null || mCccd == null ? BleError.NOT_FOUND : BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			if (!gatt.setCharacteristicNotification(mChr, true)) {
				fail(BleError.REFUSED);
				actor.finish(this);
				return;
			}

			// Registered first: a notification may come before the answer to the descriptor write
			mPrevious = actor.subscription(mChr);
			actor.subscribe(mChr, mCallback);
			actor.issue(this, g -> writeDescriptor(g, mCccd, value));
		}

		@Override
		void onDescriptorWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, int status) {
			if (!answer(same(descriptor, mCccd))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
			} else if (done()) mDispatcher.post(mCallback::onNotifyEnabled);
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			BleNotifyCallback previous = mPrevious;
			if (mActor != null && mChr != null && previous != null && previous != NOTIFY_SINK) {
				// Subscribed before: the device still notifies, the previous callback keeps getting them
				mActor.subscribe(mChr, previous);
			} else if (mActor != null && mChr != null && mActor.unsubscribe(mChr) != null) {
				BluetoothGatt gatt = mActor.gatt();
				try {
					if (gatt != null) gatt.setCharacteristicNotification(mChr, false);
				} catch (RuntimeException e) {
					BleLog.w("setCharacteristicNotification", e);
				}
			}
			mDispatcher.post(() -> mCallback.onNotifyFailed(error));
		}
	}

	static final class DisableNotify extends GattOp {
		private final CharTarget mTarget;
		private BluetoothGattDescriptor mCccd;
		@Nullable
		private BleNotifyCallback mCallback;

		DisableNotify(@NonNull Dispatcher d, @NonNull CharTarget target) {
			super(d);
			mTarget = target;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			BluetoothGattCharacteristic chr = mTarget.resolve(gatt);
			mCallback = chr == null ? null : actor.unsubscribe(chr);
			// Not subscribed: nothing to do
			if (chr == null || mCallback == null) {
				done();
				actor.finish(this);
				return;
			}

			gatt.setCharacteristicNotification(chr, false);
			mCccd = chr.getDescriptor(CCCD);
			if (mCccd == null) {
				fail(BleError.NOT_FOUND);
				actor.finish(this);
				return;
			}
			actor.issue(this, g -> writeDescriptor(g, mCccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
		}

		@Override
		void onDescriptorWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, int status) {
			if (!answer(same(descriptor, mCccd))) return;
			// Unsubscribed on the phone side whatever the answer
			fail(status);
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			BleNotifyCallback callback = mCallback;
			if (callback != null) mDispatcher.post(callback::onNotifyDisabled);
		}
	}

	/**
	 * Descriptor of a characteristic: by UUID, or the object the app got
	 */
	public static final class DescTarget {
		final GattOp.CharTarget characteristic;
		@Nullable
		final UUID descriptorUuid;
		@Nullable
		final BluetoothGattDescriptor descriptor;

		DescTarget(@NonNull GattOp.CharTarget characteristic, @NonNull UUID descriptorUuid) {
			this.characteristic = characteristic;
			this.descriptorUuid = descriptorUuid;
			this.descriptor = null;
		}

		DescTarget(@NonNull GattOp.CharTarget characteristic, @NonNull BluetoothGattDescriptor descriptor) {
			this.characteristic = characteristic;
			this.descriptorUuid = null;
			this.descriptor = descriptor;
		}

		@Nullable
		BluetoothGattDescriptor resolve(@NonNull BluetoothGatt gatt) {
			BluetoothGattCharacteristic chr = characteristic.resolve(gatt);
			if (chr == null) return null;
			return chr.getDescriptor(descriptorUuid != null ? descriptorUuid : descriptor.getUuid());
		}
	}

	static final class ReadDesc extends GattOp {
		private final DescTarget mTarget;
		private final BleReadCallback mCallback;
		private BluetoothGattDescriptor mDesc;

		ReadDesc(@NonNull Dispatcher d, @NonNull DescTarget target, @NonNull BleReadCallback callback) {
			super(d);
			mTarget = target;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mDesc = mTarget.resolve(gatt);
			if (mDesc == null) {
				fail(BleError.NOT_FOUND);
				actor.finish(this);
				return;
			}
			actor.issue(this, g -> g.readDescriptor(mDesc) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
		}

		@Override
		void onDescriptorRead(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, @NonNull byte[] value, int status) {
			if (!answer(same(descriptor, mDesc))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
			} else if (done()) mDispatcher.post(() -> mCallback.onReadSuccess(value));
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onReadFailed(error));
		}
	}

	static final class WriteDesc extends GattOp {
		private final DescTarget mTarget;
		private final byte[] mData;
		private final BleWriteCallback mCallback;
		private BluetoothGattDescriptor mDesc;

		WriteDesc(@NonNull Dispatcher d, @NonNull DescTarget target, @NonNull byte[] data, @NonNull BleWriteCallback callback) {
			super(d);
			mTarget = target;
			mData = data;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mDesc = mTarget.resolve(gatt);
			if (mDesc == null) {
				fail(BleError.NOT_FOUND);
				actor.finish(this);
				return;
			}
			actor.issue(this, g -> writeDescriptor(g, mDesc, mData));
		}

		@Override
		void onDescriptorWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, int status) {
			if (!answer(same(descriptor, mDesc))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
			} else if (done()) mDispatcher.post(mCallback::onWriteSuccess);
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onWriteFailed(error));
		}
	}

	/**
	 * The callback always comes, with the MTU in use: also when the request is refused or never answered
	 */
	static final class RequestMtu extends GattOp {
		private final int mMtu;
		@Nullable
		private final BleMtuChangedCallback mCallback;
		private DeviceActor mActor;

		RequestMtu(@NonNull Dispatcher d, int mtu, @Nullable BleMtuChangedCallback callback) {
			super(d);
			mMtu = mtu;
			mCallback = callback;
		}

		@Override
		long timeoutMs(long defaultMs) {
			return Math.min(defaultMs, 5000);
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			actor.issue(this, g -> g.requestMtu(mMtu) ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
		}

		@Override
		void onMtuChanged(@NonNull DeviceActor actor, int mtu, int status) {
			// Also an exchange asked by the device, or the late answer of a previous request: not this one before its call
			if (!answer(true)) return;
			fail(status);
			actor.finish(this);
		}

		@Override
		boolean onTimeout() {
			fail(BleError.TIMEOUT);
			// Some stacks never answer an MTU request: not a sign of a dead connection
			return false;
		}

		@Override
		void onFailure(int error) {
			BleMtuChangedCallback callback = mCallback;
			int mtu = mActor != null ? mActor.mtu() : 23;
			if (callback != null) mDispatcher.post(() -> callback.onMtuChanged(mtu));
		}
	}

	/**
	 * Preferred PHY (Android 8+): the callback gives the PHY really in use, the stack gives the requested one on a refusal
	 */
	static final class SetPhy extends GattOp {
		private final int mTxPhy, mRxPhy, mOptions;
		@Nullable
		private final BlePhyValueCallback mCallback;
		private DeviceActor mActor;

		SetPhy(@NonNull Dispatcher d, int txPhy, int rxPhy, int options, @Nullable BlePhyValueCallback callback) {
			super(d);
			mTxPhy = txPhy;
			mRxPhy = rxPhy;
			mOptions = options;
			mCallback = callback;
		}

		@Override
		long timeoutMs(long defaultMs) {
			return Math.min(defaultMs, 5000);
		}

		@Override
		@SuppressLint("NewApi")
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				fail(BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			actor.issue(this, g -> {
				g.setPreferredPhy(mTxPhy, mRxPhy, mOptions);
				return DeviceActor.CALL_OK;
			});
		}

		@Override
		void onPhy(@NonNull DeviceActor actor, int txPhy, int rxPhy, int status, boolean read) {
			if (read || !answer(true)) return;
			fail(status);
			actor.finish(this);
		}

		@Override
		boolean onTimeout() {
			fail(BleError.TIMEOUT);
			return false;
		}

		@Override
		void onFailure(int error) {
			BlePhyValueCallback callback = mCallback;
			if (callback == null || mActor == null) return;
			int tx = mActor.txPhy(), rx = mActor.rxPhy();
			mDispatcher.post(() -> callback.onPhyValue(tx, rx));
		}
	}

	static final class ReadPhy extends GattOp {
		private final BlePhyValueCallback mCallback;
		private DeviceActor mActor;

		ReadPhy(@NonNull Dispatcher d, @NonNull BlePhyValueCallback callback) {
			super(d);
			mCallback = callback;
		}

		@Override
		long timeoutMs(long defaultMs) {
			return Math.min(defaultMs, 5000);
		}

		@Override
		@SuppressLint("NewApi")
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				fail(BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			actor.issue(this, g -> {
				g.readPhy();
				return DeviceActor.CALL_OK;
			});
		}

		@Override
		void onPhy(@NonNull DeviceActor actor, int txPhy, int rxPhy, int status, boolean read) {
			if (!read || !answer(true)) return;
			fail(status);
			actor.finish(this);
		}

		@Override
		boolean onTimeout() {
			fail(BleError.TIMEOUT);
			return false;
		}

		@Override
		void onFailure(int error) {
			if (mActor == null) return;
			int tx = mActor.txPhy(), rx = mActor.rxPhy();
			mDispatcher.post(() -> mCallback.onPhyValue(tx, rx));
		}
	}

	static final class ReadRssi extends GattOp {
		private final BleReadRssiCallback mCallback;

		ReadRssi(@NonNull Dispatcher d, @NonNull BleReadRssiCallback callback) {
			super(d);
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			actor.issue(this, g -> g.readRemoteRssi() ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
		}

		@Override
		void onRssi(@NonNull DeviceActor actor, int rssi, int status) {
			if (!answer(true)) return;
			if (status != BluetoothGatt.GATT_SUCCESS) fail(status);
			else if (done()) mDispatcher.post(() -> mCallback.onRssi(rssi));
			actor.finish(this);
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onRssiFailed(error));
		}
	}

	/**
	 * A write answered by a notification: the first notification of the answer characteristic once the write is issued
	 * is the response (taken here, not given to the subscription of the app). Subscribes first when the app did not.
	 */
	static final class Request extends GattOp {
		private final CharTarget mWriteTarget, mNotifyTarget;
		private final byte[] mData;
		private final BleRequestCallback mCallback;
		private BluetoothGattCharacteristic mWriteChr, mNotifyChr;
		private BluetoothGattDescriptor mCccd;
		@Nullable
		private byte[] mResponse;
		private boolean mSubscribing, mWriting, mWritten;

		Request(@NonNull Dispatcher d, @NonNull CharTarget write, @NonNull CharTarget notify, @NonNull byte[] data, @NonNull BleRequestCallback callback) {
			super(d);
			mWriteTarget = write;
			mNotifyTarget = notify;
			mData = data;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mResponse = null;
			mSubscribing = mWriting = mWritten = false;
			mWriteChr = mWriteTarget.resolve(gatt);
			mNotifyChr = mNotifyTarget.resolve(gatt);
			if (mWriteChr == null || mNotifyChr == null) {
				fail(BleError.NOT_FOUND);
				actor.finish(this);
				return;
			}
			byte[] value = subscribeValue(mNotifyChr);
			if (!canWrite(mWriteChr.getProperties()) || value == null) {
				fail(BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			if (actor.isSubscribed(mNotifyChr)) {
				write(actor);
				return;
			}

			mCccd = mNotifyChr.getDescriptor(CCCD);
			if (mCccd == null || !gatt.setCharacteristicNotification(mNotifyChr, true)) {
				fail(mCccd == null ? BleError.NOT_FOUND : BleError.REFUSED);
				actor.finish(this);
				return;
			}
			mSubscribing = true;
			actor.issue(this, g -> writeDescriptor(g, mCccd, value));
		}

		private void write(@NonNull DeviceActor actor) {
			mSubscribing = false;
			mWriting = true;
			int type = writeType(mWriteChr.getProperties(), false);
			actor.issue(this, g -> writeCharacteristic(g, mWriteChr, mData, type));
		}

		@Override
		void onDescriptorWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattDescriptor descriptor, int status) {
			if (!mSubscribing || !answer(same(descriptor, mCccd))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
				actor.finish(this);
				return;
			}
			actor.subscribe(mNotifyChr, NOTIFY_SINK);
			refreshDeadline();
			write(actor);
		}

		@Override
		void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			if (!mWriting || !answer(same(characteristic, mWriteChr))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
				actor.finish(this);
				return;
			}
			mWritten = true;
			if (mResponse != null) respond(actor);
		}

		@Override
		boolean onNotification(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
			// The device may notify before it answers the write
			if (!mWriting || mResponse != null || !same(characteristic, mNotifyChr)) return false;
			mResponse = value;
			if (mWritten) respond(actor);
			return true;
		}

		private void respond(@NonNull DeviceActor actor) {
			byte[] response = mResponse;
			if (response != null && done()) mDispatcher.post(() -> mCallback.onResponse(response));
			actor.finish(this);
		}

		@Override
		boolean onTimeout() {
			fail(BleError.TIMEOUT);
			// Written but not answered: the device did not reply (an unknown command...), not a request lost by the stack
			return !mWritten;
		}

		@Override
		void onFailure(int error) {
			mDispatcher.post(() -> mCallback.onRequestFailed(error));
		}
	}

	/**
	 * Reliable write (prepared writes queued by the device, applied together on execute): begin, each write once the
	 * previous one is acknowledged, execute. Aborted on a failure. Each value fits in one Prepare Write (MTU - 5 bytes).
	 */
	static final class ReliableWrite extends GattOp {
		private final List<CharTarget> mTargets;
		private final List<byte[]> mValues;
		private final BleWriteCallback mCallback;
		@Nullable
		private DeviceActor mActor;
		private BluetoothGattCharacteristic mChr;
		private int mIndex;
		private boolean mExecuting, mBegun;

		ReliableWrite(@NonNull Dispatcher d, @NonNull List<CharTarget> targets, @NonNull List<byte[]> values, @NonNull BleWriteCallback callback) {
			super(d);
			mTargets = targets;
			mValues = values;
			mCallback = callback;
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			mActor = actor;
			mIndex = 0;
			mExecuting = false;
			if (mTargets.isEmpty()) {
				if (done()) mDispatcher.post(mCallback::onWriteSuccess);
				actor.finish(this);
				return;
			}
			// Each value goes in one Prepare Write (5 bytes of header): a longer one would be cut by the stack, then applied
			for (byte[] value : mValues) {
				if (value.length > actor.mtu() - 5) {
					fail(BleError.DATA_TOO_LARGE);
					actor.finish(this);
					return;
				}
			}
			if (!gatt.beginReliableWrite()) {
				fail(BleError.REFUSED);
				actor.finish(this);
				return;
			}
			mBegun = true;
			writeNext(actor, gatt);
		}

		@Override
		void replay(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			abort();
			start(actor, gatt);
		}

		private void writeNext(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			if (mIndex == mTargets.size()) {
				mExecuting = true;
				actor.issue(this, g -> g.executeReliableWrite() ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
				return;
			}
			mChr = mTargets.get(mIndex).resolve(gatt);
			if (mChr == null || (mChr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
				fail(mChr == null ? BleError.NOT_FOUND : BleError.NOT_SUPPORTED);
				actor.finish(this);
				return;
			}
			byte[] value = mValues.get(mIndex);
			actor.issue(this, g -> writeCharacteristic(g, mChr, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT));
		}

		@Override
		void onCharacteristicWrite(@NonNull DeviceActor actor, @NonNull BluetoothGattCharacteristic characteristic, int status) {
			if (mExecuting || !answer(same(characteristic, mChr))) return;
			if (status != BluetoothGatt.GATT_SUCCESS) {
				if (actor.awaitBond(this, status)) return;
				fail(status);
				actor.finish(this);
				return;
			}
			BluetoothGatt gatt = actor.gatt();
			if (gatt == null) return;
			mIndex++;
			refreshDeadline();
			writeNext(actor, gatt);
		}

		@Override
		void onReliableWriteCompleted(@NonNull DeviceActor actor, int status) {
			if (!mExecuting || !answer(true)) return;
			mBegun = false;
			if (status != BluetoothGatt.GATT_SUCCESS) fail(status);
			else if (done()) mDispatcher.post(mCallback::onWriteSuccess);
			actor.finish(this);
		}

		/**
		 * The prepared writes are dropped by the device (abort sent by the stack)
		 */
		private void abort() {
			BluetoothGatt gatt = mActor == null ? null : mActor.gatt();
			if (!mBegun || gatt == null) return;
			mBegun = false;
			try {
				gatt.abortReliableWrite();
			} catch (RuntimeException e) {
				BleLog.w("abortReliableWrite", e);
			}
		}

		@Override
		void onFailure(int error) {
			abort();
			mDispatcher.post(() -> mCallback.onWriteFailed(error));
		}
	}

	/**
	 * New discovery after a Service Changed indication, between two operations
	 */
	static final class Rediscover extends GattOp {
		Rediscover(@NonNull Dispatcher d) {
			super(d);
		}

		@Override
		void start(@NonNull DeviceActor actor, @NonNull BluetoothGatt gatt) {
			actor.issue(this, g -> g.discoverServices() ? DeviceActor.CALL_OK : DeviceActor.CALL_BUSY);
		}

		@Override
		void onFailure(int error) {
			BleLog.d("rediscovery failed: " + BleError.name(error));
		}
	}
}
