package ovh.karewan.knble.struct;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import ovh.karewan.knble.Utils;

/**
 * A device as KnBle saw it: immutable, each scan report gives a new instance. Equal to another instance of the same
 * MAC address. getName() never calls the Bluetooth process (unlike BluetoothDevice.getName(), a binder call).
 */
public final class BleDevice {
	/** TX power unknown (ScanResult.TX_POWER_NOT_PRESENT) */
	public static final int TX_POWER_UNKNOWN = 127;

	private final long mMacLong;
	// Boxed once: key of the device in the maps of KnBle, read by every getter of the connection state
	@NonNull
	private final Long mKey;
	@NonNull
	private final String mMac;
	@NonNull
	private final BluetoothDevice mDevice;
	@Nullable
	private final String mName;
	private final int mRssi;
	@Nullable
	private final ScanRecord mScanRecord;
	private final long mTimestamp;
	private final boolean mConnectable;
	private final int mPrimaryPhy, mSecondaryPhy, mTxPower;

	public BleDevice(@NonNull BluetoothDevice device) {
		this(device, null, 0, null, 0, true, BluetoothDevice.PHY_LE_1M, 0, TX_POWER_UNKNOWN);
	}

	/**
	 * A device of a scan made by the app (form of KnBle 3)
	 * @deprecated the devices of KnBle come from its scans (startScan, parseBackgroundScan) or getBleDeviceFromMac
	 */
	@Deprecated
	public BleDevice(@NonNull BluetoothDevice device, int rssi, @Nullable ScanRecord scanRecord, long timestamp) {
		this(device, scanRecord != null ? scanRecord.getDeviceName() : null, rssi, scanRecord, timestamp, true, BluetoothDevice.PHY_LE_1M, 0, TX_POWER_UNKNOWN);
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public BleDevice(@NonNull BluetoothDevice device, @Nullable String name, int rssi, @Nullable ScanRecord scanRecord, long timestamp,
					 boolean connectable, int primaryPhy, int secondaryPhy, int txPower) {
		mMac = device.getAddress();
		mMacLong = Utils.macToLong(mMac);
		mKey = mMacLong;
		mDevice = device;
		mName = name;
		mRssi = rssi;
		mScanRecord = scanRecord;
		mTimestamp = timestamp;
		mConnectable = connectable;
		mPrimaryPhy = primaryPhy;
		mSecondaryPhy = secondaryPhy;
		mTxPower = txPower;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public BleDevice withName(@Nullable String name) {
		return new BleDevice(mDevice, name, mRssi, mScanRecord, mTimestamp, mConnectable, mPrimaryPhy, mSecondaryPhy, mTxPower);
	}

	@NonNull
	public BluetoothDevice getDevice() {
		return mDevice;
	}

	public long getMacLong() {
		return mMacLong;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public Long key() {
		return mKey;
	}

	@NonNull
	public String getMac() {
		return mMac;
	}

	/**
	 * Advertised name (the last one seen), else the name known by the system, null when none is known yet
	 */
	@Nullable
	public String getName() {
		return mName;
	}

	public int getRssi() {
		return mRssi;
	}

	/**
	 * Advertising data, each field with its last value seen
	 */
	@Nullable
	public ScanRecord getScanRecord() {
		return mScanRecord;
	}

	/**
	 * System.currentTimeMillis() of the report
	 */
	public long getTimestamp() {
		return mTimestamp;
	}

	/**
	 * Connectable advertising (Android 8+; always true before)
	 */
	public boolean isConnectable() {
		return mConnectable;
	}

	/**
	 * PHY of the advertising (BluetoothDevice.PHY_LE_1M or PHY_LE_CODED; 1M before Android 8)
	 */
	public int getPrimaryPhy() {
		return mPrimaryPhy;
	}

	/**
	 * PHY of the extended advertising data, 0 for a legacy advertising
	 */
	public int getSecondaryPhy() {
		return mSecondaryPhy;
	}

	/**
	 * TX power of the report (extended advertising, Android 8+), TX_POWER_UNKNOWN when absent
	 */
	public int getTxPower() {
		return mTxPower;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof BleDevice d && d.mMacLong == mMacLong);
	}

	@Override
	public int hashCode() {
		return Long.hashCode(mMacLong);
	}

	@NonNull
	@Override
	public String toString() {
		return "BleDevice{" + mMac + ", " + mName + ", " + mRssi + " dBm}";
	}
}
