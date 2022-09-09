package ovh.karewan.knble.struct;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import ovh.karewan.knble.KnBle;

public class ScanRecord {
	private final byte[] mRawRecord;
	private Integer mManufacturerId = null;
	private byte[] mManufacturerData = null;
	private UUID mBeaconUUID = null;

	public ScanRecord(@NonNull byte[] rawRecord) {
		mRawRecord = rawRecord;
		parseRawRecord();
	}

	@Nullable
	public byte[] getRawRecord() {
		return mRawRecord;
	}

	@Nullable
	public Integer getManufacturerId() {
		return mManufacturerId;
	}

	@Nullable
	public byte[] getManufacturerData() {
		return mManufacturerData;
	}

	@Nullable
	public UUID getBeaconUUID() {
		return mBeaconUUID;
	}

	private void parseRawRecord() {
		if(mRawRecord == null) return;

		try {
			int currentPos = 0;

			while (currentPos < mRawRecord.length) {
				int length = mRawRecord[currentPos++] & 0xFF;
				if (length == 0) break;

				// MANUFACTURER_SPECIFIC_DATA
				if((mRawRecord[currentPos++] & 0xFF) == 0xFF) {
					// ID
					mManufacturerId = ((mRawRecord[currentPos + 1] & 0xFF) << 8) + (mRawRecord[currentPos] & 0xFF);

					// DATA
					mManufacturerData = new byte[length - 3];
					System.arraycopy(mRawRecord, currentPos + 2, mManufacturerData, 0, length - 3);

					// BEACON UUID 128
					if(mManufacturerData != null
							&& mManufacturerData.length > 19
							&& mManufacturerId == 0x4C
							&& mManufacturerData[0] == 0x02
							&& mManufacturerData[1] == 0x15) {

						mBeaconUUID = UUID.fromString(String.format(
								"%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
								mManufacturerData[2] & 0xFF, mManufacturerData[3] & 0xFF,
								mManufacturerData[4] & 0xFF, mManufacturerData[5] & 0xFF,
								mManufacturerData[6] & 0xFF, mManufacturerData[7] & 0xFF,
								mManufacturerData[8] & 0xFF, mManufacturerData[9] & 0xFF,
								mManufacturerData[10] & 0xFF, mManufacturerData[11] & 0xFF,
								mManufacturerData[12] & 0xFF, mManufacturerData[13] & 0xFF,
								mManufacturerData[14] & 0xFF, mManufacturerData[15] & 0xFF,
								mManufacturerData[16] & 0xFF, mManufacturerData[17] & 0xFF
						));
					}
				}

				currentPos += length - 1;
			}
		} catch (Exception e) {
			if(KnBle.DEBUG) e.printStackTrace();
		}
	}
}
