package ovh.karewan.knble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Options of a Bluetooth classic connection (KnBle.connectClassic). The defaults suit the Serial Port Profile devices.
 */
public final class ClassicOptions {
	/** Serial Port Profile */
	public static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	private final UUID mUuid;
	private final boolean mSecure;
	private final int mChannel;
	private final boolean mChannelFallback;
	@Nullable
	private final String mPin;

	private ClassicOptions(@NonNull Builder builder) {
		mUuid = builder.mUuid;
		mSecure = builder.mSecure;
		mChannel = builder.mChannel;
		mChannelFallback = builder.mChannelFallback;
		mPin = builder.mPin;
	}

	/**
	 * RFCOMM service of the device, found by its UUID in the SDP records of the device
	 */
	@NonNull
	public UUID getUuid() {
		return mUuid;
	}

	/**
	 * Authenticated and encrypted link: the phone pairs first when needed (dialog of the system). False for the devices
	 * that accept a connection without pairing.
	 */
	public boolean isSecure() {
		return mSecure;
	}

	/**
	 * RFCOMM channel used directly instead of the UUID (devices with a broken SDP record), 0 for the UUID
	 */
	public int getChannel() {
		return mChannel;
	}

	/**
	 * After a failed connection by the UUID, one attempt on channel 1 (where most serial devices are): the usual fix of
	 * "read failed, socket might closed" on many phones
	 */
	public boolean isChannelFallback() {
		return mChannelFallback;
	}

	/**
	 * PIN of a device that pairs the legacy way (fixed code, 0000 or 1234 on many serial devices): given by KnBle when
	 * Android asks for it during the connection, without the dialog of the system when the phone allows it (not Android
	 * 17+ for an app targeting API 37+). Null: BleClassicCallback.onPairingRequest, else the user types it in the
	 * dialog of the system. The confirmations of Secure Simple Pairing stay with the user.
	 */
	@Nullable
	public String getPin() {
		return mPin;
	}

	@SuppressWarnings("unused")
	public static final class Builder {
		private UUID mUuid = SPP;
		private boolean mSecure = true;
		private int mChannel = 0;
		private boolean mChannelFallback = true;
		@Nullable
		private String mPin;

		@NonNull
		public Builder setUuid(@NonNull UUID uuid) {
			mUuid = uuid;
			return this;
		}

		@NonNull
		public Builder setSecure(boolean secure) {
			mSecure = secure;
			return this;
		}

		@NonNull
		public Builder setChannel(int channel) {
			mChannel = Math.max(0, Math.min(30, channel));
			return this;
		}

		@NonNull
		public Builder setChannelFallback(boolean fallback) {
			mChannelFallback = fallback;
			return this;
		}

		@NonNull
		public Builder setPin(@Nullable String pin) {
			mPin = pin == null || pin.isEmpty() ? null : pin;
			return this;
		}

		@NonNull
		public ClassicOptions build() {
			return new ClassicOptions(this);
		}
	}
}
