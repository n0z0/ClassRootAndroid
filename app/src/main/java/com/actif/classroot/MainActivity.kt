package com.actif.classroot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.actif.classroot.theme.ClassRootTheme
import com.actif.classroot.ui.main.MainScreen
import com.actif.classroot.webrtc.ScreenCaptureService

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Pending configuration to start when permission is granted
    private var pendingServerUrl: String = "https://192.168.1.100:8443"
    private var pendingGroupName: String = "main"
    private var pendingUsername: String = "Android-Device"
    private var pendingPassword: String = ""
    private var pendingWidth: Int = 1280
    private var pendingHeight: Int = 720
    private var pendingFps: Int = 30

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_SERVER_URL, pendingServerUrl)
                putExtra(ScreenCaptureService.EXTRA_GROUP_NAME, pendingGroupName)
                putExtra(ScreenCaptureService.EXTRA_USERNAME, pendingUsername)
                putExtra(ScreenCaptureService.EXTRA_PASSWORD, pendingPassword)
                putExtra(ScreenCaptureService.EXTRA_WIDTH, pendingWidth)
                putExtra(ScreenCaptureService.EXTRA_HEIGHT, pendingHeight)
                putExtra(ScreenCaptureService.EXTRA_FPS, pendingFps)
                putExtra(ScreenCaptureService.EXTRA_CAPTURE_INTENT, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Toast.makeText(this, "Izin perekaman layar dibatalkan", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestScreenCapturePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            ClassRootTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartRequested = { serverUrl, groupName, username, password, width, height, fps ->
                            pendingServerUrl = serverUrl
                            pendingGroupName = groupName
                            pendingUsername = username
                            pendingPassword = password
                            pendingWidth = width
                            pendingHeight = height
                            pendingFps = fps

                            checkPermissionsAndStart()
                        },
                        onStopRequested = {
                            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                                action = ScreenCaptureService.ACTION_STOP
                            }
                            startService(stopIntent)
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        captureLauncher.launch(captureIntent)
    }
}
