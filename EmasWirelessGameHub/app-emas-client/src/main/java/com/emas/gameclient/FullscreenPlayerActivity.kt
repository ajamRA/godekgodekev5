package com.emas.gameclient

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import java.net.Socket

class FullscreenPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "FullscreenPlayerActivity"
        const val EXTRA_VIVO_IP = "EXTRA_VIVO_IP"
    }

    private lateinit var surfaceView: SurfaceView
    private var videoDecoder: VideoDecoder? = null
    private var inputSender: ControllerInputSender? = null
    private var clientSocket: Socket? = null
    private var vivoIp: String = "192.168.43.1" // Default hotspot gateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        setContentView(R.layout.activity_player)
        surfaceView = findViewById(R.id.surfaceView)
        surfaceView.holder.addCallback(this)

        vivoIp = intent.getStringExtra(EXTRA_VIVO_IP) ?: "192.168.43.1"
        inputSender = ControllerInputSender(vivoIp)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface bersedia. Menyambung ke vivo di $vivoIp:${ProtocolConstants.VIDEO_PORT}...")

        Thread {
            try {
                val socket = Socket(vivoIp, ProtocolConstants.VIDEO_PORT)
                clientSocket = socket

                videoDecoder = VideoDecoder(holder.surface, socket.getInputStream())
                videoDecoder?.start()
                Log.i(TAG, "Penstriman H.264 bermula ke skrin Proton e.MAS 5!")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menyambung ke vivo: ${e.message}")
            }
        }.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoDecoder?.stop()
        videoDecoder = null
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
    }

    // Capture 8BitDo controller button inputs from IHU USB port
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputSender?.handleKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Capture 8BitDo analog sticks and triggers
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputSender?.handleMotionEvent(event) == true) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        inputSender?.close()
        videoDecoder?.stop()
        super.onDestroy()
    }
}
