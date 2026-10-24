package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Destination of the logs of KnBle (KnBleSettings.setLogger). Called on the threads of KnBle: must return at once.
 */
public interface BleLogger {
	/**
	 * @param priority android.util.Log.DEBUG or WARN
	 */
	void log(int priority, @NonNull String message, @Nullable Throwable error);
}
