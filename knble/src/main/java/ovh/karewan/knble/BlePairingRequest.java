package ovh.karewan.knble;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import ovh.karewan.knble.struct.BleDevice;

/**
 * A pairing of a device KnBle works with needs the user (onPairingRequest of BleBondCallback, BleClassicCallback,
 * BleGattCallback or BleClassicServerCallback). KnBle shows no UI: when canAnswer(), the app shows its own (a dialog, an
 * activity) and answers before getDeadline(), from any thread: setPin() with the code, or cancel(). Else the dialog of
 * the system asks the user: the app may show a hint.
 */
public final class BlePairingRequest {
	/** Android asks a code: PIN of a Bluetooth classic device (legacy pairing, 1 to 16 characters), or passkey of a BLE device (6 digits) */
	public static final int VARIANT_PIN = 0;
	/** The user types the passkey of a Bluetooth classic device in the dialog of the system */
	public static final int VARIANT_PASSKEY = 1;
	/** The user checks that the device shows getPairingKey() and confirms in the dialog of the system (numeric comparison) */
	public static final int VARIANT_PASSKEY_CONFIRMATION = 2;
	/** The user accepts the pairing in the dialog of the system (just works) */
	public static final int VARIANT_CONSENT = 3;
	/** The user types getPairingKey() on the device (its keyboard) */
	public static final int VARIANT_DISPLAY_PASSKEY = 4;
	/** The user types getPairingKey() on the device (legacy pairing of a keyboard) */
	public static final int VARIANT_DISPLAY_PIN = 5;
	/** Android asks a PIN of 16 characters (Bluetooth classic device) */
	public static final int VARIANT_PIN_16_DIGITS = 7;
	/** getPairingKey() when the pairing has none */
	public static final int NO_KEY = -1;
	/**
	 * Time to answer a pairing, from its request: the Bluetooth stack ends it after that (the timeout of the pairing
	 * protocols, 30 s for BLE and for Bluetooth classic)
	 */
	public static final long ANSWER_TIMEOUT_MS = 30000;

	/**
	 * Answer of the app, given to KnBle (any thread)
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public interface Responder {
		void setPin(@NonNull byte[] pin);

		void cancel();
	}

	@NonNull
	private final BleDevice mDevice;
	private final int mVariant, mPairingKey;
	private final boolean mPasskey, mCanAnswer, mRepairing;
	@NonNull
	private final Responder mResponder;
	private final AtomicBoolean mAnswered = new AtomicBoolean();
	// SystemClock.elapsedRealtime() after which the answer comes too late
	private final long mDeadline;

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public BlePairingRequest(@NonNull BleDevice device, int variant, boolean passkey, int pairingKey, boolean canAnswer, @NonNull Responder responder) {
		this(device, variant, passkey, pairingKey, canAnswer, false, responder);
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public BlePairingRequest(@NonNull BleDevice device, int variant, boolean passkey, int pairingKey, boolean canAnswer, boolean repairing,
							 @NonNull Responder responder) {
		mRepairing = repairing;
		mDeadline = SystemClock.elapsedRealtime() + ANSWER_TIMEOUT_MS;
		mDevice = device;
		mVariant = variant;
		mPasskey = passkey;
		mPairingKey = pairingKey;
		mCanAnswer = canAnswer;
		mResponder = responder;
	}

	@NonNull
	public BleDevice getDevice() {
		return mDevice;
	}

	/**
	 * @return VARIANT_PIN, VARIANT_PIN_16_DIGITS (a code asked), or the variant answered by the user in the dialog of the
	 * system (VARIANT_PASSKEY_CONFIRMATION, VARIANT_CONSENT...)
	 */
	public int getVariant() {
		return mVariant;
	}

	/**
	 * The app may give the code (setPin) or refuse (cancel), and the dialog of the system is not shown when the callback
	 * returns true. False: the dialog of the system asks the user whatever the callback returns (a number to confirm,
	 * or Android 17+ with an app targeting API 37+, which may no longer give pairing codes), setPin does nothing.
	 */
	public boolean canAnswer() {
		return mCanAnswer;
	}

