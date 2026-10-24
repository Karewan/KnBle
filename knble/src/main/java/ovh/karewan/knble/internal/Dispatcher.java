package ovh.karewan.knble.internal;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers the callbacks of the app on its Looper (main by default) without ever blocking a thread of KnBle:
 * the producers only add to a lock-free queue and at most one message waits on the Looper, which runs the whole batch.
 * The callbacks keep the order in which they were produced.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Dispatcher {
	// A batch gives the Looper back after this long (a frame of the app), the rest follows in the next message
	private static final long BATCH_BUDGET_NS = 8_000_000;

	private final ConcurrentLinkedQueue<Runnable> mQueue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean mScheduled = new AtomicBoolean();
	private final Handler mHandler;
	private final Runnable mDrain = this::drain;

	public Dispatcher(@NonNull Looper looper) {
		mHandler = new Handler(looper);
	}

	/**
	 * Any thread, never blocks
	 */
	public void post(@NonNull Runnable callback) {
		mQueue.offer(callback);
		if (mScheduled.compareAndSet(false, true)) mHandler.post(mDrain);
	}

	private void drain() {
		long start = System.nanoTime();
		Runnable callback;
		while ((callback = mQueue.poll()) != null) {
			try {
				callback.run();
			} catch (RuntimeException | Error e) {
				// The app crashes on its own exception: the next callbacks still get delivered if it survives
				mHandler.post(mDrain);
				throw e;
			}
			if (System.nanoTime() - start > BATCH_BUDGET_NS) {
				mHandler.post(mDrain);
				return;
			}
		}

		mScheduled.set(false);
		// A callback added between the last poll and the reset of the flag
		if (!mQueue.isEmpty() && mScheduled.compareAndSet(false, true)) mHandler.post(mDrain);
	}
}
