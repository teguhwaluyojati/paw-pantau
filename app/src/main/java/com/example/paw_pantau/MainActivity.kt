package com.example.paw_pantau

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.view.Surface
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private var webRTCHelper: WebRTCHelper? = null
    private var eglBase: EglBase? = EglBase.create()
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var currentRoomId: String? = null // Simpan roomId aktif

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        android.util.Log.d("MainActivity", "onCreate started")

        viewFinder = findViewById(R.id.viewFinder)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        cameraExecutor = Executors.newSingleThreadExecutor()

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

        // Tambahkan Handler untuk Tombol Back / Swipe Back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val roleLayout = findViewById<View>(R.id.roleSelectionLayout)
                // Jika sedang tidak di menu utama (salah satu mode aktif)
                if (roleLayout.visibility == View.GONE) {
                    showExitConfirmation()
                } else {
                    // Jika di menu utama, biarkan perilaku back standar (keluar app)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showExitConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Konfirmasi Keluar")
            .setMessage("Apakah Anda yakin ingin menghentikan sesi ini dan kembali ke menu utama?")
            .setPositiveButton("Ya") { _, _ ->
                resetToDashboard()
            }
            .setNegativeButton("Tidak", null)
            .show()
    }

    private fun resetToDashboard() {
        // Hentikan semua proses WebRTC & Kamera
        stopWebRTC()
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {}

        // Reset UI
        findViewById<View>(R.id.roleSelectionLayout).visibility = View.VISIBLE
        findViewById<View>(R.id.viewFinder).visibility = View.GONE
        findViewById<View>(R.id.remoteVideoContainer).visibility = View.GONE
        
        Toast.makeText(this, "Kembali ke Dashboard", Toast.LENGTH_SHORT).show()
    }

    private fun startCctvMode() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Setup CCTV")
        
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)
        
        val inputRoom = android.widget.EditText(this)
        inputRoom.hint = "Nama Ruangan (misal: Ruang Tamu)"
        layout.addView(inputRoom)
        
        val inputPass = android.widget.EditText(this)
        inputPass.hint = "Password (kosongkan jika publik)"
        inputPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(inputPass)
        
        builder.setView(layout)
        builder.setPositiveButton("Mulai") { _, _ ->
            val roomName = inputRoom.text.toString()
            val password = inputPass.text.toString()
            if (roomName.isNotEmpty()) {
                proceedCctv(roomName, password)
            } else {
                Toast.makeText(this, "Nama ruangan wajib diisi", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Batal", null)
        builder.show()
    }

    private fun proceedCctv(roomName: String, password: String) {
        if (allPermissionsGranted()) {
            Toast.makeText(this, "Memulai Mode CCTV: $roomName", Toast.LENGTH_SHORT).show()
            viewFinder.visibility = View.VISIBLE
            findViewById<View>(R.id.roleSelectionLayout).visibility = View.GONE
            
            try {
                val context = eglBase?.eglBaseContext ?: return
                val roomId = roomName.lowercase().replace(" ", "_")
                currentRoomId = roomId // Simpan ID
                
                // Referensi ke status ruangan
                val roomRef = com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("room_list").child(roomId)
                
                // Simpan metadata ruangan
                roomRef.setValue(mapOf(
                    "name" to roomName,
                    "password" to password,
                    "status" to "online"
                ))

                // OTOMATIS OFFLINE SAAT DISCONNECT (Fitur onDisconnect Firebase)
                roomRef.child("status").onDisconnect().setValue("offline")

                webRTCHelper = WebRTCHelper(this, context, "CCTV", roomId) { }
                startCamera()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "CCTV Init Error", e)
            }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        android.util.Log.d("MainActivity", "startCamera called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                android.util.Log.d("MainActivity", "CameraProvider received")

                // 1. Setup Preview untuk Layar HP Sendiri
                val preview = Preview.Builder()
                    .build().also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                // 2. Setup Bridge untuk WebRTC (PENTING!)
                val helper = webRTCHelper
                val eglCtx = eglBase?.eglBaseContext
                
                if (helper != null && eglCtx != null) {
                    val factory = helper.peerConnectionFactory
                    if (factory != null) {
                        android.util.Log.d("MainActivity", "WebRTC Factory is available")
                        
                        // JANGAN panggil stopWebRTC() di sini karena akan men-set status menjadi offline
                        // Hentikan komponen WebRTC secara manual saja jika perlu tanpa mengubah status Firebase
                        surfaceTextureHelper?.stopListening()
                        surfaceTextureHelper?.dispose()
                        surfaceTextureHelper = null

                        val sth = SurfaceTextureHelper.create("CaptureThread_" + System.currentTimeMillis(), eglCtx)
                        surfaceTextureHelper = sth
                        
                        val source = factory.createVideoSource(false)
                        videoSource = source
                        
                        // Jembatan: Frame dari SurfaceTextureHelper dikirim ke WebRTC
                        sth.startListening { frame ->
                            if (videoSource == source && surfaceTextureHelper == sth) {
                                try {
                                    // Deteksi rotasi layar HP CCTV saat ini
                                    val rotation = when (windowManager.defaultDisplay.rotation) {
                                        Surface.ROTATION_90 -> 90
                                        Surface.ROTATION_180 -> 180
                                        Surface.ROTATION_270 -> 270
                                        else -> 0
                                    }

                                    // Buat frame baru dengan rotasi yang benar-benar dipaksa
                                    // Ini akan memberitahu WebRTC untuk memutar buffer sebelum dirender di Monitor
                                    val rotatedFrame = VideoFrame(frame.buffer, rotation, frame.timestampNs)
                                    source.capturerObserver.onFrameCaptured(rotatedFrame)
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Error capturing frame", e)
                                }
                            }
                        }
                        
                        source.capturerObserver.onCapturerStarted(true)

                        // Preview khusus untuk dikirim ke WebRTC
                        val webRtcPreview = Preview.Builder()
                            .setTargetRotation(Surface.ROTATION_90) // Paksa ke Landscape
                            .build()
                        webRtcPreview.setSurfaceProvider { request ->
                            val resolution = request.resolution
                            android.util.Log.d("MainActivity", "CCTV Landscape Resolution: ${resolution.width}x${resolution.height}")
                            
                            sth.setTextureSize(resolution.width, resolution.height)
                            val surface = Surface(sth.surfaceTexture)
                            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
                                surface.release()
                            }
                        }

                        val track = factory.createVideoTrack("VIDEO_TRACK", source)
                        videoTrack = track
                        android.util.Log.d("MainActivity", "Starting WebRTC streaming")
                        helper.startStreaming(track)

                        try {
                            cameraProvider.unbindAll()
                            // Bind kedua preview: satu untuk layar, satu untuk WebRTC
                            cameraProvider.bindToLifecycle(
                                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, webRtcPreview
                            )

                            android.util.Log.d("MainActivity", "Camera bound to lifecycle")
                        } catch (exc: Exception) {
                            android.util.Log.e("MainActivity", "Camera binding failed", exc)
                        }
                    } else {
                        android.util.Log.e("MainActivity", "WebRTC Factory is NULL")
                    }
                } else {
                    android.util.Log.e("MainActivity", "WebRTCHelper or EglContext is NULL")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "startCamera listener error", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        disposeWebRTC()
        eglBase?.release()
        eglBase = null
    }

    private fun stopWebRTC() {
        // SET OFFLINE SECARA MANUAL
        currentRoomId?.let {
            com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("room_list").child(it).child("status").setValue("offline")
        }
        currentRoomId = null

        surfaceTextureHelper?.stopListening()
        
        videoTrack?.dispose()
        videoTrack = null
        
        videoSource?.dispose()
        videoSource = null
        
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        
        webRTCHelper?.close()
        remoteVideoView.release()
    }

    private fun disposeWebRTC() {
        stopWebRTC()
        webRTCHelper?.dispose()
        webRTCHelper = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private fun startMonitorMode() {
        val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance().reference.child("room_list")
        dbRef.get().addOnSuccessListener { snapshot ->
            val rooms = mutableListOf<Triple<String, String, String>>() // ID, Name, Status
            snapshot.children.forEach { child ->
                val id = child.key ?: ""
                val name = child.child("name").value?.toString() ?: id
                val status = child.child("status").value?.toString() ?: "unknown"
                rooms.add(Triple(id, name, status))
            }
            
            if (rooms.isEmpty()) {
                Toast.makeText(this, "Tidak ada CCTV terdaftar", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            // Tampilkan list dengan format: "Nama Ruangan [🟢 ONLINE / 🔴 OFFLINE]"
            val roomDisplayNames = rooms.map { 
                val statusIcon = if (it.third == "online") "🟢 ONLINE" else "🔴 OFFLINE"
                "${it.second} ($statusIcon)"
            }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Daftar CCTV Tersedia")
                .setItems(roomDisplayNames) { _, which ->
                    val selectedRoom = rooms[which]
                    if (selectedRoom.third == "online") {
                        val correctPass = snapshot.child(selectedRoom.first).child("password").value?.toString() ?: ""
                        checkRoomPassword(selectedRoom.first, correctPass)
                    } else {
                        Toast.makeText(this, "CCTV ini sedang Offline", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }.addOnFailureListener {
            Toast.makeText(this, "Gagal mengambil daftar CCTV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkRoomPassword(roomId: String, correctPass: String) {
        if (correctPass.isEmpty()) {
            initMonitor(roomId)
            return
        }

        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Masukkan Password")
            .setView(input)
            .setPositiveButton("Masuk") { _, _ ->
                if (input.text.toString() == correctPass) {
                    initMonitor(roomId)
                } else {
                    Toast.makeText(this, "Password Salah!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun initMonitor(roomId: String) {
        Toast.makeText(this, "Menghubungkan ke $roomId...", Toast.LENGTH_SHORT).show()
        findViewById<View>(R.id.roleSelectionLayout).visibility = View.GONE
        val container = findViewById<View>(R.id.remoteVideoContainer)
        container.visibility = View.VISIBLE
        
        // --- LOGIKA ZOOM & PAN ---
        var scaleFactor = 1.0f
        var lastTouchX = 0f
        var lastTouchY = 0f
        var posX = 0f
        var posY = 0f

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f) // Limit zoom 1x - 5x
                
                container.scaleX = scaleFactor
                container.scaleY = scaleFactor
                
                // Jika kembali ke normal, reset posisi ke tengah
                if (scaleFactor <= 1.1f) {
                    posX = 0f
                    posY = 0f
                    container.translationX = 0f
                    container.translationY = 0f
                }
                return true
            }
        })

        container.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor > 1.0f) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        
                        posX += dx
                        posY += dy
                        
                        container.translationX = posX
                        container.translationY = posY
                        
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                }
            }
            true
        }
        // --------------------------

        try {
            val eglCtx = eglBase?.eglBaseContext
            if (eglCtx != null) {
                stopWebRTC()
                
                // Reset renderer
                remoteVideoView.release()
                remoteVideoView.init(eglCtx, null)
                
                // PENTING: Pastikan video muncul di atas background hitam
                remoteVideoView.setZOrderMediaOverlay(true)
                
                // ASPECT_FIT akan menjaga rasio asli (muncul bar hitam jika perlu)
                remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                remoteVideoView.setEnableHardwareScaler(false)
                remoteVideoView.setMirror(false)
                
                // Hapus padding agar kita bisa melihat apakah black bars muncul secara alami
                remoteVideoView.setPadding(0, 0, 0, 0) 

                remoteVideoView.requestLayout()
                
                webRTCHelper = WebRTCHelper(this, eglCtx, "MONITOR", roomId) { videoTrack ->
                    runOnUiThread {
                        android.util.Log.d("MainActivity", "Remote Video Track attached to UI")
                        videoTrack.removeSink(remoteVideoView)
                        videoTrack.addSink(remoteVideoView)

                        // ASPECT_FIT agar gambar Landscape CCTV masuk sempurna ke kotak 16:9 Monitor
                        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        remoteVideoView.setEnableHardwareScaler(true)
                        remoteVideoView.setMirror(false)
                        
                        remoteVideoView.requestLayout()
                    }
                }
                webRTCHelper?.startMonitoring()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "startMonitorMode error", e)
            Toast.makeText(this, "Gagal memulai monitor", Toast.LENGTH_LONG).show()
        }
    }
}
