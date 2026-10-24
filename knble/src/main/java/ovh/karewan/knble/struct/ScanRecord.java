package ovh.karewan.knble.struct;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ovh.karewan.knble.KnBle;

/**
 * Advertising data of a device (advertising and scan response), parsed once. Immutable.
 */
public class ScanRecord {
	private static final int AD_FLAGS = 0x01, AD_UUID16_PARTIAL = 0x02, AD_UUID16 = 0x03, AD_UUID32_PARTIAL = 0x04, AD_UUID32 = 0x05,
			AD_UUID128_PARTIAL = 0x06, AD_UUID128 = 0x07, AD_SHORT_NAME = 0x08, AD_COMPLETE_NAME = 0x09, AD_TX_POWER = 0x0A,
			AD_SERVICE_DATA16 = 0x16, AD_SERVICE_DATA32 = 0x20, AD_SERVICE_DATA128 = 0x21, AD_MANUFACTURER = 0xFF;
	// Bluetooth base UUID: 0000xxxx-0000-1000-8000-00805f9b34fb
	private static final long BASE_MSB_LOW = 0x1000L, BASE_LSB = 0x800000805F9B34FBL;

	private final byte[] mRawRecord;
	private Integer mManufacturerId = null;
	private byte[] mManufacturerData = null;
	private UUID mBeaconUUID = null;
	private String mDeviceName = null;
	private int mFlags = -1;
	private Integer mTxPowerLevel = null;
	@NonNull
	private Map<Integer, byte[]> mManufacturers = Collections.emptyMap();
	@NonNull
	private List<UUID> mServiceUuids = Collections.emptyList();
	@NonNull
	private Map<UUID, byte[]> mServiceData = Collections.emptyMap();

	public ScanRecord(@NonNull byte[] rawRecord) {
		mRawRecord = rawRecord;
		parseRawRecord();
	}

	private ScanRecord(@NonNull ScanRecord current) {
		mRawRecord = current.mRawRecord;
		mManufacturerId = current.mManufacturerId;
		mManufacturerData = current.mManufacturerData;
		mBeaconUUID = current.mBeaconUUID;
		mDeviceName = current.mDeviceName;
		mFlags = current.mFlags;
		mTxPowerLevel = current.mTxPowerLevel;
		mManufacturers = current.mManufacturers;
		mServiceUuids = current.mServiceUuids;
		mServiceData = current.mServiceData;
	}

	/**
	 * This report completed with what it lacks of the previous one (a report without the scan response, or the scan
	 * response alone): each field keeps its last value, a new value replaces it at once. getRawRecord() stays the bytes
	 * of this report.
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public ScanRecord mergedWith(@NonNull ScanRecord previous) {
		boolean name = mDeviceName == null && previous.mDeviceName != null;
		boolean manufacturer = mManufacturerId == null && previous.mManufacturerId != null;
		boolean flags = mFlags < 0 && previous.mFlags >= 0;
		boolean txPower = mTxPowerLevel == null && previous.mTxPowerLevel != null;
		boolean uuids = mServiceUuids.isEmpty() && !previous.mServiceUuids.isEmpty();
		boolean serviceData = mServiceData.isEmpty() && !previous.mServiceData.isEmpty();
		if (!name && !manufacturer && !flags && !txPower && !uuids && !serviceData) return this;

		ScanRecord merged = new ScanRecord(this);
		if (name) merged.mDeviceName = previous.mDeviceName;
		if (manufacturer) {
			merged.mManufacturerId = previous.mManufacturerId;
			merged.mManufacturerData = previous.mManufacturerData;
			merged.mBeaconUUID = previous.mBeaconUUID;
			merged.mManufacturers = previous.mManufacturers;
		}
		if (flags) merged.mFlags = previous.mFlags;
		if (txPower) merged.mTxPowerLevel = previous.mTxPowerLevel;
		if (uuids) merged.mServiceUuids = previous.mServiceUuids;
		if (serviceData) merged.mServiceData = previous.mServiceData;
		return merged;
	}

	@Nullable
	public byte[] getRawRecord() {
		return mRawRecord;
	}

	/**
	 * Company id of the last manufacturer specific data of the record (some devices carry their state in it)
	 */
	@Nullable
	public Integer getManufacturerId() {
		return mManufacturerId;
	}

	/**
	 * Data of the last manufacturer specific data of the record, company id excluded
	 */
	@Nullable
	public byte[] getManufacturerData() {
		return mManufacturerData;
	}

	/**
	 * Every manufacturer specific data of the record, by company id (data without the id), in the order of the record
	 */
	@NonNull
	public Map<Integer, byte[]> getManufacturerSpecificData() {
		return mManufacturers;
	}

	@Nullable
	public byte[] getManufacturerSpecificData(int companyId) {
		return mManufacturers.get(companyId);
	}

	@Nullable
	public UUID getBeaconUUID() {
		return mBeaconUUID;
	}

	/**
	 * Name advertised by the device (complete name, else short name), null when this report has none
	 */
	@Nullable
	public String getDeviceName() {
		return mDeviceName;
	}

	/**
	 * Advertising flags (0x01 LE limited, 0x02 LE general discoverable, 0x04 BR/EDR not supported...), -1 when absent
	 */
	public int getAdvertiseFlags() {
		return mFlags;
	}

