package ovh.karewan.knble.tasks;

import androidx.annotation.Nullable;

import ovh.karewan.knble.interfaces.BlePhyValueCallback;

public class UpdatePhyTask extends GattTask {
	private final int mTxPhy;
	private final int mRxPhy;
	private final int mPhyOptions;
	private final BlePhyValueCallback mCallback;

	public UpdatePhyTask(int txPhy, int rxPhy, int phyOptions, @Nullable BlePhyValueCallback callback) {
		mTxPhy = txPhy;
		mRxPhy = rxPhy;
		mPhyOptions = phyOptions;
		mCallback = callback;
	}

	public int getTxPhy() {
		return mTxPhy;
	}

	public int getRxPhy() {
		return mRxPhy;
	}

	public int getPhyOptions() {
		return mPhyOptions;
	}

	@Nullable
	public BlePhyValueCallback getCallback() {
		return mCallback;
	}
}
