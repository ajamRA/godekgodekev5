package com.emas.gameserver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BluetoothCarReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BluetoothCarReceiver"
        val KNOWN_CAR_IDENTIFIERS = listOf("e.MAS", "EMAS", "Proton", "Geely", "CAR_BT", "BT_CAR")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val deviceName = device?.name ?: "Unknown"
            Log.d(TAG, "Bluetooth Device Connected: $deviceName (${device?.address})")

            val isCar = KNOWN_CAR_IDENTIFIERS.any { deviceName.contains(it, ignoreCase = true) }
            if (isCar) {
                Log.i(TAG, "Proton e.MAS 5 detected! Triggering Game Hub Service...")
                
                // Launch MainActivity or bring server service up
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("AUTO_LAUNCHED_BY_CAR", true)
                    putExtra("CAR_NAME", deviceName)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
