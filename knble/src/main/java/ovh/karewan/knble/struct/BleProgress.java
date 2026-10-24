package ovh.karewan.knble.struct;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Progress of a transfer (splittedWrite, OTA): at most one every 100 ms, plus the last one.
 * With writes without response, Android accepts up to about 200 KB before the device receives them: the progress and
 * the rate are those of the data taken by Android, the end of an OTA is the end of its delivery.
 */
public final class BleProgress {
	private final long mSentBytes, mTotalBytes;
	private final int mPackets, mTotalPackets;
	private final long mElapsedMs, mBytesPerSecond;

	public BleProgress(long sentBytes, long totalBytes, int packets, int totalPackets, long elapsedMs, long bytesPerSecond) {
		mSentBytes = sentBytes;
		mTotalBytes = totalBytes;
		mPackets = packets;
		mTotalPackets = totalPackets;
		mElapsedMs = elapsedMs;
		mBytesPerSecond = bytesPerSecond;
	}

	public long getSentBytes() {
		return mSentBytes;
	}

	public long getTotalBytes() {
		return mTotalBytes;
	}

	/**
	 * Packets (or blocks) written
	 */
	public int getPackets() {
		return mPackets;
	}

	public int getTotalPackets() {
		return mTotalPackets;
	}

	/**
	 * 0 to 100
	 */
	public int getPercent() {
		return mTotalBytes <= 0 ? 100 : (int) (mSentBytes * 100 / mTotalBytes);
	}

	/**
	 * Since the start of the transfer
	 */
	public long getElapsedMs() {
		return mElapsedMs;
	}

	/**
	 * Rate of the last second (the average one on the last progress)
	 */
	public long getBytesPerSecond() {
		return mBytesPerSecond;
	}

	/**
	 * Average rate since the start
	 */
	public long getAverageBytesPerSecond() {
		return mElapsedMs <= 0 ? 0 : mSentBytes * 1000 / mElapsedMs;
	}

	/**
	 * The rate for a human: "820 B/s", "41.2 KB/s", "1.05 MB/s"
	 */
	@NonNull
	public String getRate() {
		return formatRate(mBytesPerSecond);
	}

	@NonNull
	public static String formatRate(long bytesPerSecond) {
		if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
		if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
		return String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
	}

	@NonNull
	@Override
	public String toString() {
		return getPercent() + " % (" + mSentBytes + "/" + mTotalBytes + " B, " + getRate() + ")";
	}
}
