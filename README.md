# ClassRoot Android Screen Broadcaster

Aplikasi Android native untuk ekosistem **ClassRoot** yang menyediakan kemampuan berbagi layar (*Screen Share*) via WebRTC secara langsung dari perangkat mobile Android.

---

## 1. Latar Belakang & Masalah
Pada sistem operasi Android, browser web mobile (seperti Google Chrome, Microsoft Edge, Mozilla Firefox) membatasi atau tidak mengizinkan pemanggilan API JavaScript `navigator.mediaDevices.getDisplayMedia()` demi kebijakan privasi dan keamanan sistem.

Aplikasi **ClassRoot Android** menjembatani keterbatasan ini dengan memanfaatkan API native Android:
- **`MediaProjectionManager`**: Mengambil token izin tangkapan layar resmi dari OS Android.
- **Foreground Service (`mediaProjection`)**: Menjalankan streaming layar secara stabil di latar belakang dengan kontrol notifikasi sistem.
- **Hardware-Accelerated WebRTC**: Melakukan enkoding video (H.264/VP8) menggunakan OpenGL EGL context perangkat.
- **Galene Protocol Client**: Terhubung langsung ke SFU ClassRoot via WebSocket (`wss://<server>:8443/ws`).

---

## 2. Fitur Utama
1. **Dukungan Protokol ClassRoot (Galene SFU):**
   - Handshake versi 2 otomatis.
   - Pendaftaran ke room / group yang dituju (default: `main`).
   - Negosiasi SDP Offer/Answer dan trickle ICE candidates.
   - Deteksi dan dukungan koneksi TLS lokal (self-signed certificate compatible).
2. **Pengaturan Kualitas Tangkapan Layar:**
   - **720p 30fps (Optimal / Rekomendasi):** Keseimbangan ideal antara kualitas teks dan efisiensi bandwidth.
   - **1080p 30fps (Full HD):** Kualitas tajam untuk presentasi beresolusi tinggi.
   - **480p 24fps (Ringan):** Mode hemat data untuk koneksi terbatas.
3. **Panel Kontrol & Konsol Log Real-Time:**
   - Indikator status koneksi (Standby, Menghubungkan, Handshake, Streaming Aktif).
   - Log event real-time untuk pemantauan koneksi WebRTC, ICE state, dan pesan server.
4. **Kontrol Notifikasi Latar Belakang:**
   - Notifikasi persistent dengan tombol cepat "Hentikan" untuk menghentikan sesi kapan saja.

---

## 3. Struktur Modul
```
app/src/main/java/com/actif/classroot/
├── MainActivity.kt                 # Entri aktivitas utama & izin MediaProjection
├── webrtc/
│   ├── GaleneSignaling.kt          # Client WebSocket protokol Galene / ClassRoot
│   ├── WebRtcManager.kt            # Engine WebRTC, capturer, dan peer connection
│   └── ScreenCaptureService.kt     # Android Foreground Service (mediaProjection)
└── ui/
    └── main/
        └── MainScreen.kt           # Antarmuka Jetpack Compose modern
```

---

## 4. Cara Membangun (*Build*) & Memasang (*Install*)

### Prasyarat
- JDK 17 atau yang lebih baru.
- Android SDK Platform 34+.

### Membangun APK Debug
Gunakan perintah Gradle Wrapper:
```powershell
.\gradlew assembleDebug
```
Berkas APK yang dihasilkan berada di:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Membangun APK Release
Gunakan perintah Gradle Wrapper:
```powershell
.\gradlew assembleRelease
```
Berkas APK yang dihasilkan berada di:
```
app/build/outputs/apk/release/app-release.apk
```

### Otomatisasi Rilis APK via GitHub Actions
Repositori ini dilengkapi GitHub Actions Workflow (`.github/workflows/release.yml`). Setiap kali tag versi di-push (misal `v1.0.0`), APK Release dan Debug akan dibangun secara otomatis dan langsung dipublikasikan ke tab **Releases**:
```powershell
git tag v1.0.0
git push origin v1.0.0
```
Setelah proses workflow selesai, APK dapat langsung diunduh pengguna dari halaman GitHub Releases repositori:
- `ClassRoot-v1.0.0.apk` (Release APK siap pasang)
- `ClassRoot-v1.0.0-debug.apk` (Debug APK)
- `checksums.txt` (Verifikasi integritas berkas SHA-256)

### Memasang ke Perangkat Android
Hubungkan perangkat Android dengan USB Debugging aktif, lalu jalankan:
```powershell
.\gradlew installDebug
```
Atau menggunakan ADB langsung:
```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 5. Penggunaan
1. Buka aplikasi **ClassRoot** di Android.
2. Masukkan alamat server ClassRoot Anda (contoh: `https://192.168.1.100:8443`).
3. Tentukan nama room (contoh: `main`) dan nama pengguna Anda.
4. Pilih profil resolusi yang diinginkan.
5. Tekan tombol **Mulai Berbagi Layar**.
6. Setujui permintaan izin "Mulai merekam atau mentransmisikan" dari sistem Android.
7. Buka browser pada komputer lain di room yang sama untuk melihat tayangan layar Android secara real-time.
