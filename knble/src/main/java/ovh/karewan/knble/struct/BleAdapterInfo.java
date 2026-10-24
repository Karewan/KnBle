package ovh.karewan.knble.struct;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.util.Locale;

/**
 * What the Bluetooth of the phone supports (KnBle.getAdapterInfo), as the "Device information" of nRF Connect shows it.
 * Immutable. Android tells the LE features only while Bluetooth is on: isKnown() says whether they were read then.
 */
public final class BleAdapterInfo {
	/** Longest legacy advertising data (Bluetooth 4) */
	public static final int LEGACY_ADVERTISING_DATA_LENGTH = 31;

	private final String mManufacturer, mModel, mRelease;
	private final int mSdkInt;
	private final boolean mClassic, mLe, mCompanion, mChannelSounding;
	private final boolean mEnabled, mKnown;
	@Nullable
	private final String mName;
	private final boolean mLe2M, mLeCoded, mExtendedAdv, mPeriodicAdv, mMultipleAdv, mOffloadedFiltering, mOffloadedBatching;
	private final int mMaxAdvLength;
	private final boolean mLeAudio, mLeAudioSource, mLeAudioAssistant, mHdt;
	private final int mMaxAudioDevices;
	private final boolean mAnswerPairing;

	private BleAdapterInfo(@NonNull Builder b) {
		mManufacturer = Build.MANUFACTURER;
		mModel = Build.MODEL;
		mRelease = Build.VERSION.RELEASE;
		mSdkInt = Build.VERSION.SDK_INT;
		mClassic = b.classic;
		mLe = b.le;
		mCompanion = b.companion;
		mChannelSounding = b.channelSounding;
		mEnabled = b.enabled;
		mKnown = b.known;
		mName = b.name;
		mLe2M = b.le2M;
		mLeCoded = b.leCoded;
		mExtendedAdv = b.extendedAdv;
		mPeriodicAdv = b.periodicAdv;
		mMultipleAdv = b.multipleAdv;
		mOffloadedFiltering = b.offloadedFiltering;
		mOffloadedBatching = b.offloadedBatching;
		mMaxAdvLength = b.maxAdvLength;
		mLeAudio = b.leAudio;
		mLeAudioSource = b.leAudioSource;
		mLeAudioAssistant = b.leAudioAssistant;
		mHdt = b.hdt;
		mMaxAudioDevices = b.maxAudioDevices;
		mAnswerPairing = b.answerPairing;
	}

	// region Phone

	@NonNull
	public String getManufacturer() {
		return mManufacturer;
	}

	@NonNull
	public String getModel() {
		return mModel;
	}

	/**
	 * Version of Android ("17"), and its API level: getSdkInt()
	 */
	@NonNull
	public String getAndroidRelease() {
		return mRelease;
	}

	public int getSdkInt() {
		return mSdkInt;
	}

	// endregion

	// region Hardware (known even with Bluetooth off)

	/**
	 * Bluetooth classic (BR/EDR): connectClassic, discovery
	 */
	public boolean hasBluetoothClassic() {
		return mClassic;
	}

	public boolean hasBluetoothLe() {
		return mLe;
	}

	/**
	 * Pairing through the companion device manager (KnBle.associate), Android 8+
	 */
	public boolean hasCompanionDeviceSetup() {
		return mCompanion;
	}

	/**
	 * Channel Sounding (Bluetooth 6 distance measurement), Android 16+
	 */
	public boolean isChannelSoundingSupported() {
		return mChannelSounding;
	}

	// endregion

	// region State

	public boolean isEnabled() {
		return mEnabled;
	}

	/**
	 * The LE features below were read with Bluetooth on (now, or earlier in this process when it is off now). False:
	 * Bluetooth was never on since KnBle started, they all read false (or 0).
	 */
	public boolean isKnown() {
		return mKnown;
	}

	/**
	 * Name of the phone seen by the other devices, null when unknown (Android 12+: BLUETOOTH_CONNECT permission)
	 */
	@Nullable
	public String getName() {
		return mName;
	}

	// endregion

	// region LE features

	/**
	 * PHY LE 2M (Bluetooth 5 "high speed"): setPreferredPhy, about twice the throughput. Android 8+.
	 */
	public boolean isLe2MPhySupported() {
		return mLe2M;
	}

	/**
	 * PHY LE Coded (Bluetooth 5 "long range"): scan and connection about 4 times farther, slower. Android 8+.
	 */
	public boolean isLeCodedPhySupported() {
		return mLeCoded;
	}

	/**
	 * Extended advertising (Bluetooth 5): scan of the extended advertisements, and advertising of up to
	 * getMaxAdvertisingDataLength() bytes (BleAdvertiseData.setExtended). Android 8+.
	 */
	public boolean isExtendedAdvertisingSupported() {
		return mExtendedAdv;
	}

