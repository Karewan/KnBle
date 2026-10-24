package ovh.karewan.knble.sample;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.zip.CRC32;

import ovh.karewan.knble.struct.BleDevice;
import ovh.karewan.knble.struct.ScanRecord;

/**
 * GATT table and commands of the ESP32 test bench (testbench/README.md)
 */
final class Bench {
	static final String NAME = "KnBleBench";
	static final String NAME_DFU = "KnBleBench-DFU";

	static final UUID SVC_BENCH = bench("0001");
	static final UUID CHR_CONTROL = bench("0002");
	static final UUID CHR_SINK = bench("0003");
	static final UUID CHR_STREAM = bench("0004");
	static final UUID CHR_INDICATE = bench("0005");
	static final UUID CHR_RESULT = bench("0006");
	static final UUID CHR_ENCRYPTED = bench("0007");
	static final UUID CHR_NOTIFY = bench("0008");
	// 512 bytes, read blob and prepared writes at MTU 23
	static final UUID CHR_LONG = bench("0009");
	static final int LONG_LENGTH = 512;
	static final UUID SVC_DUP_A = bench("0010");
	static final UUID CHR_DUP = bench("0011");
	static final UUID SVC_DUP_B = bench("0020");
	static final UUID SVC_DFU_INFO = bench("0030");
	static final UUID CHR_DFU_RESULT = bench("0031");

	// Public UUIDs of the Silicon Labs AppLoader OTA and of the TI Enhanced OAD
	static final UUID SVC_SLABS_OTA = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0");
	static final UUID CHR_SLABS_CONTROL = UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063");
	static final UUID CHR_SLABS_DATA = UUID.fromString("984227f3-34fc-4045-a5d0-2c581f81a153");
	static final UUID SVC_TI_OAD = UUID.fromString("f000ffc0-0451-4000-b000-000000000000");
	static final UUID CHR_TI_IDENTIFY = UUID.fromString("f000ffc1-0451-4000-b000-000000000000");
	static final UUID CHR_TI_BLOCK = UUID.fromString("f000ffc2-0451-4000-b000-000000000000");
	static final UUID CHR_TI_CONTROL = UUID.fromString("f000ffc5-0451-4000-b000-000000000000");

	static final byte CMD_RESET_STATS = 0x01;
	static final byte CMD_GET_STATS = 0x02;
	static final byte CMD_STREAM = 0x03;
	static final byte CMD_SINK_DELAY = 0x04;
	static final byte CMD_SINK_BUFFER = 0x05;
	static final byte CMD_ADV = 0x06;
	static final byte CMD_REBOOT = 0x07;
	static final byte CMD_INDICATE = 0x08;
	static final byte CMD_DISCONNECT_IN = 0x09;
	static final byte CMD_GET_INFO = 0x0A;
	// ESP32-C3 only (the phone bench answers status 1): pairing mode, bonds forgotten by the bench, connection parameters
	// asked by the bench
	static final byte CMD_SECURITY = 0x0B;
	static final byte CMD_DELETE_BONDS = 0x0C;
	static final byte CMD_CONN_PARAMS = 0x0D;

	static final int SECURITY_JUST_WORKS = 0;
	static final int SECURITY_PASSKEY = 1;
	static final int SECURITY_NUMERIC_COMPARISON = 2;
	// Shown by the bench in the passkey mode
	static final String PASSKEY = "123456";

	static final int ADV_NORMAL = 0;
	static final int ADV_NAME_IN_SCAN_RESPONSE = 1;
	static final int ADV_NON_CONNECTABLE = 2;
	static final int ADV_SLOW = 3;
	static final int ADV_STOPPED = 4;

	// Manufacturer data of the bench advertising: company 0xFFFF, counter, mode
	static final int COMPANY_TEST = 0xFFFF;

	private Bench() {}

	/**
	 * Image of the TI OAD run by KnBle: the one of image(), with the fields of the OAD header KnBle checks (length at 24,
	 * header length at 40)
	 */
	static byte[] tiImage(int size, int seed) {
		byte[] image = image(size, seed);
		putLe32(image, 24, size);
		image[40] = 44;
		image[41] = 0;
		return image;
	}

