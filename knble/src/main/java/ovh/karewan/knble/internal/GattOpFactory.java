package ovh.karewan.knble.internal;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.util.List;
import java.util.UUID;

import ovh.karewan.knble.OtaOptions;
import ovh.karewan.knble.interfaces.BleFastTransferCallback;
import ovh.karewan.knble.interfaces.BleMtuChangedCallback;
import ovh.karewan.knble.interfaces.BleOtaCallback;
import ovh.karewan.knble.interfaces.BleNotifyCallback;
import ovh.karewan.knble.interfaces.BlePhyValueCallback;
import ovh.karewan.knble.interfaces.BleReadCallback;
import ovh.karewan.knble.interfaces.BleReadRssiCallback;
import ovh.karewan.knble.interfaces.BleRequestCallback;
import ovh.karewan.knble.interfaces.BleSplittedWriteCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;

/**
 * The GATT operations for the KnBle facade
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class GattOpFactory {
	private GattOpFactory() {}

	@NonNull
	public static GattOp.CharTarget target(@NonNull UUID service, @NonNull UUID characteristic) {
		return new GattOp.CharTarget(service, characteristic);
	}

	@NonNull
	public static GattOp.CharTarget target(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic) {
		return new GattOp.CharTarget(service, characteristic);
	}

	@NonNull
	public static GattOps.DescTarget target(@NonNull UUID service, @NonNull UUID characteristic, @NonNull UUID descriptor) {
		return new GattOps.DescTarget(new GattOp.CharTarget(service, characteristic), descriptor);
	}

	@NonNull
	public static GattOps.DescTarget target(@NonNull BluetoothGattService service, @NonNull BluetoothGattCharacteristic characteristic, @NonNull BluetoothGattDescriptor descriptor) {
		return new GattOps.DescTarget(new GattOp.CharTarget(service, characteristic), descriptor);
	}

	@NonNull
	public static GattOp read(@NonNull BleEngine e, @NonNull GattOp.CharTarget target, @NonNull BleReadCallback callback) {
		return new GattOps.Read(e.dispatcher(), target, callback);
	}

	@NonNull
	public static GattOp write(@NonNull BleEngine e, @NonNull GattOp.CharTarget target, @NonNull byte[] data, boolean noResponse, @NonNull BleWriteCallback callback) {
		return new GattOps.Write(e.dispatcher(), target, data, noResponse, callback);
	}

	@NonNull
	public static GattOp splittedWrite(@NonNull BleEngine e, @NonNull GattOp.CharTarget target, @NonNull byte[] data, int splitSize, boolean noResponse, long interval,
									   @NonNull BleSplittedWriteCallback callback) {
		return new GattOps.SplittedWrite(e.dispatcher(), target, data, splitSize, noResponse, interval, callback);
	}

	@NonNull
	public static GattOp enableNotify(@NonNull BleEngine e, @NonNull GattOp.CharTarget target, @NonNull BleNotifyCallback callback) {
		return new GattOps.EnableNotify(e.dispatcher(), target, callback);
	}

	@NonNull
	public static GattOp disableNotify(@NonNull BleEngine e, @NonNull GattOp.CharTarget target) {
		return new GattOps.DisableNotify(e.dispatcher(), target);
	}

	@NonNull
	public static GattOp readDesc(@NonNull BleEngine e, @NonNull GattOps.DescTarget target, @NonNull BleReadCallback callback) {
		return new GattOps.ReadDesc(e.dispatcher(), target, callback);
	}

	@NonNull
	public static GattOp writeDesc(@NonNull BleEngine e, @NonNull GattOps.DescTarget target, @NonNull byte[] data, @NonNull BleWriteCallback callback) {
		return new GattOps.WriteDesc(e.dispatcher(), target, data, callback);
	}

	@NonNull
	public static GattOp requestMtu(@NonNull BleEngine e, int mtu, @Nullable BleMtuChangedCallback callback) {
		return new GattOps.RequestMtu(e.dispatcher(), mtu, callback);
	}

	@NonNull
	public static GattOp setPhy(@NonNull BleEngine e, int txPhy, int rxPhy, int options, @Nullable BlePhyValueCallback callback) {
		return new GattOps.SetPhy(e.dispatcher(), txPhy, rxPhy, options, callback);
	}

	@NonNull
	public static GattOp readPhy(@NonNull BleEngine e, @NonNull BlePhyValueCallback callback) {
		return new GattOps.ReadPhy(e.dispatcher(), callback);
	}

	@NonNull
	public static GattOp readRssi(@NonNull BleEngine e, @NonNull BleReadRssiCallback callback) {
		return new GattOps.ReadRssi(e.dispatcher(), callback);
	}

	@NonNull
	public static GattOp fastTransfer(@NonNull BleEngine e, @NonNull BleFastTransferCallback callback) {
		return new GattOps.FastTransfer(e.dispatcher(), callback);
	}

	@NonNull
	public static GattOp silabsOta(@NonNull BleEngine e, @NonNull byte[] image, @NonNull OtaOptions options, @NonNull BleOtaCallback callback) {
		return new GattOps.SilabsOta(e.dispatcher(), image, options, callback);
	}

	@NonNull
	public static GattOp request(@NonNull BleEngine e, @NonNull GattOp.CharTarget write, @NonNull GattOp.CharTarget notify, @NonNull byte[] data,
								 @NonNull BleRequestCallback callback) {
		return new GattOps.Request(e.dispatcher(), write, notify, data, callback);
	}

	@NonNull
	public static GattOp reliableWrite(@NonNull BleEngine e, @NonNull List<GattOp.CharTarget> targets, @NonNull List<byte[]> values,
									   @NonNull BleWriteCallback callback) {
		return new GattOps.ReliableWrite(e.dispatcher(), targets, values, callback);
	}

	@NonNull
	public static GattOp tiOad(@NonNull BleEngine e, @NonNull byte[] image, @NonNull BleOtaCallback callback) {
		return new GattOps.TiOad(e.dispatcher(), image, callback);
	}
}