	/**
	 * Periodic advertising (Bluetooth 5). Android 8+.
	 */
	public boolean isPeriodicAdvertisingSupported() {
		return mPeriodicAdv;
	}

	/**
	 * The peripheral role: the phone can advertise (KnBle.startAdvertising), and several advertisements at once
	 */
	public boolean isMultipleAdvertisementSupported() {
		return mMultipleAdv;
	}

	/**
	 * The scan filters run in the controller: a filtered scan wakes the phone less (and the background scans work
	 * better, with the screen off)
	 */
	public boolean isOffloadedFilteringSupported() {
		return mOffloadedFiltering;
	}

	/**
	 * The controller can batch the scan results (ScanSettings report delay)
	 */
	public boolean isOffloadedScanBatchingSupported() {
		return mOffloadedBatching;
	}

	/**
	 * Longest advertising data the phone can send: LEGACY_ADVERTISING_DATA_LENGTH (31) without extended advertising,
	 * up to 1650 with it. 0 when unknown.
	 */
	public int getMaxAdvertisingDataLength() {
		return mMaxAdvLength;
	}

	/**
	 * LE Audio (unicast), Android 13+
	 */
	public boolean isLeAudioSupported() {
		return mLeAudio;
	}

	/**
	 * LE Audio broadcast source (Auracast), Android 13+
	 */
	public boolean isLeAudioBroadcastSourceSupported() {
		return mLeAudioSource;
	}

	/**
	 * LE Audio broadcast assistant (Auracast), Android 13+
	 */
	public boolean isLeAudioBroadcastAssistantSupported() {
		return mLeAudioAssistant;
	}

	/**
	 * High Data Throughput PHY (Bluetooth 6), Android 17+
	 */
	public boolean isHighDataThroughputPhySupported() {
		return mHdt;
	}

	/**
	 * Audio devices connected at once (Android 13+, BLUETOOTH_CONNECT permission), -1 when unknown
	 */
	public int getMaxConnectedAudioDevices() {
		return mMaxAudioDevices;
	}

	// endregion

	// region KnBle

	/**
	 * The app may answer a pairing that asks a code (BlePairingRequest.canAnswer for a PIN or a passkey). False on
	 * Android 17+ for an app targeting API 37+: the user types the codes in the dialog of the system.
	 */
	public boolean canAnswerPairing() {
		return mAnswerPairing;
	}

	// endregion

