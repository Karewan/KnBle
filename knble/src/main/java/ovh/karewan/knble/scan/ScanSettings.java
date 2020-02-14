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
package ovh.karewan.knble.scan;

public class ScanSettings {
	/**
	 * A special Bluetooth LE scan mode,
	 * results append when others apps installed on the device do a BLE scan
	 */
	public static final int SCAN_MODE_OPPORTUNISTIC = -1;
	/**
	 * Perform Bluetooth LE scan in low power mode.
	 */
	public static final int SCAN_MODE_LOW_POWER = 0;
	/**
	 * Perform Bluetooth LE scan in balanced power mode
	 */
	public static final int SCAN_MODE_BALANCED = 1;
	/**
	 * Scan using highest duty cycle
	 */
	public static final int SCAN_MODE_LOW_LATENCY = 2;




	/**
	 * In Aggressive mode, hw will determine a match sooner
	 * even with feeble signal strength and few number of sightings/match in a duration
	 */
	public static final int MATCH_MODE_AGGRESSIVE = 1;
	/**
	 * For sticky mode, higher threshold of signal strength
	 * and sightings is required before reporting by hw
	 */
	public static final int MATCH_MODE_STICKY = 2;




	/**
	 * Match one advertisement per filter
	 */
	public static final int MATCH_NUM_ONE_ADVERTISEMENT = 1;
	/**
	 * Match few advertisement per filter,
	 * depends on current capability and availibility of the resources in hw
	 */
	public static final int MATCH_NUM_FEW_ADVERTISEMENT = 2;
	/**
	 * Match as many advertisement per filter as hw could allow,
	 * depends on current capability and availibility of the resources in hw
	 */
	public static final int MATCH_NUM_MAX_ADVERTISEMENT = 3;




	/**
	 * Trigger a callback for every Bluetooth advertisement
	 * found that matches the filter criteria.
	 */
	public static final int CALLBACK_TYPE_ALL_MATCHES = 1;
	/**
	 * A result callback is only triggered
	 * for the first advertisement packet received that matches the filter criteria.
	 */
	public static final int CALLBACK_TYPE_FIRST_MATCH = 2;
	/**
	 * Receive a callback when advertisements are no longer received
	 * from a device that has been previously reported by a first match callback.
	 */
	public static final int CALLBACK_TYPE_MATCH_LOST = 4;




	/**
	 * PHY masks (Android 6+)
	 */
	public static final int PHY_LE_1M = 1;
	public static final int PHY_LE_2M = 2;
	public static final int PHY_LE_CODED = 3;
	public static final int PHY_LE_1M_MASK = 1; // BT 5.0 (Compatible 4.0, 4.1, 4.2)
	public static final int PHY_LE_2M_MASK = 2; // BT 5.0 Only
	public static final int PHY_LE_CODED_MASK = 4; // BT 5.0 Only
	public static final int PHY_OPTION_NO_PREFERRED = 0;
	public static final int PHY_OPTION_S2 = 1;
	public static final int PHY_OPTION_S8 = 2;
	public static final int PHY_LE_ALL_SUPPORTED = 255; // All masks supported




	/**
	 * The settings
	 */
	private final long mScanTimeout;
	private final long mAutoRestartScanAfter;
	private final int mScanMode;
	private final int mMatchMode;
	private final int mNbMatch;
	private final int mCallbackType;
	private final int mPhy;
	private final long mReportDelay;
	private final boolean mLegacy;

	/**
	 * Class constructor
	 */
	private ScanSettings(long scanTimeout, int scanMode, int matchMode, int nbMatch, int callbackType, int  phy, long reportDelay, boolean legacy, long autoRestartScanAfter) {
		this.mScanTimeout = scanTimeout;
		this.mAutoRestartScanAfter = autoRestartScanAfter;
		this.mScanMode = scanMode;
		this.mMatchMode = matchMode;
		this.mNbMatch = nbMatch;
		this.mCallbackType = callbackType;
		this.mPhy = phy;
		this.mReportDelay = reportDelay;
		this.mLegacy = legacy;
	}

	/**
	 * Get the scan timeout (Android 4.4+)
	 * @return mScanTimeout
	 */
	public long getScanTimeout() {
		return mScanTimeout;
	}

	/**
	 * Auto restart scan after x ms (Android 4.4+)
	 * @return mAutoRestartScanAfter
	 */
	public long getAutoRestartScanAfter() {
		return mAutoRestartScanAfter;
	}

	/**
	 * Get the scan mode (Android 6+)
	 * @return mScanMode
	 */
	public int getScanMode() {
		return mScanMode;
	}

	/**
	 * Get the match mode (Android 6+)
	 * @return mMatchMode
	 */
	public int getMatchMode() {
		return mMatchMode;
	}

	/**
	 * Get the number of match (Android 6+)
	 * @return mNbMatch
	 */
	public int getNbMatch() {
		return mNbMatch;
	}

	/**
	 * Get the callback type (Android 6+)
	 * @return mCallbackType
	 */
	public int getCallbackType() {
		return mCallbackType;
	}

	/**
	 * Get the report delay (Android 6+)
	 * @return mReportDelay
	 */
	public long getReportDelay() {
		return mReportDelay;
	}

	/**
	 * Get the PHY (Android 8+)
	 * @return mPhy
	 */
	public int getPhy() {
		return mPhy;
	}

	/**
	 * Use legacy advertisements (Android 8+)
	 * @return mLegacy
	 */
	public boolean isLegacy() {
		return mLegacy;
	}

	/**
	 * Builder
	 */
	public static final class Builder {
		private long mScanTimeout = 0;
		private long mAutoRestartScanAfter = 0;
		private int mScanMode = SCAN_MODE_LOW_LATENCY;
		private int mMatchMode = MATCH_MODE_AGGRESSIVE;
		private int mNbMatch = MATCH_NUM_MAX_ADVERTISEMENT;
		private int mCallbackType = CALLBACK_TYPE_ALL_MATCHES;
		private int mPhy = PHY_LE_ALL_SUPPORTED;
		private long mReportDelay = 0;
		private boolean mLegacy = true;

		public Builder setScanTimeout(long timeout) {
			this.mScanTimeout = timeout;
			return this;
		}

		public Builder setAutoRestartScanAfter(long time) {
			this.mAutoRestartScanAfter = time;
			return this;
		}

		public Builder setScanMode(int mode) {
			this.mScanMode = mode;
			return this;
		}

		public Builder setMatchMode(int matchMode) {
			this.mMatchMode = matchMode;
			return this;
		}

		public Builder setNbMatch(int nbMatch) {
			this.mNbMatch = nbMatch;
			return this;
		}

		public Builder setCallbackType(int callbackType) {
			this.mCallbackType = callbackType;
			return this;
		}

		public Builder setPhy(int Phy) {
			this.mPhy = Phy;
			return this;
		}

		public Builder setReportDelay(long reportDelay) {
			this.mReportDelay = reportDelay;
			return this;
		}

		public Builder setLegacy(boolean legacy) {
			this.mLegacy = legacy;
			return this;
		}

		public ScanSettings build() {
			return new ScanSettings(mScanTimeout, mScanMode, mMatchMode, mNbMatch, mCallbackType, mPhy, mReportDelay, mLegacy, mAutoRestartScanAfter);
		}
	}
}
