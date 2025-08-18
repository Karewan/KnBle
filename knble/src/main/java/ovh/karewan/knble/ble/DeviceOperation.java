package ovh.karewan.knble.ble;

import android.annotation.SuppressLint;
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

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.Utils;
import ovh.karewan.knble.cache.CharacteristicCache;
import ovh.karewan.knble.cache.ServiceCache;
import ovh.karewan.knble.interfaces.BleGattCallback;
import ovh.karewan.knble.interfaces.BleGetCharacteristic;
import ovh.karewan.knble.interfaces.BleGetDescriptor;
import ovh.karewan.knble.interfaces.BleGetService;
import ovh.karewan.knble.interfaces.BleMtuChangedCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BlePhyValueCallback;
import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;
import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.tasks.DisableNotifyTask;
import ovh.karewan.knble.tasks.EnableNotifyTask;
import ovh.karewan.knble.tasks.GattTask;
import ovh.karewan.knble.tasks.ReadRssiTask;
import ovh.karewan.knble.tasks.SplittedWriteCharaTask;
import ovh.karewan.knble.tasks.UpdateMtuTask;
import ovh.karewan.knble.tasks.ReadPhyTask;
import ovh.karewan.knble.tasks.UpdatePhyTask;
import ovh.karewan.knble.tasks.ReadCharaTask;
import ovh.karewan.knble.tasks.ReadDescTask;
import ovh.karewan.knble.tasks.WriteCharaTask;
import ovh.karewan.knble.tasks.WriteDescTask;

@SuppressWarnings("MissingPermission")
public class DeviceOperation {
	private final HandlerThread mHandlerThread;
	private final Handler mHandler;
	private final Handler mUiHandler = new Handler(Looper.getMainLooper());
	private final ConcurrentLinkedQueue<GattTask> mTasksQueue = new ConcurrentLinkedQueue<>();
	private final ServiceCache mServices = new ServiceCache();
	private final CharacteristicCache mCharas = new CharacteristicCache();
	private final ConcurrentHashMap<UUID, BleNotifyCallback> mNotifyCallbacks = new ConcurrentHashMap<>();
	private volatile BleDevice mDevice;
	private volatile BluetoothGatt mBluetoothGatt;
	private volatile int mState = BleGattCallback.DISCONNECTED;
	private volatile GattTask mPendingTask;
	private volatile BleGattCallback mCallback;
	private volatile int mMtu = 23;

	/**
	 * Class constructor
	 * @param device BleDevice
	 */
	public DeviceOperation(@NonNull BleDevice device) {
		mDevice = device;

		mHandlerThread = new HandlerThread("KnBle" + device.getMac());
		mHandlerThread.start();

		mHandler =  new Handler(mHandlerThread.getLooper());
	}

	/**
	 * Update the BleDevice
	 * @param device BleDevice
	 */
	public synchronized void setDevice(@NonNull BleDevice device) {
		mDevice = device;
	}

	/**
	 * Get the BleDevice
	 * @return BleDevice
	 */
	@NonNull
	public BleDevice getDevice() {
		return mDevice;
	}

	/**
	 * Set current connection state
	 * @param state int
	 */
	private synchronized void setState(int state) {
		mState = state;
	}

	/**
	 * Return the current connection state
	 * @return int
	 */
	public int getState() {
		return mState;
	}

	/**
	 * Check if device is connected
	 * @return boolean
	 */
	public boolean isConnected() {
		return mBluetoothGatt != null && mState == BleGattCallback.CONNECTED;
	}

	/**
	 * Set BluetoothGatt
	 * @param gatt BluetoothGatt
	 */
	private synchronized void setBluetoothGatt(@Nullable BluetoothGatt gatt) {
		mBluetoothGatt = gatt;
	}

