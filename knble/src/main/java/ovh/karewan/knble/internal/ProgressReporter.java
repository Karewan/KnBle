package ovh.karewan.knble.internal;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicReference;

import ovh.karewan.knble.struct.BleProgress;

/**
 * Progress of a transfer for the app, like the ProgressReporter of KnHttp: at most one event every 100 ms plus the last
 * one, coalesced (a slow app gets the latest values, never a backlog). Costs a clock read per packet; the rate and the
 * BleProgress are only made for the events that go to the app.
 */
final class ProgressReporter {
	private static final long INTERVAL_NS = 100_000_000, RATE_WINDOW_NS = 1_000_000_000;
	// Samples of the events sent (10 per second): the rate of the last second
	private static final int SAMPLES = 16;

	interface Sink {
		void onProgress(@NonNull BleProgress progress);
	}

	private final Dispatcher mDispatcher;
	private final Sink mSink;
	private final AtomicReference<BleProgress> mLatest = new AtomicReference<>();
	private final Runnable mDeliver = new Runnable() {
		@Override
		public void run() {
			BleProgress p = mLatest.getAndSet(null);
			if (p != null) mSink.onProgress(p);
		}
	};

	// Thread of the device only
	private final long[] mSampleNs = new long[SAMPLES], mSampleBytes = new long[SAMPLES];
	private int mSamples, mNextSample;
	private long mStartNs, mLastNs;

	ProgressReporter(@NonNull Dispatcher dispatcher, @NonNull Sink sink) {
		mDispatcher = dispatcher;
		mSink = sink;
		start();
	}

	/**
	 * Start of the transfer (again for a new attempt)
	 */
	void start() {
		mStartNs = System.nanoTime();
		mLastNs = mStartNs - INTERVAL_NS;
		mSamples = 0;
		mNextSample = 0;
		sample(mStartNs, 0);
	}

	/**
	 * Thread of the device only
	 */
	void update(long sentBytes, long totalBytes, int packets, int totalPackets) {
		long now = System.nanoTime();
		boolean last = packets >= totalPackets;
		if (!last && now - mLastNs < INTERVAL_NS) return;
		mLastNs = now;

		long rate = last ? perSecond(sentBytes, now - mStartNs) : recentRate(now, sentBytes);
		sample(now, sentBytes);
		BleProgress p = new BleProgress(sentBytes, totalBytes, packets, totalPackets, (now - mStartNs) / 1_000_000, rate);
		if (mLatest.getAndSet(p) == null) mDispatcher.post(mDeliver);
	}

	private void sample(long ns, long bytes) {
		mSampleNs[mNextSample] = ns;
		mSampleBytes[mNextSample] = bytes;
		mNextSample = (mNextSample + 1) % SAMPLES;
		if (mSamples < SAMPLES) mSamples++;
	}

	/**
	 * Bytes per second since the oldest sample of the last second
	 */
	private long recentRate(long now, long bytes) {
		int oldest = -1;
		for (int i = 0; i < mSamples; i++) {
			int k = (mNextSample - 1 - i + SAMPLES) % SAMPLES;
			if (now - mSampleNs[k] > RATE_WINDOW_NS && oldest >= 0) break;
			oldest = k;
		}
		return oldest < 0 ? 0 : perSecond(bytes - mSampleBytes[oldest], now - mSampleNs[oldest]);
	}

	private static long perSecond(long bytes, long ns) {
		return ns <= 0 ? 0 : bytes * 1_000_000_000L / ns;
	}
}
