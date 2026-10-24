// KnBle Bluetooth classic test bench (ESP32, BR/EDR only): the Serial Port Profile server driven by the sample app. It
// emulates a radio receiver (commands in, a continuous stream of frames out), measures the throughput and the latency
// of the link, closes it on demand (link loss), and covers the pairings of a classic device: open, passkey shown by the
// bench, numeric comparison, legacy PIN, and the bond forgotten by the bench. Protocol: testbench/README.md, "Bluetooth
// classic bench". Built on the SPP API of the IDF (callbacks with whole buffers): Arduino BluetoothSerial queues the
// received bytes one by one and would be the bottleneck.

#include <Arduino.h>
#include <esp_bt.h>
#include <esp_bt_main.h>
#include <esp_bt_device.h>
#include <esp_gap_bt_api.h>
#include <esp_spp_api.h>

#include <atomic>

static const char *NAME = "KnBleSpp";
// Pairing mode, kept across ESP.restart() (lost on power off: back to open)
// 0 open: no pairing needed, just works when the phone asks for one
// 1 forced, passkey: authentication required, a passkey shown on the serial port for the user to type on the phone
// 2 forced, numeric comparison: the phone shows a number to confirm, the bench accepts it
// 3 forced, legacy PIN 1234 (Secure Simple Pairing off): the phone asks the PIN, the app may give it itself
static const uint32_t PAIR_MAGIC = 0x4B4E5053;
RTC_NOINIT_ATTR static uint32_t sPairMagic;
RTC_NOINIT_ATTR static uint32_t sPairModeKept;
// PAIR_MAGIC: the mode just changed, the bonds of the previous one go at boot
RTC_NOINIT_ATTR static uint32_t sPairChanged;
static uint8_t sPairMode = 0;
static const uint8_t FW_VERSION = 2;
static const char *MODE_NAMES[] = {"open", "passkey", "numeric comparison", "legacy PIN 1234"};
// RFCOMM frames of the ESP32 SPP: the stack sends at most this per write
static const int CHUNK = 990;
// One write at a time, its buffer kept until its ESP_SPP_WRITE_EVT: the stack may read it after esp_spp_write
// returns, and it refuses (status FAILURE) a write that comes while the link is congested
static uint8_t sSlot[CHUNK];
static SemaphoreHandle_t sWriteDone;
static volatile int sWriteStatus = ESP_SPP_SUCCESS;

static volatile uint32_t sHandle = 0;
static volatile bool sCongested = false;
static std::atomic<uint32_t> sTxBytes{0};
static volatile uint32_t sRxBytes = 0, sCongestions = 0, sWriteFailures = 0;

// Answers of the commands, written by the output task (the only writer of the connection)
struct Answer {
	uint8_t len;
	char text[63];
};
static QueueHandle_t sAnswers;

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

// Byte i of a STREAM, checked by the phone at its position
static inline uint8_t pattern(uint32_t i) {
	return (uint8_t) (i * 31 + (i >> 8));
}

// ---------- Output (output task only, except answer()) ----------

/**
 * Written once the stack accepted it: a write refused because of the congestion goes again once it ended, in order
 */
static bool sendRaw(const uint8_t *d, int n) {
	if (n > CHUNK) return false;
	memcpy(sSlot, d, n);
	for (;;) {
		while (sHandle != 0 && sCongested) vTaskDelay(1);
		uint32_t h = sHandle;
		if (h == 0) return false;
		xSemaphoreTake(sWriteDone, 0);
		if (esp_spp_write(h, n, sSlot) != ESP_OK) {
			vTaskDelay(1);
			continue;
		}
		// No answer: the connection ended
		if (xSemaphoreTake(sWriteDone, pdMS_TO_TICKS(3000)) != pdTRUE) return false;
		if (sWriteStatus == ESP_SPP_SUCCESS) {
			sTxBytes += n;
			return true;
		}
		sWriteFailures = sWriteFailures + 1;
	}
}

/**
 * Any task: queued for the output task
 */
static void answer(const char *fmt, ...) {
	Answer a;
	va_list args;
	va_start(args, fmt);
	int n = vsnprintf(a.text, sizeof(a.text) - 1, fmt, args);
	va_end(args);
	if (n < 0) return;
	if (n > (int) sizeof(a.text) - 2) n = sizeof(a.text) - 2;
	a.text[n++] = '\n';
	a.len = n;
	xQueueSend(sAnswers, &a, 0);
}

static void sendAnswers() {
	Answer a;
	while (xQueueReceive(sAnswers, &a, 0) == pdTRUE) sendRaw((const uint8_t *) a.text, a.len);
}