	/**
	 * Get the bluetooth gatt
	 * @return BluetoothGatt
	 */
	@Nullable
	public BluetoothGatt getBluetoothGatt() {
		return mBluetoothGatt;
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
	 * Return the current Mtu
	 * @return int
	 */
	public int getMtu() {
		return mMtu;
	}

	/**
	 * Set pending task
	 * @param task DeviceTask
	 */
	private synchronized void setPendingTask(@Nullable GattTask task) {
		mPendingTask = task;
	}

	/**
	 * Enqueue task
	 * @param task DeviceTask
	 */
	public void enqueueTask(@NonNull GattTask task) {
		mTasksQueue.add(task);
		doNextTask();
	}

	/**
	 * Do the next task
	 */
	@SuppressLint("NewApi")
	private void doNextTask() {
		if(mPendingTask != null) return;

		setPendingTask(mTasksQueue.poll());
		if(mPendingTask == null) return;

		// Execute the task
		mHandler.post(() -> {
			switch(mPendingTask) {
				case UpdateMtuTask t -> updateMtu(t);
				case UpdatePhyTask t -> updatePhy(t);
				case ReadPhyTask t -> readPhy(t);
				case ReadCharaTask t -> readChara(t);
				case WriteCharaTask t -> writeChara(t);
				case SplittedWriteCharaTask t -> splittedWriteChara(t);
				case EnableNotifyTask t -> enableNotify(t);
				case DisableNotifyTask t -> disableNotify(t);
				case ReadDescTask t -> readDesc(t);
				case WriteDescTask t -> writeDesc(t);
				default -> {}
			}
		});
	}

	/**
	 * Signal end of task
	 */
	private void signalEndOfTask() {
		setPendingTask(null);
		doNextTask();
	}

	/**
	 * The gatt callback
	 */
	private final BluetoothGattCallback mBluetoothGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			super.onConnectionStateChange(gatt, status, newState);

			mHandler.post(() -> {
				Utils.log("onConnectionStateChange status=" + status + " newState=" + newState);

				switch (newState) {
					// Connected
					case BluetoothProfile.STATE_CONNECTED:
						discoverServices();
						break;

					// Disconnect
					case BluetoothProfile.STATE_DISCONNECTED:
						if(mState != BleGattCallback.DISCONNECTED) disconnect(false);
						break;
				}
			});
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			super.onServicesDiscovered(gatt, status);

			mHandler.post(() -> {
				Utils.log("onServicesDiscovered status=" + status);

				if(mState == BleGattCallback.CONNECTED) return;

				setState(BleGattCallback.CONNECTED);
				if(mCallback != null) mCallback.onConnectSuccess(gatt.getServices());
			});
		}

		@Override
		public void onServiceChanged(@NonNull BluetoothGatt gatt) {
			super.onServiceChanged(gatt);

			mHandler.post(() -> {
				Utils.log("onServiceChanged");
				discoverServices();
			});
		}

		@Override
		public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
			super.onCharacteristicChanged(gatt, characteristic, value);

