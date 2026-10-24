package ovh.karewan.knble.internal;

import android.app.Activity;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Invisible activity of KnBle.associate: asks the system for the association, shows its dialog and gets its result,
 * then finishes. The app needs no onActivityResult. Created again (a configuration change, an activity destroyed in
 * the background), the new instance takes over the request asked by the first one and gets its result.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@RequiresApi(Build.VERSION_CODES.O)
public final class AssociateActivity extends Activity {
	@VisibleForTesting
	static final int REQUEST_CHOOSER = 1;
	private static final String STATE_ASKED = "knble.associate.asked";
	private static final String STATE_CHOOSER = "knble.associate.chooser";

	@Nullable
	private Companion.Pending mPending;
	// The system was asked (by this instance or the one before), its dialog shown
	private boolean mAsked, mChooserShown;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Read, not taken: an instance created again finds it
		mPending = Companion.pending();
		if (mPending == null) {
			finish();
			return;
		}
		mPending.created = true;
		mPending.activity = this;
		// Created again: the request is asked, its callback and the result of its dialog come to this instance
		if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_ASKED)) {
			mAsked = true;
			mChooserShown = savedInstanceState.getBoolean(STATE_CHOOSER);
			return;
		}

		CompanionDeviceManager cdm = Companion.supported(this) ? getSystemService(CompanionDeviceManager.class) : null;
		if (cdm == null) {
			fail(BleError.NOT_SUPPORTED);
			return;
		}
		try {
			// Callback on the main thread
			cdm.associate(Companion.request(mPending.filters, mPending.singleDevice), new Callback(mPending), null);
			mAsked = true;
		} catch (SecurityException e) {
			BleLog.w("associate", e);
			fail(BleError.PERMISSION_DENIED);
		} catch (RuntimeException e) {
			BleLog.w("associate", e);
			fail(BleError.INTERNAL);
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(STATE_ASKED, mAsked);
		outState.putBoolean(STATE_CHOOSER, mChooserShown);
	}

	private void showChooser(@NonNull IntentSender chooser) {
		if (mChooserShown || isFinishing()) return;
		try {
			startIntentSenderForResult(chooser, REQUEST_CHOOSER, null, 0, 0, 0);
			mChooserShown = true;
		} catch (IntentSender.SendIntentException | RuntimeException e) {
			BleLog.w("startIntentSenderForResult", e);
			fail(BleError.INTERNAL);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_CHOOSER) return;
		BleDevice device = resultCode == RESULT_OK && data != null ? Companion.device(data) : null;
		if (device != null) done(device);
		else fail(resultCode == RESULT_OK ? BleError.INTERNAL : BleError.CANCELLED);
	}

	private void done(@NonNull BleDevice device) {
		Companion.Pending pending = mPending;
		if (pending != null) pending.associated(device);
		finish();
	}

	private void fail(int error) {
		Companion.Pending pending = mPending;
		if (pending != null) pending.failed(error);
		finish();
	}

	@Override
	protected void onDestroy() {
		Companion.Pending pending = mPending;
		if (pending != null) {
			if (pending.activity == this) pending.activity = null;
			// Closed without a result (the user left the app...). Not finishing: created again for the result
			if (isFinishing()) pending.failed(BleError.CANCELLED);
		}
		super.onDestroy();
	}

	/**
	 * Callback of the system for one request: to the instance showing it (a new one after a recreation)
	 */
	private static final class Callback extends CompanionDeviceManager.Callback {
		@NonNull
		private final Companion.Pending mPending;

		Callback(@NonNull Companion.Pending pending) {
			mPending = pending;
		}

		// Android 8 to 12
		@Override
		@SuppressWarnings("deprecation")
		public void onDeviceFound(@NonNull IntentSender chooser) {
			AssociateActivity a = mPending.activity;
			if (a != null) a.showChooser(chooser);
		}

		// Android 13+
		@Override
		public void onAssociationPending(@NonNull IntentSender chooser) {
			AssociateActivity a = mPending.activity;
			if (a != null) a.showChooser(chooser);
		}

		@Override
		public void onAssociationCreated(@NonNull AssociationInfo info) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
			BleDevice device = Companion.device(info);
			if (device == null) return;
			AssociateActivity a = mPending.activity;
			if (a != null) a.done(device);
			else mPending.associated(device);
		}

		// Android 15+: with its cause
		@Override
		public void onFailure(int errorCode, @Nullable CharSequence error) {
			BleLog.d("association failure " + errorCode + ": " + error);
			end(Companion.error(errorCode));
		}

		@Override
		public void onFailure(@Nullable CharSequence error) {
			BleLog.d("association failure: " + error);
			AssociateActivity a = mPending.activity;
			// Dialog shown (or no instance: the next one gets its result): its result says whether the user cancelled
			if (a != null && !a.mChooserShown) a.fail(BleError.INTERNAL);
		}

		private void end(int error) {
			AssociateActivity a = mPending.activity;
			if (a != null) a.fail(error);
			else mPending.failed(error);
		}
	}
}
