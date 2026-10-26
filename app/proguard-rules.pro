# Sona uses no third-party libraries. The only reflection is on framework classes
# (BluetoothDevice.getBatteryLevel), which R8 never renames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
