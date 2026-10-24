package ovh.karewan.knble.sample;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;

import ovh.karewan.knble.BlePairingRequest;

/**
 * What a real app does with the pairing requests of KnBle (onPairingRequest of its callbacks, main thread): its own
 * dialog for the code when KnBle can give it, else a hint while the dialog of the system asks the user. The runs from
 * adb answer with the code given by "--es pin 1234" ("--es pin cancel" refuses).
 */
final class PairingUi {
	@Nullable
	private static WeakReference<Activity> sActivity;
	// Answer of the runs from adb, null: the dialog of the app
	@Nullable
	static volatile String sAutoPin;
	@Nullable
	static volatile Report sReport;
	// The last request, and whether the app answered it (the scenarios check what Android asked)
	@Nullable
	static volatile BlePairingRequest sLast;
	static volatile boolean sLastAnsweredByApp;

	private PairingUi() {}

	static void attach(@Nullable Activity activity) {
		sActivity = activity == null ? null : new WeakReference<>(activity);
	}

	/**
	 * @return true when the app answers the code itself (no dialog of the system)
	 */
	static boolean onPairingRequest(@NonNull BlePairingRequest request) {
		Report report = sReport;
		if (report != null) {
			report.log("Pairing request of %s: variant %d, %s, key %d, %s", request.getDevice().getMac(), request.getVariant(),
					request.isPasskey() ? "passkey" : "PIN", request.getPairingKey(), request.canAnswer() ? "answered by the app" : "answered in the dialog of the system");
		}
		Activity activity = sActivity == null ? null : sActivity.get();
		sLast = request;
		sLastAnsweredByApp = false;

		if (!request.canAnswer()) {
			// The dialog of the system asks the user (KnBle brings it over the app): a hint only
			if (activity != null) {
				String text = request.getPairingKey() != BlePairingRequest.NO_KEY
						? String.format(Locale.US, "Check that %s shows %06d, then confirm in the Android window", request.getDevice().getMac(), request.getPairingKey())
						: "Confirm the pairing of " + request.getDevice().getMac() + " in the Android window";
				Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
			}
			return false;
		}

		String auto = sAutoPin;
		if (auto != null) {
			if ("cancel".equals(auto)) request.cancel();
			else request.setPin(auto);
			sLastAnsweredByApp = true;
			return true;
		}
		if (activity == null || activity.isFinishing()) return false;

		EditText input = new EditText(activity);
		input.setInputType(request.isPasskey() ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
		input.setHint(request.isPasskey() ? "6 digits" : "PIN (often 0000 or 1234)");
		new AlertDialog.Builder(activity)
				.setTitle("Pairing with " + (request.getDevice().getName() != null ? request.getDevice().getName() : request.getDevice().getMac()))
				.setMessage((request.isPasskey() ? "Type the passkey of the device" : "Type the PIN of the device")
						+ String.format(Locale.US, " (%d s)", request.getTimeLeftMs() / 1000))
				.setView(input)
				.setCancelable(false)
				.setPositiveButton("Pair", (d, w) -> {
					try {
						request.setPin(input.getText().toString());
						sLastAnsweredByApp = true;
					} catch (IllegalArgumentException e) {
						Toast.makeText(activity, "Not a valid code", Toast.LENGTH_SHORT).show();
						request.cancel();
					}
				})
				.setNegativeButton("Cancel", (d, w) -> request.cancel())
				.show();
		return true;
	}
}
