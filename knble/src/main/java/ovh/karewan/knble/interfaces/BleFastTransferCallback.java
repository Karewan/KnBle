package ovh.karewan.knble.interfaces;

/**
 * Link parameters in use after KnBle.requestFastTransfer: always called, also when the device refused a part of them
 */
public interface BleFastTransferCallback {
	/**
	 * @param mtu MTU in use (packets of mtu - 3 bytes)
	 * @param txPhy BluetoothDevice.PHY_LE_1M or PHY_LE_2M
	 * @param rxPhy BluetoothDevice.PHY_LE_1M or PHY_LE_2M
	 */
	void onFastTransfer(int mtu, int txPhy, int rxPhy);
}
