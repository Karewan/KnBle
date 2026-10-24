// KnBle BLE test bench, Seeed XIAO ESP32-C3 (Bluetooth 5: 2M PHY, data length extension): the BLE peripheral the sample
// app drives to benchmark and stress KnBle. GATT table and protocol: testbench/README.md. It covers the transfers (sink,
// streams, OTA emulations of Silicon Labs and TI, slow device, bootloader without flow control), indications, long
// values, duplicate services, advertising modes, a DFU reboot with other services, the link loss, the pairing modes
// (just works, passkey shown by the bench, numeric comparison), the bond forgotten by the bench, and connection
// parameters asked by the device. No product code or key here: the Silicon Labs AppLoader OTA and TI OAD services only
// reuse the public UUIDs of their SDKs.

#include <Arduino.h>
#include <NimBLEDevice.h>

#define KB_UUID(x) "4b42" x "-8d28-4316-88b3-6e0111311ac6"

static const char *SVC_BENCH = KB_UUID("0001");
static const char *CHR_CONTROL = KB_UUID("0002");
static const char *CHR_SINK = KB_UUID("0003");
static const char *CHR_STREAM = KB_UUID("0004");
static const char *CHR_INDICATE = KB_UUID("0005");
static const char *CHR_RESULT = KB_UUID("0006");
static const char *CHR_ENCRYPTED = KB_UUID("0007");
static const char *CHR_NOTIFY = KB_UUID("0008");
static const char *CHR_LONG = KB_UUID("0009");
static const char *SVC_DUP_A = KB_UUID("0010");
static const char *CHR_DUP = KB_UUID("0011");
static const char *SVC_DUP_B = KB_UUID("0020");
static const char *SVC_DFU_INFO = KB_UUID("0030");
static const char *CHR_DFU_RESULT = KB_UUID("0031");

static const char *SVC_SLABS_OTA = "1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0";
static const char *CHR_SLABS_CONTROL = "f7bf3564-fb6d-4e53-88a4-5e37e0326063";
static const char *CHR_SLABS_DATA = "984227f3-34fc-4045-a5d0-2c581f81a153";

static const char *SVC_TI_OAD = "f000ffc0-0451-4000-b000-000000000000";
static const char *CHR_TI_IDENTIFY = "f000ffc1-0451-4000-b000-000000000000";
static const char *CHR_TI_BLOCK = "f000ffc2-0451-4000-b000-000000000000";
static const char *CHR_TI_CONTROL = "f000ffc5-0451-4000-b000-000000000000";

static const uint8_t FW_VERSION = 2;
// Found by its name (the Android bench host is found by its service UUID in the advertising, --es bench pc)
static const char *NAME_NORMAL = "KnBleBench";
static const char *NAME_DFU = "KnBleBench-DFU";
// Passkey shown by the bench in the passkey pairing mode, typed on the phone (or by the app: BlePairingRequest.setPin)
static const uint32_t PASSKEY = 123456;
// Length of the long characteristic (the longest attribute value)
static const uint16_t LONG_LENGTH = 512;
// 512 bytes is the maximum length of an attribute value: an MTU of 515 makes MTU - 3 a valid write size
static const uint16_t PREFERRED_MTU = 515;
static const uint32_t DFU_IDLE_REBOOT_MS = 120000;
static const uint32_t ADV_MODE_DURATION_MS = 20000;
static const uint32_t DFU_END_REBOOT_DELAY_MS = 1500;
static uint32_t sRebootAt = 0;

// Boot mode kept across ESP.restart() (lost on power off, back to normal mode)
static const uint32_t BOOT_MAGIC = 0x4B4E424C;
RTC_NOINIT_ATTR static uint32_t sBootMagic;
RTC_NOINIT_ATTR static uint32_t sBootMode;
static uint8_t sMode = 0; // 0 normal, 1 DFU

// Commands
enum : uint8_t {
	CMD_RESET_STATS = 0x01,
	CMD_GET_STATS = 0x02,
	CMD_STREAM = 0x03,
	CMD_SINK_DELAY = 0x04,
	CMD_SINK_BUFFER = 0x05,
	CMD_ADV = 0x06,
	CMD_REBOOT = 0x07,
	CMD_INDICATE = 0x08,
	CMD_DISCONNECT_IN = 0x09,
	CMD_GET_INFO = 0x0A,
	CMD_SECURITY = 0x0B,
	CMD_DELETE_BONDS = 0x0C,
	CMD_CONN_PARAMS = 0x0D,
	// Internal, posted by the BLE callbacks to the loop task
	CMD_OTA_END = 0xF0,
};

