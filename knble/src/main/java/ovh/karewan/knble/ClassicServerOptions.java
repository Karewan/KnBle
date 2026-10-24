package ovh.karewan.knble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Options of the Bluetooth classic server of the phone (KnBle.startClassicServer). The defaults make a Serial Port
 * Profile server that the devices find by its UUID.
 */
public final class ClassicServerOptions {
	/** Most active Bluetooth classic links at once (piconet of 7) */
	public static final int MAX_CLIENTS = 7;

	private final UUID mUuid;
	private final String mName;
	private final boolean mSecure;
	private final int mMaxClients;
	@Nullable
	private final String mPin;

	private ClassicServerOptions(@NonNull Builder builder) {
		mUuid = builder.mUuid;
		mName = builder.mName;
		mSecure = builder.mSecure;
		mMaxClients = builder.mMaxClients;
		mPin = builder.mPin;
	}

	/**
	 * UUID of the service in the SDP record of the phone: the clients connect to it (ClassicOptions.SPP by default)
	 */
	@NonNull
	public UUID getUuid() {
		return mUuid;
	}

	/**
	 * Name of the service in the SDP record
	 */
	@NonNull
	public String getName() {
		return mName;
	}

	/**
	 * Authenticated and encrypted links: a client that is not bonded pairs first (default). False for the devices that
	 * connect without pairing.
	 */
	public boolean isSecure() {
		return mSecure;
	}

	/**
	 * Clients connected at once: a client over it is refused (its socket closed)
	 */
	public int getMaxClients() {
		return mMaxClients;
	}

	/**
	 * PIN given to the legacy pairing a client asks while the server listens (a device with a fixed code), without the
	 * dialog of the system when the phone allows it (not Android 17+ for an app targeting API 37+). Null:
	 * BleClassicServerCallback.onPairingRequest, else the user types it in the dialog of the system.
	 */
	@Nullable
	public String getPin() {
		return mPin;
	}

	@SuppressWarnings("unused")
	public static final class Builder {
		private UUID mUuid = ClassicOptions.SPP;
		private String mName = "KnBle";
		private boolean mSecure = true;
		private int mMaxClients = MAX_CLIENTS;
		@Nullable
		private String mPin;

		@NonNull
		public Builder setUuid(@NonNull UUID uuid) {
			mUuid = uuid;
			return this;
		}

		@NonNull
		public Builder setName(@NonNull String name) {
			mName = name;
			return this;
		}

		@NonNull
		public Builder setSecure(boolean secure) {
			mSecure = secure;
			return this;
		}

		/**
		 * @param maxClients 1 to MAX_CLIENTS
		 */
		@NonNull
		public Builder setMaxClients(int maxClients) {
			mMaxClients = Math.max(1, Math.min(MAX_CLIENTS, maxClients));
			return this;
		}

		@NonNull
		public Builder setPin(@Nullable String pin) {
			mPin = pin == null || pin.isEmpty() ? null : pin;
			return this;
		}

		@NonNull
		public ClassicServerOptions build() {
			return new ClassicServerOptions(this);
		}
	}
}
