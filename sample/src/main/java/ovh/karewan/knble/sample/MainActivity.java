package ovh.karewan.knble.sample;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ovh.karewan.knble.KnBle;

/**
 * One button per scenario, run one at a time on a background thread. The results are written in the log area,
 * in Logcat (tag KnBleSample) and, for the RESULT lines, in files/results.txt.
 * Needs the benches of testbench/ (ESP32-C3 for BLE, ESP32 for classic, or a phone running testbench/benchhost) near the
 * phone.
 * A scenario can also be started from adb, by a part of its label:
 * adb shell am start -n ovh.karewan.knble.sample/.MainActivity --es run "stream"
 */
public class MainActivity extends Activity {
	private static final int REQUEST_PERMISSIONS = 1;
	private static final int MAX_LOG_LINES = 300;

	private final ExecutorService mScenarioThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "scenario"));
	private final Map<String, Runnable> mActions = new LinkedHashMap<>();
	private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
	private final ArrayDeque<String> mLines = new ArrayDeque<>();

	private TextView mLog;
	private ScrollView mLogScroll;
	private ProgressBar mProgress;
	private LinearLayout mButtons;

	private Report mReport;
	private Scenarios mScenarios;
	private ContractScenarios mContract;
	private FeatureScenarios mFeatures;
	private ClassicScenarios mClassic;
	private PairingScenarios mPairing;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Screen on during the measurements: screen off, Android slows the scans down (and pauses the unfiltered ones),
		// then Doze. Also shown over the lock screen, for the runs started from adb
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		} else {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		}
		setContentView(R.layout.activity_main);

		mLog = findViewById(R.id.log);
		mLogScroll = findViewById(R.id.log_scroll);
		mProgress = findViewById(R.id.progress);
		mButtons = findViewById(R.id.buttons);

		final TextView info = findViewById(R.id.info);
		info.setText("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL
				+ " · KnBle " + ovh.karewan.knble.BuildConfig.VERSION_NAME);

		mReport = new Report(this::log, new File(getExternalFilesDir(null), "results.txt"));
		mScenarios = new Scenarios(mReport, this::showProgress);
		mContract = new ContractScenarios(mReport, this::showProgress);
		mFeatures = new FeatureScenarios(mReport, this::showProgress);
		mClassic = new ClassicScenarios(mReport, this::showProgress);
		mPairing = new PairingScenarios(mReport, this::showProgress);
		PairingUi.sReport = mReport;
		PairingUi.attach(this);

		addButton("Clear log", () -> {
			mLines.clear();
			mLog.setText("");
		}, false);
		addButton("Permissions", this::requestBlePermissions, false);
		addButton("KnBle debug logs on / off", () -> {
			KnBle.DEBUG = !KnBle.DEBUG;
			log("KnBle.DEBUG = " + KnBle.DEBUG);
		}, false);

		addButton("Bluetooth: device information (as nRF Connect)", () -> {
			for (String line : KnBle.getAdapterInfo(this).toString().split("\n")) mReport.log("%s", line);
		}, true);

		addButton("Run all (about 20 min, no pairing)", this::runAll, true);

		addButton("Scan: duplicate filtering of this phone (20 s)", mScenarios::scanDiagnostic, true);
		addButton("Scan: cost of getName() on the main thread", mScenarios::nameCost, true);
		addButton("Scan: bench advertising modes", mScenarios::advertisingModes, true);
		addButton("Scan: legacy only versus default settings", mScenarios::scanLegacyVersusDefault, true);
		addButton("Scan: KnBle callbacks versus a plain Android scan", () -> mScenarios.scanVersusRaw(this), true);
		addButton("Scan: device lost then found again (bench connected 20 s)", mScenarios::scanLostAndFound, true);
		addButton("Scan: background scan to a PendingIntent (20 s)", () -> mFeatures.backgroundScan(this), true);

		addButton("Connect the bench", mScenarios::connect, true);
		addButton("Disconnect the bench", mScenarios::disconnect, true);
		addButton("Stress: 30 connections / disconnections", () -> mScenarios.stressConnections(30), true);
		addButton("Link loss (the bench disconnects)", mScenarios::linkLoss, true);

		addButton("Write 256 KB without response", () -> mScenarios.sinkWrite(256 * 1024, true), true);
		addButton("Write 64 KB with response", () -> mScenarios.sinkWrite(64 * 1024, false), true);
		addButton("Notifications: stream of 2000 x 512 bytes", () -> mScenarios.notifyStream(2000, 0), true);
		addButton("Notifications: stream of 5000 x 20 bytes", () -> mScenarios.notifyStream(5000, 20), true);
		addButton("OTA Silicon Labs 256 KB", () -> mScenarios.silabsOta(256 * 1024), true);
		addButton("OTA Silicon Labs, bootloader without flow control", () -> mScenarios.silabsOtaWithoutFlowControl(64 * 1024), true);
		addButton("OTA TI 128 KB, KnBle thread", () -> mScenarios.tiOad(128 * 1024, false), true);
		addButton("OTA TI 128 KB, through the main thread", () -> mScenarios.tiOad(128 * 1024, true), true);
		addButton("Busy main: OTA Silicon Labs 256 KB", () -> mScenarios.silabsOtaBusyMain(256 * 1024), true);
		addButton("Busy main: OTA TI 128 KB", () -> mScenarios.tiOadBusyMain(128 * 1024), true);
		addButton("DFU reboot (same MAC, other services)", mScenarios::dfuReboot, true);
		addButton("KnBle OTA: Silicon Labs 256 KB", () -> mScenarios.silabsOtaLib(256 * 1024), true);
		addButton("KnBle OTA: TI 128 KB", () -> mScenarios.tiOadLib(128 * 1024), true);
		addButton("KnBle OTA busy main: Silicon Labs 256 KB", () -> mScenarios.silabsOtaLibBusyMain(256 * 1024), true);
		addButton("KnBle OTA busy main: TI 128 KB", () -> mScenarios.tiOadLibBusyMain(128 * 1024), true);

		addButton("Contract: blocking progress callback", mContract::blockingCallback, true);
		addButton("Contract: frozen main thread", mContract::frozenMainThread, true);
		addButton("Contract: API latency on the main thread", mContract::apiLatency, true);
		addButton("Contract: reads enqueued from the main thread", mContract::enqueueRace, true);

		addButton("Bug: disableNotify without enableNotify", mContract::disableNotifyWithoutEnable, true);
		addButton("Bug: readDesc of a missing descriptor", mContract::readMissingDescriptor, true);
		addButton("Bug: indicate-only characteristic", mContract::indicateOnly, true);
		addButton("Bug: same characteristic UUID in two services", mContract::sameUuidTwoServices, true);
		addButton("Bug: disconnect then connect right away", mContract::disconnectThenConnect, true);
		addButton("Encrypted characteristic (pairing)", mContract::encryptedRead, true);

		addButton("Features: request, connection parameters, reliable write", mFeatures::benchFeatures, true);
		addButton("Features: auto-reconnection after a link loss", mFeatures::autoReconnect, true);
		addButton("Features: long characteristic (512 bytes)", mFeatures::longCharacteristic, true);
		addButton("Features: connection parameters asked by the device (ESP32-C3)", mFeatures::deviceConnectionParameters, true);
		addButton("Companion: associate the bench (pick it in the dialog)", () -> mFeatures.companionAssociate(this), true);
		addButton("Peripheral: KnBle server and advertising (3 min)", () -> mFeatures.peripheral(180_000), true);
		addButton("Central of the KnBle peripheral (other phone)", () -> mFeatures.peripheralCentral(2000), true);

		// Bluetooth classic bench (testbench/esp32-classic)
		addButton("Classic: run all (about 3 min)", mClassic::classicRunAll, true);
		addButton("Classic: discovery", mClassic::classicDiscovery, true);
		addButton("Classic: connect", mClassic::classicConnect, true);
		addButton("Classic: secure connection (pairing)", mClassic::classicSecureConnect, true);
		addButton("Classic: pairing with a code (bench passkey mode)", mClassic::classicPairingWithCode, true);
		addButton("Classic pairing: run all (legacy PIN by the app and in advance, lost bond)", mClassic::classicPairingRunAll, true);
		addButton("Classic pairing: legacy PIN typed by the app (bench PIN mode)", mClassic::classicLegacyPinByApp, true);
		addButton("Classic pairing: legacy PIN given in advance", mClassic::classicLegacyPinInAdvance, true);
		addButton("Classic pairing: numeric comparison (confirm on the phone)", mClassic::classicNumericComparison, true);
		addButton("Classic pairing: lost bond (the bench forgets the phone)", mClassic::classicLostBond, true);
		addButton("Classic: latency (200 commands)", () -> mClassic.classicLatency(200), true);
		addButton("Classic: receive 1 MB", mClassic::classicReceive, true);
		addButton("Classic: send 1 MB in 1 KB writes", () -> mClassic.classicSend(1024), true);
		addButton("Classic: send 1 MB in 64 KB writes", () -> mClassic.classicSend(65536), true);
		addButton("Classic: receiver stream with commands (20 s)", () -> mClassic.classicReceiver(20), true);
		addButton("Classic: frozen main thread", mClassic::classicFrozenMain, true);
		addButton("Classic: link loss and auto-reconnection", mClassic::classicLinkLoss, true);
		addButton("Classic: plain Android socket (reference)", mClassic::classicRawSocket, true);
		addButton("Classic: KnBle versus plain socket (6 rounds)", () -> mClassic.classicVersusRaw(6), true);
		// This phone as the classic bench, with the server of KnBle: the classic scenarios run on another phone
		addButton("Classic server: KnBle bench server, insecure (10 min)", () -> new ClassicServerScenario(mReport).run(600_000, false), true);
		addButton("Classic server: KnBle bench server, secure (10 min)", () -> new ClassicServerScenario(mReport).run(600_000, true), true);

		// Pairings, as a real app runs them (dialog of the app for the codes it can give, else the dialog of the system)
		addButton("Pairing: encrypted characteristic of the bench (stack pairing)", mPairing::bleEncryptedRead, true);
		addButton("Pairing: createBond of the bench", mPairing::bleCreateBond, true);
		addButton("Pairing: remove the bond of the bench", mPairing::bleRemoveBond, true);
		addButton("Pairing: run all BLE (passkey by the app, lost bond)", mPairing::bleRunAll, true);
		addButton("Pairing: passkey of the bench typed by the app (ESP32-C3)", mPairing::blePasskeyByApp, true);
		addButton("Pairing: numeric comparison (confirm on the phone, ESP32-C3)", mPairing::bleNumericComparison, true);
		addButton("Pairing: lost bond (the bench forgets the phone)", mPairing::bleLostBond, true);
		addButton("Pairing: createBond of the classic device", () -> mPairing.classicCreateBond(ClassicScenarios.sPin), true);
		addButton("Pairing: remove the bond of the classic device", mPairing::classicRemoveBond, true);

		requestBlePermissions();
		runFromIntent(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		runFromIntent(intent);
	}

	@Override
	protected void onDestroy() {
		PairingUi.attach(null);
		mScenarioThread.shutdownNow();
		super.onDestroy();
	}

	private void runAll() {
		List<Runnable> all = new ArrayList<>();
		all.add(mScenarios::scanDiagnostic);
		all.add(mScenarios::nameCost);
		all.add(mScenarios::scanLegacyVersusDefault);
		all.add(() -> mScenarios.scanVersusRaw(this));
		all.add(() -> mFeatures.backgroundScan(this));
		all.add(mScenarios::connect);
		all.add(() -> mScenarios.sinkWrite(256 * 1024, true));
		all.add(() -> mScenarios.sinkWrite(64 * 1024, false));
		all.add(() -> mScenarios.notifyStream(2000, 0));
		all.add(() -> mScenarios.notifyStream(5000, 20));
		all.add(() -> mScenarios.silabsOta(256 * 1024));
		all.add(() -> mScenarios.tiOad(128 * 1024, false));
		all.add(() -> mScenarios.tiOad(128 * 1024, true));
		all.add(() -> mScenarios.silabsOtaBusyMain(256 * 1024));
		all.add(() -> mScenarios.tiOadBusyMain(128 * 1024));
		all.add(() -> mScenarios.silabsOtaWithoutFlowControl(64 * 1024));
		all.add(() -> mScenarios.silabsOtaLib(256 * 1024));
		all.add(() -> mScenarios.tiOadLib(128 * 1024));
		all.add(() -> mScenarios.silabsOtaLibBusyMain(256 * 1024));
		all.add(() -> mScenarios.tiOadLibBusyMain(128 * 1024));
		all.add(mContract::blockingCallback);
		all.add(mContract::frozenMainThread);
		all.add(mContract::apiLatency);
		all.add(mContract::enqueueRace);
		all.add(mContract::disableNotifyWithoutEnable);
		all.add(mContract::readMissingDescriptor);
		all.add(mContract::indicateOnly);
		all.add(mContract::sameUuidTwoServices);
		all.add(mContract::disconnectThenConnect);
		all.add(mFeatures::longCharacteristic);
		all.add(mFeatures::deviceConnectionParameters);
		all.add(mFeatures::benchFeatures);
		all.add(mScenarios::linkLoss);
		all.add(mFeatures::autoReconnect);
		all.add(mScenarios::scanLostAndFound);
		all.add(() -> mScenarios.stressConnections(30));
		all.add(mScenarios::dfuReboot);
		all.add(mScenarios::advertisingModes);

		for (Runnable scenario : all) {
			try {
				scenario.run();
			} catch (RuntimeException e) {
				mReport.log("Scenario crashed: %s", e);
			}
		}
		mReport.log("Run all: done");
	}

	private void requestBlePermissions() {
		List<String> missing = new ArrayList<>();
		String[] needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}
				: new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
		for (String p : needed) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
		if (missing.isEmpty()) log("Bluetooth permissions granted");
		else requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		for (int i = 0; i < permissions.length; i++) log(permissions[i] + (grantResults[i] == PackageManager.PERMISSION_GRANTED ? " granted" : " DENIED"));
	}

	private void addButton(String label, Runnable action, boolean background) {
		final Runnable logged = () -> {
			mReport.log("▶ " + label);
			if (!background) {
				action.run();
				return;
			}
			mScenarioThread.execute(() -> {
				try {
					action.run();
				} catch (RuntimeException e) {
					mReport.log("Scenario crashed: %s", e);
				}
				// End marker for the scripts
				mReport.log("■ " + label);
				runOnUiThread(() -> mProgress.setProgress(0));
			});
		};
		mActions.put(label, logged);

		final Button button = new Button(this);
		button.setText(label);
		button.setAllCaps(false);
		button.setOnClickListener(v -> logged.run());
		mButtons.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	/**
	 * Runs the first scenario whose label contains the "run" extra (case insensitive)
	 */
	private void runFromIntent(Intent intent) {
		// --es bench pc: the phone bench (testbench/benchhost) instead of the ESP32-C3
		if (intent != null && intent.hasExtra("bench")) {
			boolean pc = "pc".equals(intent.getStringExtra("bench"));
			if (pc != Scenarios.sPcBench) {
				Scenarios.sPcBench = pc;
				Scenarios.sLink = null;
			}
		}
		// --ez debug true: logs of KnBle
		if (intent != null && intent.hasExtra("debug")) KnBle.DEBUG = intent.getBooleanExtra("debug", false);
		// --es pin 1234 (or cancel, or none): the answer of the user to the pairing dialog of the app
		if (intent != null && intent.hasExtra("pin")) {
			String pin = intent.getStringExtra("pin");
			PairingUi.sAutoPin = "none".equals(pin) ? null : pin;
		}
		// --es classic <MAC> [--ez classicsecure true] [--es classicuuid <service UUID>] [--es classicpin 1234]: the classic
		// device of the scenarios (the ESP32 bench is found by its name without it)
		if (intent != null && intent.hasExtra("classic")) {
			ClassicScenarios.setTarget(intent.getStringExtra("classic"), intent.getBooleanExtra("classicsecure", false), intent.getStringExtra("classicuuid"));
		}
		if (intent != null && intent.hasExtra("classicpin")) {
			String pin = intent.getStringExtra("classicpin");
			ClassicScenarios.sPin = "none".equals(pin) ? null : pin;
		}
		final String run = intent != null ? intent.getStringExtra("run") : null;
		if (run == null) return;

		// A label starting with it first ("Pairing: lost bond" is not "Classic pairing: lost bond"), else containing it
		String wanted = run.toLowerCase(Locale.ROOT);
		Runnable found = null;
		for (Map.Entry<String, Runnable> entry : mActions.entrySet()) {
			String label = entry.getKey().toLowerCase(Locale.ROOT);
			if (label.startsWith(wanted)) {
				found = entry.getValue();
				break;
			}
			if (found == null && label.contains(wanted)) found = entry.getValue();
		}
		if (found != null) found.run();
		else log("No scenario matching \"" + run + "\"");
	}

	private void showProgress(int permille) {
		mProgress.setProgress(permille);
	}

	private void log(String message) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			runOnUiThread(() -> append(message));
			return;
		}
		append(message);
	}

	/**
	 * Last lines only: a TextView growing for the whole session would make the main thread (where KnBle delivers its
	 * callbacks) slower and slower, and distort the measurements
	 */
	private void append(String message) {
		mLines.addLast(mTimeFormat.format(new Date()) + "  " + message);
		while (mLines.size() > MAX_LOG_LINES) mLines.removeFirst();
		mLog.setText(String.join("\n", mLines));
		mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
	}
}
