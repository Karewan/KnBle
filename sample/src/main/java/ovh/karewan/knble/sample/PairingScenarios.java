package ovh.karewan.knble.sample;

import android.bluetooth.BluetoothDevice;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.BlePairingRequest;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleBondCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * Pairings as a real app runs them: the stack pairs for an encrypted characteristic, the app pairs first (createBond,
 * with a code known in advance or asked to the user), the bond removed. The pairing requests go to PairingUi (the dialog
 * of the app, or a hint while the dialog of the system asks the user).
 */
final class PairingScenarios extends Scenarios {
	private static final long PAIRING_TIMEOUT = 70000;

	PairingScenarios(@NonNull Report report, @NonNull IntConsumer progress) {
		super(report, progress);
	}

	private static boolean await(@NonNull CountDownLatch latch, long ms) {
		try {
			return latch.await(ms, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Bond removed on the phone side (refused to the apps targeting API 37 on Android 17, unless associated)
	 */
	private void removeBond(@NonNull BleDevice d) {
		if (KnBle.getBondState(d) == BluetoothDevice.BOND_NONE) return;
		KnBle.removeBond(d);
		long until = SystemClock.elapsedRealtime() + 5000;
		while (KnBle.getBondState(d) != BluetoothDevice.BOND_NONE && SystemClock.elapsedRealtime() < until) sleep(100);
		r.log("Bond of %s removed: %b", d.getMac(), KnBle.getBondState(d) == BluetoothDevice.BOND_NONE);
	}

	/**
	 * createBond, the code given in advance or null (asked to PairingUi)
	 * @return BleError of the failure, 0 when bonded, 1 on timeout of the scenario
	 */
	private int createBond(@NonNull String name, @NonNull BleDevice d, @Nullable String pin) {
		CountDownLatch done = new CountDownLatch(1);
		AtomicInteger error = new AtomicInteger(1);
		AtomicInteger requests = new AtomicInteger();
		long start = SystemClock.elapsedRealtimeNanos();
		KnBle.createBond(d, pin, new BleBondCallback() {
			@Override
			public void onBonded() {
				error.set(0);
				done.countDown();
			}

			@Override
			public void onBondFailed(int e) {
				error.set(e);
				done.countDown();
			}

			@Override
			public boolean onPairingRequest(@NonNull BlePairingRequest request) {
				requests.incrementAndGet();
				return PairingUi.onPairingRequest(request);
			}
		});
		await(done, PAIRING_TIMEOUT);
		long ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000;
		r.result(name, "bonded=%b error=%s ms=%d pinInAdvance=%b appRequests=%d bondState=%d", error.get() == 0, error.get() == 1 ? "SCENARIO_TIMEOUT" : BleError.name(error.get()),
				ms, pin != null, requests.get(), KnBle.getBondState(d));
		return error.get();
	}

	// region BLE (the bench)

	/**
	 * The encrypted characteristic of the bench, not bonded: the stack pairs, KnBle reads again once bonded
	 */
	void bleEncryptedRead() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		r.log("Not bonded: the read of the encrypted characteristic makes the phone pair (answer on the phone, and on the Mac for its bench)");
		BenchLink.Outcome<byte[]> e = link.read(Bench.SVC_BENCH, Bench.CHR_ENCRYPTED, PAIRING_TIMEOUT);
		r.result("pairing_encrypted_read", "status=%s value=%s ms=%d bondState=%d", e.label(), Bench.text(e.value), e.ms, KnBle.getBondState(link.device));
	}

	/**
	 * The app pairs with the bench before using it (createBond while connected)
	 */
	void bleCreateBond() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		createBond("pairing_ble_create_bond", link.device, null);
	}

	/**
	 * The pairing mode of the bench (ESP32-C3), both sides forget the bond, connected again: the next encrypted read pairs
	 * in that mode
	 * @return false when the bench has no such mode (the phone bench)
	 */
	private boolean freshPairing(@NonNull BenchLink link, int mode) {
		BenchLink.Outcome<byte[]> s = link.command(Bench.command(Bench.CMD_SECURITY, (byte) mode), BenchLink.OP_TIMEOUT);
		if (!s.ok()) {
			r.log("This bench has no pairing mode %d (only the ESP32-C3 has)", mode);
			return false;
		}
		link.command(Bench.command(Bench.CMD_DELETE_BONDS), BenchLink.OP_TIMEOUT);
		link.disconnect(BenchLink.OP_TIMEOUT);
		removeBond(link.device);
		sleep(1000);
		return link.connect(BenchLink.CONNECT_TIMEOUT).ok();
	}

	/**
	 * Read of the encrypted characteristic that makes the phone pair, with what Android asked and who answered
	 */
	private void encryptedRead(@NonNull String name, @NonNull BenchLink link, @Nullable String autoPin) {
		String auto = PairingUi.sAutoPin;
		if (autoPin != null) PairingUi.sAutoPin = autoPin;
		PairingUi.sLast = null;
		PairingUi.sLastAnsweredByApp = false;
		BenchLink.Outcome<byte[]> e = link.read(Bench.SVC_BENCH, Bench.CHR_ENCRYPTED, PAIRING_TIMEOUT);
		PairingUi.sAutoPin = auto;
		BlePairingRequest q = PairingUi.sLast;
		r.result(name, "status=%s value=%s ms=%d variant=%d passkey=%b key=%d canAnswer=%b answeredByApp=%b bondState=%d", e.label(),
				Bench.text(e.value), e.ms, q == null ? -1 : q.getVariant(), q != null && q.isPasskey(), q == null ? -1 : q.getPairingKey(),
				q != null && q.canAnswer(), PairingUi.sLastAnsweredByApp, KnBle.getBondState(link.device));
	}

	/**
	 * The bench shows a passkey (123456), the app types it (BlePairingRequest.setPin): no dialog of the system. On
	 * Android 17+ with an app targeting API 37 the dialog of the system asks it (canAnswer false).
	 */
	void blePasskeyByApp() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		if (!freshPairing(link, Bench.SECURITY_PASSKEY)) {
			r.result("pairing_ble_passkey_app", "supported=false");
			return;
		}
		encryptedRead("pairing_ble_passkey_app", link, Bench.PASSKEY);
		link.command(Bench.command(Bench.CMD_SECURITY, (byte) Bench.SECURITY_JUST_WORKS), BenchLink.OP_TIMEOUT);
	}

