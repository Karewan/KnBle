package ovh.karewan.knble.benchhost;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Starts the bench servers (BLE GATT, Bluetooth classic SPP) and shows their events. The screen stays on: screen off, Android may slow the radio down.
 */
public class MainActivity extends Activity {
	private static final int MAX_LINES = 200;

	private final ArrayDeque<String> mLines = new ArrayDeque<>();
	private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
	private TextView mLog;
	private BenchServer mServer;
	private ClassicServer mClassic;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		}

		ScrollView scroll = new ScrollView(this);
		mLog = new TextView(this);
		mLog.setTextSize(12);
		mLog.setPadding(24, 24, 24, 24);
		scroll.addView(mLog);
		setContentView(scroll);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
				|| checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
			requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}, 1);
			log("Bluetooth permissions needed, then start the app again");
			return;
		}
		mServer = new BenchServer(this, this::log);
		mServer.start();
		// Bluetooth classic bench (SPP, insecure) for the classic scenarios of the sample
		mClassic = new ClassicServer(this, this::log);
		mClassic.start();
	}

	@Override
	protected void onDestroy() {
		if (mServer != null) mServer.stop();
		if (mClassic != null) mClassic.stop();
		super.onDestroy();
	}

	private void log(String message) {
		android.util.Log.i("KnBleBenchHost", message);
		runOnUiThread(() -> {
			mLines.addLast(mTime.format(new Date()) + "  " + message);
			while (mLines.size() > MAX_LINES) mLines.removeFirst();
			mLog.setText(String.join("\n", mLines));
		});
	}
}
