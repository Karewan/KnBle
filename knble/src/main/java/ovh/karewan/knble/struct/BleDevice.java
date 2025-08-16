package ovh.karewan.knble.struct;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ovh.karewan.knble.Utils;

@SuppressWarnings("MissingPermission")
public class BleDevice {
	private final long mMacLong;
	private BluetoothDevice mDevice;
	private ScanRecord mScanRecord;
	private int mRssi;
	private long mTimestamp;

	public BleDevice(@NonNull BluetoothDevice device) {
		this.mMacLong = Utils.macToLong(device.getAddress());
		this.mDevice = device;
	}

	public BleDevice(@NonNull BluetoothDevice device, int rssi, @Nullable ScanRecord scanRecord, long timestamp) {
		this.mMacLong = Utils.macToLong(device.getAddress());
		this.mDevice = device;
		this.mRssi = rssi;
		this.mScanRecord = scanRecord;
		this.mTimestamp = timestamp;
	}

	public synchronized void updateDevice(@NonNull BluetoothDevice device, int rssi, @Nullable ScanRecord scanRecord, long timestamp) {
		this.mDevice = device;
		this.mRssi = rssi;
		this.mScanRecord = scanRecord;
		this.mTimestamp = timestamp;
	}

	@NonNull
	public BluetoothDevice getDevice() {
		return mDevice;
	}

	public long getMacLong() {
		return mMacLong;
	}

	@NonNull
	public String getMac() {
		return mDevice.getAddress();
	}

	@Nullable
	public String getName() {
		return mDevice.getName();
	}

	public int getRssi() {
		return mRssi;
	}

	@Nullable
	public ScanRecord getScanRecord() {
		return mScanRecord;
	}

	public long getTimestamp() {
		return mTimestamp;
	}
}
