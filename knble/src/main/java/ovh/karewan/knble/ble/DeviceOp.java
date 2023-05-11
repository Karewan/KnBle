package ovh.karewan.knble.ble;

import android.annotation.TargetApi;
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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.Queue;
import java.util.UUID;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleCheckCallback;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.utils.Utils;

@SuppressWarnings("MissingPermission")
public class DeviceOp {
	private static final String LOG = "KnBle##DeviceOp";

	// Nb max of retry
	private static final int MAX_RETRY = 80;

	// Delay between retry
	private static final int RETRY_DELAY = 60;

	// Always use main thread with connect/disconnect to avoid issues
	private final Handler mMainHandler = new Handler(Looper.getMainLooper());
	private final BleDevice mDevice;

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

	private volatile BleNotifyCallback mNotifyCallback;
	private volatile BluetoothGattCharacteristic mNotifyCharacteristic;

	private volatile BluetoothGattDescriptor mNotifyDescriptor;

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
	 * Set notify callback
	 * @param callback BleReadCallback
	 */
	private synchronized void setNotifyCallback(@Nullable BleNotifyCallback callback) {
		mNotifyCallback = callback;
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
	 * Set notify BluetoothGattCharacteristic
	 * @param characteristic read BluetoothGattCharacteristic
	 */
	private synchronized void setNotifyCharacteristic(@Nullable BluetoothGattCharacteristic characteristic) {
		mNotifyCharacteristic = characteristic;
	}

	/**
	 * Set write BluetoothGattCharacteristic
	 * @param characteristic write BluetoothGattCharacteristic
	 */
	private synchronized void setWriteCharacteristic(@Nullable BluetoothGattCharacteristic characteristic) {
		mWriteCharacteristic = characteristic;
	}

	/**
	 * Set notify BluetoothGattCharacteristic
	 * @param descriptor BluetoothGattDescriptor
	 */
	private synchronized void setNotifyDescriptor(@Nullable BluetoothGattDescriptor descriptor) {
		mNotifyDescriptor = descriptor;
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
			if(KnBle.DEBUG) Log.d(LOG, "onConnectionStateChange status=" + status + " newState=" + newState);
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
					clearDeviceCache();
					disconnect();
					break;
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onServicesDiscovered status=" + status);
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
			if(KnBle.DEBUG) Log.d(LOG, "onCharacteristicWrite characteristic=" + characteristic.getUuid().toString() + " status=" + status);
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
					// Set last write to fail
					setLastWriteSuccess(false);

					// Clear the write queue
					setWriteQueue(null);

					// Notify
					if (mWriteCallback != null) {
						mWriteCallback.onWriteFailed();
						setWriteCallback(null);
					}
				}
			});
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onCharacteristicRead characteristic=" + characteristic.getUuid().toString() + " status=" + status);
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
			if(KnBle.DEBUG) Log.d(LOG, "onCharacteristicChanged characteristic=" + characteristic.getUuid().toString());
			super.onCharacteristicChanged(gatt, characteristic);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				if(mNotifyCallback == null
						|| mNotifyCharacteristic == null
						|| !characteristic.getUuid().equals(mNotifyCharacteristic.getUuid())) return;

				mNotifyCallback.onNotify(characteristic.getValue());
			});
		}

		@Override
		public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onReliableWriteCompleted status=" + status);
			super.onReliableWriteCompleted(gatt, status);
			setLastGattStatus(status);
		}

		@Override
		public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onDescriptorRead descriptor=" + descriptor.getUuid().toString() + " status=" + status);
			super.onDescriptorRead(gatt, descriptor, status);
			setLastGattStatus(status);
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onDescriptorWrite descriptor=" + descriptor.getUuid().toString() + " status=" + status);
			super.onDescriptorWrite(gatt, descriptor, status);
			setLastGattStatus(status);

			// Run on the md thread
			if(mdHandler != null) mdHandler.post(() -> {
				if(mNotifyCallback == null) return;

				// If success
				if(status == BluetoothGatt.GATT_SUCCESS) {
					if(mNotifyCharacteristic != null) mNotifyCallback.onNotifyEnabled();
					else {
						mNotifyCallback.onNotifyDisabled();
						setNotifyCallback(null);
					}
				} else {
					mNotifyCallback.onNotifyDisabled();
					setNotifyCharacteristic(null);
					setNotifyDescriptor(null);
					setNotifyCallback(null);
				}
			});
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onReadRemoteRssi rssi=" + rssi + " status=" + status);
			super.onReadRemoteRssi(gatt, rssi, status);
			setLastGattStatus(status);
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onMtuChanged mtu=" + mtu + " status=" + status);
			super.onMtuChanged(gatt, mtu, status);
			setLastGattStatus(status);
			setMtu(mtu);
		}

		@Override
		public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onPhyUpdate txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
			super.onPhyUpdate(gatt, txPhy, rxPhy, status);
			setLastGattStatus(status);
		}

		@Override
		public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onPhyRead txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
			super.onPhyRead(gatt, txPhy, rxPhy, status);
			setLastGattStatus(status);
		}
	};

	/**
	 * Connect to the device
	 * @param callback The callback
	 */
	public void connect(@NonNull BleGattCallback callback) {
		if(KnBle.DEBUG) Log.d(LOG, "connect");

		// Init the Md Handler
		initMdHandler();

		// Run on the main thread
		mMainHandler.post(() -> {
			// Set the callback
			setGattCallback(callback);

			// Check the current state
			switch (mState) {
				case BleGattCallback.CONNECTING:
					if(KnBle.DEBUG) Log.d(LOG, "already connecting");
					callback.onConnecting();
					return;

				case BleGattCallback.CONNECTED:
					if(KnBle.DEBUG) Log.d(LOG, "already connected");
					if(mBluetoothGatt != null) {
						if(KnBle.DEBUG) Log.d(LOG, "already connected but mBluetoothGatt is null");
						callback.onConnectSuccess(mBluetoothGatt.getServices());
						return;
					}
					break;
			}

			// Delay before connect
			int delayBeforeConnect = 0;

			// Enable bluetooth if not enabled
			if(!KnBle.gi().isBluetoothEnabled()) {
				if(KnBle.DEBUG) Log.d(LOG, "bluetooth is disabled");

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
		if(KnBle.DEBUG) Log.d(LOG, "hasService serviceUUID=" + serviceUUID);

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
		if(KnBle.DEBUG) Log.d(LOG, "hasCharacteristic serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

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

		if(KnBle.DEBUG) Log.d(LOG, "write serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID
				+ " datalen=" + data.length + " spliteSize=" + spliteSize + " sendNextWhenLastSuccess=" + sendNextWhenLastSuccess + " intervalBetweenTwoPackage=" + intervalBetweenTwoPackage);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {

			// Check if is connected
			if(mBluetoothGatt == null) {
				if(KnBle.DEBUG) Log.d(LOG, "write mBluetoothGatt is null");
				callback.onWriteFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				if(KnBle.DEBUG) Log.d(LOG, "write service is null");
				callback.onWriteFailed();
				return;
			}

			// Get the characteristic
			setWriteCharacteristic(service.getCharacteristic(UUID.fromString(characteristicUUID)));
			if(mWriteCharacteristic == null || (mWriteCharacteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
				if(KnBle.DEBUG) Log.d(LOG, "write characteristic is null or write flag = 0");
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
		if(KnBle.DEBUG) Log.d(LOG, "private write");

		// Check if queue is empty
		if(mWriteQueue == null || mWriteQueue.peek() == null) {
			// If last write is a success
			if(mLastWriteSuccess && mWriteCallback != null) mWriteCallback.onWriteSuccess();
			else if(KnBle.DEBUG) Log.d(LOG, "private write lastWriteSuccess==false");

			// Cleanup
			setWriteCharacteristic(null);
			setWriteQueue(null);
			setWriteCallback(null);
			resetWriteRetry();
			return;
		}

		// Check if gatt is not null
		if(mBluetoothGatt == null) {
			if(KnBle.DEBUG) Log.d(LOG, "private write mBluetoothGatt is null");
			if(mWriteCallback != null) mWriteCallback.onWriteFailed();
			return;
		}

		// Get data part from the queue without remove
		byte[] data = mWriteQueue.peek();

		// Write type
		int writeType = mWriteAfterSuccess ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;

		// Write
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			int success = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic, data, writeType);
			if(KnBle.DEBUG) Log.d(LOG, "private writeCharacteristic=" + success);

			// Failed
			if(!mWriteAfterSuccess && success != BluetoothStatusCodes.SUCCESS) {
				if(mdHandler != null && mWriteRetry < MAX_RETRY) {
					incWriteRetry();
					mdHandler.postDelayed(this::write, RETRY_DELAY);
				} else if(mWriteCallback != null) {
					setLastWriteSuccess(false);
					setWriteQueue(null);
					mWriteCallback.onWriteFailed();
					setWriteCallback(null);
				}

				return;
			} else {
				resetWriteRetry();
			}
		} else {
			mWriteCharacteristic.setWriteType(writeType);
			boolean success = mWriteCharacteristic.setValue(data) && mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
			if(KnBle.DEBUG) Log.d(LOG, "private writeCharacteristic=" + success);

			// Failed
			if(!mWriteAfterSuccess && !success) {
				if(mdHandler != null && mWriteRetry < MAX_RETRY) {
					mdHandler.postDelayed(this::write, RETRY_DELAY);
					incWriteRetry();
				} else if(mWriteCallback != null) {
					setLastWriteSuccess(false);
					setWriteQueue(null);
					mWriteCallback.onWriteFailed();
					setWriteCallback(null);
				}

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
		if(KnBle.DEBUG) Log.d(LOG, "requestConnectionPriority connectionPriority=" + connectionPriority);
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt != null) mBluetoothGatt.requestConnectionPriority(connectionPriority);
		});
	}

	/**
	 * Request MTU
	 * @param mtu MTU
	 */
	public void requestMtu(int mtu) {
		if(KnBle.DEBUG) Log.d(LOG, "requestMtu mtu=" + mtu);
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt != null)  mBluetoothGatt.requestMtu(mtu);
		});
	}

	/**
	 *Set prefered PHY
	 * @param txPhy TX PHY
	 * @param rxPhy RX PHY
	 * @param phyOptions CODING FOR LE CODED PHY
	 */
	@TargetApi(Build.VERSION_CODES.O)
	public void setPreferredPhy(int txPhy, int rxPhy, int phyOptions) {
		if(KnBle.DEBUG) Log.d(LOG, "setPreferredPhy txPhy=" + txPhy + " rxPhy=" + rxPhy + " phyOptions=" + phyOptions);
		if(!isConnected()) return;

		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt != null)  mBluetoothGatt.setPreferredPhy(txPhy, rxPhy, phyOptions);
		});
	}

	/**
	 * Read a characteristic data
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public void read(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		if(KnBle.DEBUG) Log.d(LOG, "read serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			// Check if is connected
			if(mBluetoothGatt == null) {
				if(KnBle.DEBUG) Log.d(LOG, "read mBluetoothGatt is null");
				callback.onReadFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				if(KnBle.DEBUG) Log.d(LOG, "read service is null");
				callback.onReadFailed();
				return;
			}

			// Get the characteristic
			setReadCharacteristic(service.getCharacteristic(UUID.fromString(characteristicUUID)));
			if(mReadCharacteristic == null || (mReadCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
				if(KnBle.DEBUG) Log.d(LOG, "read characteristic is null or flag read = 0");
				callback.onReadFailed();
				return;
			}

			// Set the callback
			setReadCallback(callback);

			// Read
			mBluetoothGatt.readCharacteristic(mReadCharacteristic);
		});
	}

	/**
	 * Enable notify
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param descriptorUUID The descriptor UUID
	 * @param callback The callback
	 */
	public void enableNotify(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull String descriptorUUID, @NonNull BleNotifyCallback callback) {
		if(KnBle.DEBUG) Log.d(LOG, "enableNotify serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID + " descriptorUUID=" + descriptorUUID);

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			// Check if is connected
			if(mBluetoothGatt == null) {
				if(KnBle.DEBUG) Log.d(LOG, "enableNotify mBluetoothGatt is null");
				callback.onNotifyDisabled();
				return;
			}

			// Get the service
			BluetoothGattService service = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(service == null) {
				if(KnBle.DEBUG) Log.d(LOG, "enableNotify service is null");

				callback.onNotifyDisabled();
				return;
			}

			// Get the characteristic
			setNotifyCharacteristic(service.getCharacteristic(UUID.fromString(characteristicUUID)));
			if(mNotifyCharacteristic == null
					|| (mNotifyCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0
					|| (mNotifyCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {

				if(KnBle.DEBUG) Log.d(LOG, "enableNotify characteristic is null or flag read = 0 or flag notify = 0");
				setNotifyCharacteristic(null);
				callback.onNotifyDisabled();
				return;
			}

			// Get the descriptor
			setNotifyDescriptor(mNotifyCharacteristic.getDescriptor(UUID.fromString(descriptorUUID)));
			if(mNotifyDescriptor == null) {

				if(KnBle.DEBUG) Log.d(LOG, "enableNotify descriptor is null");
				setNotifyCharacteristic(null);
				setNotifyDescriptor(null);
				callback.onNotifyDisabled();
				return;
			}

			// Enable notification
			if(!mBluetoothGatt.setCharacteristicNotification(mNotifyCharacteristic, true)) {
				if(KnBle.DEBUG) Log.d(LOG, "enableNotify failed to enable characteristic notification");
				setNotifyCharacteristic(null);
				setNotifyDescriptor(null);
				callback.onNotifyDisabled();
				return;
			}

			// Set the callback
			setNotifyCallback(callback);

			// Write descriptor
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				int success = mBluetoothGatt.writeDescriptor(mNotifyDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
				if(KnBle.DEBUG) Log.d(LOG, "enableNotify writeDescriptor=" + success);

				if(success != BluetoothStatusCodes.SUCCESS) {
					setNotifyCharacteristic(null);
					setNotifyDescriptor(null);
					setNotifyCallback(null);
					callback.onNotifyDisabled();
				}
			} else {
				mNotifyDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
				boolean success = mBluetoothGatt.writeDescriptor(mNotifyDescriptor);
				if(KnBle.DEBUG) Log.d(LOG, "enableNotify writeDescriptor=" + success);

				if(!success) {
					setNotifyCharacteristic(null);
					setNotifyDescriptor(null);
					setNotifyCallback(null);
					callback.onNotifyDisabled();
				}
			}
		});
	}

	/**
	 * Disable notify
	 */
	public void disableNotify() {
		if(KnBle.DEBUG) Log.d(LOG, "disableNotify");

		// Run on the md thread
		if(mdHandler != null) mdHandler.post(() -> {
			if(mBluetoothGatt == null || mNotifyCharacteristic == null || mNotifyDescriptor == null) return;

			// Disable notification
			boolean stopNotif = mBluetoothGatt.setCharacteristicNotification(mNotifyCharacteristic, false);
			if(KnBle.DEBUG) Log.d(LOG, "disableNotify setCharacteristicNotification=" + stopNotif);

			// Write descriptor
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				int success = mBluetoothGatt.writeDescriptor(mNotifyDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
				if(KnBle.DEBUG) Log.d(LOG, "disableNotify writeDescriptor=" + success);
			} else {
				mNotifyDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
				boolean success = mBluetoothGatt.writeDescriptor(mNotifyDescriptor);
				if(KnBle.DEBUG) Log.d(LOG, "disableNotify writeDescriptor=" + success);
			}

			// Clear
			setNotifyCharacteristic(null);
			setNotifyDescriptor(null);
		});
	}

	/**
	 * Disconnect the device
	 */
	public void disconnect() {
		if(KnBle.DEBUG) Log.d(LOG, "disconnect");

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
			setWriteCallback(null);
			setWriteQueue(null);
			setLastWriteSuccess(false);
			resetWriteRetry();
			setReadCharacteristic(null);
			setReadCallback(null);
			setNotifyCharacteristic(null);
			setNotifyDescriptor(null);
			setNotifyCallback(null);
			setLastGattStatus(0);
			setMtu(23);
		});
	}

	/**
	 * Clear device cache
	 */
	@SuppressWarnings({"JavaReflectionMemberAccess"})
	private void clearDeviceCache() {
		if(KnBle.DEBUG) Log.d(LOG, "clearDeviceCache");

		if(mdHandler != null) mdHandler.post(() -> {
			try {
				Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
				refresh.invoke(mBluetoothGatt);
			} catch (Exception e) {
				if(KnBle.DEBUG) e.printStackTrace();
			}
		});
	}
}
