KnBle Change Log
==========

Version 2.4.5 *(2023-06-12)*
----------------------------
- Various bug fixes and improvements

Version 2.4.4 *(2023-05-12)*
----------------------------
- Various bug fixes and improvements

Version 2.4.3 *(2023-05-12)*
----------------------------
- Various bug fixes and improvements

Version 2.4.2 *(2023-05-11)*
----------------------------
- Various bug fixes and improvements

Version 2.4.1 *(2023-05-02)*
----------------------------
- Write no response, correctly handle remote gatt busy and retry every 60ms, 80 times
- Various bug fixes and improvements

Version 2.4.0 *(2023-05-02)*
----------------------------
- Add enableNotify method
- Add disableNotify method
- Add getBluetoothGatt method
- DeviceOp: Use main thread only for connect / disconnect, use new thread for the rest
- Various bug fixes and improvements

Version 2.3.2 *(2023-04-25)*
----------------------------
- Add setPreferredPhy function (Android 8+)

Version 2.3.1 *(2023-03-08)*
----------------------------
- Enable/disable adapter is deprecated since Android 13
- Bump target SDK to 33
- Bump gradle plugin to 7.4.2
- Bump gradle to 7.5
- Bump androidx.annotation:annotation to 1.6.0
- Various bug fixes and improvements

Version 2.3.0 *(2022-09-09)*
----------------------------
- New scan filter: beaconUUID
- Beacon service UUID parsing (available in the ScanRecord)
- Removed post SDK 23 code chunks
- Bump Java to 11
- Bump target SDK to 32
- Bump min SDK to 23
- Bump gradle plugin to 7.1.3
- Bump gradle to 7.2
- Bump androidx.annotation:annotation to 1.4.0
- Various bug fixes and improvements

Version 2.2.7 *(2022-01-27)*
----------------------------
- Various bug fixes and improvements

Version 2.2.6 *(2022-01-27)*
----------------------------
- New scan filter: devicesMacsStartsWith

Version 2.2.5 *(2021-11-24)*
----------------------------
- Remove unbond by default on disconnect

Version 2.2.4 *(2021-11-23)*
----------------------------
- Ability to enable/disable Android 6 BLE scan filters

Version 2.2.3 *(2021-11-23)*
----------------------------
- New scan filters: deviceNameStartsWith and deviceNameEndsWith
- Various bug fixes and improvements

Version 2.2.2 *(2021-11-22)*
----------------------------
- Bump gradle plugin to 4.2.2
- Bump gradle to 6.7.1
- Various bug fixes and improvements

Version 2.2.1 *(2021-11-08)*
----------------------------
- Scanner: onUpdatedDevice event added

Version 2.2.0 *(2021-11-08)*
----------------------------
- Breaking change: getInstance() remplaced by gi()

Version 2.0.2 *(2020-11-20)*
----------------------------
- Add requestMtu and getMtu methods
- Add DEBUG flag
- Bump min SDK to 21
- Bump gradle plugin to 4.1.1
- Bump gradle to 6.5

Version 2.0.1 *(2020-06-18)*
----------------------------
- Disable data split by default on write operations
- Bump gradle plugin to 4.0.0
- Bump gradle to 6.1.1

Version 2.0.0 *(2020-02-16)*
----------------------------
- BleDevices are now updated when scanning (recreated before)
- Better instance management
- Better performances
- Bugs fix

Version 1.0.1 *(2020-02-14)*
----------------------------
 * Destroy previous instance before init a new one
 * Remove synchronized flag on few methods (unnecessary)
 * Unbound device after disconnect
 * Bump compile and target SDK to 29
 * Bugs fix

Version 1.0.0 *(2019-09-27)*
----------------------------
 * Initial release.