	/**
	 * The raw advertising holds the bench service UUID (the phone bench keeps the Bluetooth name of the phone). Parsed here:
	 * the same code for every version of KnBle.
	 */
	static boolean advertisesBenchService(@Nullable byte[] raw) {
		if (raw == null) return false;
		byte[] uuid = new byte[16];
		long msb = SVC_BENCH.getMostSignificantBits(), lsb = SVC_BENCH.getLeastSignificantBits();
		for (int i = 0; i < 8; i++) {
			uuid[i] = (byte) (lsb >> (8 * i));
			uuid[8 + i] = (byte) (msb >> (8 * i));
		}
		int pos = 0;
		while (pos < raw.length) {
			int length = raw[pos++] & 0xFF;
			if (length == 0 || pos + length > raw.length) return false;
			int type = raw[pos] & 0xFF;
			if (type == 0x06 || type == 0x07) {
				for (int i = pos + 1; i + 16 <= pos + length; i += 16) {
					boolean same = true;
					for (int k = 0; k < 16 && same; k++) same = raw[i + k] == uuid[k];
					if (same) return true;
				}
			}
			pos += length;
		}
		return false;
	}

	/**
	 * Value of the long characteristic: the initial one for seed 0
	 */
	@NonNull
	static byte[] longPattern(int seed) {
		byte[] b = new byte[LONG_LENGTH];
		for (int i = 0; i < LONG_LENGTH; i++) b[i] = (byte) (i * 31 + (i >> 8) + seed);
		return b;
	}

	static UUID bench(String id) {
		return UUID.fromString("4b42" + id + "-8d28-4316-88b3-6e0111311ac6");
	}

	/**
	 * Command with little endian arguments: Integer = u32, Short = u16, Byte = u8
	 */
	@NonNull
	static byte[] command(byte cmd, Object... args) {
		int len = 1;
		for (Object a : args) len += a instanceof Integer ? 4 : a instanceof Short ? 2 : 1;

		byte[] b = new byte[len];
		b[0] = cmd;
		int p = 1;
		for (Object a : args) {
			if (a instanceof Integer i) {
				putLe32(b, p, i);
				p += 4;
			} else if (a instanceof Short s) {
				b[p++] = (byte) (s & 0xFF);
				b[p++] = (byte) ((s >> 8) & 0xFF);
			} else {
				b[p++] = (Byte) a;
			}
		}
		return b;
	}

	static void putLe32(@NonNull byte[] b, int p, int v) {
		for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i));
	}

	static long le32(@NonNull byte[] b, int p) {
		return (b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16 | (b[p + 3] & 0xFFL) << 24;
	}

	static int le16(@NonNull byte[] b, int p) {
		return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
	}

	/**
	 * Deterministic pseudo random image
	 */
	@NonNull
	static byte[] image(int size, long seed) {
		byte[] b = new byte[size];
		new Random(seed).nextBytes(b);
		return b;
	}

	static long crc32(@NonNull byte[] b) {
		CRC32 crc = new CRC32();
		crc.update(b);
		return crc.getValue();
	}

	/**
	 * Name advertised by the device (Complete or Shortened Local Name), read from the raw scan record:
	 * KnBle 3.0.5 only exposes the name known by the system
	 */
	@Nullable
	static String advertisedName(@Nullable ScanRecord record) {
		byte[] raw = record == null ? null : record.getRawRecord();
		if (raw == null) return null;

		int p = 0;
		while (p < raw.length) {
			int len = raw[p] & 0xFF;
			if (len == 0 || p + len >= raw.length) break;
			int type = raw[p + 1] & 0xFF;
			if ((type == 0x09 || type == 0x08) && len > 1) return new String(raw, p + 2, len - 1, StandardCharsets.UTF_8);
			p += len + 1;
		}
		return null;
	}

	/**
	 * Counter of the bench advertising, -1 when absent
	 */
	static int advertisedCounter(@NonNull BleDevice device) {
		ScanRecord record = device.getScanRecord();
		if (record == null || record.getManufacturerId() == null || record.getManufacturerId() != COMPANY_TEST) return -1;
		byte[] data = record.getManufacturerData();
		return data == null || data.length < 2 ? -1 : le16(data, 0);
	}

	@NonNull
	static String text(@Nullable byte[] data) {
		return data == null ? "null" : new String(data, StandardCharsets.UTF_8);
	}
}
