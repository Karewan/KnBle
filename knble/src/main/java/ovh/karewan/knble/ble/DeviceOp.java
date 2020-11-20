/*
	KnBle

	Released under the MIT License (MIT)

	Copyright (c) 2019-2020 Florent VIALATTE

	Permission is hereby granted, free of charge, to any person obtaining a copy
	of this software and associated documentation files (the "Software"), to deal
	in the Software without restriction, including without limitation the rights
	to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
	copies of the Software, and to permit persons to whom the Software is
	furnished to do so, subject to the following conditions:

	The above copyright notice and this permission notice shall be included in
	all copies or substantial portions of the Software.

	THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
	IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
	AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
	LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
	OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
	THE SOFTWARE.
 */
package ovh.karewan.knble.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
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
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.utils.Utils;

@SuppressWarnings("MissingPermission")
public class DeviceOp {
	private static final String LOG = "KnBle##DeviceOp";

	// Always use main thread with BluetoothGatt to avoid issues
	private final Handler mMainHandler = new Handler(Looper.getMainLooper());
	private final BleDevice mDevice;

	private BleGattCallback mCallback;

	private int mState = BleGattCallback.DISCONNECTED;
	private BluetoothGatt mBluetoothGatt;
	private int mLastGattStatus = 0;

	private boolean mLastWriteSuccess = false;
	private BleWriteCallback mWriteCallback;
	private BluetoothGattCharacteristic mWriteCharacteristic;
	private Queue<byte[]> mWriteQueue;
	private int mWriteTotalPkg;
	private boolean mWriteAfterSuccess;
	private long mWriteInterval;
	private int mMtu = 23;

	private BleReadCallback mReadCallback;
	private BluetoothGattCharacteristic mReadCharacteristic;


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
					mMainHandler.postDelayed(mBluetoothGatt::discoverServices, 250);
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

			mMainHandler.post(() -> {
				if(mCallback != null) mCallback.onConnectSuccess(gatt.getServices());
			});
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onCharacteristicWrite characteristic=" + characteristic.getUuid().toString() + " status=" + status);
			super.onCharacteristicWrite(gatt, characteristic, status);
			setLastGattStatus(status);

			// Run on the main thread
			mMainHandler.post(() -> {
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

			// Run on the main thread
			mMainHandler.post(() -> {
				// If success
				if(status == BluetoothGatt.GATT_SUCCESS) {
					if(mReadCallback != null) mReadCallback.onReadSuccess(characteristic.getValue());
				} else {
					if(mReadCallback != null) mReadCallback.onReadFailed();
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
		}

		@Override
		public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onReliableWriteCompleted status=" + status);
			super.onReliableWriteCompleted(gatt, status);
		}

		@Override
		public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onDescriptorRead descriptor=" + descriptor.getUuid().toString() + " status=" + status);
			super.onDescriptorRead(gatt, descriptor, status);
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onDescriptorWrite descriptor=" + descriptor.getUuid().toString() + " status=" + status);
			super.onDescriptorWrite(gatt, descriptor, status);
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onReadRemoteRssi rssi=" + rssi + " status=" + status);
			super.onReadRemoteRssi(gatt, rssi, status);
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onMtuChanged mtu=" + mtu + " status=" + status);
			super.onMtuChanged(gatt, mtu, status);
			setMtu(mtu);
		}

		@Override
		public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onPhyUpdate txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
			super.onPhyUpdate(gatt, txPhy, rxPhy, status);
		}

		@Override
		public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			if(KnBle.DEBUG) Log.d(LOG, "onPhyRead txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);
			super.onPhyRead(gatt, txPhy, rxPhy, status);
		}
	};