	/**
	 * A report on several lines, for the logs or a screen
	 */
	@NonNull
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder(640);
		s.append(String.format(Locale.US, "%s %s, Android %s (API %d)%n", mManufacturer, mModel, mRelease, mSdkInt));
		s.append("Bluetooth: ").append(mEnabled ? "on" : "off");
		if (mName != null) s.append(", name \"").append(mName).append('"');
		s.append(String.format(Locale.US, "%nClassic (BR/EDR): %s%nLE: %s%nCompanion device setup: %s%nChannel Sounding: %s%n",
				yes(mClassic), yes(mLe), yes(mCompanion), yes(mChannelSounding)));
		if (!mKnown) {
			s.append("LE features: unknown (Bluetooth off since KnBle started)").append(String.format(Locale.US, "%n"));
		} else {
			s.append(String.format(Locale.US,
					"High speed (PHY LE 2M): %s%nLong range (PHY LE Coded): %s%nExtended advertising: %s%nPeriodic advertising: %s%n"
							+ "Multiple advertisement (peripheral): %s%nOffloaded filtering: %s%nOffloaded scan batching: %s%n"
							+ "Max advertising data length: %d bytes%nLE Audio: %s%nLE Audio broadcast source: %s%n"
							+ "LE Audio broadcast assistant: %s%nHigh Data Throughput PHY: %s%n",
					yes(mLe2M), yes(mLeCoded), yes(mExtendedAdv), yes(mPeriodicAdv), yes(mMultipleAdv), yes(mOffloadedFiltering),
					yes(mOffloadedBatching), mMaxAdvLength, yes(mLeAudio), yes(mLeAudioSource), yes(mLeAudioAssistant), yes(mHdt)));
		}
		if (mMaxAudioDevices >= 0) s.append(String.format(Locale.US, "Max connected audio devices: %d%n", mMaxAudioDevices));
		s.append("Pairing codes given by the app: ").append(mAnswerPairing ? "yes" : "no (dialog of the system)");
		return s.toString();
	}

	@NonNull
	private static String yes(boolean b) {
		return b ? "supported" : "not supported";
	}

	// region Reading (KnBle)

	/**
	 * The LE features read with Bluetooth on: kept by KnBle for the times it is off
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static final class LeFeatures {
		final boolean le2M, leCoded, extendedAdv, periodicAdv, multipleAdv, offloadedFiltering, offloadedBatching;
		final int maxAdvLength;
		final boolean leAudio, leAudioSource, leAudioAssistant, hdt;

		LeFeatures(@NonNull BluetoothAdapter a) {
			multipleAdv = safe(a::isMultipleAdvertisementSupported);
			offloadedFiltering = safe(a::isOffloadedFilteringSupported);
			offloadedBatching = safe(a::isOffloadedScanBatchingSupported);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				le2M = safe(a::isLe2MPhySupported);
				leCoded = safe(a::isLeCodedPhySupported);
				extendedAdv = safe(a::isLeExtendedAdvertisingSupported);
				periodicAdv = safe(a::isLePeriodicAdvertisingSupported);
				int max = 0;
				try {
					max = a.getLeMaximumAdvertisingDataLength();
				} catch (RuntimeException ignored) {
					// Bluetooth turned off meanwhile
				}
				maxAdvLength = max > 0 ? max : multipleAdv ? LEGACY_ADVERTISING_DATA_LENGTH : 0;
			} else {
				le2M = leCoded = extendedAdv = periodicAdv = false;
				maxAdvLength = multipleAdv ? LEGACY_ADVERTISING_DATA_LENGTH : 0;
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				leAudio = supported(a::isLeAudioSupported);
				leAudioSource = supported(a::isLeAudioBroadcastSourceSupported);
				leAudioAssistant = supported(a::isLeAudioBroadcastAssistantSupported);
			} else {
				leAudio = leAudioSource = leAudioAssistant = false;
			}
			hdt = Build.VERSION.SDK_INT >= 37 && supported(a::isLeHighDataThroughputPhySupported);
		}

		private interface Flag {
			boolean get();
		}

		private interface Status {
			int get();
		}

		private static boolean safe(@NonNull Flag f) {
			try {
				return f.get();
			} catch (RuntimeException e) {
				return false;
			}
		}

		private static boolean supported(@NonNull Status f) {
			try {
				return f.get() == BluetoothStatusCodes.FEATURE_SUPPORTED;
			} catch (RuntimeException e) {
				return false;
			}
		}
	}

	/**
	 * The LE features, read now (binder calls): Bluetooth must be on, else they all read false
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public static LeFeatures readLeFeatures(@NonNull BluetoothAdapter adapter) {
		return new LeFeatures(adapter);
	}

	/**
	 * @param adapter Null on a phone without Bluetooth
	 * @param features LE features read with Bluetooth on, null when never read
	 */
	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@SuppressLint("MissingPermission")
	@NonNull
	public static BleAdapterInfo read(@NonNull Context context, @Nullable BluetoothAdapter adapter, boolean enabled, @Nullable LeFeatures features,
									  boolean answerPairing) {
		Builder b = new Builder();
		PackageManager pm = context.getPackageManager();
		b.classic = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
		b.le = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
		b.companion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pm.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
		b.channelSounding = Build.VERSION.SDK_INT >= 36 && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING);
		b.enabled = adapter != null && enabled;
		b.answerPairing = answerPairing;
		boolean connect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
				|| context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
		if (adapter != null && connect) {
			try {
				b.name = adapter.getName();
			} catch (RuntimeException ignored) {
				// Refused, or Bluetooth turned off meanwhile
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && b.enabled) {
				try {
					b.maxAudioDevices = adapter.getMaxConnectedAudioDevices();
				} catch (RuntimeException ignored) {
					// Refused, or Bluetooth turned off meanwhile
				}
			}
		}
		if (features != null) {
			b.known = true;
			b.le2M = features.le2M;
			b.leCoded = features.leCoded;
			b.extendedAdv = features.extendedAdv;
			b.periodicAdv = features.periodicAdv;
			b.multipleAdv = features.multipleAdv;
			b.offloadedFiltering = features.offloadedFiltering;
			b.offloadedBatching = features.offloadedBatching;
			b.maxAdvLength = features.maxAdvLength;
			b.leAudio = features.leAudio;
			b.leAudioSource = features.leAudioSource;
			b.leAudioAssistant = features.leAudioAssistant;
			b.hdt = features.hdt;
		}
		return new BleAdapterInfo(b);
	}

	private static final class Builder {
		boolean classic, le, companion, channelSounding, enabled, known;
		String name;
		boolean le2M, leCoded, extendedAdv, periodicAdv, multipleAdv, offloadedFiltering, offloadedBatching;
		int maxAdvLength;
		boolean leAudio, leAudioSource, leAudioAssistant, hdt;
		int maxAudioDevices = -1;
		boolean answerPairing;
	}

	// endregion
}
