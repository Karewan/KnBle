package ovh.karewan.knble.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleAssociateCallback;
import ovh.karewan.knble.scan.ScanFilters;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Companion device association: the request lives until its result, whatever happens to AssociateActivity
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CompanionTest {
	private static final String MAC = "AA:BB:CC:DD:EE:09";

	private Context mContext;
	private final List<String> mEvents = new CopyOnWriteArrayList<>();

	private final BleAssociateCallback mCallback = new BleAssociateCallback() {
		@Override
		public void onAssociated(@NonNull BleDevice device) {
			mEvents.add("associated " + device.getMac());
		}

		@Override
		public void onAssociationFailed(int error) {
			mEvents.add("failed " + error);
		}
	};

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		clearPending();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		BluetoothAdapter adapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(adapter).setEnabled(true);
		assertTrue(KnBle.init(mContext));
	}

	@After
	public void tearDown() {
		clearPending();
		BleEngine.resetForTests();
	}

	private static void clearPending() {
		Companion.Pending p = Companion.pending();
		if (p != null) p.failed(BleError.CANCELLED);
	}

	private static void idle(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	private static ScanFilters filters() {
		return new ScanFilters.Builder().addDeviceNameStartsWith("PTI").build();
	}

	@Test
	public void aPhoneWithoutTheCompanionServiceIsNotSupported() {
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, false);
		KnBle.associate(mContext, filters(), true, mCallback);
		idle(100);
		assertEquals(List.of("failed " + BleError.NOT_SUPPORTED), mEvents);
		// No invisible activity left over the app
		assertNull(shadowOf((Application) mContext).getNextStartedActivity());
		assertNull(Companion.pending());
	}

	@Test
	public void anActivityStartDroppedByAndroidFailsTheRequest() {
		// Robolectric records the start without creating the activity: what Android 10+ does for an app in the background
		KnBle.associate(mContext, filters(), true, mCallback);
		assertNotNull(shadowOf((Application) mContext).getNextStartedActivity());
		idle(Companion.START_TIMEOUT_MS - 1000);
		assertTrue(mEvents.isEmpty());
		idle(2000);
		assertEquals(List.of("failed " + BleError.REFUSED), mEvents);
		assertNull(Companion.pending());
	}

	@Test
	public void theRequestSurvivesARecreationOfTheActivity() {
		KnBle.associate(mContext, filters(), true, mCallback);
		Intent started = shadowOf((Application) mContext).getNextStartedActivity();
		ActivityController<AssociateActivity> controller = Robolectric.buildActivity(AssociateActivity.class, started).setup();
		CompanionDeviceManager.Callback asked = shadowOf(controller.get().getSystemService(CompanionDeviceManager.class)).getLastAssociationCallback();
		assertNotNull(asked);

		// A configuration change it does not handle: neither failed nor asked again
		controller.recreate();
		idle(100);
		assertTrue(mEvents.isEmpty());
		AssociateActivity activity = controller.get();
		CompanionDeviceManager.Callback last = shadowOf(activity.getSystemService(CompanionDeviceManager.class)).getLastAssociationCallback();
		assertTrue(last == null || last == asked);

		// The result of the dialog comes to the new instance
		BleDevice device = KnBle.getBleDeviceFromMac(MAC);
		assertNotNull(device);
		activity.onActivityResult(AssociateActivity.REQUEST_CHOOSER, Activity.RESULT_OK,
				new Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, device.getDevice()));
		idle(100);
		assertEquals(List.of("associated " + MAC), mEvents);
		assertTrue(activity.isFinishing());
		controller.pause().stop().destroy();
		idle(Companion.START_TIMEOUT_MS + 1000);
		assertEquals(1, mEvents.size());
		assertNull(Companion.pending());
	}

	@Test
	public void closedWithoutAResultIsCancelledOnce() {
		KnBle.associate(mContext, filters(), true, mCallback);
		Intent started = shadowOf((Application) mContext).getNextStartedActivity();
		ActivityController<AssociateActivity> controller = Robolectric.buildActivity(AssociateActivity.class, started).setup();
		controller.get().finish();
		controller.pause().stop().destroy();
		idle(Companion.START_TIMEOUT_MS + 1000);
		assertEquals(List.of("failed " + BleError.CANCELLED), mEvents);
	}
}