	/**
	 * Connect to the device
	 * @param callback The callback
	 */
	public void connect(@NonNull BleGattCallback callback) {
		if(KnBle.DEBUG) Log.d(LOG, "connect");

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
			if(!KnBle.getInstance().isBluetoothEnabled()) {
				if(KnBle.DEBUG) Log.d(LOG, "bluetooth is disabled");
				KnBle.getInstance().enableBluetooth(true);
				delayBeforeConnect += 5000;
			}

			// Set state connecting
			setState(BleGattCallback.CONNECTING);
			callback.onConnecting();

			// Connecting after the delay
			mMainHandler.postDelayed(() -> {
				setBluetoothGatt(null);

				// Always connect with autoConnect==false for better connection speed
				// Android 6+ (Connect with TRANSPORT_LE)
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					setBluetoothGatt(mDevice.getDevice().connectGatt(KnBle.getInstance().getContext(), false, mBluetoothGattCallback, BluetoothDevice.TRANSPORT_LE));
				} else {
					// Android 5.0+ (Connect with reflection method for getting TRANSPORT_LE before Android 6.0)
					try {
						Method connectGattMethod = mDevice.getDevice().getClass().getDeclaredMethod("connectGatt", Context.class, boolean.class, BluetoothGattCallback.class, int.class);
						connectGattMethod.setAccessible(true);
						setBluetoothGatt((BluetoothGatt) connectGattMethod.invoke(mDevice.getDevice(), KnBle.getInstance().getContext(), false, mBluetoothGattCallback, 2));
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				// If other methods have failed
				if(mBluetoothGatt == null) setBluetoothGatt(mDevice.getDevice().connectGatt(KnBle.getInstance().getContext(), false, mBluetoothGattCallback));

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

		// Run on the main thread
		mMainHandler.post(() -> {
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

		// Run on the main thread
		mMainHandler.post(() -> {
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

		// Run on the main thread
		mMainHandler.post(() -> {
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
				if(KnBle.DEBUG) Log.d(LOG, "write characteristic is null");
				callback.onWriteFailed();
				return;
			}

			// Set write type
			if(!sendNextWhenLastSuccess && (mWriteCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0) mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			else mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

			// Split the data and create the queue
			setWriteQueue(Utils.splitBytesArray(data, split, spliteSize));

			synchronized (DeviceOp.class) {
				mWriteTotalPkg = mWriteQueue.size();
				mWriteAfterSuccess = sendNextWhenLastSuccess;
				mWriteInterval = intervalBetweenTwoPackage;
			}

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
			if(mLastWriteSuccess) mWriteCallback.onWriteSuccess();
			else if(KnBle.DEBUG) Log.d(LOG, "private write lastWriteSuccess==false");

			// Cleanup
			setWriteCharacteristic(null);
			setWriteQueue(null);
			setWriteCallback(null);
			return;
		}

		// Check if gatt is not null
		if(mBluetoothGatt == null) {
			if(KnBle.DEBUG) Log.d(LOG, "private write mBluetoothGatt is null");
			if(mWriteCallback != null) mWriteCallback.onWriteFailed();
			return;
		}

		// Set value to the Characteristic
		byte[] data = mWriteQueue.poll();
		mWriteCharacteristic.setValue(data);

		// Write
		mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);

		// If no wait success
		if(!mWriteAfterSuccess) {
			mWriteCallback.onWriteProgress(mWriteTotalPkg-mWriteQueue.size(), mWriteTotalPkg);
			mMainHandler.postDelayed(this::write, mWriteInterval);
		}
	}

	/**
	 * Request connection priority
	 * @param connectionPriority priority
	 */
	public void requestConnectionPriority(int connectionPriority) {
		if(KnBle.DEBUG) Log.d(LOG, "requestConnectionPriority connectionPriority=" + connectionPriority);
		if(!isConnected()) return;

		mMainHandler.post(() -> {
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

		mMainHandler.post(() -> {
			if(mBluetoothGatt != null)  mBluetoothGatt.requestMtu(mtu);
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

		// Run on the main thread
		mMainHandler.post(() -> {
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
				if(KnBle.DEBUG) Log.d(LOG, "read characteristic is null");
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
	 * Disconnect the device
	 */

	@SuppressWarnings({"JavaReflectionMemberAccess", "rawtypes"})
	public void disconnect() {
		if(KnBle.DEBUG) Log.d(LOG, "disconnect");

		// Clear the handler
		mMainHandler.removeCallbacksAndMessages(null);

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

			// Unbond the device
			try {
				Method methodUnBond = mDevice.getDevice().getClass().getMethod("removeBond", (Class[]) null);
				methodUnBond.invoke(mDevice.getDevice(), (Object[]) null);
			} catch (Exception e) {
				e.printStackTrace();
			}

			// Callback
			if(mCallback != null) {
				mCallback.onDisconnected();
				setGattCallback(null);
			}
		});
	}

	/**
	 * Clear device cache
	 */
	@SuppressWarnings({"JavaReflectionMemberAccess"})
	private void clearDeviceCache() {
		if(KnBle.DEBUG) Log.d(LOG, "clearDeviceCache");

		mMainHandler.post(() -> {
			try {
				Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
				refresh.invoke(mBluetoothGatt);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
}
