package ovh.karewan.knble.interfaces;

import androidx.annotation.NonNull;

import ovh.karewan.knble.struct.BleDevice;

/**
 * Result of KnBle.associate. Only onAssociated() is mandatory: a lambda works.
 */
public interface BleAssociateCallback {
	/**
	 * The user chose this device: the app is associated with it (no location permission needed to find it again)
	 */
	void onAssociated(@NonNull BleDevice device);

	/**
	 * @param error BleError: CANCELLED (the user closed the dialog), TIMEOUT (no device found), NOT_SUPPORTED
	 *              (Android 7, or a phone without the companion device service), REFUSED (Android did not show the
	 *              dialog: an app in the background, Android 10+), PERMISSION_DENIED, INTERNAL
	 */
	default void onAssociationFailed(int error) {}
}
