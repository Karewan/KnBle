package ovh.karewan.knble;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ovh.karewan.knble.interfaces.BleLogger;

/**
 * Settings of KnBle, given to KnBle.init(context, settings). The defaults suit most devices and phones.
 */
public final class KnBleSettings {
	private final long mConnectTimeout;
	private final int mConnectAttempts;
	private final long mOperationTimeout;
	private final long mDiscoveryDelay;
	private final boolean mRefreshOnDisconnect;
	@Nullable
	private final Looper mCallbackLooper;
	@Nullable
	private final BleLogger mLogger;
	private final boolean mDebug;
	private final boolean mPairingDialogInForeground;

	private KnBleSettings(@NonNull Builder builder) {
		mConnectTimeout = builder.mConnectTimeout;
		mConnectAttempts = builder.mConnectAttempts;
		mOperationTimeout = builder.mOperationTimeout;
		mDiscoveryDelay = builder.mDiscoveryDelay;
		mRefreshOnDisconnect = builder.mRefreshOnDisconnect;
		mCallbackLooper = builder.mCallbackLooper;
		mLogger = builder.mLogger;
		mDebug = builder.mDebug;
		mPairingDialogInForeground = builder.mPairingDialogInForeground;
	}

	/**
	 * Time for a connection, all its attempts and the discovery of the services included (ms)
	 */
	public long getConnectTimeout() {
		return mConnectTimeout;
	}

	/**
	 * Attempts of a connection that fails (status 133, 62, 147...), each one after a close() of the previous one
	 */
	public int getConnectAttempts() {
		return mConnectAttempts;
	}

	/**
	 * Time for the device or the Android stack to answer an operation (read, write, packet...), in ms
	 */
	public long getOperationTimeout() {
		return mOperationTimeout;
	}

	/**
	 * Wait between the connection and the discovery of the services (ms), 1600 at least for a bonded device
	 */
	public long getDiscoveryDelay() {
		return mDiscoveryDelay;
	}

	/**
	 * Clears the GATT cache of Android at each disconnection (the next connection discovers the services again)
	 */
	public boolean isRefreshOnDisconnect() {
		return mRefreshOnDisconnect;
	}

	/**
	 * Looper of the callbacks, null for the main thread
	 */
	@Nullable
	public Looper getCallbackLooper() {
		return mCallbackLooper;
	}

	/**
	 * Destination of the logs, null for Logcat (tag KnBle)
	 */
	@Nullable
	public BleLogger getLogger() {
		return mLogger;
	}

	public boolean isDebug() {
		return mDebug;
	}

	/**
	 * The dialog of the system for a pairing of a device of KnBle shows over the app, not as a notification
	 */
	public boolean isPairingDialogInForeground() {
		return mPairingDialogInForeground;
	}

	@SuppressWarnings("unused")
	public static final class Builder {
		private long mConnectTimeout = 30000;
		private int mConnectAttempts = 3;
		private long mOperationTimeout = 10000;
		private long mDiscoveryDelay = 300;
		private boolean mRefreshOnDisconnect = true;
		@Nullable
		private Looper mCallbackLooper = null;
		@Nullable
		private BleLogger mLogger = null;
		private boolean mDebug = false;
		private boolean mPairingDialogInForeground = true;

		@NonNull
		public Builder setConnectTimeout(long timeoutMs) {
			mConnectTimeout = Math.max(1000, timeoutMs);
			return this;
		}

		@NonNull
		public Builder setConnectAttempts(int attempts) {
			mConnectAttempts = Math.max(1, attempts);
			return this;
		}

		@NonNull
		public Builder setOperationTimeout(long timeoutMs) {
			mOperationTimeout = Math.max(500, timeoutMs);
			return this;
		}

		@NonNull
		public Builder setDiscoveryDelay(long delayMs) {
			mDiscoveryDelay = Math.max(0, delayMs);
			return this;
		}

		@NonNull
		public Builder setRefreshOnDisconnect(boolean refresh) {
			mRefreshOnDisconnect = refresh;
			return this;
		}

		/**
		 * Callbacks on the thread of this Looper instead of the main thread (a HandlerThread of the app): KnBle never
		 * waits for it, whatever the app does there
		 */
		@NonNull
		public Builder setCallbackLooper(@Nullable Looper looper) {
			mCallbackLooper = looper;
			return this;
		}

		@NonNull
		public Builder setLogger(@Nullable BleLogger logger) {
			mLogger = logger;
			return this;
		}

		/**
		 * Debug logs (the same as KnBle.DEBUG)
		 */
		@NonNull
		public Builder setDebug(boolean debug) {
			mDebug = debug;
			return this;
		}

		/**
		 * When a pairing of a device of KnBle needs the user (a number to confirm, a code the app does not give) while the
		 * app is in the foreground, Android often puts its dialog in a notification the user does not see: the connection
		 * then waits for nothing. True (default): KnBle starts a classic discovery of a few hundred ms just before, so
		 * Android shows the dialog over the app (it does during 60 s after a discovery). Needs the scan permission
		 * (BLUETOOTH_SCAN, location before Android 12), else the choice of Android.
		 */
		@NonNull
		public Builder setPairingDialogInForeground(boolean inForeground) {
			mPairingDialogInForeground = inForeground;
			return this;
		}

		@NonNull
		public KnBleSettings build() {
			return new KnBleSettings(this);
		}
	}
}
