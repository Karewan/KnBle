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
	private BluetoothGatt mBluetoothGatt;
	private BleGattCallback mCallback;
	private int mState = BleGattCallback.DISCONNECTED;
	private int lastGattStatus = 0;
	private boolean lastWriteSuccess = false;

	private BleWriteCallback mWriteCallback;
	private BluetoothGattService mWriteService;
	private BluetoothGattCharacteristic mWriteCharacteristic;
	private Queue<byte[]> mWriteQueue;
	private int mWriteTotalPkg;
	private boolean mWriteAfterSuccess;
	private long mWriteInterval;

	private BleReadCallback mReadCallback;
	private BluetoothGattService mReadService;
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
	 * Get the handler
	 * @return mMainHandler
	 */
	public Handler getHandler() {
		return mMainHandler;
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
	 * @return lastGattStatus
	 */
	public int getLastGattStatus() {
		return lastGattStatus;
	}

	/**
	 * The Gatt callback
	 */
	private final BluetoothGattCallback mBluetoothGattCallback = new BluetoothGattCallback() {
		@Override
		public synchronized void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			Log.d(LOG, "onConnectionStateChange status" + status + " newState=" + newState);
			super.onConnectionStateChange(gatt, status, newState);
			lastGattStatus = status;

			switch (newState) {
				// Connected
				case BluetoothProfile.STATE_CONNECTED:
					// Discover gatt services with 250ms of delay for slow devices
					mMainHandler.postDelayed(mBluetoothGatt::discoverServices, 250);
					break;

				// Connecting
				case BluetoothProfile.STATE_CONNECTING:
					if(mState == BleGattCallback.CONNECTING) break;
					mState = BleGattCallback.CONNECTING;
					mMainHandler.post(() -> {
						if(mCallback != null) mCallback.onConnecting();
					});
					break;

				// Disconnect
				case BluetoothProfile.STATE_DISCONNECTED:
					clearDeviceCache();
					disconnect();
					break;
			}
		}

		@Override
		public synchronized void onServicesDiscovered(BluetoothGatt gatt, int status) {
			Log.d(LOG, "onServicesDiscovered status" + status);
			super.onServicesDiscovered(gatt, status);
			lastGattStatus = status;

			// Set state connected after services have been discovered
			if(mState != BleGattCallback.CONNECTED) {
				mState = BleGattCallback.CONNECTED;
				mMainHandler.post(() -> {
					if(mCallback != null) mCallback.onConnectSuccess(gatt.getServices());
				});
			}
		}

		@Override
		public synchronized void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			Log.d(LOG, "onCharacteristicWrite characteristic=" + characteristic.getUuid().toString() + " status=" + status);
			super.onCharacteristicWrite(gatt, characteristic, status);
			lastGattStatus = status;

			// Run on the main thread
			mMainHandler.post(() -> {
				// if success
				if(status == BluetoothGatt.GATT_SUCCESS) {
					// Set last write to success
					lastWriteSuccess = true;

					// If write after success
					if(mWriteAfterSuccess) {
						// Notify progress
						if(mWriteCallback != null) mWriteCallback.onWriteProgress(mWriteTotalPkg-mWriteQueue.size(), mWriteTotalPkg);
						// Write next packet
						write();
					}
				} else {
					// Set last write to fail
					lastWriteSuccess = false;

					// Clear the write queu
					if(mWriteQueue != null) mWriteQueue.clear();
					// Notify
					if(mWriteCallback != null) mWriteCallback.onWriteFailed();
				}
			});
		}

		@Override
		public synchronized void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			Log.d(LOG, "onCharacteristicRead characteristic=" + characteristic.getUuid().toString() + " status=" + status);
			super.onCharacteristicRead(gatt, characteristic, status);
			lastGattStatus = status;

			// Run on the main thread
			mMainHandler.post(() -> {
				// If success
				if(status == BluetoothGatt.GATT_SUCCESS) {
					if(mReadCallback != null) mReadCallback.onReadSuccess(characteristic.getValue());
				} else {
					if(mReadCallback != null) mReadCallback.onReadFailed();
				}

				// Clean
				mReadCallback = null;
				mReadService = null;
				mReadCharacteristic = null;
			});
		}

		/*@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			super.onCharacteristicChanged(gatt, characteristic);
		}

		@Override
		public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
			super.onReliableWriteCompleted(gatt, status);
		}

		@Override
		public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			super.onDescriptorRead(gatt, descriptor, status);
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			super.onDescriptorWrite(gatt, descriptor, status);
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
			super.onReadRemoteRssi(gatt, rssi, status);
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			super.onMtuChanged(gatt, mtu, status);
		}

		@Override
		public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			super.onPhyUpdate(gatt, txPhy, rxPhy, status);
		}

		@Override
		public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			super.onPhyRead(gatt, txPhy, rxPhy, status);
		}*/
	};

	/**
	 * Connect to the device
	 * @param callback The callback
	 */
	public synchronized void connect(@NonNull BleGattCallback callback) {
		Log.d(LOG, "connect");

		// Run on the main thread
		mMainHandler.post(() -> {
			// Set the callback
			mCallback = callback;

			// Check the current state
			switch (mState) {
				case BleGattCallback.CONNECTING:
					Log.d(LOG, "already connecting");
					callback.onConnecting();
					return;
				case BleGattCallback.CONNECTED:
					Log.d(LOG, "already connected");
					if(mBluetoothGatt != null) {
						Log.d(LOG, "already connected but mBluetoothGatt is null");
						callback.onConnectSuccess(mBluetoothGatt.getServices());
						return;
					}
					break;
			}

			// Delay before connect
			int delayBeforeConnect = 0;

			// Enable bluetooth if not enabled
			if(!KnBle.isBluetoothEnabled()) {
				Log.d(LOG, "bluetooth is disabled");
				KnBle.enableBluetooth(true);
				delayBeforeConnect += 5000;
			}

			// Set state connecting
			mState = BleGattCallback.CONNECTING;

			// Callback
			callback.onConnecting();

			// Connecting after the delay
			mMainHandler.postDelayed(() -> {
				mBluetoothGatt = null;

				// Always connect with autoConnect==false for better connection speed
				// Android 6+ (Connect with TRANSPORT_LE)
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					mBluetoothGatt = mDevice.getDevice().connectGatt(KnBle.getContext(), false, mBluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
				}
				// Android 5.0+ (Connect with reflection method for getting TRANSPORT_LE before Android 6.0)
				else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					try {
						Method connectGattMethod = mDevice.getDevice().getClass().getDeclaredMethod("connectGatt", Context.class, boolean.class, BluetoothGattCallback.class, int.class);
						connectGattMethod.setAccessible(true);
						mBluetoothGatt = (BluetoothGatt) connectGattMethod.invoke(mDevice.getDevice(), KnBle.getContext(), false, mBluetoothGattCallback, 2);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				// If Android 4.4 or If other methods have failed
				if(mBluetoothGatt == null) mBluetoothGatt =  mDevice.getDevice().connectGatt(KnBle.getContext(), false, mBluetoothGattCallback);

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
		Log.d(LOG, "hasService() serviceUUID=" + serviceUUID);

		// Run on the main thread
		mMainHandler.post(() -> {
			// Check if is connected
			if(!isConnected()) {
				callback.onResponse(false);
				return;
			}

			// Convert to uuid
			UUID uuid = UUID.fromString(serviceUUID);

			// Check each services
			for(BluetoothGattService service : mBluetoothGatt.getServices()) {
				// Check if service UUID is the same
				if(service.getUuid().equals(uuid)) {
					Log.d(LOG, "hasService() YES serviceUUID=" + serviceUUID);
					callback.onResponse(true);
					return;
				}
			}

			// Not found
			Log.d(LOG, "hasService() NO serviceUUID=" + serviceUUID);
			callback.onResponse(false);
		});
	}

	/**
	 * Check if a characteristic exist
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 */
	public void hasCharacteristic(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleCheckCallback callback) {
		Log.d(LOG, "hasCharacteristic() serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);
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
				Log.d(LOG, "hasCharacteristic() NO serviceUUID=" + serviceUUID);
				callback.onResponse(false);
				return;
			}

			// Get the characteristic
			BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID));
			if(characteristic == null) {
				Log.d(LOG, "hasCharacteristic() YES serviceUUID=" + serviceUUID + " NO characteristicUUID=" + characteristicUUID);
				callback.onResponse(false);
			} else {
				Log.d(LOG, "hasCharacteristic() YES serviceUUID=" + serviceUUID + " YES characteristicUUID=" + characteristicUUID);
				callback.onResponse(true);
			}
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
	public synchronized void write(@NonNull String serviceUUID,
								   @NonNull String characteristicUUID,
								   @NonNull byte[] data,
								   boolean split,
								   int spliteSize,
								   boolean sendNextWhenLastSuccess,
								   long intervalBetweenTwoPackage,
								   @NonNull BleWriteCallback callback) {

		Log.d(LOG, "write() serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID + " datalen=" + data.length
				+ " split=" + split + " sendNextWhenLastSuccess=" + sendNextWhenLastSuccess + " intervalBetweenTwoPackage=" + intervalBetweenTwoPackage);

		// Run on the main thread
		mMainHandler.post(() -> {
			// Check if is connected
			if(mBluetoothGatt == null) {
				Log.d(LOG, "write() mBluetoothGatt is null");
				callback.onWriteFailed();
				return;
			}

			// Get the service
			mWriteService = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(mWriteService == null) {
				Log.d(LOG, "write() service is null");
				callback.onWriteFailed();
				return;
			}

			// Get the characteristic
			mWriteCharacteristic = mWriteService.getCharacteristic(UUID.fromString(characteristicUUID));
			if(mWriteCharacteristic == null || (mWriteCharacteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
				Log.d(LOG, "write() characteristic is null");
				callback.onWriteFailed();
				return;
			}

			// Set write type
			if(!sendNextWhenLastSuccess && (mWriteCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0) mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			else mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

			// Split the data and create the queue
			mWriteQueue = Utils.splitBytesArray(data, split, spliteSize);
			mWriteTotalPkg = mWriteQueue.size();

			// Set others vars
			mWriteAfterSuccess = sendNextWhenLastSuccess;
			mWriteInterval = intervalBetweenTwoPackage;
			mWriteCallback = callback;

			// Start write
			write();
		});
	}

	/**
	 * Write
	 */
	private synchronized void write() {
		Log.d(LOG, "write()");

		// Check if queue is empty
		if(mWriteQueue.peek() == null) {
			// If last write is a success
			if(lastWriteSuccess) mWriteCallback.onWriteSuccess();
			else Log.d(LOG, "write() lastWriteSuccess==false");

			// Cleanup
			mWriteQueue = null;
			mWriteCharacteristic = null;
			mWriteService = null;
			mWriteCallback = null;
			return;
		}

		// Check if gatt is not null
		if(mBluetoothGatt == null) {
			Log.d(LOG, "write() mBluetoothGatt is null");
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
		Log.d(LOG, "requestConnectionPriority() connectionPriority=" + connectionPriority);
		if(!isConnected()) return;

		mMainHandler.post(() -> {
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mBluetoothGatt!= null) mBluetoothGatt.requestConnectionPriority(connectionPriority);
		});
	}

	/**
	 * Read a characteristic data
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback The callback
	 */
	public synchronized void read(@NonNull String serviceUUID, @NonNull String characteristicUUID, @NonNull BleReadCallback callback) {
		Log.d(LOG, "read() serviceUUID=" + serviceUUID + " characteristicUUID=" + characteristicUUID);

		// Run on the main thread
		mMainHandler.post(() -> {
			// Check if is connected
			if(mBluetoothGatt == null) {
				Log.d(LOG, "read() mBluetoothGatt is null");
				callback.onReadFailed();
				return;
			}

			// Get the service
			mReadService = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
			if(mReadService == null) {
				Log.d(LOG, "read() service is null");
				callback.onReadFailed();
				return;
			}

			// Get the characteristic
			mReadCharacteristic = mReadService.getCharacteristic(UUID.fromString(characteristicUUID));
			if(mReadCharacteristic == null || (mReadCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
				Log.d(LOG, "read() characteristic is null");
				callback.onReadFailed();
				return;
			}

			// Set the callback
			mReadCallback = callback;

			// Read
			mBluetoothGatt.readCharacteristic(mReadCharacteristic);
		});
	}

	/**
	 * Disconnect the device
	 */

	@SuppressWarnings({"JavaReflectionMemberAccess", "ConstantConditions"})
	public synchronized void disconnect() {
		Log.d(LOG, "disconnect()");

		// Clear the handler
		mMainHandler.removeCallbacksAndMessages(null);

		// Run on the main thread
		mMainHandler.post(() -> {
			// Connect failed
			if(mState == BleGattCallback.CONNECTING && mCallback != null) mCallback.onConnectFailed();

			// Set state disconnected
			mState = BleGattCallback.DISCONNECTED;

			// Use close instead disconnect to avoid weird behavior (never use disconnect before close)
			if(mBluetoothGatt != null) {
				mBluetoothGatt.close();
				mBluetoothGatt = null;
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
				mCallback = null;
			}
		});
	}

	/**
	 * Change the BleGattCallback
	 * @param calback The callback
	 */
	public void setGattCallback(@NonNull BleGattCallback calback) {
		Log.d(LOG, "setGattCallback()");

		mMainHandler.post(() -> {
			synchronized (DeviceOp.class) {
				mCallback = calback;
			}
		});
	}

	/**
	 * Clear device cache
	 */
	@SuppressWarnings({"JavaReflectionMemberAccess"})
	private void clearDeviceCache() {
		Log.d(LOG, "clearDeviceCache()");

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
