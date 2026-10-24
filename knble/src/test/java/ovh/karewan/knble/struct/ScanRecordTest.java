package ovh.karewan.knble.struct;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class ScanRecordTest {

	private static final class Record {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Record ad(int type, byte... data) {
			out.write(data.length + 1);
			out.write(type);
			out.write(data, 0, data.length);
			return this;
		}

		byte[] build() {
			return out.toByteArray();
		}
	}

	private static byte[] manufacturer(int company, byte... payload) {
		byte[] b = new byte[payload.length + 2];
		b[0] = (byte) company;
		b[1] = (byte) (company >> 8);
		System.arraycopy(payload, 0, b, 2, payload.length);
		return b;
	}

	@Test
	public void manufacturerIdAndData() {
		byte[] raw = new Record()
				.ad(0x01, (byte) 0x06)
				.ad(0x09, "KnBleBench".getBytes(StandardCharsets.UTF_8))
				.ad(0xFF, manufacturer(0x6959, (byte) 1, (byte) 2, (byte) 3))
				.build();

		ScanRecord record = new ScanRecord(raw);
		assertEquals(Integer.valueOf(0x6959), record.getManufacturerId());
		assertArrayEquals(new byte[]{1, 2, 3}, record.getManufacturerData());
		assertNull(record.getBeaconUUID());
	}

	/**
	 * The PTIblue4 carries its state and battery in the 16 bits of the company id: getManufacturerId() must keep its meaning
	 */
	@Test
	public void manufacturerIdCarriesDataLikeThePtiblue4() {
		ScanRecord record = new ScanRecord(new Record().ad(0xFF, manufacturer(0x0341)).build());
		assertEquals(Integer.valueOf(0x0341), record.getManufacturerId());
		assertEquals(0x03, (record.getManufacturerId() & 0xFF00) >> 8);
		assertEquals(0x41, record.getManufacturerId() & 0xFF);
	}

	@Test
	public void iBeaconUuid() {
		UUID uuid = UUID.fromString("e2c56db5-dffb-48d2-b060-d0f5a71096e0");
		byte[] payload = new byte[23];
		payload[0] = 0x02;
		payload[1] = 0x15;
		long msb = uuid.getMostSignificantBits(), lsb = uuid.getLeastSignificantBits();
		for (int i = 0; i < 8; i++) {
			payload[2 + i] = (byte) (msb >> (56 - 8 * i));
			payload[10 + i] = (byte) (lsb >> (56 - 8 * i));
		}

		ScanRecord record = new ScanRecord(new Record().ad(0x01, (byte) 0x06).ad(0xFF, manufacturer(0x004C, payload)).build());
		assertEquals(Integer.valueOf(0x004C), record.getManufacturerId());
		assertEquals(uuid, record.getBeaconUUID());
	}

	@Test
	public void noManufacturerData() {
		ScanRecord record = new ScanRecord(new Record().ad(0x01, (byte) 0x06).ad(0x09, (byte) 'A').build());
		assertNull(record.getManufacturerId());
		assertNull(record.getManufacturerData());
	}

	@Test
	public void serviceUuidsOfEverySize() {
		UUID u128 = UUID.fromString("4b420001-8d28-4316-88b3-6e0111311ac6");
		byte[] le128 = new byte[16];
		long msb = u128.getMostSignificantBits(), lsb = u128.getLeastSignificantBits();
		for (int i = 0; i < 8; i++) {
			le128[i] = (byte) (lsb >> (8 * i));
			le128[8 + i] = (byte) (msb >> (8 * i));
		}
		ScanRecord record = new ScanRecord(new Record()
				.ad(0x03, (byte) 0x0D, (byte) 0x18, (byte) 0x0F, (byte) 0x18)
				.ad(0x05, (byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12)
				.ad(0x07, le128)
				.build());
		assertEquals(List.of(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"), UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
				UUID.fromString("12345678-0000-1000-8000-00805f9b34fb"), u128), record.getServiceUuids());
	}

	@Test
	public void serviceDataTxPowerFlagsAndEveryManufacturer() {
		ScanRecord record = new ScanRecord(new Record()
				.ad(0x01, (byte) 0x06)
				.ad(0x0A, (byte) -12)
				.ad(0x16, (byte) 0xAA, (byte) 0xFE, (byte) 0x10, (byte) 0x20)
				.ad(0xFF, manufacturer(0x0059, (byte) 1))
				.ad(0xFF, manufacturer(0x6957, (byte) 2, (byte) 3))
				.build());
		assertEquals(0x06, record.getAdvertiseFlags());
		assertEquals(Integer.valueOf(-12), record.getTxPowerLevel());
		assertArrayEquals(new byte[]{0x10, 0x20}, record.getServiceData(UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")));
		assertEquals(2, record.getManufacturerSpecificData().size());
		assertArrayEquals(new byte[]{1}, record.getManufacturerSpecificData(0x0059));
		// The last one stays the one of getManufacturerId() (3.x semantics)
		assertEquals(Integer.valueOf(0x6957), record.getManufacturerId());
		assertArrayEquals(new byte[]{2, 3}, record.getManufacturerData());
	}

	@Test
	public void mergeKeepsEveryFieldTheReportLacks() {
		ScanRecord full = new ScanRecord(new Record()
				.ad(0x01, (byte) 0x06)
				.ad(0x03, (byte) 0x0D, (byte) 0x18)
				.ad(0x0A, (byte) 4)
				.ad(0xFF, manufacturer(0x6957, (byte) 1))
				.ad(0x09, "PTIBLUE5".getBytes(StandardCharsets.UTF_8))
				.build());
		// Scan response alone: the name only
		ScanRecord response = new ScanRecord(new Record().ad(0x09, "PTIBLUE5".getBytes(StandardCharsets.UTF_8)).build()).mergedWith(full);
		assertEquals(Integer.valueOf(0x6957), response.getManufacturerId());
		assertEquals(1, response.getServiceUuids().size());
		assertEquals(Integer.valueOf(4), response.getTxPowerLevel());
		assertEquals(0x06, response.getAdvertiseFlags());
		// New manufacturer data: replaces the old one at once
		ScanRecord next = new ScanRecord(new Record().ad(0xFF, manufacturer(0x6957, (byte) 9)).build()).mergedWith(response);
		assertArrayEquals(new byte[]{9}, next.getManufacturerData());
		assertEquals("PTIBLUE5", next.getDeviceName());
	}

	@Test
	public void malformedRecordDoesNotThrow() {
		// Length running past the end of the record, then a truncated manufacturer data
		new ScanRecord(new byte[]{0x05, (byte) 0xFF, 0x01});
		new ScanRecord(new byte[]{0x02, (byte) 0xFF, 0x01});
		new ScanRecord(new byte[]{(byte) 0xFF});
		new ScanRecord(new byte[0]);
	}
}
