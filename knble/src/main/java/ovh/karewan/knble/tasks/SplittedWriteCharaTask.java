package ovh.karewan.knble.tasks;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;

public class SplittedWriteCharaTask extends GattTask {
	private final UUID mServiceUUID;
	private final BluetoothGattService mService;
	private final UUID mCharacteristicUUID;
	private final BluetoothGattCharacteristic mCharacteristic;
	private final byte[] mData;
	private final int mSplitSize;
	private final boolean mNoResponse;
	private final long mIntervalBetweenTwoPackage;
	private final BleSplittedWriteCallback mCallback;
	private final ConcurrentLinkedQueue<byte[]> mQueue = new ConcurrentLinkedQueue<>();
	private int mTotalPkg;
	private Runnable mRunnable;

	public SplittedWriteCharaTask(@NonNull UUID serviceUUID, @NonNull UUID characteristicUUID, @NonNull byte[] data, int splitSize, boolean noResponse, long intervalBetweenTwoPackage, @NonNull BleSplittedWriteCallback callback) {
		mServiceUUID = serviceUUID;
		mService = null;
		mCharacteristicUUID = characteristicUUID;
		mCharacteristic = null;
		mData = data;
		mSplitSize = splitSize;
		mNoResponse = noResponse;
		mIntervalBetweenTwoPackage = intervalBetweenTwoPackage;
		mCallback = callback;
	}

	public SplittedWriteCharaTask(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data, int splitSize, boolean noResponse, long intervalBetweenTwoPackage, @NonNull BleSplittedWriteCallback callback) {
		mServiceUUID = null;
		mService = service;
		mCharacteristicUUID = null;
		mCharacteristic = characteristic;
		mData = data;
		mSplitSize = splitSize;
		mNoResponse = noResponse;
		mIntervalBetweenTwoPackage = intervalBetweenTwoPackage;
		mCallback = callback;
	}

	@Nullable
	public UUID getServiceUUID() {
		return mServiceUUID;
	}

	@Nullable
	public BluetoothGattService getService() {
		return mService;
	}

	@Nullable
	public UUID getCharacteristicUUID() {
		return mCharacteristicUUID;
	}

	@Nullable
	public BluetoothGattCharacteristic getCharacteristic() {
		return mCharacteristic;
	}

	@NonNull
	public byte[] getData() {
		return mData;
	}

	public int getSplitSize() {
		return mSplitSize;
	}

	public boolean isNoResponse() {
		return mNoResponse;
	}

	public long getIntervalBetweenTwoPackage() {
		return mIntervalBetweenTwoPackage;
	}

	@NonNull
	public BleSplittedWriteCallback getCallback() {
		return mCallback;
	}

	@NonNull
	public ConcurrentLinkedQueue<byte[]> getQueue() {
		return mQueue;
	}

	public int getQueueSize() {
		return mQueue.size();
	}

	@Nullable
	public byte[] peekQueue() {
		return mQueue.peek();
	}

	@Nullable
	public byte[] pollQueue() {
		return mQueue.poll();
	}

	@NonNull
	public Runnable setRunnable(@NonNull Runnable runnable) {
		return mRunnable = runnable;
	}

	@Nullable
	public Runnable getRunnable() {
		return mRunnable;
	}

	public void setTotalPkg() {
		mTotalPkg = mQueue.size();
	}

	public int getTotalPkg() {
		return mTotalPkg;
	}
}