	/**
	 * The code asked is the passkey of a BLE pairing: 6 digits, shown by the device or written in its documentation (a
	 * numeric keyboard in the dialog of the app). False: the PIN of a Bluetooth classic device (often 0000 or 1234).
	 */
	public boolean isPasskey() {
		return mPasskey;
	}

	/**
	 * Number of the pairing (6 digits) for VARIANT_PASSKEY_CONFIRMATION, VARIANT_DISPLAY_PASSKEY and VARIANT_DISPLAY_PIN:
	 * the one to compare with the device, or to type on it. NO_KEY otherwise.
	 */
	public int getPairingKey() {
		return mPairingKey;
	}

	/**
	 * Android 17+: the system pairs again a bonded device that lost its keys (BleGattCallback.onBondLost: reset, bond
	 * removed on its side). Accepting replaces the old bond, refusing keeps it (the device stays unusable until paired
	 * again).
	 */
	public boolean isRepairing() {
		return mRepairing;
	}

	/**
	 * SystemClock.elapsedRealtime() after which the answer comes too late: the pairing has failed (its operation fails,
	 * a setPin is ignored). The dialog of the app closes itself then.
	 */
	public long getDeadline() {
		return mDeadline;
	}

	/**
	 * Milliseconds left to answer, 0 once too late: a countdown in the dialog of the app
	 */
	public long getTimeLeftMs() {
		return Math.max(0, mDeadline - SystemClock.elapsedRealtime());
	}

	/**
	 * Gives the code to Android (when canAnswer()). Only the first answer (setPin or cancel) counts.
	 * @param pin The code: 1 to 6 digits for a passkey (leading zeros added), 1 to 16 characters for a PIN
	 * @throws IllegalArgumentException when the code cannot be one (empty, too long, not digits for a passkey)
	 */
	public void setPin(@NonNull String pin) {
		byte[] bytes = encode(pin, mPasskey);
		if (bytes == null) throw new IllegalArgumentException(mPasskey ? "passkey: 1 to 6 digits" : "PIN: 1 to 16 bytes in UTF-8");
		if (mCanAnswer && mAnswered.compareAndSet(false, true)) mResponder.setPin(bytes);
	}

	/**
	 * The user refused, or the app stops waiting: the operation that needed the pairing fails at once with
	 * BleError.CANCELLED (createBond, connectClassic, the GATT operation) and KnBle does not start the pairing again by
	 * itself for a while. Android ends the pairing at once on Android 7 to 11, for a createBond of the app on Android 17+,
	 * and for a classic createBond of the app on Android 12 to 16 when the app answers the code; else it ends by itself
	 * within 30 s, and while the dialog of the system shows (canAnswer() false) the user may still accept it.
	 */
	public void cancel() {
		if (mAnswered.compareAndSet(false, true)) mResponder.cancel();
	}

	/**
	 * setPin() or cancel() already called
	 */
	public boolean isAnswered() {
		return mAnswered.get();
	}

	/**
	 * Bytes of a code for Android, null when it cannot be one. A passkey goes as 6 ASCII digits (the stack reads 6
	 * bytes), a PIN as its UTF-8 bytes (BluetoothDevice.convertPinToBytes).
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@Nullable
	public static byte[] encode(@Nullable String pin, boolean passkey) {
		if (pin == null || pin.isEmpty()) return null;
		if (passkey) {
			if (pin.length() > 6) return null;
			for (int i = 0; i < pin.length(); i++) {
				char c = pin.charAt(i);
				if (c < '0' || c > '9') return null;
			}
			return ("000000".substring(pin.length()) + pin).getBytes(StandardCharsets.US_ASCII);
		}
		byte[] bytes = pin.getBytes(StandardCharsets.UTF_8);
		return bytes.length > 16 ? null : bytes;
	}

	@NonNull
	@Override
	public String toString() {
		return "BlePairingRequest{" + mDevice.getMac() + ", variant=" + mVariant + ", passkey=" + mPasskey + ", canAnswer=" + mCanAnswer
				+ (mRepairing ? ", repairing" : "") + "}";
	}
}
