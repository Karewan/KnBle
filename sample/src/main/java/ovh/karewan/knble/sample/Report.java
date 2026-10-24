package ovh.karewan.knble.sample;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Output of the scenarios: Logcat (tag KnBleSample), the log area of the screen, and the results file
 * (adb pull /sdcard/Android/data/ovh.karewan.knble.sample/files/results.txt) for the RESULT lines.
 */
final class Report {
	static final String TAG = "KnBleSample";

	private final Consumer<String> mScreen;
	private final File mResults;
	private final SimpleDateFormat mDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

	Report(@NonNull Consumer<String> screen, @NonNull File results) {
		mScreen = screen;
		mResults = results;
	}

	void log(@NonNull String format, Object... args) {
		String line = args.length == 0 ? format : String.format(Locale.US, format, args);
		Log.i(TAG, line);
		mScreen.accept(line);
	}

	/**
	 * Machine readable line: RESULT scenario key=value..., kept in the results file with the phone and the date
	 */
	void result(@NonNull String scenario, @NonNull String format, Object... args) {
		String values = String.format(Locale.US, format, args);
		log("RESULT %s %s", scenario, values);

		String line = String.format(Locale.US, "%s\t%s %s (Android %s, API %d)\tKnBle %s\t%s\t%s%n",
				mDate.format(new Date()), Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
				ovh.karewan.knble.BuildConfig.VERSION_NAME, scenario, values);
		synchronized (this) {
			try (FileWriter w = new FileWriter(mResults, true)) {
				w.write(line);
			} catch (IOException e) {
				Log.e(TAG, "results file", e);
			}
		}
	}
}
