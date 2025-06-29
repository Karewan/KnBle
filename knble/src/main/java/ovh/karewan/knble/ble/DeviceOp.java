package ovh.karewan.knble.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleCheckCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleMtuChangedCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BlePhyValueCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.Utils;

@SuppressWarnings("MissingPermission")
public class DeviceOp {
	// Notify descriptor UUID
	private static final String NOTIFY_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb";

	// Nb max of retry
	private static final int MAX_RETRY = 80;

	// Delay between retry
	private static final int RETRY_DELAY = 60;

	// Always use main thread with connect/disconnect to avoid issues
	private final Handler mMainHandler = new Handler(Looper.getMainLooper());
	private volatile BleDevice mDevice;

	// Device Handler + Thread
	private volatile HandlerThread mdThread = null;
	private volatile Handler mdHandler = null;

	private volatile BleGattCallback mCallback;

	private volatile int mState = BleGattCallback.DISCONNECTED;
	private volatile BluetoothGatt mBluetoothGatt;
	private volatile int mLastGattStatus = 0;

	private volatile boolean mLastWriteSuccess = false;
	private volatile BleWriteCallback mWriteCallback;
	private volatile BluetoothGattCharacteristic mWriteCharacteristic;
	private volatile Queue<byte[]> mWriteQueue;
	private volatile int mWriteTotalPkg;
	private volatile boolean mWriteAfterSuccess;
	private volatile long mWriteInterval;
	private volatile int mWriteRetry = 0;

	private volatile int mMtu = 23;

	private volatile BleReadCallback mReadCallback;
	private volatile BluetoothGattCharacteristic mReadCharacteristic;

	private volatile BleMtuChangedCallback mMtuCallback;

	private volatile BlePhyValueCallback mBlePhyUpdateCallback;
	private volatile BlePhyValueCallback mBlePhyReadCallback;

	private final ConcurrentHashMap<String, BleNotifyCallback> mNotifyCallbacks = new ConcurrentHashMap<>();

	/**
	 * Class constructor
	 * @param device The device
	 */
	public DeviceOp(@NonNull BleDevice device) {
		this.mDevice = device;
	}

	/**
	 * Get the BLE device
	 * @return mDevice
	 */
	@NonNull
	public BleDevice getDevice() {
		return mDevice;
	}

	/**
	 * Update BleDevice
	 * @param device BleDevice
	 */
	public synchronized void setDevice(@NonNull BleDevice device) {
		this.mDevice = device;
	}

	/**
	 * Get the bluetooth gatt
	 * @return mBluetoothGatt
	 */
	@Nullable
	public BluetoothGatt getBluetoothGatt() {
		return mBluetoothGatt;
	}

	/**
	 * Set current connection state
	 * @param state state
	 */
	private synchronized void setState(int state) {
		mState = state;
	}

	/**
	 * Return the current connection state
	 * @return mState
	 */
	public int getState() {
		return mState;
	}

	/**
	 * Check if device is connected
	 * @return boolean
	 */
	public boolean isConnected() {
		return mState == BleGattCallback.CONNECTED;
	}

	/**
	 * Get the last gatt status
	 * @return mLastGattStatus
	 */
	public int getLastGattStatus() {
		return mLastGattStatus;
	}

	/**
	 * Return the current Mtu
	 * @return int
	 */
	public int getMtu() {
		return mMtu;
	}

	/**
	 * Set last gatt status
	 * @param status status
	 */
	private synchronized void setLastGattStatus(int status) {
		mLastGattStatus = status;
	}

	/**
	 * Set last write success
 	 * @param success success ?
	 */
	private synchronized void setLastWriteSuccess(boolean success) {
		mLastWriteSuccess = success;
	}

	/**
	 * Set write queue
	 */
	private synchronized void setWriteQueue(@Nullable Queue<byte[]> queue) {
		if(mWriteQueue != null) mWriteQueue.clear();
		mWriteQueue = queue;
	}

	/**
	 * Set write callback
	 * @param callback BleWriteCallback
	 */
	private synchronized void setWriteCallback(@Nullable BleWriteCallback callback) {
		mWriteCallback = callback;
	}

