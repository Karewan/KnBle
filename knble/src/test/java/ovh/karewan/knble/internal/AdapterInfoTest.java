package ovh.karewan.knble.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.struct.BleAdapterInfo;

/**
 * KnBle.getAdapterInfo: the features of the adapter, before and after init, kept while Bluetooth is off
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AdapterInfoTest {
	private Context mContext;
	private BluetoothAdapter mAdapter;

	@Before
	public void setUp() {
		BleEngine.resetForTests();
		mContext = ApplicationProvider.getApplicationContext();
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true);
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_BLUETOOTH, true);
		shadowOf(mContext.getPackageManager()).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true);
		shadowOf((Application) mContext).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
		mAdapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
		shadowOf(mAdapter).setIsLe2MPhySupported(true);
		shadowOf(mAdapter).setIsLeCodedPhySupported(true);
		shadowOf(mAdapter).setIsLeExtendedAdvertisingSupported(true);
		shadowOf(mAdapter).setIsMultipleAdvertisementSupported(true);
		shadowOf(mAdapter).setLeAudioSupported(BluetoothStatusCodes.FEATURE_SUPPORTED);
	}

	@After
	public void tearDown() {
		BleEngine.resetForTests();
	}

	private static void assertFeatures(BleAdapterInfo info) {
		assertTrue(info.isKnown());
		assertTrue(info.isLe2MPhySupported());
		assertTrue(info.isLeCodedPhySupported());
		assertTrue(info.isExtendedAdvertisingSupported());
		assertTrue(info.isMultipleAdvertisementSupported());
		assertTrue(info.isLeAudioSupported());
		assertTrue(info.getMaxAdvertisingDataLength() >= BleAdapterInfo.LEGACY_ADVERTISING_DATA_LENGTH);
	}

	@Test
	public void featuresWithBluetoothOn() {
		shadowOf(mAdapter).setEnabled(true);
		assertTrue(KnBle.init(mContext));
		BleAdapterInfo info = KnBle.getAdapterInfo(mContext);
		assertTrue(info.isEnabled());
		assertTrue(info.hasBluetoothLe());
		assertTrue(info.hasBluetoothClassic());
		assertTrue(info.hasCompanionDeviceSetup());
		assertFeatures(info);
		// Before Android 17 the app may always give the codes
		assertTrue(info.canAnswerPairing());
		String report = info.toString();
		assertTrue(report, report.contains("High speed (PHY LE 2M): supported"));
		assertTrue(report, report.contains("Long range (PHY LE Coded): supported"));
		assertTrue(report, report.contains("LE Audio: supported"));
		assertTrue(report, report.contains("Bluetooth: on"));
	}

	@Test
	public void worksBeforeInit() {
		shadowOf(mAdapter).setEnabled(true);
		BleAdapterInfo info = KnBle.getAdapterInfo(mContext);
		assertTrue(info.isEnabled());
		assertFeatures(info);
	}

	@Test
	public void featuresKeptWhileBluetoothIsOff() {
		shadowOf(mAdapter).setEnabled(true);
		assertTrue(KnBle.init(mContext));
		assertFeatures(KnBle.getAdapterInfo(mContext));

		// Android answers false to everything while it is off
		shadowOf(mAdapter).setEnabled(false);
		shadowOf(mAdapter).setIsLe2MPhySupported(false);
		BleAdapterInfo off = KnBle.getAdapterInfo(mContext);
		assertFalse(off.isEnabled());
		assertFeatures(off);
	}

	@Test
	public void unknownWhenBluetoothWasNeverOn() {
		shadowOf(mAdapter).setEnabled(false);
		assertTrue(KnBle.init(mContext));
		BleAdapterInfo info = KnBle.getAdapterInfo(mContext);
		assertFalse(info.isEnabled());
		assertFalse(info.isKnown());
		assertFalse(info.isLe2MPhySupported());
		assertEquals(0, info.getMaxAdvertisingDataLength());
		// Known without Bluetooth on
		assertTrue(info.hasBluetoothLe());
		assertTrue(info.toString(), info.toString().contains("LE features: unknown"));
	}
}
