package com.example.paw_pantau

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Trik 1: Paksa layar terus menyala (Anti-Sleep)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Trik 2: Dimatikan sementara agar UI terlihat
        /*
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 0.0f
        window.attributes = layoutParams
        */

        val roleSelectionLayout = findViewById<LinearLayout>(R.id.roleSelectionLayout)
        val btnCctv = findViewById<Button>(R.id.btnCctv)
        val btnMonitor = findViewById<Button>(R.id.btnMonitor)

        btnCctv.setOnClickListener {
            startCctvMode()
            roleSelectionLayout.visibility = View.GONE
        }

        btnMonitor.setOnClickListener {
            startMonitorMode()
            roleSelectionLayout.visibility = View.GONE
        }
    }

    private fun startCctvMode() {
        Toast.makeText(this, "Mode CCTV Aktif", Toast.LENGTH_SHORT).show()
        // Di sini nanti kita panggil fungsi untuk aktifkan CameraX dan WebRTC Sender
    }

    private fun startMonitorMode() {
        Toast.makeText(this, "Mode Monitor Aktif", Toast.LENGTH_SHORT).show()
        // Di sini nanti kita panggil fungsi untuk aktifkan WebRTC Receiver
    }
}
