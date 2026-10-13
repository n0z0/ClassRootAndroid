package com.actif.classroot.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.actif.classroot.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "classroot_screen_share_channel"
        const val NOTIFICATION_ID = 60601

        const val ACTION_START = "com.actif.classroot.ACTION_START"
        const val ACTION_STOP = "com.actif.classroot.ACTION_STOP"

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_CAPTURE_INTENT = "extra_capture_intent"

        private val _statusFlow = MutableStateFlow("Terputus")
        val statusFlow = _statusFlow.asStateFlow()

        private val _isSharingFlow = MutableStateFlow(false)
        val isSharingFlow = _isSharingFlow.asStateFlow()

        private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val logFlow = _logFlow.asSharedFlow()

        var activeManager: WebRtcManager? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_STOP) {
            stopScreenSharing()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "https://127.0.0.1:8443"
            val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "main"
            val username = intent.getStringExtra(EXTRA_USERNAME) ?: "Android-User"
            val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
            val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
            val fps = intent.getIntExtra(EXTRA_FPS, 30)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_CAPTURE_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_CAPTURE_INTENT)
            }

            if (captureIntent == null) {
                _logFlow.tryEmit("Error: MediaProjection intent data kosong")
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundNotification()
            startScreenSharing(serverUrl, groupName, username, password, captureIntent, width, height, fps)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClassRoot Berbagi Layar")
            .setContentText("Layar Android sedang dibagikan secara aktif.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenSharing(
        serverUrl: String,
        groupName: String,
        username: String,
        password: String,
        captureIntent: Intent,
        width: Int,
        height: Int,
        fps: Int
    ) {
        stopScreenSharing()

        val manager = WebRtcManager(
            context = applicationContext,
            serverUrl = serverUrl,
            groupName = groupName,
            username = username,
            password = password,
            captureIntentData = captureIntent,
            captureWidth = width,
            captureHeight = height,
            captureFps = fps,
            statusListener = object : WebRtcManager.StatusListener {
                override fun onStatusChanged(status: String) {
                    _statusFlow.value = status
                }

                override fun onLog(message: String) {
                    _logFlow.tryEmit(message)
                }

                override fun onError(message: String) {
                    _logFlow.tryEmit("ERROR: $message")
                    _statusFlow.value = "Error: $message"
                }

                override fun onStreamStarted() {
                    _isSharingFlow.value = true
                }

                override fun onStreamStopped() {
                    _isSharingFlow.value = false
                    stopSelf()
                }
            }
        )

        activeManager = manager
        manager.start()
    }

    private fun stopScreenSharing() {
        activeManager?.stop()
        activeManager = null
        _isSharingFlow.value = false
        _statusFlow.value = "Terputus"
    }

    override fun onDestroy() {
        stopScreenSharing()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ClassRoot Screen Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status berbagi layar ClassRoot"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