	/**
	 * Numeric comparison: the dialog of the system asks the user to confirm the number (shown over the app), the app is
	 * told the number. Needs the user (confirm on the phone).
	 */
	void bleNumericComparison() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		if (!freshPairing(link, Bench.SECURITY_NUMERIC_COMPARISON)) {
			r.result("pairing_ble_numeric_comparison", "supported=false");
			return;
		}
		r.log("Confirm the number in the pairing dialog of the phone");
		encryptedRead("pairing_ble_numeric_comparison", link, null);
		link.command(Bench.command(Bench.CMD_SECURITY, (byte) Bench.SECURITY_JUST_WORKS), BenchLink.OP_TIMEOUT);
	}

	/**
	 * The bench forgets the phone (reset, bond removed on its side) while the phone keeps its keys: Android 16+ tells
	 * onBondLost, KnBle ends the connection with BOND_LOST without auto-reconnection; before, what the stack does. Bonded
	 * first with the passkey typed by the app (no user). A pairing asked again is refused: the scenario sees it.
	 */
	void bleLostBond() {
		BenchLink link = ensureConnected();
		if (link == null) return;
		// The bond of the previous scenario when there is one (Android 17 + API 37: the app cannot remove it to pair again)
		if (KnBle.getBondState(link.device) != BluetoothDevice.BOND_BONDED) {
			if (!freshPairing(link, Bench.SECURITY_PASSKEY)) {
				r.result("pairing_ble_lost_bond", "supported=false");
				return;
			}
			encryptedRead("pairing_ble_lost_bond_pairing", link, Bench.PASSKEY);
		}
		boolean bonded = KnBle.getBondState(link.device) == BluetoothDevice.BOND_BONDED;
		// Forgotten by the bench at the disconnection. Android keeps the link a few seconds after the last close (a quick
		// connection would reuse it, the bench would not see the disconnection)
		BenchLink.Outcome<byte[]> forgot = link.command(Bench.command(Bench.CMD_DELETE_BONDS), BenchLink.OP_TIMEOUT);
		link.disconnect(BenchLink.OP_TIMEOUT);
		sleep(6000);

		link.bondLostReason = -1;
		link.lastStatus = 0;
		String auto = PairingUi.sAutoPin;
		PairingUi.sAutoPin = "cancel";
		PairingUi.sLast = null;
		long start = SystemClock.elapsedRealtimeNanos();
		BenchLink.Outcome<Void> c = link.connect(BenchLink.CONNECT_TIMEOUT);
		BenchLink.Outcome<byte[]> e = c.ok() ? link.read(Bench.SVC_BENCH, Bench.CHR_ENCRYPTED, PAIRING_TIMEOUT) : null;
		sleep(3000);
		PairingUi.sAutoPin = auto;
		BlePairingRequest again = PairingUi.sLast;
		// Bonds the bench still has (GET_INFO, byte 5): 0 when it forgot the phone
		BenchLink.Outcome<byte[]> info = link.connected() ? link.command(Bench.command(Bench.CMD_GET_INFO), BenchLink.OP_TIMEOUT) : null;
		int benchBonds = info != null && info.ok() && info.value != null && info.value.length >= 8 ? info.value[7] : -1;
		r.result("pairing_ble_lost_bond", "bonded=%b benchForgot=%s benchBondsAfter=%d reconnect=%s read=%s value=%s pairingAskedAgain=%b repairing=%b bondLost=%d lastStatus=%s connected=%b bondState=%d ms=%d",
				bonded, forgot.label(), benchBonds, c.label(), e == null ? "-" : e.label(), e == null ? "-" : Bench.text(e.value), again != null,
				again != null && again.isRepairing(), link.bondLostReason, BleError.name(link.lastStatus), link.connected(), KnBle.getBondState(link.device),
				(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000);

		// Both sides forget, the bench back to just works
		link.disconnect(BenchLink.OP_TIMEOUT);
		removeBond(link.device);
		if (KnBle.getBondState(link.device) != BluetoothDevice.BOND_NONE) {
			r.log("The phone keeps its bond (Android 17 + API 37): forget %s in the Bluetooth settings of the phone", link.device.getMac());
		} else if (link.connect(BenchLink.CONNECT_TIMEOUT).ok()) {
			link.command(Bench.command(Bench.CMD_SECURITY, (byte) Bench.SECURITY_JUST_WORKS), BenchLink.OP_TIMEOUT);
			link.command(Bench.command(Bench.CMD_DELETE_BONDS), BenchLink.OP_TIMEOUT);
			link.disconnect(BenchLink.OP_TIMEOUT);
		}
	}

	/**
	 * The pairings without the user: the passkey typed by the app, the lost bond
	 */
	void bleRunAll() {
		blePasskeyByApp();
		bleLostBond();
	}

	void bleRemoveBond() {
		BenchLink link = findBench();
		if (link == null) return;
		removeBond(link.device);
		r.result("pairing_ble_remove_bond", "bondState=%d", KnBle.getBondState(link.device));
	}

	// endregion

	// region Bluetooth classic (ClassicScenarios.target)

	/**
	 * createBond of the classic device, its PIN given in advance when "--es classicpin" was given
	 */
	void classicCreateBond(@Nullable String pin) {
		BleDevice d = ClassicScenarios.target();
		if (d == null) {
			r.log("No classic device: --es classic <MAC>");
			return;
		}
		createBond("pairing_classic_create_bond", d, pin);
	}

	void classicRemoveBond() {
		BleDevice d = ClassicScenarios.target();
		if (d == null) return;
		removeBond(d);
		r.result("pairing_classic_remove_bond", "bondState=%d", KnBle.getBondState(d));
	}

	// endregion
}
