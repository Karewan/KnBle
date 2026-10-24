# Rules applied to the apps using KnBle (consumerProguardFiles)

# Callback of Android not in the public SDK (connection parameters, Android 8+): called by the framework, never by the
# code, R8 would remove it
-keepclassmembers class ovh.karewan.knble.internal.GattBridge {
	public void onConnectionUpdated(android.bluetooth.BluetoothGatt, int, int, int, int);
}
