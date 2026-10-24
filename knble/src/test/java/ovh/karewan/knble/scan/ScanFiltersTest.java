package ovh.karewan.knble.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

public class ScanFiltersTest {

	@Test
	public void emptyByDefault() {
		ScanFilters filters = new ScanFilters.Builder().build();
		assertEquals(0, filters.count());
		assertEquals(0, filters.count6Filters());
		assertTrue(filters.isUsingAndroid6Filters());
	}

	@Test
	public void countsHardwareAndSoftwareFilters() {
		ScanFilters filters = new ScanFilters.Builder()
				.addDeviceName("A")
				.addDeviceNameStartsWith("PTIBLUE")
				.addDeviceNameEndsWith("_X")
				.addMacAddress("00:0B:57:00:00:01")
				.addMacAddressStartsWith("00:0B:57")
				.addManufacturerId(0x6957)
				.addBeaconUUID(UUID.randomUUID())
				.build();

		assertEquals(7, filters.count());
		// Name, MAC, manufacturer id and beacon UUID can be given to the system (hardware filters)
		assertEquals(4, filters.count6Filters());
	}

	@Test
	public void macAddressesAreUpperCase() {
		ScanFilters filters = new ScanFilters.Builder().addMacAddress("aa:bb:cc:dd:ee:0f").addMacAddressStartsWith("00:0b:5").build();
		// As Android gives them: the filters of KnBle and of the controller match the same devices
		assertEquals(List.of("AA:BB:CC:DD:EE:0F"), filters.getDevicesMacs());
		assertEquals(List.of("00:0B:5"), filters.getDevicesMacsStartsWith());
	}

	@Test
	public void invalidFiltersThrowWhenAdded() {
		// Thrown here, on the thread of the app, not by the system on the thread of the scan
		ScanFilters.Builder b = new ScanFilters.Builder();
		assertThrows(IllegalArgumentException.class, () -> b.addMacAddress("AA:BB:CC"));
		assertThrows(IllegalArgumentException.class, () -> b.addMacAddress("AA-BB-CC-DD-EE-FF"));
		assertThrows(IllegalArgumentException.class, () -> b.addMacAddress("GG:BB:CC:DD:EE:FF"));
		assertThrows(IllegalArgumentException.class, () -> b.addMacAddressStartsWith("AABB"));
		assertThrows(IllegalArgumentException.class, () -> b.addManufacturerId(-1));
		assertThrows(IllegalArgumentException.class, () -> b.addManufacturerId(0x10000));
		assertThrows(NullPointerException.class, () -> b.addBeaconUUID(null));
		assertThrows(NullPointerException.class, () -> b.addServiceUuid(null));
		assertThrows(NullPointerException.class, () -> b.addServiceData(null, new byte[0]));
		assertThrows(NullPointerException.class, () -> b.addDeviceName(null));
		assertEquals(0, b.build().count());
	}

	@Test
	public void builtFiltersDoNotChangeWithTheBuilder() {
		ScanFilters.Builder b = new ScanFilters.Builder().addDeviceName("A");
		ScanFilters filters = b.build();
		b.addDeviceName("B");
		assertEquals(List.of("A"), filters.getDeviceNames());
		assertThrows(UnsupportedOperationException.class, () -> filters.getDeviceNames().add("C"));
	}
}