// ---------- Jobs of the output task ----------

enum : uint8_t { JOB_NONE = 0, JOB_STREAM = 1, JOB_RATE = 2 };
static volatile uint8_t sJob = JOB_NONE;
static volatile uint32_t sStreamTotal = 0;
static volatile uint32_t sRateBytesPerS = 0, sRateFrame = 0;
static volatile bool sStopRate = false;

static void runStream(uint32_t total) {
	static uint8_t buf[CHUNK];
	uint32_t start = millis(), sent = 0;
	while (sent < total && sHandle != 0) {
		int n = total - sent < (uint32_t) CHUNK ? total - sent : CHUNK;
		for (int i = 0; i < n; i++) buf[i] = pattern(sent + i);
		if (!sendRaw(buf, n)) {
			vTaskDelay(1);
			continue;
		}
		sent += n;
	}
	answer("END %u %u %u", total, millis() - start, sWriteFailures);
}

/**
 * The receiver: "$KNB,<seq>,<payload>*<xor>\r\n" frames at a steady rate until STOP, the answers in between
 */
static void runRate(uint32_t bytesPerS, uint32_t frameSize) {
	static char frame[600];
	if (frameSize < 24) frameSize = 24;
	if (frameSize > sizeof(frame)) frameSize = sizeof(frame);
	uint32_t framesPerS = bytesPerS / frameSize;
	if (framesPerS == 0) framesPerS = 1;
	uint32_t seq = 0, start = millis();
	sStopRate = false;
	while (!sStopRate && sHandle != 0) {
		sendAnswers();
		// Frame seq is due at start + seq / framesPerS: no drift, a late frame goes at once
		uint32_t due = start + (uint32_t) ((uint64_t) seq * 1000 / framesPerS);
		int32_t wait = (int32_t) (due - millis());
		if (wait > 0) {
			vTaskDelay(1);
			continue;
		}
		int n = snprintf(frame, sizeof(frame), "$KNB,%u,", seq);
		int payload = (int) frameSize - n - 5;
		for (int i = 0; i < payload; i++) frame[n++] = 'A' + (seq + i) % 26;
		uint8_t x = 0;
		for (int i = 1; i < n; i++) x ^= (uint8_t) frame[i];
		n += snprintf(frame + n, sizeof(frame) - n, "*%02X\r\n", x);
		if (sendRaw((const uint8_t *) frame, n)) seq++;
	}
	if (sHandle != 0) answer("STOP %u %u", seq, millis() - start);
}

static void outputTask(void *) {
	for (;;) {
		Answer a;
		if (xQueueReceive(sAnswers, &a, pdMS_TO_TICKS(1)) == pdTRUE) {
			sendRaw((const uint8_t *) a.text, a.len);
			continue;
		}
		uint8_t job = sJob;
		if (job == JOB_NONE || sHandle == 0) continue;
		if (job == JOB_STREAM) runStream(sStreamTotal);
		else if (job == JOB_RATE) runRate(sRateBytesPerS, sRateFrame);
		sJob = JOB_NONE;
	}
}

// ---------- Input: commands, or the bytes of a SINK ----------

static char sLine[64];
static int sLineLen = 0;
static uint32_t sSinkLeft = 0, sSinkTotal = 0, sSinkCrc = 0, sSinkFirstMs = 0;
static volatile uint32_t sDisconnectAt = 0;
static volatile uint32_t sRebootAt = 0;
// UNBOND: the bonds go once the phone disconnected (a device reset between two connections)
static volatile bool sUnbond = false;

static int removeBonds() {
	int n = esp_bt_gap_get_bond_device_num();
	if (n > 0) {
		esp_bd_addr_t *list = (esp_bd_addr_t *) malloc(sizeof(esp_bd_addr_t) * n);
		if (list && esp_bt_gap_get_bond_device_list(&n, list) == ESP_OK) {
			for (int i = 0; i < n; i++) esp_bt_gap_remove_bond_device(list[i]);
		}
		free(list);
	}
	if (n < 0) n = 0;
	Serial.printf("%d bonds removed\n", n);
	return n;
}

