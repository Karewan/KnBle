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
package ovh.karewan.knble.struct;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressWarnings("MissingPermission")
public class BleDevice {
	private BluetoothDevice mDevice;
	private byte[] mScanRecord;
	private int mRssi;
	private long mTimestamp;

	public BleDevice(@NonNull BluetoothDevice device) {
		this.mDevice = device;
	}

	public BleDevice(@NonNull BluetoothDevice device, int rssi, @Nullable byte[] scanRecord, long timestamp) {
		this.mDevice = device;
		this.mRssi = rssi;
		this.mScanRecord = scanRecord;
		this.mTimestamp = timestamp;
	}

	public synchronized void updateDevice(@NonNull BluetoothDevice device, int rssi, @Nullable byte[] scanRecord, long timestamp) {
		this.mDevice = device;
		this.mRssi = rssi;
		this.mScanRecord = scanRecord;
		this.mTimestamp = timestamp;
	}

	@NonNull
	public BluetoothDevice getDevice() {
		return mDevice;
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
	public byte[] getScanRecord() {
		return mScanRecord;
	}

	public long getTimestamp() {
		return mTimestamp;
	}
}
