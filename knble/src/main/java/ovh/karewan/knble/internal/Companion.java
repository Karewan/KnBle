package ovh.karewan.knble.internal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.companion.AssociatedDevice;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.interfaces.BleAssociateCallback;
import ovh.karewan.knble.interfaces.BleDevicesCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Companion device association (Android 8+): the system dialog lists the devices matching the filters, the user picks
 * one, the app is associated with it (found again without location permission, background privileges of a companion
 * app). The dialog runs in AssociateActivity, so the app needs no onActivityResult.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
@SuppressLint("MissingPermission")
public final class Companion {
	// AssociateActivity not created after this long: Android dropped its start (app in the background, Android 10+)
	@VisibleForTesting
	static final long START_TIMEOUT_MS = 5000;

	// The association of AssociateActivity until its result (kept when the activity is created again): one at a time
	@Nullable
	private static Pending sPending;

	private Companion() {}

	/**
	 * An association asked by the app: its result is given once
	 */
	static final class Pending {
		@NonNull
		final ScanFilters filters;
		final boolean singleDevice;
		@NonNull
		private final BleAssociateCallback mCallback;
		@NonNull
		private final Dispatcher mDispatcher;
		private boolean mDone;
		// Main thread: AssociateActivity started for it, and the instance showing it (a new one after a recreation)
		boolean created;
		@Nullable
		AssociateActivity activity;

		Pending(@NonNull ScanFilters filters, boolean singleDevice, @NonNull BleAssociateCallback callback, @NonNull Dispatcher dispatcher) {
			this.filters = filters;
			this.singleDevice = singleDevice;
			mCallback = callback;
			mDispatcher = dispatcher;
		}

		synchronized void associated(@NonNull BleDevice device) {
			if (mDone) return;
			mDone = true;
			clear(this);
			BleLog.d("associated");
			mDispatcher.post(() -> mCallback.onAssociated(device));
		}

		synchronized void failed(int error) {
			if (mDone) return;
			mDone = true;
			clear(this);
			BleLog.d("association failed: " + BleError.name(error));
			mDispatcher.post(() -> mCallback.onAssociationFailed(error));
		}
	}

	public static void associate(@NonNull BleEngine engine, @NonNull Context context, @NonNull ScanFilters filters, boolean singleDevice,
								 @NonNull BleAssociateCallback callback) {
		Pending pending = new Pending(filters, singleDevice, callback, engine.dispatcher());
		// Without the feature, the manager has no service: its associate() returns without a callback
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supported(context)) {
			pending.failed(BleError.NOT_SUPPORTED);
			return;
		}
		Pending previous;
		synchronized (Companion.class) {
			previous = sPending;
			sPending = pending;
		}
		if (previous != null) previous.failed(BleError.CANCELLED);