	/**
	 * Set read callback
	 * @param callback BleReadCallback
	 */
	private synchronized void setReadCallback(@Nullable BleReadCallback callback) {
		mReadCallback = callback;
	}

	/**
	 * Set mtu callback
	 * @param callback BleMtuChangedCallback
	 */
	private synchronized void setMtuCallback(@Nullable BleMtuChangedCallback callback) {
		mMtuCallback = callback;
	}

	/**
	 * Set phy update callback
	 * @param callback BlePhyValueCallback
	 */
	private synchronized void setPhyUpdateCallback(@Nullable BlePhyValueCallback callback) {
		mBlePhyUpdateCallback = callback;
	}

	/**
	 * Set phy read callback
	 * @param callback BlePhyValueCallback
	 */
	private synchronized void setPhyReadCallback(@Nullable BlePhyValueCallback callback) {
		mBlePhyReadCallback = callback;
	}

	/**
	 * Set the BleGattCallback
	 * @param calback BleGattCallback
	 */
	public synchronized void setGattCallback(@Nullable BleGattCallback calback) {
		mCallback = calback;
	}

	/**
	 * Set current MTU
	 * @param mtu int
	 */
	private synchronized void setMtu(int mtu) {
		mMtu = mtu;
	}

	/**
	 * Inc write retry
	 */
	private synchronized void incWriteRetry() {
		mWriteRetry++;
	}

	/**
	 * Reset write retry
	 */
	private synchronized void resetWriteRetry() {
		mWriteRetry = 0;
	}

	/**
	 * Set BluetoothGatt
	 * @param gatt BluetoothGatt
	 */
	private synchronized void setBluetoothGatt(@Nullable BluetoothGatt gatt) {
		mBluetoothGatt = gatt;
	}

	/**
	 * Set read BluetoothGattCharacteristic
	 * @param characteristic read BluetoothGattCharacteristic
	 */
	private synchronized void setReadCharacteristic(@Nullable BluetoothGattCharacteristic characteristic) {
		mReadCharacteristic = characteristic;
	}

	/**
	 * Set write BluetoothGattCharacteristic
	 * @param characteristic write BluetoothGattCharacteristic
	 */
	private synchronized void setWriteCharacteristic(@Nullable BluetoothGattCharacteristic characteristic) {
		mWriteCharacteristic = characteristic;
	}

	/**
	 * Init Md Handler
	 */
	private synchronized void initMdHandler() {
		destroyMdHandler();

		mdThread = new HandlerThread(mDevice.getMac());
		mdThread.start();

		mdHandler = new Handler(mdThread.getLooper());
	}

	/**
	 * Destroy Md Handler
	 */
	private synchronized void destroyMdHandler() {
		if(mdHandler != null) {
			mdHandler.removeCallbacksAndMessages(null);
			mdHandler = null;
		}

		if(mdThread != null) {
			mdThread.quit();
			mdThread = null;
		}
	}

	/**
	 * The Gatt callback
	 */
	private final BluetoothGattCallback mBluetoothGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			Utils.log("onConnectionStateChange status=" + status + " newState=" + newState);
			super.onConnectionStateChange(gatt, status, newState);
			setLastGattStatus(status);

