package ovh.karewan.knble.struct;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ScanRecord {
	private final byte[] mRawRecord;
	private final Integer mManufacturerId;
	private final byte[] mManufacturerData;

	public ScanRecord(@NonNull byte[] rawRecord, @Nullable Integer manufacturerId, @Nullable byte[] manufacturerData) {
		mRawRecord = rawRecord;
		mManufacturerId = manufacturerId;
		mManufacturerData = manufacturerData;
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
}