// OTA result
enum : uint8_t { PROTO_NONE = 0, PROTO_SLABS = 1, PROTO_TI = 2 };
enum : uint8_t { OTA_IDLE = 0, OTA_RUNNING = 1, OTA_DONE = 2, OTA_ERROR = 3 };

// TI Enhanced OAD
static const uint8_t TI_GET_BLOCK_SIZE = 0x01, TI_START = 0x03, TI_ENABLE_IMAGE = 0x04, TI_BLOCK_REQUEST = 0x12;
static const uint8_t TI_SUCCESS = 0x00, TI_DOWNLOAD_COMPLETE = 0x0E;

struct Command {
	uint8_t len;
	uint8_t data[20];
};

static NimBLEServer *sServer;
static NimBLECharacteristic *sControl, *sStream, *sIndicate, *sResult, *sTiIdentify, *sTiBlock, *sTiControl, *sLong;
// Pairing mode: 0 just works, 1 passkey shown by the bench, 2 numeric comparison (confirmed by the bench)
static uint8_t sSecurity = 0;
static QueueHandle_t sCommands;
static volatile uint16_t sConn = BLE_HS_CONN_HANDLE_NONE;
static volatile uint32_t sDisconnectedAt = 0;
static volatile bool sRestartAdvertising = false;
// CMD_DELETE_BONDS: the bonds go once the phone disconnected (a device reset between two connections)
static volatile bool sDeleteBonds = false;

// ---------- CRC-32 (zlib, same as java.util.zip.CRC32) ----------

static uint32_t sCrcTable[256];

static void crcInit() {
	for (uint32_t i = 0; i < 256; i++) {
		uint32_t c = i;
		for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		sCrcTable[i] = c;
	}
}

static uint32_t crcUpdate(uint32_t crc, const uint8_t *p, size_t n) {
	crc = ~crc;
	while (n--) crc = sCrcTable[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
	return ~crc;
}

static void putLe16(uint8_t *p, uint16_t v) {
	p[0] = v;
	p[1] = v >> 8;
}

static void putLe32(uint8_t *p, uint32_t v) {
	for (int i = 0; i < 4; i++) p[i] = v >> (8 * i);
}

static uint32_t getLe32(const uint8_t *p) {
	return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t) p[3] << 24;
}

static uint16_t getLe16(const uint8_t *p) {
	return p[0] | p[1] << 8;
}

// ---------- Sink: every written byte goes through it (sink, Silicon Labs data, TI blocks) ----------

// Written by a single producer at a time (BLE host task, or the drain task in no flow control mode)
static volatile uint32_t sBytes, sWrites, sDropped, sCrc, sFirstMs, sLastMs;
// Pauses between two packets: the largest one, how many lasted 50 ms or more
static volatile uint32_t sMaxGapMs, sGaps50;
static volatile uint32_t sSinkDelayUs = 0;
static volatile uint8_t sSinkMode = 0; // 0 inline, 1 no flow control
static volatile uint16_t sSinkDepth = 8;
static volatile uint32_t sDrainUs = 2000;
static QueueHandle_t sSinkQueue;

struct SinkItem {
	uint16_t len;
	uint8_t data[512];
};
static SinkItem sIncoming;

static void sinkReset() {
	if (sSinkQueue) xQueueReset(sSinkQueue);
	sBytes = sWrites = sDropped = sCrc = sFirstMs = sLastMs = sMaxGapMs = sGaps50 = 0;
}

static void sinkConsume(const uint8_t *d, size_t n) {
	uint32_t now = millis();
	if (sWrites == 0) sFirstMs = now;
	else {
		uint32_t gap = now - sLastMs;
		if (gap > sMaxGapMs) sMaxGapMs = gap;
		if (gap >= 50) sGaps50 = sGaps50 + 1;
	}
	sLastMs = now;
	sWrites = sWrites + 1;
	sBytes = sBytes + n;
	sCrc = crcUpdate(sCrc, d, n);
}

