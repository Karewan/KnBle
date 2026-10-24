package ovh.karewan.knble;

/**
 * Why an operation failed. Positive values are the GATT status sent by the device or the Android stack
 * (BluetoothGatt.GATT_*, 133 = GATT_ERROR...), negative values are found by KnBle.
 */
public final class BleError {
	/** The device is not connected (or the connection was lost before the end of the operation) */
	public static final int NOT_CONNECTED = -1;
	/** Service, characteristic or descriptor not found on the device */
	public static final int NOT_FOUND = -2;
	/** The characteristic or the phone does not allow this operation (read, write, notify, peripheral role, L2CAP before Android 10...) */
	public static final int NOT_SUPPORTED = -3;
	/** No answer from the device or from the Android stack in time */
	public static final int TIMEOUT = -4;
	/** The Android stack stayed busy */
	public static final int BUSY = -5;
	/** Missing Bluetooth permission (BLUETOOTH_CONNECT on Android 12+) */
	public static final int PERMISSION_DENIED = -6;
	/** Bluetooth is off */
	public static final int BLUETOOTH_DISABLED = -7;
	/** The Android stack refused the request */
	public static final int REFUSED = -8;
	/** Unexpected failure of the Android stack (exception of a vendor ROM...) */
	public static final int INTERNAL = -9;
	/** Cancelled by the app */
	public static final int CANCELLED = -10;
	/** OTA image not valid for the protocol (TI OAD: header length), or refused by the device */
	public static final int INVALID_IMAGE = -11;
	/** Pairing refused or cancelled (by the user or the device) */
	public static final int BOND_FAILED = -12;
	/** Data larger than allowed: a notification over MTU - 3, advertising data over 31 bytes (legacy advertising) */
	public static final int DATA_TOO_LARGE = -13;
	/** Bluetooth classic: the connection ended without being asked (link lost, or closed by the device) */
	public static final int CONNECTION_LOST = -14;
	/**
	 * The bond of the device is lost: bonded to the phone, it could not give its keys (reset to factory settings, bond
	 * removed on its side, or another device using its address). Android 16+ (ACTION_KEY_MISSING). Pair again
	 * (removeBond then createBond, or the re-pairing of the system on Android 17+).
	 */
	public static final int BOND_LOST = -15;

	private BleError() {}

	/**
	 * Readable name, for the logs
	 */
	public static String name(int error) {
		return switch (error) {
			case NOT_CONNECTED -> "NOT_CONNECTED";
			case NOT_FOUND -> "NOT_FOUND";
			case NOT_SUPPORTED -> "NOT_SUPPORTED";
			case TIMEOUT -> "TIMEOUT";
			case BUSY -> "BUSY";
			case PERMISSION_DENIED -> "PERMISSION_DENIED";
			case BLUETOOTH_DISABLED -> "BLUETOOTH_DISABLED";
			case REFUSED -> "REFUSED";
			case INTERNAL -> "INTERNAL";
			case CANCELLED -> "CANCELLED";
			case INVALID_IMAGE -> "INVALID_IMAGE";
			case BOND_FAILED -> "BOND_FAILED";
			case DATA_TOO_LARGE -> "DATA_TOO_LARGE";
			case CONNECTION_LOST -> "CONNECTION_LOST";
			case BOND_LOST -> "BOND_LOST";
			default -> "GATT_STATUS_" + error;
		};
	}
}
