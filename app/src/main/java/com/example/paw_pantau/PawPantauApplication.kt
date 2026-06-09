package com.example.paw_pantau

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class PawPantauApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Inisialisasi Firebase secara manual
        // Catatan: Sangat disarankan untuk menggunakan google-services.json dan plugin google-services
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId("paw-pantau")
                    .setApplicationId("com.example.paw_pantau")
                    .setApiKey("YOUR_API_KEY") // Pastikan ini diganti dengan API Key yang valid dari Firebase Console
                    .setDatabaseUrl("https://paw-pantau-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .build()
                FirebaseApp.initializeApp(this, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