		Intent intent = new Intent(context, AssociateActivity.class);
		if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			context.startActivity(intent);
		} catch (RuntimeException e) {
			BleLog.w("associate", e);
			pending.failed(BleError.INTERNAL);
			return;
		}
		// Android 10+ drops the start of an activity from the background without an error
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			if (!pending.created) pending.failed(BleError.REFUSED);
		}, START_TIMEOUT_MS);
	}

	/**
	 * The association waiting for its result, read by AssociateActivity (and the instance created again after a
	 * configuration change): it stays until associated() or failed()
	 */
	@Nullable
	static synchronized Pending pending() {
		return sPending;
	}

	private static synchronized void clear(@NonNull Pending pending) {
		if (sPending == pending) sPending = null;
	}

	/**
	 * The system service of the companion devices (android.software.companion_device_setup): absent on some phones
	 */
	static boolean supported(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
		try {
			return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
		} catch (RuntimeException e) {
			BleLog.w("hasSystemFeature", e);
			return false;
		}
	}

	/**
	 * The request of the system for these filters: one device filter per filter of the app (any of them matches). The
	 * MAC prefixes and the minimum RSSI cannot be given to the system.
	 */
	@RequiresApi(Build.VERSION_CODES.O)
	@NonNull
	static AssociationRequest request(@NonNull ScanFilters f, boolean singleDevice) {
		List<BluetoothLeDeviceFilter> filters = new ArrayList<>();
		for (String name : f.getDeviceNames()) filters.add(nameFilter("^" + Pattern.quote(name) + "$"));
		for (String prefix : f.getDeviceStartsWithNames()) filters.add(nameFilter("^" + Pattern.quote(prefix) + ".*"));
		for (String suffix : f.getDeviceEndsWithNames()) filters.add(nameFilter(".*" + Pattern.quote(suffix) + "$"));
		for (ScanFilter sf : ScanEngine.hardwareFilters(f, false)) filters.add(new BluetoothLeDeviceFilter.Builder().setScanFilter(sf).build());
		// No filter: every LE device (none would list the classic devices too)
		if (filters.isEmpty()) filters.add(new BluetoothLeDeviceFilter.Builder().build());

		AssociationRequest.Builder b = new AssociationRequest.Builder().setSingleDevice(singleDevice);
		for (BluetoothLeDeviceFilter filter : filters) b.addDeviceFilter(filter);
		return b.build();
	}

	@RequiresApi(Build.VERSION_CODES.O)
	@NonNull
	private static BluetoothLeDeviceFilter nameFilter(@NonNull String regex) {
		return new BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile(regex)).build();
	}

	/**
	 * The device chosen by the user, from the result of the dialog
	 */
	@Nullable
	@SuppressWarnings("deprecation")
	static BleDevice device(@NonNull Intent data) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			AssociationInfo info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo.class);
			if (info != null) return device(info);
		}
		Parcelable p = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
		if (p instanceof ScanResult r) return ScanEngine.toBleDevice(r);
		if (p instanceof BluetoothDevice d) return new BleDevice(d);
		return null;
	}

	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	@Nullable
	static BleDevice device(@NonNull AssociationInfo info) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			AssociatedDevice associated = info.getAssociatedDevice();
			ScanResult r = associated == null ? null : associated.getBleDevice();
			if (r != null) return ScanEngine.toBleDevice(r);
		}
		MacAddress mac = info.getDeviceMacAddress();
		return mac == null ? null : remote(mac.toString());
	}

	@Nullable
	private static BleDevice remote(@NonNull String mac) {
		BleEngine e = BleEngine.get();
		if (e == null) return null;
		try {
			return new BleDevice(e.adapter().getRemoteDevice(mac.toUpperCase(Locale.ROOT)));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * BleError of a failure of the dialog (Android 15+ gives its cause)
	 */
	static int error(int resultCode) {
		return switch (resultCode) {
			case CompanionDeviceManager.RESULT_CANCELED, CompanionDeviceManager.RESULT_USER_REJECTED -> BleError.CANCELLED;
			case CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT -> BleError.TIMEOUT;
			case CompanionDeviceManager.RESULT_SECURITY_ERROR -> BleError.PERMISSION_DENIED;
			default -> BleError.INTERNAL;
		};
	}

	@Nullable
	private static CompanionDeviceManager manager(@NonNull Context context) {
		// Without the feature: a manager without its service, every call does nothing
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supported(context)) return null;
		try {
			return context.getSystemService(CompanionDeviceManager.class);
		} catch (RuntimeException e) {
			BleLog.w("CompanionDeviceManager", e);
			return null;
		}
	}

	/**
	 * Thread of the engine (binder calls)
	 */
	public static void associatedDevices(@NonNull BleEngine engine, @NonNull BleDevicesCallback callback) {
		engine.handler().post(() -> {
			List<BleDevice> devices = new ArrayList<>();
			for (String mac : associatedMacs(engine.context())) {
				BleDevice d = remote(mac);
				if (d != null) devices.add(d);
			}
			engine.dispatcher().post(() -> callback.onDevices(devices));
		});
	}

	@NonNull
	@SuppressWarnings("deprecation")
	private static List<String> associatedMacs(@NonNull Context context) {
		List<String> macs = new ArrayList<>();
		CompanionDeviceManager cdm = manager(context);
		if (cdm == null) return macs;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				for (AssociationInfo info : cdm.getMyAssociations()) {
					MacAddress mac = info.getDeviceMacAddress();
					if (mac != null) macs.add(mac.toString().toUpperCase(Locale.ROOT));
				}
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				for (String mac : cdm.getAssociations()) macs.add(mac.toUpperCase(Locale.ROOT));
			}
		} catch (RuntimeException e) {
			BleLog.w("associations", e);
		}
		return macs;
	}

	/**
	 * Id of the association with a device (Android 13+), -1 when not associated
	 */
	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	private static int associationId(@NonNull CompanionDeviceManager cdm, @NonNull String mac) {
		for (AssociationInfo info : cdm.getMyAssociations()) {
			MacAddress m = info.getDeviceMacAddress();
			if (m != null && m.toString().equalsIgnoreCase(mac)) return info.getId();
		}
		return -1;
	}

	/**
	 * Thread of the engine (binder calls)
	 */
	@SuppressWarnings("deprecation")
	public static void disassociate(@NonNull BleEngine engine, @NonNull BleDevice device) {
		engine.handler().post(() -> {
			CompanionDeviceManager cdm = manager(engine.context());
			if (cdm == null) return;
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					int id = associationId(cdm, device.getMac());
					if (id >= 0) cdm.disassociate(id);
				} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					cdm.disassociate(device.getMac());
				}
			} catch (RuntimeException e) {
				BleLog.w("disassociate", e);
			}
		});
	}

	/**
	 * Removes the bond of an associated device with the public API (Android 16+). Thread of the engine.
	 * @return false when not possible (not associated, older Android): the caller uses the hidden method
	 */
	static boolean removeBond(@NonNull Context context, @NonNull BleDevice device) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false;
		CompanionDeviceManager cdm = manager(context);
		if (cdm == null) return false;
		try {
			int id = associationId(cdm, device.getMac());
			return id >= 0 && cdm.removeBond(id);
		} catch (RuntimeException e) {
			BleLog.w("removeBond", e);
			return false;
		}
	}
}
