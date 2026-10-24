package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import ovh.karewan.knble.BlePairingRequest;

/**
 * Result of KnBle.createBond. Only onBonded() is mandatory: a lambda works.
 */
public interface BleBondCallback {
	void onBonded();

	/**
	 * @param error BleError: BOND_FAILED (refused or cancelled by the user or the device, wrong code), CANCELLED
	 *              (BlePairingRequest.cancel), TIMEOUT, REFUSED (the stack did not start the pairing), PERMISSION_DENIED...
	 */
	default void onBondFailed(int error) {}

	/**
	 * This pairing needs the user, on the main thread (not called when the code was given to createBond). When
	 * request.canAnswer() (Android asks a PIN or a passkey), return true to answer it with request.setPin() or
	 * request.cancel(), at once or later from a dialog of the app: the dialog of the system is not shown. Else, or when
	 * false is returned (default), the dialog of the system asks the user, shown over the app while it is in the
	 * foreground (KnBleSettings.setPairingDialogInForeground): the app may show a hint meanwhile.
	 */
	default boolean onPairingRequest(@NonNull BlePairingRequest request) {
		return false;
	}
}