	/**
	 * TX power level advertised by the device (dBm), null when absent
	 */
	@Nullable
	public Integer getTxPowerLevel() {
		return mTxPowerLevel;
	}

	/**
	 * Service UUIDs advertised (16, 32 and 128 bits, complete or partial lists)
	 */
	@NonNull
	public List<UUID> getServiceUuids() {
		return mServiceUuids;
	}

	/**
	 * Service data, by service UUID
	 */
	@NonNull
	public Map<UUID, byte[]> getServiceData() {
		return mServiceData;
	}

	@Nullable
	public byte[] getServiceData(@NonNull UUID serviceUuid) {
		return mServiceData.get(serviceUuid);
	}

	private void parseRawRecord() {
		if (mRawRecord == null) return;

		try {
			String shortName = null;
			Map<Integer, byte[]> manufacturers = null;
			List<UUID> uuids = null;
			Map<UUID, byte[]> serviceData = null;
			int pos = 0;

			while (pos < mRawRecord.length) {
				int length = mRawRecord[pos++] & 0xFF;
				if (length == 0 || pos + length > mRawRecord.length) break;
				int type = mRawRecord[pos] & 0xFF;
				int data = pos + 1, dataLength = length - 1;

				switch (type) {
					case AD_MANUFACTURER -> {
						if (dataLength < 2) break;
						mManufacturerId = ((mRawRecord[data + 1] & 0xFF) << 8) + (mRawRecord[data] & 0xFF);
						mManufacturerData = new byte[dataLength - 2];
						System.arraycopy(mRawRecord, data + 2, mManufacturerData, 0, dataLength - 2);
						if (manufacturers == null) manufacturers = new LinkedHashMap<>();
						manufacturers.put(mManufacturerId, mManufacturerData);

						// iBeacon: 0x004C, type 0x02, length 0x15, then the 128 bits UUID
						if (mManufacturerId == 0x4C && mManufacturerData.length > 19 && mManufacturerData[0] == 0x02 && mManufacturerData[1] == 0x15) {
							mBeaconUUID = new UUID(bigEndian(mManufacturerData, 2), bigEndian(mManufacturerData, 10));
						}
					}
					case AD_COMPLETE_NAME -> {
						if (dataLength > 0) mDeviceName = new String(mRawRecord, data, dataLength, StandardCharsets.UTF_8);
					}
					case AD_SHORT_NAME -> {
						if (dataLength > 0) shortName = new String(mRawRecord, data, dataLength, StandardCharsets.UTF_8);
					}
					case AD_FLAGS -> {
						if (dataLength > 0) mFlags = mRawRecord[data] & 0xFF;
					}
					case AD_TX_POWER -> {
						if (dataLength > 0) mTxPowerLevel = (int) mRawRecord[data];
					}
					case AD_UUID16, AD_UUID16_PARTIAL, AD_UUID32, AD_UUID32_PARTIAL, AD_UUID128, AD_UUID128_PARTIAL -> {
						int size = type <= AD_UUID16 ? 2 : type <= AD_UUID32 ? 4 : 16;
						if (uuids == null) uuids = new ArrayList<>();
						for (int i = 0; i + size <= dataLength; i += size) uuids.add(uuid(mRawRecord, data + i, size));
					}
					case AD_SERVICE_DATA16, AD_SERVICE_DATA32, AD_SERVICE_DATA128 -> {
						int size = type == AD_SERVICE_DATA16 ? 2 : type == AD_SERVICE_DATA32 ? 4 : 16;
						if (dataLength < size) break;
						byte[] value = new byte[dataLength - size];
						System.arraycopy(mRawRecord, data + size, value, 0, value.length);
						if (serviceData == null) serviceData = new LinkedHashMap<>();
						serviceData.put(uuid(mRawRecord, data, size), value);
					}
					default -> {}
				}

				pos += length;
			}

			if (mDeviceName == null) mDeviceName = shortName;
			if (manufacturers != null) mManufacturers = Collections.unmodifiableMap(manufacturers);
			if (uuids != null) mServiceUuids = Collections.unmodifiableList(uuids);
			if (serviceData != null) mServiceData = Collections.unmodifiableMap(serviceData);
		} catch (Exception e) {
			if (KnBle.DEBUG) e.printStackTrace();
		}
	}

	/**
	 * UUID of 16, 32 or 128 bits, little endian in the record
	 */
	@NonNull
	private static UUID uuid(@NonNull byte[] b, int offset, int size) {
		if (size == 16) {
			long lsb = 0, msb = 0;
			for (int i = 7; i >= 0; i--) lsb = (lsb << 8) | (b[offset + i] & 0xFF);
			for (int i = 15; i >= 8; i--) msb = (msb << 8) | (b[offset + i] & 0xFF);
			return new UUID(msb, lsb);
		}
		long value = 0;
		for (int i = size - 1; i >= 0; i--) value = (value << 8) | (b[offset + i] & 0xFF);
		return new UUID((value << 32) | BASE_MSB_LOW, BASE_LSB);
	}

	private static long bigEndian(@NonNull byte[] b, int offset) {
		long v = 0;
		for (int i = 0; i < 8; i++) v = (v << 8) | (b[offset + i] & 0xFF);
		return v;
	}
}