static void handleLine(char *line) {
	char cmd[12] = {0};
	unsigned long a = 0, b = 0;
	int args = sscanf(line, "%11s %lu %lu", cmd, &a, &b);
	if (args < 1) return;

	if (!strcmp(cmd, "PING")) {
		answer("PONG %lu", a);
	} else if (!strcmp(cmd, "INFO")) {
		answer("INFO knble-classic v%u pair=%u", FW_VERSION, sPairMode);
	} else if (!strcmp(cmd, "STREAM") && args >= 2 && sJob == JOB_NONE) {
		sStreamTotal = a;
		sJob = JOB_STREAM;
	} else if (!strcmp(cmd, "SINK") && args >= 2) {
		sSinkTotal = sSinkLeft = a;
		sSinkCrc = 0;
		sSinkFirstMs = 0;
		answer("SINK READY");
	} else if (!strcmp(cmd, "RATE") && args >= 3 && sJob == JOB_NONE) {
		sRateBytesPerS = a;
		sRateFrame = b;
		sJob = JOB_RATE;
	} else if (!strcmp(cmd, "STOP")) {
		sStopRate = true;
	} else if (!strcmp(cmd, "DISC")) {
		sDisconnectAt = millis() + a;
		if (sDisconnectAt == 0) sDisconnectAt = 1;
		answer("DISC %lu", a);
	} else if (!strcmp(cmd, "PAIR") && args >= 2) {
		// The bonds go (a new pairing each time), then a reboot in the new mode
		sPairMagic = PAIR_MAGIC;
		sPairModeKept = a <= 3 ? a : 0;
		sPairChanged = PAIR_MAGIC;
		answer("PAIR %lu", a);
		sRebootAt = millis() + 300;
	} else if (!strcmp(cmd, "UNBOND")) {
		// The bench forgets the phones once disconnected: a bonded phone keeps keys the bench no longer knows (a lost bond
		// for it)
		sUnbond = true;
		answer("UNBOND %d", esp_bt_gap_get_bond_device_num());
	} else if (!strcmp(cmd, "STATS")) {
		answer("STATS rx=%u tx=%u cong=%u failures=%u", sRxBytes, sTxBytes.load(), sCongestions, sWriteFailures);
	} else {
		answer("ERR %s", cmd);
	}
}

static void onReceive(const uint8_t *d, int n) {
	sRxBytes = sRxBytes + n;
	while (n > 0) {
		if (sSinkLeft > 0) {
			if (sSinkFirstMs == 0) sSinkFirstMs = millis();
			int take = n < (int) sSinkLeft ? n : sSinkLeft;
			sSinkCrc = crcUpdate(sSinkCrc, d, take);
			sSinkLeft -= take;
			d += take;
			n -= take;
			if (sSinkLeft == 0) answer("SINK %u %08X %u", sSinkTotal, sSinkCrc, millis() - sSinkFirstMs);
			continue;
		}
		char c = (char) *d++;
		n--;
		if (c == '\r') continue;
		if (c == '\n') {
			sLine[sLineLen] = 0;
			if (sLineLen > 0) handleLine(sLine);
			sLineLen = 0;
		} else if (sLineLen < (int) sizeof(sLine) - 1) {
			sLine[sLineLen++] = c;
		}
	}
}

// ---------- Bluetooth ----------

static void sppCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
	switch (event) {
		case ESP_SPP_INIT_EVT:
			// Open: the phone may connect with or without pairing (secure or insecure RFCOMM socket). Forced:
			// authenticated and encrypted only
			esp_spp_start_srv(sPairMode ? (ESP_SPP_SEC_AUTHENTICATE | ESP_SPP_SEC_ENCRYPT) : ESP_SPP_SEC_NONE, ESP_SPP_ROLE_SLAVE, 0, NAME);
			break;
		case ESP_SPP_START_EVT:
			esp_bt_dev_set_device_name(NAME);
			esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);
			Serial.printf("SPP server on channel %u\n", param->start.scn);
			break;
		case ESP_SPP_SRV_OPEN_EVT:
			xQueueReset(sAnswers);
			sCongested = false;
			sSinkLeft = 0;
			sLineLen = 0;
			sHandle = param->srv_open.handle;
			Serial.println("connected");
			break;
		case ESP_SPP_CLOSE_EVT:
			sHandle = 0;
			sStopRate = true;
			sSinkLeft = 0;
			sDisconnectAt = 0;
			Serial.printf("disconnected rx=%u tx=%u\n", sRxBytes, sTxBytes.load());
			// Discoverable again
			esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);
			break;
		case ESP_SPP_DATA_IND_EVT:
			onReceive(param->data_ind.data, param->data_ind.len);
			break;
		case ESP_SPP_CONG_EVT:
			if (param->cong.cong) sCongestions = sCongestions + 1;
			sCongested = param->cong.cong;
			break;
		case ESP_SPP_WRITE_EVT:
			sCongested = param->write.cong;
			sWriteStatus = param->write.status;
			xSemaphoreGive(sWriteDone);
			break;
		default:
			break;
	}
}

