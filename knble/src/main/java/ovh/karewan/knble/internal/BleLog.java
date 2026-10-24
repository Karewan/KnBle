package ovh.karewan.knble.internal;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleLogger;

/**
 * Debug logs of KnBle (KnBle.DEBUG), to Logcat or to the logger of the app. The hot paths test KnBle.DEBUG before
 * building their message.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class BleLog {
	static final String TAG = "KnBle";

	@Nullable
	private static volatile BleLogger sLogger;

	private BleLog() {}

	static void setLogger(@Nullable BleLogger logger) {
		sLogger = logger;
	}

	public static void d(@NonNull String msg) {
		if (KnBle.DEBUG) log(Log.DEBUG, Thread.currentThread().getName() + " " + msg, null);
	}

	public static void w(@NonNull String msg, @NonNull Throwable t) {
		if (KnBle.DEBUG) log(Log.WARN, Thread.currentThread().getName() + " " + msg, t);
	}

	private static void log(int priority, @NonNull String msg, @Nullable Throwable t) {
		BleLogger logger = sLogger;
		if (logger != null) {
			try {
				logger.log(priority, msg, t);
			} catch (RuntimeException ignored) {
				// The logger of the app never breaks KnBle
			}
			return;
		}
		if (priority == Log.WARN) Log.w(TAG, msg, t);
		else Log.d(TAG, msg);
	}
}