static void sinkWrite(const uint8_t *d, size_t n) {
	if (sSinkMode == 1) {
		// Buggy bootloader: the write is acknowledged at once and kept in a small buffer, what does not fit is lost
		if (uxQueueMessagesWaiting(sSinkQueue) >= sSinkDepth) {
			sDropped = sDropped + 1;
			return;
		}
		sIncoming.len = n > sizeof(sIncoming.data) ? sizeof(sIncoming.data) : n;
		memcpy(sIncoming.data, d, sIncoming.len);
		if (xQueueSend(sSinkQueue, &sIncoming, 0) != pdTRUE) sDropped = sDropped + 1;
		return;
	}

	// Slow device: the host task is held, the link layer flow control slows the phone down
	if (sSinkDelayUs) delayMicroseconds(sSinkDelayUs);
	sinkConsume(d, n);
}

static void drainTask(void *) {
	static SinkItem item;
	for (;;) {
		if (xQueueReceive(sSinkQueue, &item, pdMS_TO_TICKS(50)) != pdTRUE) continue;
		sinkConsume(item.data, item.len);
		uint32_t us = sDrainUs;
		if (us >= 1000) vTaskDelay(pdMS_TO_TICKS(us / 1000));
		else if (us) delayMicroseconds(us);
	}
}

// ---------- OTA result ----------

static volatile uint8_t sOtaProtocol = PROTO_NONE, sOtaState = OTA_IDLE;
static volatile uint32_t sOtaStartMs = 0;

static void otaStart(uint8_t protocol) {
	sinkReset();
	sOtaProtocol = protocol;
	sOtaState = OTA_RUNNING;
	sOtaStartMs = millis();
}

static void otaPublish() {
	uint8_t r[18];
	r[0] = sOtaProtocol;
	r[1] = sOtaState;
	putLe32(r + 2, sBytes);
	putLe32(r + 6, sCrc);
	putLe32(r + 10, sDropped);
	putLe32(r + 14, sOtaState == OTA_IDLE ? 0 : millis() - sOtaStartMs);
	sResult->setValue(r, sizeof(r));
}

static void postCommand(const uint8_t *d, size_t n) {
	Command c;
	c.len = n > sizeof(c.data) ? sizeof(c.data) : n;
	memcpy(c.data, d, c.len);
	if (xQueueSend(sCommands, &c, 0) != pdTRUE) Serial.println("command queue full");
}

/**
 * Notification sent again while the stack has no buffer left (right after a stream for instance):
 * NimBLECharacteristic::notify() would drop it silently. Loop task only.
 */
static bool notifyReliable(NimBLECharacteristic *c, const uint8_t *d, size_t n) {
	for (int attempt = 0; attempt < 1000; attempt++) {
		uint16_t conn = sConn;
		if (conn == BLE_HS_CONN_HANDLE_NONE) return false;
		os_mbuf *om = ble_hs_mbuf_from_flat(d, n);
		if (om != nullptr && ble_gattc_notify_custom(conn, c->getHandle(), om) == 0) return true;
		vTaskDelay(1);
	}
	return false;
}

static void answer(uint8_t cmd, uint8_t status, const uint8_t *payload = nullptr, size_t n = 0) {
	uint8_t b[32];
	b[0] = cmd;
	b[1] = status;
	if (n > sizeof(b) - 2) n = sizeof(b) - 2;
	if (n) memcpy(b + 2, payload, n);
	if (!notifyReliable(sControl, b, n + 2)) Serial.printf("answer 0x%02x lost\n", cmd);
}

// ---------- TI Enhanced OAD emulation (runs in the BLE host task: one block request per block) ----------

static uint16_t sTiBlockSize = 0;
static uint32_t sTiImageLen = 0, sTiBlockCount = 0, sTiNext = 0;
static bool sTiActive = false;

static void tiRequestBlock(uint32_t block) {
	uint8_t b[6] = {TI_BLOCK_REQUEST, TI_SUCCESS};
	putLe32(b + 2, block);
	sTiControl->notify(b, sizeof(b));
}

static uint16_t peerMtu() {
	uint16_t conn = sConn;
	return conn == BLE_HS_CONN_HANDLE_NONE ? 23 : sServer->getPeerMTU(conn);
}

class TiControlCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
		NimBLEAttValue v = c->getValue();
		if (v.size() == 0) return;

		switch (v.data()[0]) {
			case TI_GET_BLOCK_SIZE: {
				uint16_t size = peerMtu() - 3;
				if (size > 244) size = 244;
				sTiBlockSize = size;
				uint8_t b[3] = {TI_GET_BLOCK_SIZE};
				putLe16(b + 1, size);
				sTiControl->notify(b, sizeof(b));
				break;
			}
			case TI_START: {
				if (sTiBlockSize <= 4 || sTiImageLen == 0) {
					uint8_t b[2] = {TI_START, 0x01};
					sTiControl->notify(b, sizeof(b));
					return;
				}
				otaStart(PROTO_TI);
				sTiNext = 0;
				sTiActive = true;
				tiRequestBlock(0);
				break;
			}
			case TI_ENABLE_IMAGE: {
				uint8_t b[2] = {TI_ENABLE_IMAGE, TI_SUCCESS};
				sTiControl->notify(b, sizeof(b));
				uint8_t end[2] = {CMD_OTA_END, PROTO_TI};
				postCommand(end, sizeof(end));
				break;
			}
		}
	}
};

class TiIdentifyCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
		NimBLEAttValue v = c->getValue();
		uint8_t status = 0x01;
		if (v.size() >= 18 && sTiBlockSize > 4) {
			sTiImageLen = getLe32(v.data() + 14);
			uint32_t dataSize = sTiBlockSize - 4;
			sTiBlockCount = (sTiImageLen + dataSize - 1) / dataSize;
			if (sTiImageLen > 0) status = TI_SUCCESS;
		}
		sTiIdentify->notify(&status, 1);
	}
};

class TiBlockCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
		if (!sTiActive) return;
		NimBLEAttValue v = c->getValue();
		if (v.size() <= 4) return;

		// A block out of order is asked again, like the TI target does
		uint32_t block = getLe32(v.data());
		if (block != sTiNext) {
			tiRequestBlock(sTiNext);
			return;
		}

		sinkWrite(v.data() + 4, v.size() - 4);
		sTiNext++;
		if (sTiNext >= sTiBlockCount) {
			sTiActive = false;
			uint8_t b[2] = {TI_BLOCK_REQUEST, TI_DOWNLOAD_COMPLETE};
			sTiControl->notify(b, sizeof(b));
		} else {
			tiRequestBlock(sTiNext);
		}
	}
};

// ---------- Silicon Labs AppLoader emulation ----------

class SlabsControlCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
		NimBLEAttValue v = c->getValue();
		if (v.size() == 0) return;
		if (v.data()[0] == 0x00) {
			otaStart(PROTO_SLABS);
		} else if (v.data()[0] == 0x03) {
			uint8_t end[2] = {CMD_OTA_END, PROTO_SLABS};
			postCommand(end, sizeof(end));
		}
	}
};

class SinkCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
		NimBLEAttValue v = c->getValue();
		sinkWrite(v.data(), v.size());
	}
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
	void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
		NimBLEAttValue v = c->getValue();
		if (v.size()) postCommand(v.data(), v.size());
	}
};

class SubscribeLogger : public NimBLECharacteristicCallbacks {
	void onSubscribe(NimBLECharacteristic *c, NimBLEConnInfo &, uint16_t sub) override {
		Serial.printf("subscribe %s value=%u\n", c->getUUID().toString().c_str(), sub);
	}
};

class ServerCallbacks : public NimBLEServerCallbacks {
	void onConnect(NimBLEServer *s, NimBLEConnInfo &info) override {
		sConn = info.getConnHandle();
		// Data length extension: up to 251 bytes per link layer packet
		s->setDataLen(info.getConnHandle(), 251);
		Serial.printf("connected %s\n", info.getAddress().toString().c_str());
	}