static void gapCallback(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param) {
	switch (event) {
		case ESP_BT_GAP_AUTH_CMPL_EVT:
			Serial.printf("pairing %s\n", param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS ? "done" : "failed");
			break;
		case ESP_BT_GAP_CFM_REQ_EVT:
			// Just works, or numeric comparison: accepted by the bench, the user confirms on the phone
			Serial.printf("numeric comparison %06u: accepted\n", param->cfm_req.num_val);
			esp_bt_gap_ssp_confirm_reply(param->cfm_req.bda, true);
			break;
		case ESP_BT_GAP_KEY_NOTIF_EVT:
			// Read by the test harness, typed in the dialog of the phone
			Serial.printf("passkey %06u\n", param->key_notif.passkey);
			break;
		case ESP_BT_GAP_PIN_REQ_EVT: {
			// Legacy pairing (mode 3, or an old phone): PIN 1234
			Serial.println("legacy PIN asked: 1234");
			esp_bt_pin_code_t pin = {'1', '2', '3', '4'};
			esp_bt_gap_pin_reply(param->pin_req.bda, true, 4, pin);
			break;
		}
		default:
			break;
	}
}

// The Arduino core frees the memory of the Bluetooth controller at boot unless this says it is used
extern "C" bool btInUse() {
	return true;
}

void setup() {
	Serial.begin(115200);
	crcInit();
	sPairMode = sPairMagic == PAIR_MAGIC && sPairModeKept <= 3 ? sPairModeKept : 0;
	Serial.printf("KnBle classic bench v%u, pairing %s\n", FW_VERSION, MODE_NAMES[sPairMode]);

	// No BLE in this firmware: its controller memory goes back to the heap
	esp_bt_controller_mem_release(ESP_BT_MODE_BLE);
	esp_bt_controller_config_t cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
	// The mode enabled must be the one of the configuration (dual mode by default in the Arduino build)
	cfg.mode = ESP_BT_MODE_CLASSIC_BT;
	esp_err_t err = esp_bt_controller_init(&cfg);
	if (err == ESP_OK) err = esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT);
	if (err != ESP_OK) {
		Serial.printf("controller init failed: %s\n", esp_err_to_name(err));
		return;
	}
	// Legacy PIN mode: Secure Simple Pairing off, the phone asks the PIN
	esp_bluedroid_config_t bluedroid = BT_BLUEDROID_INIT_CONFIG_DEFAULT();
	bluedroid.ssp_en = sPairMode != 3;
	if (esp_bluedroid_init_with_cfg(&bluedroid) != ESP_OK || esp_bluedroid_enable() != ESP_OK) {
		Serial.println("bluedroid init failed");
		return;
	}
	if (sPairChanged == PAIR_MAGIC) {
		sPairChanged = 0;
		// Asked by PAIR: the bonds of the previous mode go
		removeBonds();
	}
	esp_bt_gap_register_callback(gapCallback);
	esp_spp_register_callback(sppCallback);
	esp_spp_init(ESP_SPP_MODE_CB);

	// Secure Simple Pairing: no input nor output (just works), display only (the phone asks the user for the passkey
	// shown here), display and yes/no (numeric comparison). Legacy pairing: the PIN asked through ESP_BT_GAP_PIN_REQ_EVT
	esp_bt_io_cap_t iocap = sPairMode == 1 ? ESP_BT_IO_CAP_OUT : sPairMode == 2 ? ESP_BT_IO_CAP_IO : ESP_BT_IO_CAP_NONE;
	esp_bt_gap_set_security_param(ESP_BT_SP_IOCAP_MODE, &iocap, sizeof(uint8_t));
	esp_bt_pin_code_t pin;
	esp_bt_gap_set_pin(ESP_BT_PIN_TYPE_VARIABLE, 0, pin);

	sAnswers = xQueueCreate(16, sizeof(Answer));
	sWriteDone = xSemaphoreCreateBinary();
	xTaskCreatePinnedToCore(outputTask, "output", 4096, nullptr, 2, nullptr, 1);
}

void loop() {
	if (sUnbond && sHandle == 0) {
		sUnbond = false;
		removeBonds();
	}
	uint32_t reboot = sRebootAt;
	if (reboot != 0 && (int32_t) (millis() - reboot) >= 0) ESP.restart();
	uint32_t at = sDisconnectAt;
	if (at != 0 && (int32_t) (millis() - at) >= 0) {
		sDisconnectAt = 0;
		uint32_t h = sHandle;
		if (h != 0) esp_spp_disconnect(h);
	}
	delay(5);
}
