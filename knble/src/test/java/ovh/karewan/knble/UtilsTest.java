package ovh.karewan.knble;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.UUID;

public class UtilsTest {

	@Test
	public void macToLong() {
		assertEquals(0xAABBCCDDEEFFL, Utils.macToLong("AA:BB:CC:DD:EE:FF"));
		assertEquals(1L, Utils.macToLong("00:00:00:00:00:01"));
	}

	@Test
	public void uuidAsBytesIsBigEndian() {
		byte[] b = Utils.uuidAsBytes(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
		assertEquals(16, b.length);
		assertEquals(0x00, b[0]);
		assertEquals((byte) 0xFF, b[15]);
		assertEquals(0x44, b[4]);
	}
}