	void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int reason) override {
		sConn = BLE_HS_CONN_HANDLE_NONE;
		sDisconnectedAt = millis();
		sTiActive = false;
		sRestartAdvertising = true;
		Serial.printf("disconnected, reason 0x%x\n", reason);
	}

	void onMTUChange(uint16_t mtu, NimBLEConnInfo &) override {
		Serial.printf("mtu %u\n", mtu);
	}

	void onAuthenticationComplete(NimBLEConnInfo &info) override {
		Serial.printf("security encrypted=%d authenticated=%d bonded=%d\n", info.isEncrypted(), info.isAuthenticated(), info.isBonded());
	}

	// Numeric comparison: the bench accepts, the user confirms on the phone
	void onConfirmPassKey(NimBLEConnInfo &info, uint32_t pin) override {
		Serial.printf("numeric comparison %06u: accepted\n", pin);
		NimBLEDevice::injectConfirmPasskey(info, true);
	}

	// Passkey mode: shown by the bench, typed on the phone
	uint32_t onPassKeyDisplay() override {
		Serial.printf("passkey %06u\n", PASSKEY);
		return PASSKEY;
	}

	void onConnParamsUpdate(NimBLEConnInfo &info) override {
		Serial.printf("connection parameters interval=%u latency=%u timeout=%u\n", info.getConnInterval(), info.getConnLatency(),
					  info.getConnTimeout());
	}
};

static ServerCallbacks sServerCallbacks;
static ControlCallbacks sControlCallbacks;
static SinkCallbacks sSinkCallbacks;
static SubscribeLogger sSubscribeLogger;
static SlabsControlCallbacks sSlabsControlCallbacks;
static TiControlCallbacks sTiControlCallbacks;
static TiIdentifyCallbacks sTiIdentifyCallbacks;
static TiBlockCallbacks sTiBlockCallbacks;

// ---------- Advertising ----------

static uint8_t sAdvMode = 0;
static uint32_t sAdvParam = 0, sAdvResumeAt = 0, sLastCounterMs = 0;
static uint16_t sAdvCounter = 0;

static NimBLEAdvertisementData advData(bool withName) {
	NimBLEAdvertisementData d;
	d.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
	if (withName) d.setName(sMode ? NAME_DFU : NAME_NORMAL);
	// Company 0xFFFF (test), then a counter changing every 500 ms and the advertising mode
	uint8_t m[5] = {0xFF, 0xFF};
	putLe16(m + 2, sAdvCounter);
	m[4] = sAdvMode;
	d.setManufacturerData(m, sizeof(m));
	return d;
}

static void applyAdvertising() {
	NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
	adv->stop();

	NimBLEAdvertisementData ad = advData(sAdvMode != 1);
	NimBLEAdvertisementData sr;
	if (sAdvMode == 1) sr.setName(sMode ? NAME_DFU : NAME_NORMAL);
	adv->setAdvertisementData(ad);
	adv->enableScanResponse(sAdvMode == 1);
	if (sAdvMode == 1) adv->setScanResponseData(sr);
	adv->setConnectableMode(sAdvMode == 2 ? BLE_GAP_CONN_MODE_NON : BLE_GAP_CONN_MODE_UND);

	// Interval in units of 0.625 ms: 100 ms by default
	uint32_t units = 160;
	if (sAdvMode == 3) {
		units = sAdvParam * 8 / 5;
		if (units < 32) units = 32;
		if (units > 16384) units = 16384;
	}
	adv->setMinInterval(units);
	adv->setMaxInterval(units);

	// Never advertises while connected: restarted by the loop after the disconnection
	if (sAdvMode != 4 && sConn == BLE_HS_CONN_HANDLE_NONE) adv->start();
}

// ---------- GATT table ----------