			switch (newState) {
				// Connected
				case BluetoothProfile.STATE_CONNECTED:
					// Discover gatt services with 250ms of delay for slow devices
					if(mdHandler != null) mdHandler.postDelayed(mBluetoothGatt::discoverServices, 250);
					break;

				// Disconnect
				case BluetoothProfile.STATE_DISCONNECTED:
					disconnect();
					break;
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			Utils.log("onServicesDiscovered status=" + status);
			super.onServicesDiscovered(gatt, status);
			setLastGattStatus(status);

			if(mState == BleGattCallback.CONNECTED) return;
			setState(BleGattCallback.CONNECTED);

			if(mdHandler != null) mdHandler.post(() -> {
				if(mCallback != null) mCallback.onConnectSuccess(gatt.getServices());
			});
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			Utils.log("onCharacteristicWrite characteristic=" + characteristic.getUuid().toString() + " status=" + status);
			super.onCharacteristicWrite(gatt, characteristic, status);
			setLastGattStatus(status);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				// if success
				if(status == BluetoothGatt.GATT_SUCCESS) {
					// Set last write to success
					setLastWriteSuccess(true);

					// If write after success
					if(mWriteAfterSuccess) {
						// Notify progress
						if(mWriteCallback != null && mWriteQueue != null) mWriteCallback.onWriteProgress(mWriteTotalPkg-mWriteQueue.size(), mWriteTotalPkg);

						// Write next packet
						write();
					}
				} else {
					// Notify
					if (mWriteCallback != null) mWriteCallback.onWriteFailed();

					// Cleanup
					setLastWriteSuccess(false);
					setWriteCharacteristic(null);
					setWriteQueue(null);
					setWriteCallback(null);
					resetWriteRetry();
				}
			});
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			Utils.log("onCharacteristicRead characteristic=" + characteristic.getUuid().toString() + " status=" + status);
			super.onCharacteristicRead(gatt, characteristic, status);
			setLastGattStatus(status);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				if(mReadCallback != null) {
					if(status == BluetoothGatt.GATT_SUCCESS) mReadCallback.onReadSuccess(characteristic.getValue());
					else mReadCallback.onReadFailed();
				}

				// Clean
				setReadCallback(null);
				setReadCharacteristic(null);
			});
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			Utils.log("onCharacteristicChanged characteristic=" + characteristic.getUuid().toString());
			super.onCharacteristicChanged(gatt, characteristic);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				BleNotifyCallback callback = mNotifyCallbacks.get(characteristic.getUuid().toString());
				if(callback != null) callback.onNotify(characteristic.getValue());
			});
		}

		@Override
		public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
			Utils.log("onReliableWriteCompleted status=" + status);
			super.onReliableWriteCompleted(gatt, status);
			setLastGattStatus(status);
		}

		@Override
		public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			Utils.log("onDescriptorRead descriptor=" + descriptor.getUuid().toString() + " status=" + status);
			super.onDescriptorRead(gatt, descriptor, status);
			setLastGattStatus(status);
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			String characteristic = descriptor.getCharacteristic().getUuid().toString();
			Utils.log("onDescriptorWrite characteristic=" + characteristic
					+ " descriptor=" + descriptor.getUuid().toString() + " status=" + status);
			super.onDescriptorWrite(gatt, descriptor, status);
			setLastGattStatus(status);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				BleNotifyCallback callback = mNotifyCallbacks.get(characteristic);
				if(callback == null) return;

				// If success
				if(status == BluetoothGatt.GATT_SUCCESS) callback.onNotifyEnabled();
				else {
					mNotifyCallbacks.remove(characteristic);
					callback.onNotifyDisabled();
				}
			});
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
			Utils.log("onReadRemoteRssi rssi=" + rssi + " status=" + status);
			super.onReadRemoteRssi(gatt, rssi, status);
			setLastGattStatus(status);
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			Utils.log("onMtuChanged mtu=" + mtu + " status=" + status);
			super.onMtuChanged(gatt, mtu, status);
			setLastGattStatus(status);
			setMtu(mtu);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				if(mMtuCallback != null) mMtuCallback.onMtuChanged(mtu);
			});
		}

		@Override
		public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			Utils.log("onPhyUpdate txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
			super.onPhyUpdate(gatt, txPhy, rxPhy, status);
			setLastGattStatus(status);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				if(mBlePhyUpdateCallback != null) mBlePhyUpdateCallback.onPhyValue(txPhy, rxPhy);
			});
		}

		@Override
		public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			Utils.log("onPhyRead txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
			super.onPhyRead(gatt, txPhy, rxPhy, status);
			setLastGattStatus(status);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				if(mBlePhyReadCallback != null) mBlePhyReadCallback.onPhyValue(txPhy, rxPhy);
			});
		}
	};

	/**
	 * Connect to the device
	 * @param callback The callback
	 */
	public void connect(@NonNull BleGattCallback callback) {
		Utils.log("connect");

		// Init the Md Handler
		initMdHandler();

		// Run on the main thread
		mMainHandler.post(() -> {
			// Set the callback
			setGattCallback(callback);

			// Check the current state
			switch (mState) {
				case BleGattCallback.CONNECTING:
					Utils.log("already connecting => re-emit the event");
					callback.onConnecting();
					return;

				case BleGattCallback.CONNECTED:
					if(mBluetoothGatt != null) {
						Utils.log("already connected => re-emit the event");
						callback.onConnectSuccess(mBluetoothGatt.getServices());
						return;
					} else {
						Utils.log("already connected but mBluetoothGatt is null");
						disconnect();
					}
					break;
			}

			// Delay before connect
			int delayBeforeConnect = 0;

			// Enable bluetooth if not enabled
			if(!KnBle.gi().isBluetoothEnabled()) {
				Utils.log("bluetooth is disabled");

				if(!KnBle.gi().enableBluetooth(true)) {
					// Connected failed
					callback.onConnectFailed();
					disconnect();
				} else {
					// Add delay to be sure the adapter has time to init before connect
					delayBeforeConnect += 5000;
				}
			}

			// Set state connecting
			setState(BleGattCallback.CONNECTING);
			callback.onConnecting();

			// Connecting after the delay
			mMainHandler.postDelayed(() -> {
				setBluetoothGatt(null);

				// Always connect with autoConnect==false for better connection speed
				setBluetoothGatt(mDevice.getDevice().connectGatt(KnBle.gi().getContext(), false, mBluetoothGattCallback, BluetoothDevice.TRANSPORT_LE));

				// If other methods have failed
				if(mBluetoothGatt == null) setBluetoothGatt(mDevice.getDevice().connectGatt(KnBle.gi().getContext(), false, mBluetoothGattCallback));

				// All method have failed
				if(mBluetoothGatt == null) {
					callback.onConnectFailed();
					disconnect();
				} else {
					// Clear device cache
					clearDeviceCache();
				}
			}, delayBeforeConnect);
		});
	}

	/**
	 * Check if a service exist
	 * @param serviceUUID The service UUID
	 */
	public void hasService(@NonNull String serviceUUID, @NonNull BleCheckCallback callback) {
		Utils.log("hasService serviceUUID=" + serviceUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			// Check if is connected
			if(!isConnected()) {
				callback.onResponse(false);
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			callback.onResponse(service != null);
		});
	}

	/**
	 * Check if a characteristic exist
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 */
	public void hasCharacteristic(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleCheckCallback callback) {
		Utils.log("hasCharacteristic serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			// Check if is connected
			if(!isConnected()) {
				callback.onResponse(false);
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				callback.onResponse(false);
				return;
			}

			// Get the characteristic
			BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID));
			callback.onResponse(characteristic != null);
		});
	}

	/**
	 * Write
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param data The data
	 * @param split Split the data if len > 20
	 * @param spliteSize Split packet size
	 * @param sendNextWhenLastSuccess Send next pkg after success
	 * @param intervalBetweenTwoPackage Interval between pkg (sendNextWhenLastSuccess == false)
	 * @param callback The callback
	 */
	public void write(@NonNull String serviceUUID,
								   @NonNull String characteristicUUID,
								   @NonNull byte[] data,
								   boolean split,
								   int spliteSize,
								   boolean sendNextWhenLastSuccess,
								   long intervalBetweenTwoPackage,
								   @NonNull BleWriteCallback callback) {

		Utils.log("write serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID
				+ " datalen=" + data.length + " spliteSize=" + spliteSize + " sendNextWhenLastSuccess=" + sendNextWhenLastSuccess + " intervalBetweenTwoPackage=" + intervalBetweenTwoPackage);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {

			// Check if is connected
			if(mBluetoothGatt == null) {
				Utils.log("write mBluetoothGatt is null");
				callback.onWriteFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				Utils.log("write service is null");
				callback.onWriteFailed();
				return;
			}

			// Get the characteristic
			setWriteCharacteristic(service.getCharacteristic(UUID.fromString(characteristicUUID)));
			if (
				mWriteCharacteristic == null ||
				(mWriteCharacteristic.getPermissions() & BluetoothGattCharacteristic.PERMISSION_WRITE) == 0 ||
				(mWriteCharacteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0
			) {
				Utils.log("write characteristic is null or write permission = 0 or write flag = 0");
				callback.onWriteFailed();
				return;
			}

			// Force write after success if no response is not available
			boolean writeAfterSuccess = sendNextWhenLastSuccess;
			if(!writeAfterSuccess && (mWriteCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) writeAfterSuccess = true;

			// Split the data and create the queue
			setWriteQueue(Utils.splitBytesArray(data, split, spliteSize));

			// Set params
			synchronized (DeviceOp.class) {
				mWriteTotalPkg = mWriteQueue.size();
				mWriteAfterSuccess = writeAfterSuccess;
				mWriteInterval = intervalBetweenTwoPackage;
			}

			// Reset write retry
			resetWriteRetry();

			// Set callback
			setWriteCallback(callback);

			// Start write
			write();
		});
	}

	/**
	 * Write
	 */
	private void write() {
		Utils.log("private write");

		// Check if queue is empty
		if(mWriteQueue == null || mWriteQueue.peek() == null) {
			// If last write is a success
			if(mLastWriteSuccess && mWriteCallback != null) mWriteCallback.onWriteSuccess();
			else Utils.log("private write lastWriteSuccess==false");

			// Cleanup
			setLastWriteSuccess(false);
			setWriteCharacteristic(null);
			setWriteQueue(null);
			setWriteCallback(null);
			resetWriteRetry();
			return;
		}

		// Check if gatt is not null
		if(mBluetoothGatt == null) {
			Utils.log("private write mBluetoothGatt is null");
			if(mWriteCallback != null) mWriteCallback.onWriteFailed();

			// Cleanup
			setLastWriteSuccess(false);
			setWriteCharacteristic(null);
			setWriteQueue(null);
			setWriteCallback(null);
			resetWriteRetry();
			return;
		}

		// Get data part from the queue without remove
		byte[] data = mWriteQueue.peek();

		// Write type
		int writeType = mWriteAfterSuccess ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;

		// Write
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			int success = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic, data, writeType);
			Utils.log("private writeCharacteristic=" + success);

			// Failed
			if(success != BluetoothStatusCodes.SUCCESS) {
				if(mdHandler != null && mWriteRetry < MAX_RETRY) {
					incWriteRetry();
					mdHandler.postDelayed(this::write, RETRY_DELAY);
					return;
				} else if(mWriteCallback != null) {
					mWriteCallback.onWriteFailed();
				}

				// Cleanup
				setLastWriteSuccess(false);
				setWriteCharacteristic(null);
				setWriteQueue(null);
				setWriteCallback(null);
				resetWriteRetry();

				return;
			} else {
				resetWriteRetry();
			}
		} else {
			mWriteCharacteristic.setWriteType(writeType);
			boolean success = mWriteCharacteristic.setValue(data) && mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
			Utils.log("private writeCharacteristic=" + success);

			// Failed
			if(!success) {
				if(mdHandler != null && mWriteRetry < MAX_RETRY) {
					mdHandler.postDelayed(this::write, RETRY_DELAY);
					incWriteRetry();
					return;
				} else if(mWriteCallback != null) {
					mWriteCallback.onWriteFailed();
				}

				// Cleanup
				setLastWriteSuccess(false);
				setWriteCharacteristic(null);
				setWriteQueue(null);
				setWriteCallback(null);
				resetWriteRetry();

				return;
			} else {
				resetWriteRetry();
			}
		}

		// Remove data part from queue
		mWriteQueue.poll();

		// If no wait success
		if(!mWriteAfterSuccess) {
			if(mWriteCallback != null) mWriteCallback.onWriteProgress(mWriteTotalPkg-mWriteQueue.size(), mWriteTotalPkg);
			if(mdHandler != null) mdHandler.postDelayed(this::write, mWriteInterval);
		}
	}

	/**
	 * Request connection priority
	 * @param connectionPriority priority
	 */
	public void requestConnectionPriority(int connectionPriority) {
		Utils.log("requestConnectionPriority connectionPriority=" + connectionPriority);
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt != null) mBluetoothGatt.requestConnectionPriority(connectionPriority);
		});
	}

	/**
	 * Request MTU
	 * @param mtu MTU
	 * @param callback Callback
	 */
	public void requestMtu(int mtu, @Nullable BleMtuChangedCallback callback) {
		Utils.log("requestMtu mtu=" + mtu);
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt == null) return;
			setMtuCallback(callback);
			mBluetoothGatt.requestMtu(mtu);
		});
	}

	/**
	 * Set prefered PHY
	 * @param txPhy TX PHY
	 * @param rxPhy RX PHY
	 * @param phyOptions CODING FOR LE CODED PHY
	 * @param callback Callback
	 */
	@RequiresApi(Build.VERSION_CODES.O)
	public void setPreferredPhy(int txPhy, int rxPhy, int phyOptions, @Nullable BlePhyValueCallback callback) {
		Utils.log("setPreferredPhy txPhy=" + txPhy + " rxPhy=" + rxPhy + " phyOptions=" + phyOptions);
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt == null) return;
			setPhyUpdateCallback(callback);
			mBluetoothGatt.setPreferredPhy(txPhy, rxPhy, phyOptions);
		});
	}

	/**
	 * Read PHY
	 * @param callback Callback
	 */
	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	public void readPhy(@Nullable BlePhyValueCallback callback) {
		Utils.log("readPhy");
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt == null) return;
			setPhyReadCallback(callback);
			mBluetoothGatt.readPhy();
		});
	}

	/**
	 * Read a characteristic data
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public void read(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		Utils.log("read serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			// Check if is connected
			if(mBluetoothGatt == null) {
				Utils.log("read mBluetoothGatt is null");
				callback.onReadFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				Utils.log("read service is null");
				callback.onReadFailed();
				return;
			}

			// Get the characteristic
			setReadCharacteristic(service.getCharacteristic(UUID.fromString(characteristicUUID)));
			if(
				mReadCharacteristic == null ||
				(mReadCharacteristic.getPermissions() & BluetoothGattCharacteristic.PERMISSION_READ) == 0 ||
				(mReadCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0
			) {
				Utils.log("read characteristic is null or permission read = 0 or flag read = 0");
				callback.onReadFailed();
				setReadCharacteristic(null);
				return;
			}

			// Set the callback
			setReadCallback(callback);

			// Read
			if(!mBluetoothGatt.readCharacteristic(mReadCharacteristic)) {
				callback.onReadFailed();
				setReadCallback(null);
				setReadCharacteristic(null);
			}
		});
	}

	/**
	 * Enable notify
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public void enableNotify(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleNotifyCallback callback) {
		Utils.log("enableNotify serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			// Check if is connected
			if(mBluetoothGatt == null) {
				Utils.log("enableNotify mBluetoothGatt is null");
				callback.onNotifyDisabled();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				Utils.log("enableNotify service is null");
				callback.onNotifyDisabled();
				return;
			}

			// Get the characteristic
			BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID));
			if(
				characteristic == null ||
				((characteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0)
			) {
				Utils.log("enableNotify characteristic is null or flag notify and indicate = 0");
				callback.onNotifyDisabled();
				return;
			}

			// Enable notification
			if(!mBluetoothGatt.setCharacteristicNotification(characteristic, true)) {
				Utils.log("enableNotify failed to enable characteristic notification");
				callback.onNotifyDisabled();
				return;
			}

			// Get the descriptor
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(NOTIFY_DESCRIPTOR));
			if(descriptor == null) {
				Utils.log("enableNotify descriptor is null");
				callback.onNotifyDisabled();
				return;
			}

			// Set the callback
			mNotifyCallbacks.put(characteristicUUID, callback);

			// Write descriptor
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				int success = mBluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
				Utils.log("enableNotify writeDescriptor=" + success);

				if(success != BluetoothStatusCodes.SUCCESS) {
					mNotifyCallbacks.remove(characteristicUUID);
					mBluetoothGatt.setCharacteristicNotification(characteristic, false);
					callback.onNotifyDisabled();
				}
			} else {
				descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
				boolean success = mBluetoothGatt.writeDescriptor(descriptor);
				Utils.log("enableNotify writeDescriptor=" + success);

				if(!success) {
					mNotifyCallbacks.remove(characteristicUUID);
					mBluetoothGatt.setCharacteristicNotification(characteristic, false);
					callback.onNotifyDisabled();
				}
			}
		});
	}

	/**
	 * Disable notify
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 */
	public void disableNotify(@NonNull String serviceUUID, @NonNull String characteristicUUID) {
		Utils.log("disableNotify serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			BleNotifyCallback callback = mNotifyCallbacks.remove(characteristicUUID);
			if(callback == null) return;

			// Check if is connected
			if(mBluetoothGatt == null) {
				Utils.log("disableNotify mBluetoothGatt is null");
				callback.onNotifyDisabled();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				Utils.log("disableNotify service is null");
				callback.onNotifyDisabled();
				return;
			}

			// Get the characteristic
			BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID));
			if(characteristic == null) {
				Utils.log("disableNotify characteristic is null");
				callback.onNotifyDisabled();
				return;
			}

			// Disable notification
			boolean stopNotif = mBluetoothGatt.setCharacteristicNotification(characteristic, false);
			Utils.log("disableNotify setCharacteristicNotification=" + stopNotif);

			// Get the descriptor
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(NOTIFY_DESCRIPTOR));
			if(descriptor == null) {
				Utils.log("disableNotify descriptor is null");
				callback.onNotifyDisabled();
				return;
			}

			// Write descriptor
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				int success = mBluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
				Utils.log("disableNotify writeDescriptor=" + success);
			} else {
				descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
				boolean success = mBluetoothGatt.writeDescriptor(descriptor);
				Utils.log("disableNotify writeDescriptor=" + success);
			}
		});
	}

	/**
	 * Disconnect the device
	 */
	public void disconnect() {
		Utils.log("disconnect");

		// Clear the main handler
		mMainHandler.removeCallbacksAndMessages(null);

		// Destroy the Md Handler
		destroyMdHandler();

		// Run on the main thread
		mMainHandler.post(() -> {
			// Connect failed
			if(mState == BleGattCallback.CONNECTING && mCallback != null) mCallback.onConnectFailed();

			// Set state disconnected
			setState(BleGattCallback.DISCONNECTED);

			// Clear device cache
			clearDeviceCache();

			// Use close instead disconnect to avoid weird behavior (never use disconnect before close)
			if(mBluetoothGatt != null) {
				mBluetoothGatt.close();
				setBluetoothGatt(null);
			}

			// Callback
			if(mCallback != null) {
				mCallback.onDisconnected();
				setGattCallback(null);
			}

			// Clean
			setWriteCharacteristic(null);
			if(mWriteCallback != null) mWriteCallback.onWriteFailed();
			setWriteCallback(null);
			setWriteQueue(null);
			setLastWriteSuccess(false);
			resetWriteRetry();
			setReadCharacteristic(null);
			if(mReadCallback != null) mReadCallback.onReadFailed();
			setReadCallback(null);
			setMtuCallback(null);
			mNotifyCallbacks.clear();
			setLastGattStatus(0);
			setMtu(23);
		});
	}

	/**
	 * Clear device cache
	 * @noinspection CallToPrintStackTrace
	 */
	@SuppressWarnings({"JavaReflectionMemberAccess"})
	private void clearDeviceCache() {
		Utils.log("clearDeviceCache");

		if(mdHandler != null) mdHandler.post(() -> {
			try {
				if(mBluetoothGatt == null) return;
				Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
				refresh.invoke(mBluetoothGatt);
			} catch (Exception e) {
				if(KnBle.DEBUG) e.printStackTrace();
			}
		});
	}
}