			mHandler.post(() -> {
				Utils.log("onCharacteristicChanged");

				BleNotifyCallback callback = mNotifyCallbacks.get(characteristic.getUuid());
				if(callback != null) callback.onNotify(value);
			});
		}

		@Override
		public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
			super.onCharacteristicRead(gatt, characteristic, value, status);

			mHandler.post(() -> {
				Utils.log("onCharacteristicRead status=" + status);

				if(mPendingTask instanceof ReadCharaTask t) {
					if(status == BluetoothGatt.GATT_SUCCESS) {
						t.getCallback().onReadSuccess(value);
					} else {
						t.getCallback().onReadFailed();
					}

					signalEndOfTask();
				}
			});
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			super.onCharacteristicWrite(gatt, characteristic, status);

			mHandler.post(() -> {
				Utils.log("onCharacteristicWrite status=" + status);

				if(mPendingTask instanceof SplittedWriteCharaTask t) {
					if(status == BluetoothGatt.GATT_SUCCESS) {
						if(!t.isSendNextWhenLastSuccess()) return;

						// Notify progress
						int totalPkg = t.getTotalPkg();
						t.getCallback().onWriteProgress(totalPkg-t.getQueueSize(), totalPkg);

						// Execute next pkg
						Runnable r = t.getRunnable();
						if(r != null) mHandler.postDelayed(r, t.getIntervalBetweenTwoPackage());
						else {
							t.getCallback().onWriteFailed();
							signalEndOfTask();
						}
					} else {
						if(t.isSendNextWhenLastSuccess()) {
							t.getCallback().onWriteFailed();
							signalEndOfTask();
						} else {
							t.setOneWriteHasFailed(true);
						}
					}
				} else if(mPendingTask instanceof WriteCharaTask t) {
					if(status == BluetoothGatt.GATT_SUCCESS) {
						t.getCallback().onWriteSuccess();
					} else {
						t.getCallback().onWriteFailed();
					}

					signalEndOfTask();
				}
			});
		}

		@Override
		public void onDescriptorRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattDescriptor descriptor, int status, @NonNull byte[] value) {
			super.onDescriptorRead(gatt, descriptor, status, value);

			mHandler.post(() -> {
				Utils.log("onDescriptorRead status=" + status);

				if(mPendingTask instanceof ReadDescTask t) {
					if(status == BluetoothGatt.GATT_SUCCESS) {
						t.getCallback().onReadSuccess(value);
					} else {
						t.getCallback().onReadFailed();
					}

					signalEndOfTask();
				}
			});
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			super.onDescriptorWrite(gatt, descriptor, status);

			mHandler.post(() -> {
				Utils.log("onDescriptorWrite status=" + status);

				switch(mPendingTask) {
					// Enable notify task
					case EnableNotifyTask t -> {
						if(status == BluetoothGatt.GATT_SUCCESS) {
							t.getCallback().onNotifyEnabled();
						} else {
							gatt.setCharacteristicNotification(descriptor.getCharacteristic(), false);
							mNotifyCallbacks.remove(descriptor.getCharacteristic().getUuid());
							t.getCallback().onNotifyDisabled();
						}

						signalEndOfTask();
					}

					// Disable notify task
					case DisableNotifyTask t -> {
						BleNotifyCallback callback = t.getCallback();
						if(callback != null) callback.onNotifyDisabled();
						signalEndOfTask();
					}

					// Write descriptor task
					case WriteDescTask t -> {
						if(status == BluetoothGatt.GATT_SUCCESS) {
							t.getCallback().onWriteSuccess();
						} else {
							t.getCallback().onWriteFailed();
						}

						signalEndOfTask();
					}

					// ?
					default -> {}
				}
			});
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			super.onMtuChanged(gatt, mtu, status);

			mHandler.post(() -> {
				Utils.log("onMtuChanged mtu=" + mtu + " status=" + status);

				if(mPendingTask instanceof UpdateMtuTask t) {
					setMtu(mtu);
					BleMtuChangedCallback callback = t.getCallback();
					if(callback != null) callback.onMtuChanged(mtu);
					signalEndOfTask();
				}
			});
		}

		@Override
		public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			super.onPhyRead(gatt, txPhy, rxPhy, status);

			mHandler.post(() -> {
				Utils.log("onPhyRead txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);

				if(mPendingTask instanceof ReadPhyTask t) {
					t.getCallback().onPhyValue(txPhy, rxPhy);
					signalEndOfTask();
				}
			});
		}

		@Override
		public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
			super.onPhyUpdate(gatt, txPhy, rxPhy, status);

			mHandler.post(() -> {
				Utils.log("onPhyUpdate txPhy=" + txPhy + " rxPhy=" + rxPhy + " status=" + status);

				if(mPendingTask instanceof UpdatePhyTask t) {
					BlePhyValueCallback callback = t.getCallback();
					if(callback != null) callback.onPhyValue(txPhy, rxPhy);
					signalEndOfTask();
				}
			});
		}

		@Override
		public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
			super.onReliableWriteCompleted(gatt, status);

			mHandler.post(() -> {
				Utils.log("onReliableWriteCompleted status=" + status);
			});
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
			super.onReadRemoteRssi(gatt, rssi, status);

			mHandler.post(() -> {
				Utils.log("onReadRemoteRssi rssi=" + rssi + " status=" + status);

				if(mPendingTask instanceof ReadRssiTask t)  {
					t.getCallback().onRssi(rssi);
					signalEndOfTask();
				}
			});
		}
	};

	/**
	 * Connect the device
	 * @param callback BleGattCallback
	 */
	public void connect(@NonNull BleGattCallback callback) {
		mHandler.post(() -> {
			Utils.log("connect");

			// Set the callback
			setGattCallback(callback);

			// Check the current state
			if(mState != BleGattCallback.DISCONNECTED) {
				Utils.log("already connecting or connected");
				return;
			}

			// Set state connecting
			setState(BleGattCallback.CONNECTING);
			callback.onConnecting();

			// Delay before connect
			int delayBeforeConnect = 0;

			// Enable bluetooth if not enabled
			if(!KnBle.gi().isBluetoothEnabled()) {
				Utils.log("bluetooth is disabled");

				if(!KnBle.gi().enableBluetooth(true)) {
					// Connect failed => Disconnect
					disconnect(false);
				} else {
					// Add delay to be sure the adapter has time to init before connect
					delayBeforeConnect += 5000;
				}
			}

			// Connecting after the delay
			mHandler.postDelayed(() -> {
				setBluetoothGatt(null);

				// Always connect with autoConnect==false for better connection speed
				setBluetoothGatt(mDevice.getDevice().connectGatt(KnBle.gi().getContext(), false, mBluetoothGattCallback, BluetoothDevice.TRANSPORT_LE));

				// If other methods have failed
				if(mBluetoothGatt == null) setBluetoothGatt(mDevice.getDevice().connectGatt(KnBle.gi().getContext(), false, mBluetoothGattCallback));

				// Connect failed => Disconnect
				if(mBluetoothGatt == null) disconnect(false);
			}, delayBeforeConnect);
		});
	}

	/**
	 * Get a service
	 * @param serviceUUID The service UUID
	 * @param callback BleGetService
	 */
	public void getService(@NonNull UUID serviceUUID, @NonNull BleGetService callback) {
		mHandler.post(() -> {
			Utils.log("hasService");

			// Check if is connected
			if(mBluetoothGatt == null) {
				callback.onFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mServices.get(serviceUUID, mBluetoothGatt);
			if(service != null) callback.onSuccess(service);
			else callback.onFailed();
		});
	}

	/**
	 * Get a characteristic
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param callback BleGetCharacteristic
	 */
	public void getCharacteristic(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull BleGetCharacteristic callback) {
		mHandler.post(() -> {
			Utils.log("getCharacteristic");

			// Check if is connected
			if(mBluetoothGatt == null) {
				callback.onFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mServices.get(serviceUUID, mBluetoothGatt);
			if(service == null) {
				callback.onFailed();
				return;
			}

			// Get the characteristic
			BluetoothGattCharacteristic characteristic = mCharas.get(characteristicUUID, service);
			if(characteristic != null) callback.onSuccess(characteristic);
			else callback.onFailed();
		});
	}

	/**
	 * Get a descriptor
	 * @param serviceUUID The service UUID
	 * @param characteristicUUID The characteristic UUID
	 * @param descriptorUUID The characteristic UUID
	 * @param callback BleGetDescriptor
	 */
	public void getDescriptor(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull UUID descriptorUUID, @NonNull BleGetDescriptor callback) {
		mHandler.post(() -> {
			Utils.log("getDescriptor");

			// Check if is connected
			if(mBluetoothGatt == null) {
				callback.onFailed();
				return;
			}

			// Get the service
			BluetoothGattService service = mServices.get(serviceUUID, mBluetoothGatt);
			if(service == null) {
				callback.onFailed();
				return;
			}

			// Get the characteristic
			BluetoothGattCharacteristic characteristic = mCharas.get(characteristicUUID, service);
			if(characteristic == null) {
				callback.onFailed();
				return;
			}

			// Get the descriptor
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(descriptorUUID);
			if(descriptor != null) callback.onSuccess(descriptor);
			else callback.onFailed();
		});
	}

	/**
	 * Request connection priority
	 * @param connectionPriority priority
	 */
	public void requestConnectionPriority(int connectionPriority) {
		mHandler.post(() -> {
			Utils.log("requestConnectionPriority connectionPriority=" + connectionPriority);
			if(mBluetoothGatt == null) return;
			mBluetoothGatt.requestConnectionPriority(connectionPriority);
		});
	}

	/**
	 * Update Mtu
	 * @param t UpdateMtuTask
	 */
	private void updateMtu(@NonNull UpdateMtuTask t) {
		int mtu = t.getMtu();
		Utils.log("execute UpdateMtuTask mtu=" + mtu);
		if(mBluetoothGatt != null) mBluetoothGatt.requestMtu(mtu);
		else signalEndOfTask();
	}

	/**
	 * Update Phy
	 * @param t UpdatePhyTask
	 */
	@SuppressLint("NewApi")
	private void updatePhy(@NonNull UpdatePhyTask t) {
		int txPhy = t.getTxPhy();
		int rxPhy = t.getRxPhy();
		int phyOptions = t.getPhyOptions();
		Utils.log("execute UpdatePhyTask txPhy=" + txPhy + " rxPhy=" + rxPhy + " phyOptions=" + phyOptions);
		if(mBluetoothGatt != null) mBluetoothGatt.setPreferredPhy(txPhy, rxPhy, phyOptions);
		else signalEndOfTask();
	}

	/**
	 * Read Phy
	 * @param t ReadPhyTask
	 */
	@SuppressLint("NewApi")
	private void readPhy(@NonNull ReadPhyTask t) {
		Utils.log("execute ReadPhyTask");
		if(mBluetoothGatt != null) mBluetoothGatt.readPhy();
		else signalEndOfTask();
	}

	/**
	 * Read Chara
	 * @param t ReadCharaTask
	 */
	private void readChara(@NonNull ReadCharaTask t) {
		Utils.log("execute ReadCharaTask");

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("readChara mBluetoothGatt is null");
			t.getCallback().onReadFailed();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("readChara service is null");
			t.getCallback().onReadFailed();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
			Utils.log("readChara characteristic is null or property read = 0");
			t.getCallback().onReadFailed();
			signalEndOfTask();
			return;
		}

		// Retry counter
		final int[] retry = {0};

		// Execute the read (with retry in case of gatt busy)
		(new Runnable() {
			@Override
			public void run() {
				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("readChara mBluetoothGatt is null");
					t.getCallback().onReadFailed();
					signalEndOfTask();
					return;
				}

				// Success
				if(mBluetoothGatt.readCharacteristic(characteristic)) return;

				// Failed
				Utils.log("readChara failed to initiate the read retry = " + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					t.getCallback().onReadFailed();
					signalEndOfTask();
				}
			}
		}).run();
	}

	/**
	 * Write Chara
	 * @param t WriteCharaTask
	 */
	private void writeChara(@NonNull WriteCharaTask t) {
		Utils.log("execute WriteCharaTask");

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("writeChara mBluetoothGatt is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("writeChara service is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		int properties;
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null || ((properties = characteristic.getProperties()) & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
			Utils.log("writeChara characteristic is null or properties write = 0");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Correct the write type (if needed)
		boolean isNoResponse = t.isNoResponse();
		if(isNoResponse && (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) isNoResponse = false;
		else if(!isNoResponse && (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) isNoResponse = true;

		// Write type
		int writeType = isNoResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

		// Retry counter
		final int[] retry = {0};

		// Execute the write (with retry in case of gatt busy)
		(new Runnable() {
			@Override
			public void run() {
				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("writeChara mBluetoothGatt is null");
					t.getCallback().onWriteFailed();
					signalEndOfTask();
					return;
				}

				// Try to write
				boolean success;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					success = mBluetoothGatt.writeCharacteristic(characteristic, t.getData(), writeType) == BluetoothStatusCodes.SUCCESS;
				} else {
					characteristic.setWriteType(writeType);
					success = characteristic.setValue(t.getData()) && mBluetoothGatt.writeCharacteristic(characteristic);
				}

				// Success
				if(success) return;

				// Failed
				Utils.log("writeChara failed to initiate the write retry=" + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					t.getCallback().onWriteFailed();
					signalEndOfTask();
				}
			}
		}).run();
	}

	/**
	 * Splitted Write Chara
	 * @param t SplittedWriteCharaTask
	 */
	private void splittedWriteChara(@NonNull SplittedWriteCharaTask t) {
		Utils.log("execute SplittedWriteCharaTask");

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("splittedWriteChara mBluetoothGatt is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("splittedWriteChara service is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		int properties;
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null || ((properties = characteristic.getProperties()) & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
			Utils.log("splittedWriteChara characteristic is null or properties write = 0");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Correct the write type (if needed)
		boolean isNoResponse = t.isNoResponse();
		if(isNoResponse && (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) isNoResponse = false;
		else if(!isNoResponse && (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) isNoResponse = true;

		// Write type
		int writeType = isNoResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

		// Split data and fill the queue
		Utils.splitBytesArrayAndFillQueue(t.getData(), t.getSplitSize(), t.getQueue());
		int totalPkg = t.setTotalPkg();

		// Callback
		BleSplittedWriteCallback callback = t.getCallback();

		// Interval between pkg
		long intervalBetweenTwoPackage = t.getIntervalBetweenTwoPackage();

		boolean sendNextWhenLastSuccess = t.isSendNextWhenLastSuccess();

		// Retry counter
		final int[] retry = {0};

		// Execute the write (with retry in case of gatt busy)
		(t.setRunnable(new Runnable() {
			@Override
			public void run() {
				// One write has failed
				boolean oneWriteHasFailed = t.isOneWriteHasFailed();

				// Peek
				byte[] data = t.peekQueue();

				// Success
				if(data == null && !oneWriteHasFailed) {
					callback.onWriteSuccess();
					signalEndOfTask();
					return;
				}

				// One write has failed
				if(oneWriteHasFailed) {
					Utils.log("splittedWriteChara one write has failed");
					callback.onWriteFailed();
					signalEndOfTask();
					return;
				}

				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("splittedWriteChara mBluetoothGatt is null");
					callback.onWriteFailed();
					signalEndOfTask();
					return;
				}

				// Try to write
				boolean success;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					success = mBluetoothGatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS;
				} else {
					characteristic.setWriteType(writeType);
					success = characteristic.setValue(data) && mBluetoothGatt.writeCharacteristic(characteristic);
				}

				// Success
				if(success) {
					// Remove pkg from the queue
					t.pollQueue();

					// If no wait
					if(!sendNextWhenLastSuccess) {
						// Notify progress
						callback.onWriteProgress(totalPkg-t.getQueueSize(), totalPkg);

						// Send the next pkg
						mHandler.postDelayed(this, intervalBetweenTwoPackage);
					}
					return;
				}

				// Failed
				Utils.log("splittedWriteChara failed to initiate the write retry=" + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					callback.onWriteFailed();
					signalEndOfTask();
				}
			}
		})).run();
	}

	/**
	 * Enable notify
	 * @param t EnableNotifyTask
	 */
	private void enableNotify(@NonNull EnableNotifyTask t) {
		Utils.log("execute EnableNotifyTask");

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("enableNotify mBluetoothGatt is null");
			t.getCallback().onNotifyDisabled();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("enableNotify service is null");
			t.getCallback().onNotifyDisabled();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null || (characteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) {
			Utils.log("enableNotify characteristic is null or (property notify and property indicate) = 0");
			t.getCallback().onNotifyDisabled();
			signalEndOfTask();
			return;
		}

		// Enable notification
		if(!mBluetoothGatt.setCharacteristicNotification(characteristic, true)) {
			Utils.log("enableNotify failed to enable characteristic notification");
			t.getCallback().onNotifyDisabled();
			return;
		}

		// Get the descriptor
		BluetoothGattDescriptor descriptor = characteristic.getDescriptor(t.getDescriptorUUID());
		if(descriptor == null) {
			Utils.log("enableNotify descriptor is null");
			t.getCallback().onNotifyDisabled();
			return;
		}

		// Set the callback
		mNotifyCallbacks.put(characteristic.getUuid(), t.getCallback());

		// Retry counter
		final int[] retry = {0};

		// Execute the write (with retry in case of gatt busy)
		(new Runnable() {
			@Override
			public void run() {
				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("enableNotify mBluetoothGatt is null");
					mNotifyCallbacks.remove(characteristic.getUuid());
					t.getCallback().onNotifyDisabled();
					signalEndOfTask();
					return;
				}

				// Write descriptor
				boolean success;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					success = mBluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
				} else {
					success = descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && mBluetoothGatt.writeDescriptor(descriptor);
				}

				// Success
				if(success) return;

				// Failed
				Utils.log("enableNotify failed to initiate the write retry = " + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					mNotifyCallbacks.remove(characteristic.getUuid());
					mBluetoothGatt.setCharacteristicNotification(characteristic, false);
					t.getCallback().onNotifyDisabled();
					signalEndOfTask();
				}
			}
		}).run();
	}

	/**
	 * Disable notify
	 * @param t DisableNotifyTask
	 */
	private void disableNotify(@NonNull DisableNotifyTask t) {
		Utils.log("execute DisableNotifyTask");

		// Remove the callback
		//noinspection DataFlowIssue
		BleNotifyCallback callback = mNotifyCallbacks.remove(Optional.ofNullable(t.getCharacteristicUUID()).orElseGet(() -> t.getCharacteristic().getUuid()));
		if(callback == null) return;

		// Save the callback for onDescriptorWrite
		t.setCallback(callback);

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("disableNotify mBluetoothGatt is null");
			callback.onNotifyDisabled();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("disableNotify service is null");
			callback.onNotifyDisabled();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null) {
			Utils.log("disableNotify characteristic is null");
			callback.onNotifyDisabled();
			signalEndOfTask();
			return;
		}

		// Disable notification
		boolean stopNotif = mBluetoothGatt.setCharacteristicNotification(characteristic, false);
		Utils.log("disableNotify setCharacteristicNotification=" + stopNotif);

		// Get the descriptor
		BluetoothGattDescriptor descriptor = characteristic.getDescriptor(t.getDescriptorUUID());
		if(descriptor == null) {
			Utils.log("disableNotify descriptor is null");
			callback.onNotifyDisabled();
			return;
		}

		// Retry counter
		final int[] retry = {0};

		// Execute the write (with retry in case of gatt busy)
		(new Runnable() {
			@Override
			public void run() {
				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("disableNotify mBluetoothGatt is null");
					callback.onNotifyDisabled();
					signalEndOfTask();
					return;
				}

				// Write descriptor
				boolean success;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					success = mBluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
				} else {
					success = descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) && mBluetoothGatt.writeDescriptor(descriptor);
				}

				// Success
				if(success) return;

				// Failed
				Utils.log("disableNotify failed to initiate the write retry = " + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					callback.onNotifyDisabled();
					signalEndOfTask();
				}
			}
		}).run();
	}

	/**
	 * Read descriptor
	 * @param t ReadDescTask
	 */
	private void readDesc(@NonNull ReadDescTask t) {
		Utils.log("execute WriteDescTask");

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("readDesc mBluetoothGatt is null");
			t.getCallback().onReadFailed();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("readDesc service is null");
			t.getCallback().onReadFailed();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null) {
			Utils.log("readDesc characteristic is null");
			t.getCallback().onReadFailed();
			signalEndOfTask();
			return;
		}

		// Get the descriptor
		BluetoothGattDescriptor descriptor = characteristic.getDescriptor(t.getDescriptorUUID());
		if(descriptor == null) {
			Utils.log("readDesc descriptor is null");
			t.getCallback().onReadFailed();
			return;
		}

		// Retry counter
		final int[] retry = {0};

		// Execute the write (with retry in case of gatt busy)
		(new Runnable() {
			@Override
			public void run() {
				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("readDesc mBluetoothGatt is null");
					t.getCallback().onReadFailed();
					signalEndOfTask();
					return;
				}

				// Success
				if(mBluetoothGatt.readDescriptor(descriptor)) return;

				// Failed
				Utils.log("readDesc failed to initiate the read retry = " + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					t.getCallback().onReadFailed();
					signalEndOfTask();
				}
			}
		}).run();
	}

	/**
	 * Write descriptor
	 * @param t WriteDescTask
	 */
	private void writeDesc(@NonNull WriteDescTask t) {
		Utils.log("execute WriteDescTask");

		// Check if is connected
		if(mBluetoothGatt == null) {
			Utils.log("writeDesc mBluetoothGatt is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the service
		BluetoothGattService service = Optional.ofNullable(t.getService()).orElse(mServices.get(t.getServiceUUID(), mBluetoothGatt));
		if(service == null) {
			Utils.log("writeDesc service is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the characteristic
		BluetoothGattCharacteristic characteristic = Optional.ofNullable(t.getCharacteristic()).orElse(mCharas.get(t.getCharacteristicUUID(), service));
		if(characteristic == null) {
			Utils.log("writeDesc characteristic is null");
			t.getCallback().onWriteFailed();
			signalEndOfTask();
			return;
		}

		// Get the descriptor
		BluetoothGattDescriptor descriptor = characteristic.getDescriptor(t.getDescriptorUUID());
		if(descriptor == null) {
			Utils.log("writeDesc descriptor is null");
			t.getCallback().onWriteFailed();
			return;
		}

		// Retry counter
		final int[] retry = {0};

		// Execute the write (with retry in case of gatt busy)
		(new Runnable() {
			@Override
			public void run() {
				// Check if is connected
				if(mBluetoothGatt == null) {
					Utils.log("writeDesc mBluetoothGatt is null");
					t.getCallback().onWriteFailed();
					signalEndOfTask();
					return;
				}

				// Write descriptor
				boolean success;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					success = mBluetoothGatt.writeDescriptor(descriptor, t.getData()) == BluetoothStatusCodes.SUCCESS;
				} else {
					success = descriptor.setValue(t.getData()) && mBluetoothGatt.writeDescriptor(descriptor);
				}

				// Success
				if(success) return;

				// Failed
				Utils.log("writeDesc failed to initiate the write retry = " + retry[0]);

				// Retry
				if(retry[0] < 100) {
					retry[0]++;
					mHandler.postDelayed(this, 50);
				} else {
					// Failed
					t.getCallback().onWriteFailed();
					signalEndOfTask();
				}
			}
		}).run();
	}

	/**
	 * Disconnect the device
	 */
	public void disconnect(boolean destroy) {
		mHandler.post(() -> {
			Utils.log("disconnect destroy=" + destroy);

			// Disconnect
			if(mBluetoothGatt != null) {
				// Clear device cache (must be call before close)
				clearDeviceCache();

				// Use close instead disconnect to avoid weird behavior (never use disconnect before close)
				mBluetoothGatt.close();
				setBluetoothGatt(null);
			}

			// Connect failed
			boolean connectFailed = (mState == BleGattCallback.CONNECTING);

			// Clean
			mTasksQueue.clear();
			setPendingTask(null);
			mHandler.removeCallbacksAndMessages(null);
			mUiHandler.removeCallbacksAndMessages(null);
			mCharas.clear();
			mServices.clear();
			mNotifyCallbacks.clear();
			setState(BleGattCallback.DISCONNECTED);
			setMtu(23);

			// Callback
			if(mCallback != null) {
				mCallback.onDisconnected(connectFailed);
				setGattCallback(null);
			}

			// Destroy the thread
			if(destroy) mHandlerThread.quit();
		});
	}

	/**
	 * Discover services
	 */
	private void discoverServices() {
		// Discover gatt services on ui thread to avoid rare threading issue
		// 300 ms delay when not bonded, 1600 ms when bonded
		mUiHandler.postDelayed(() -> {
			if(mBluetoothGatt != null) mBluetoothGatt.discoverServices();
		}, 300);
	}

	/**
	 * Clear device cache
	 * @noinspection CallToPrintStackTrace
	 */
	@SuppressWarnings({"JavaReflectionMemberAccess"})
	private void clearDeviceCache() {
		Utils.log("clearDeviceCache");

		try {
			Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
			refresh.invoke(mBluetoothGatt);
		} catch (Exception e) {
			if(KnBle.DEBUG) e.printStackTrace();
		}
	}
}
