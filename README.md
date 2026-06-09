# 🐾 PawPantau - Aplikasi Pemantau Anabul Real-Time

**PawPantau** adalah aplikasi Android berbasis WebRTC yang mengubah smartphone lama Anda menjadi sistem CCTV pintar untuk memantau hewan peliharaan (anabul) secara real-time. Aplikasi ini mendukung koneksi Peer-to-Peer (P2P) yang stabil, aman, dan hemat biaya.

## 🚀 Fitur Utama

- **📹 Streaming Video Real-Time**: Koneksi ultra-rendah latency menggunakan teknologi WebRTC.
- **📱 Multi-Room System**: Gunakan banyak HP sebagai CCTV dengan nama ruangan unik dan proteksi password.
- **🎙️ Interkom Dua Arah**: Dengarkan suara anabul dan bicara langsung dari HP Monitor untuk menenangkan mereka.
- **🔦 Remote Flashlight**: Nyalakan lampu flash HP CCTV langsung dari HP Monitor saat ruangan gelap.
- **👻 Stealth Mode**: Gelapkan layar HP CCTV untuk menghemat baterai dan menjaga perangkat tidak panas.
- **🔍 Pinch-to-Zoom**: Cubit layar pada HP Monitor untuk melihat detail lebih dekat dengan fitur pan & scan.
- **🟢 Status Indikator**: Pantau daftar CCTV yang sedang Online atau Offline secara langsung.

## 🛠️ Teknologi yang Digunakan

- **Kotlin**: Bahasa pemrograman utama.
- **WebRTC**: Protokol komunikasi video & audio P2P.
- **Firebase Realtime Database**: Sebagai signaling server untuk jabat tangan (handshake) antar perangkat.
- **CameraX**: Library kamera Android yang modern untuk menangkap gambar berkualitas tinggi.

## 📦 Cara Setup

1. **Firebase Configuration**:
   - Buat proyek baru di [Firebase Console](https://console.firebase.google.com/).
   - Aktifkan **Realtime Database**.
   - Set Database Rules ke public (untuk testing) atau gunakan autentikasi:
     ```json
     {
       "rules": {
         ".read": true,
         ".write": true
       }
     }
     ```
   - Download `google-services.json` dan letakkan di folder `app/`.

2. **Build & Run**:
   - Clone repository ini.
   - Buka di Android Studio (Koala atau versi lebih baru).
   - Sync Gradle dan jalankan di minimal 2 perangkat Android.

## 📖 Cara Penggunaan

1. **Sisi CCTV (Pengirim)**:
   - Pilih "Jadikan CCTV".
   - Masukkan nama ruangan (misal: "Kamar Kucing").
   - Set password jika diinginkan.
   - Klik "Mulai". Gunakan "Mode Stealth" jika ingin meletakkan HP dalam waktu lama.

2. **Sisi Monitor (Penerima)**:
   - Pilih "Jadikan Monitor".
   - Pilih ruangan yang muncul di daftar.
   - Masukkan password (jika ada).
   - Gunakan tombol Flashlight untuk cahaya, dan tombol Mic untuk berbicara.

## 🛡️ Izin Aplikasi (Permissions)

Aplikasi ini memerlukan izin berikut untuk berfungsi:
- `CAMERA`: Untuk menangkap video di sisi CCTV.
- `RECORD_AUDIO`: Untuk fitur suara dan interkom.
- `INTERNET`: Untuk komunikasi antar perangkat.

---
Dikembangkan dengan ❤️ untuk para pecinta anabul.
