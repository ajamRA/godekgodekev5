package com.emas.gameserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class GameStreamService : Service() {
    companion object {
        private const val TAG = "GameStreamService"
        private const val CHANNEL_ID = "EmasGameStreamChannel"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "ACTION_START_STREAM"
        const val ACTION_STOP = "ACTION_STOP_STREAM"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        var isStreamingActive = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var udpInputSocket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

            if (resultCode != 0 && data != null) {
                startForeground(NOTIF_ID, createNotification("Menstrim ke Proton e.MAS 5..."))
                startStreamingServer(resultCode, data)
            }
        } else if (action == ACTION_STOP) {
            stopStreaming()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startStreamingServer(resultCode: Int, data: Intent) {
        if (isRunning.get()) return
        isRunning.set(true)
        isStreamingActive = true

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Thread to listen for IHU connection
        Thread({
            try {
                Log.i(TAG, "Membuka TCP Server di port ${ProtocolConstants.VIDEO_PORT}...")
                serverSocket = ServerSocket(ProtocolConstants.VIDEO_PORT)

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    Log.i(TAG, "IHU e.MAS 5 tersambung dari: ${socket.inetAddress.hostAddress}")
                    clientSocket = socket

                    val projection = mediaProjection ?: break
                    screenEncoder = ScreenEncoder(projection, socket.getOutputStream())
                    screenEncoder?.start()

                    // Start controller input receiver
                    startInputReceiver()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ralat Server: ${e.message}")
            }
        }, "StreamServerThread").start()
    }

    private fun startInputReceiver() {
        Thread({
            try {
                udpInputSocket = DatagramSocket(ProtocolConstants.INPUT_PORT)
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.i(TAG, "Mendengar input controller di UDP port ${ProtocolConstants.INPUT_PORT}...")
                while (isRunning.get()) {
                    udpInputSocket?.receive(packet)
                    // Process 8BitDo controller packet
                    processControllerPacket(buffer, packet.length)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ralat Input Receiver: ${e.message}")
            }
        }, "InputReceiverThread").start()
    }

    private fun processControllerPacket(data: ByteArray, length: Int) {
        if (length < 12) return
        // Parse controller packet: Magic (4B), Buttons (4B), LeftStick (4B)
        val magic = (data[0].toInt() and 0xFF shl 24) or
                    (data[1].toInt() and 0xFF shl 16) or
                    (data[2].toInt() and 0xFF shl 8) or
                    (data[3].toInt() and 0xFF)

        if (magic == ProtocolConstants.PACKET_MAGIC) {
            val buttons = (data[4].toInt() and 0xFF shl 24) or
                          (data[5].toInt() and 0xFF shl 16) or
                          (data[6].toInt() and 0xFF shl 8) or
                          (data[7].toInt() and 0xFF)
            // Log for debugging / pass to virtual input injection
            // Log.d(TAG, "Controller Buttons Bitmask: $buttons")
        }
    }

    private fun stopStreaming() {
        isRunning.set(false)
        isStreamingActive = false

        screenEncoder?.stop()
        screenEncoder = null

        try {
            clientSocket?.close()
            serverSocket?.close()
            udpInputSocket?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Ralat menutup soket: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "e.MAS Game Stream Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("e.MAS 5 Wireless Game Hub")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
