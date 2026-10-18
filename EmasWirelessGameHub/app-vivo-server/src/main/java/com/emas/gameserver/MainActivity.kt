package com.emas.gameserver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_PERMISSIONS = 1002
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var btnToggleStream: Button
    private lateinit var btnLaunchSwitch: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        btnToggleStream = findViewById(R.id.btnToggleStream)
        btnLaunchSwitch = findViewById(R.id.btnLaunchSwitch)

        checkPermissions()
        updateIpAddress()

        btnToggleStream.setOnClickListener {
            if (GameStreamService.isStreamingActive) {
                stopStreamService()
            } else {
                requestScreenCapture()
            }
        }

        btnLaunchSwitch.setOnClickListener {
            launchEmulatorPackage("org.sudachi.sudachi_emu")
        }

        findViewById<Button>(R.id.btnLaunchLemuroid).setOnClickListener {
            launchEmulatorPackage("com.swordfish.lemuroid")
        }

        if (intent.getBooleanExtra("AUTO_LAUNCHED_BY_CAR", false)) {
            val carName = intent.getStringExtra("CAR_NAME") ?: "e.MAS 5"
            tvStatus.text = "Tersambung ke $carName! Bersedia untuk strim..."
            // Auto request screen capture
            requestScreenCapture()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun updateIpAddress() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        tvIpAddress.text = "IP Wi-Fi vivo: $ip (Port: ${ProtocolConstants.VIDEO_PORT})"
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startStreamService(resultCode, data)
            } else {
                Toast.makeText(this, "Kebenaran rakaman skrin ditolak", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStreamService(resultCode: Int, data: Intent) {
        val intent = Intent(this, GameStreamService::class.java).apply {
            action = GameStreamService.ACTION_START
            putExtra(GameStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(GameStreamService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        btnToggleStream.text = "HENTIKAN PENSTRIMAN"
        tvStatus.text = "Status: Menstrim secara langsung ke Proton e.MAS 5..."
    }

    private fun stopStreamService() {
        val intent = Intent(this, GameStreamService::class.java).apply {
            action = GameStreamService.ACTION_STOP
        }
        startService(intent)
        btnToggleStream.text = "MULAKAN PENSTRIMAN"
        tvStatus.text = "Status: Terhenti (Menunggu sambungan)"
    }

    private fun launchEmulatorPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Aplikasi $packageName belum dipasang di vivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateIpAddress()
        if (GameStreamService.isStreamingActive) {
            btnToggleStream.text = "HENTIKAN PENSTRIMAN"
            tvStatus.text = "Status: Menstrim secara langsung ke Proton e.MAS 5..."
        }
    }
}
