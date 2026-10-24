package ovh.karewan.knble;

import androidx.annotation.NonNull;

/**
 * Options of an OTA (KnBle.otaSilabs, KnBle.otaTi). The defaults are the fastest ones: change them for a bootloader
 * that loses packets.
 */
public final class OtaOptions {
	private final int mPacketSize;
	private final long mPacketInterval;
	private final int mRetries;
	private final long mRetryIntervalStep;
	private final int mSyncEvery;

	private OtaOptions(@NonNull Builder builder) {
		mPacketSize = builder.mPacketSize;
		mPacketInterval = builder.mPacketInterval;
		mRetries = builder.mRetries;
		mRetryIntervalStep = builder.mRetryIntervalStep;
		mSyncEvery = builder.mSyncEvery;
	}

	/**
	 * Bytes per packet of the image, 0 for the largest one of the connection (MTU - 3, 512 at most). Silicon Labs only:
	 * TI OAD uses the block size asked by the device.
	 */
	public int getPacketSize() {
		return mPacketSize;
	}

	/**
	 * Pause between two packets (ms), 0 for none. Silicon Labs only.
	 */
	public long getPacketInterval() {
		return mPacketInterval;
	}

	/**
	 * New attempts from the beginning after a failure while the device stays connected, 0 for none. Silicon Labs only.
	 */
	public int getRetries() {
		return mRetries;
	}

	/**
	 * Pause added between two packets at each new attempt (ms)
	 */
	public long getRetryIntervalStep() {
		return mRetryIntervalStep;
	}

	/**
	 * One packet in N written with response (the others without), 0 for none. Silicon Labs only.
	 * Real flow control for a bootloader that cannot slow the phone down: Android never keeps more than N packets
	 * waiting, the progress follows what the device received. Costs a round trip every N packets.
	 */
	public int getSyncEvery() {
		return mSyncEvery;
	}

	@SuppressWarnings("unused")
	public static final class Builder {
		private int mPacketSize = 0;
		private long mPacketInterval = 0;
		private int mRetries = 0;
		private long mRetryIntervalStep = 5;
		private int mSyncEvery = 0;

		@NonNull
		public Builder setPacketSize(int bytes) {
			mPacketSize = Math.max(0, bytes);
			return this;
		}

		@NonNull
		public Builder setPacketInterval(long ms) {
			mPacketInterval = Math.max(0, ms);
			return this;
		}

		@NonNull
		public Builder setRetries(int retries) {
			mRetries = Math.max(0, retries);
			return this;
		}

		@NonNull
		public Builder setRetryIntervalStep(long ms) {
			mRetryIntervalStep = Math.max(0, ms);
			return this;
		}

		@NonNull
		public Builder setSyncEvery(int packets) {
			mSyncEvery = Math.max(0, packets);
			return this;
		}

		@NonNull
		public OtaOptions build() {
			return new OtaOptions(this);
		}
	}
}