static void buildNormalTable() {
	NimBLEService *bench = sServer->createService(SVC_BENCH);
	sControl = bench->createCharacteristic(CHR_CONTROL, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
	sControl->setCallbacks(&sControlCallbacks);
	bench->createCharacteristic(CHR_SINK, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR)->setCallbacks(&sSinkCallbacks);
	sStream = bench->createCharacteristic(CHR_STREAM, NIMBLE_PROPERTY::NOTIFY);
	sStream->setCallbacks(&sSubscribeLogger);
	sIndicate = bench->createCharacteristic(CHR_INDICATE, NIMBLE_PROPERTY::INDICATE);
	sIndicate->setCallbacks(&sSubscribeLogger);
	sResult = bench->createCharacteristic(CHR_RESULT, NIMBLE_PROPERTY::READ);
	bench->createCharacteristic(CHR_ENCRYPTED, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC)->setValue((const uint8_t *) "SECURE-OK", 9);
	bench->createCharacteristic(CHR_NOTIFY, NIMBLE_PROPERTY::NOTIFY)->setCallbacks(&sSubscribeLogger);
	// Long value: read in several requests (read blob) and written with prepared writes while the MTU is 23
	sLong = bench->createCharacteristic(CHR_LONG, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE, LONG_LENGTH);
	static uint8_t pattern[LONG_LENGTH];
	for (uint16_t i = 0; i < LONG_LENGTH; i++) pattern[i] = (uint8_t) (i * 31 + (i >> 8));
	sLong->setValue(pattern, LONG_LENGTH);
	bench->start();

	NimBLEService *a = sServer->createService(SVC_DUP_A);
	a->createCharacteristic(CHR_DUP, NIMBLE_PROPERTY::READ)->setValue((const uint8_t *) "A", 1);
	a->start();
	NimBLEService *b = sServer->createService(SVC_DUP_B);
	b->createCharacteristic(CHR_DUP, NIMBLE_PROPERTY::READ)->setValue((const uint8_t *) "B", 1);
	b->start();

	NimBLEService *ti = sServer->createService(SVC_TI_OAD);
	sTiIdentify = ti->createCharacteristic(CHR_TI_IDENTIFY, NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
	sTiIdentify->setCallbacks(&sTiIdentifyCallbacks);
	sTiBlock = ti->createCharacteristic(CHR_TI_BLOCK, NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
	sTiBlock->setCallbacks(&sTiBlockCallbacks);
	sTiControl = ti->createCharacteristic(CHR_TI_CONTROL, NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
	sTiControl->setCallbacks(&sTiControlCallbacks);
	ti->start();
}

static void buildSlabsOta() {
	NimBLEService *ota = sServer->createService(SVC_SLABS_OTA);
	ota->createCharacteristic(CHR_SLABS_CONTROL, NIMBLE_PROPERTY::WRITE)->setCallbacks(&sSlabsControlCallbacks);
	ota->createCharacteristic(CHR_SLABS_DATA, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR)->setCallbacks(&sSinkCallbacks);
	ota->start();
}

static void buildDfuTable() {
	NimBLEService *info = sServer->createService(SVC_DFU_INFO);
	sResult = info->createCharacteristic(CHR_DFU_RESULT, NIMBLE_PROPERTY::READ);
	info->start();
}

// ---------- Pairing ----------

/**
 * For the next pairings: bonding and LE Secure Connections always, MITM protection for the passkey and the numeric
 * comparison
 */
static void applySecurity(uint8_t mode) {
	sSecurity = mode > 2 ? 0 : mode;
	switch (sSecurity) {
		case 1:
			NimBLEDevice::setSecurityAuth(true, true, true);
			NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
			NimBLEDevice::setSecurityPasskey(PASSKEY);
			break;
		case 2:
			NimBLEDevice::setSecurityAuth(true, true, true);
			NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_YESNO);
			break;
		default:
			NimBLEDevice::setSecurityAuth(true, false, true);
			NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
	}
	Serial.printf("pairing mode %u (%s)\n", sSecurity, sSecurity == 1 ? "passkey 123456" : sSecurity == 2 ? "numeric comparison" : "just works");
}

// ---------- Commands (loop task) ----------

static void reboot(uint8_t mode) {
	sBootMagic = BOOT_MAGIC;
	sBootMode = mode;
	Serial.printf("reboot mode=%u\n", mode);
	// The phone sees the end of the link at once, not at its supervision timeout (up to 20 s)
	uint16_t conn = sConn;
	if (conn != BLE_HS_CONN_HANDLE_NONE) {
		sServer->disconnect(conn);
		delay(300);
	}
	delay(200);
	ESP.restart();
}

static void stream(uint32_t count, uint16_t size, uint16_t intervalMs) {
	static uint8_t buf[512];
	uint16_t max = peerMtu() - 3;
	if (size > max) size = max;
	if (size > sizeof(buf)) size = sizeof(buf);
	if (size < 4) size = 4;

	uint32_t sent = 0, retries = 0;
	uint16_t handle = sStream->getHandle();
	for (uint32_t seq = 0; seq < count; seq++) {
		putLe32(buf, seq);
		for (uint16_t i = 4; i < size; i++) buf[i] = (uint8_t) (seq + i);

		// Back pressure: the stack has no buffer left, wait for the radio to send the previous ones
		bool ok = false;
		while (sConn != BLE_HS_CONN_HANDLE_NONE) {
			os_mbuf *om = ble_hs_mbuf_from_flat(buf, size);
			if (om != nullptr && ble_gattc_notify_custom(sConn, handle, om) == 0) {
				ok = true;
				break;
			}
			retries++;
			vTaskDelay(1);
		}
		if (!ok) break;
		sent++;
		if (intervalMs) vTaskDelay(pdMS_TO_TICKS(intervalMs));
	}

	uint8_t p[8];
	putLe32(p, sent);
	putLe32(p + 4, retries);
	answer(CMD_STREAM, sent == count ? 0 : 1, p, sizeof(p));
}

static void otaEnd(uint8_t protocol) {
	// No flow control mode: let the buffer drain before computing the result
	uint32_t until = millis() + 3000;
	while (sSinkQueue && uxQueueMessagesWaiting(sSinkQueue) > 0 && millis() < until) vTaskDelay(1);
	vTaskDelay(pdMS_TO_TICKS(10));

	sOtaProtocol = protocol;
	sOtaState = OTA_DONE;
	otaPublish();
	Serial.printf("ota end protocol=%u bytes=%u crc=%08x dropped=%u ms=%u\n", protocol, sBytes, sCrc, sDropped, millis() - sOtaStartMs);

	// The AppLoader reboots on the application at the end of the upload, after the phone read the result
	if (sMode == 1 && protocol == PROTO_SLABS) sRebootAt = millis() + DFU_END_REBOOT_DELAY_MS;
}

static void handleCommand(const Command &c) {
	if (c.len == 0) return;
	const uint8_t *d = c.data;
	uint8_t cmd = d[0];

	switch (cmd) {
		case CMD_RESET_STATS:
			sinkReset();
			answer(cmd, 0);
			break;

		case CMD_GET_STATS: {
			uint8_t p[28];
			putLe32(p, sBytes);
			putLe32(p + 4, sWrites);
			putLe32(p + 8, sDropped);
			putLe32(p + 12, sCrc);
			putLe32(p + 16, sLastMs - sFirstMs);
			putLe32(p + 20, sMaxGapMs);
			putLe32(p + 24, sGaps50);
			answer(cmd, 0, p, sizeof(p));
			break;
		}

		case CMD_STREAM:
			if (c.len < 9) answer(cmd, 2);
			else stream(getLe32(d + 1), getLe16(d + 5), getLe16(d + 7));
			break;

		case CMD_SINK_DELAY:
			if (c.len < 5) answer(cmd, 2);
			else {
				sSinkDelayUs = getLe32(d + 1);
				answer(cmd, 0);
			}
			break;

		case CMD_SINK_BUFFER:
			if (c.len < 8) answer(cmd, 2);
			else {
				sSinkMode = d[1];
				sSinkDepth = getLe16(d + 2);
				if (sSinkDepth == 0 || sSinkDepth > 64) sSinkDepth = 64;
				sDrainUs = getLe32(d + 4);
				answer(cmd, 0);
			}
			break;

		case CMD_ADV:
			if (c.len < 6) answer(cmd, 2);
			else {
				sAdvMode = d[1];
				sAdvParam = getLe32(d + 2);
				// Every special mode goes back to normal by itself: a non connectable bench could not be reset
				sAdvResumeAt = millis() + (sAdvMode == 4 ? sAdvParam : ADV_MODE_DURATION_MS);
				answer(cmd, 0);
				applyAdvertising();
			}
			break;

		case CMD_REBOOT:
			answer(cmd, 0);
			reboot(c.len > 1 ? d[1] : 0);
			break;

		case CMD_INDICATE:
			sIndicate->indicate((const uint8_t *) "IND-OK", 6);
			answer(cmd, 0);
			break;

		case CMD_DISCONNECT_IN: {
			uint32_t ms = c.len >= 5 ? getLe32(d + 1) : 0;
			answer(cmd, 0);
			vTaskDelay(pdMS_TO_TICKS(ms));
			uint16_t conn = sConn;
			if (conn != BLE_HS_CONN_HANDLE_NONE) sServer->disconnect(conn);
			break;
		}

		case CMD_GET_INFO: {
			uint8_t p[6] = {FW_VERSION, sMode};
			putLe16(p + 2, peerMtu());
			p[4] = sSecurity;
			p[5] = (uint8_t) NimBLEDevice::getNumBonds();
			answer(cmd, 0, p, sizeof(p));
			break;
		}

		case CMD_SECURITY:
			if (c.len < 2) answer(cmd, 2);
			else {
				applySecurity(d[1]);
				answer(cmd, 0);
			}
			break;

		case CMD_DELETE_BONDS: {
			// The bench forgets the phones once disconnected: a bonded phone then has keys the bench no longer knows (a lost
			// bond for it)
			uint8_t n = (uint8_t) NimBLEDevice::getNumBonds();
			sDeleteBonds = true;
			answer(cmd, 0, &n, 1);
			break;
		}

		case CMD_CONN_PARAMS: {
			// The bench asks the phone for other connection parameters (interval in 1.25 ms, timeout in 10 ms)
			uint16_t conn = sConn;
			if (c.len < 9 || conn == BLE_HS_CONN_HANDLE_NONE) {
				answer(cmd, 2);
				break;
			}
			answer(cmd, 0);
			sServer->updateConnParams(conn, getLe16(d + 1), getLe16(d + 3), getLe16(d + 5), getLe16(d + 7));
			break;
		}

		case CMD_OTA_END:
			otaEnd(c.len > 1 ? d[1] : PROTO_NONE);
			break;

		default:
			answer(cmd, 1);
	}
}

void setup() {
	Serial.begin(115200);
	crcInit();

	sMode = (sBootMagic == BOOT_MAGIC && sBootMode == 1) ? 1 : 0;
	sBootMagic = 0;
	Serial.printf("KnBle BLE bench v%u (ESP32-C3), %s mode, %d NimBLE buffers\n", FW_VERSION, sMode ? "DFU" : "normal", MYNEWT_VAL_MSYS_1_BLOCK_COUNT);

	sCommands = xQueueCreate(16, sizeof(Command));
	sSinkQueue = xQueueCreate(64, sizeof(SinkItem));
	// Second core when there is one (the ESP32-C3 has a single core)
	xTaskCreatePinnedToCore(drainTask, "drain", 4096, nullptr, 1, nullptr, portNUM_PROCESSORS - 1);

	NimBLEDevice::init(sMode ? NAME_DFU : NAME_NORMAL);
	NimBLEDevice::setMTU(PREFERRED_MTU);
	NimBLEDevice::setPower(9);
	// Just works pairing by default, asked by the phone when it reads the encrypted characteristic
	applySecurity(0);

	sServer = NimBLEDevice::createServer();
	sServer->setCallbacks(&sServerCallbacks);
	sServer->advertiseOnDisconnect(false);
	if (sMode) buildDfuTable();
	else buildNormalTable();
	buildSlabsOta();
	otaPublish();

	applyAdvertising();
	sDisconnectedAt = millis();
}

void loop() {
	Command c;
	if (xQueueReceive(sCommands, &c, pdMS_TO_TICKS(50)) == pdTRUE) handleCommand(c);

	uint32_t now = millis();

	// Special advertising mode over: back to normal
	if (sAdvMode != 0 && (int32_t) (now - sAdvResumeAt) >= 0) {
		sAdvMode = 0;
		applyAdvertising();
	}

	if (sDeleteBonds && sConn == BLE_HS_CONN_HANDLE_NONE) {
		sDeleteBonds = false;
		int n = NimBLEDevice::getNumBonds();
		NimBLEDevice::deleteAllBonds();
		Serial.printf("%d bonds deleted\n", n);
	}

	// After a disconnection, in the current advertising mode (NimBLE would restart it in any mode)
	if (sRestartAdvertising) {
		sRestartAdvertising = false;
		applyAdvertising();
	}

	// Counter in the manufacturer data, updated while advertising
	if (now - sLastCounterMs >= 500) {
		sLastCounterMs = now;
		sAdvCounter++;
		NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
		if (adv->isAdvertising()) {
			NimBLEAdvertisementData ad = advData(sAdvMode != 1);
			adv->setAdvertisementData(ad);
		}
	}

	if (sRebootAt != 0 && (int32_t) (now - sRebootAt) >= 0) reboot(0);

	// A device left in DFU mode goes back to its application, like a bootloader timeout
	if (sMode == 1 && sConn == BLE_HS_CONN_HANDLE_NONE && now - sDisconnectedAt > DFU_IDLE_REBOOT_MS) reboot(0);
}
