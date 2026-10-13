package com.actif.classroot.webrtc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Handles communication with Galene WebRTC SFU server (ClassRoot)
 * implements symmetric JSON-based protocol over WebSocket.
 */
class GaleneSignaling(
    private val serverUrl: String,
    private val groupName: String,
    private val username: String,
    private val password: String = "",
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onHandshakeComplete()
        fun onJoined(iceServers: List<PeerConnection.IceServer>)
        fun onAnswer(streamId: String, sdp: String)
        fun onIceCandidate(streamId: String, candidate: IceCandidate)
        fun onError(message: String)
        fun onDisconnected(reason: String)
        fun onLog(message: String)
    }

    private val tag = "GaleneSignaling"
    private var webSocket: WebSocket? = null
    private val clientId = UUID.randomUUID().toString()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pingTask: ScheduledFuture<*>? = null

    fun connect() {
        try {
            val wsUrl = formatWebSocketUrl(serverUrl)
            listener.onLog("Connecting to WebSocket: $wsUrl")

            val okHttpClient = createUnsafeOkHttpClient()
            val request = Request.Builder().url(wsUrl).build()

            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onLog("WebSocket opened. Sending handshake...")
                    listener.onConnected()
                    sendHandshake()
                    startPingTimer()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onLog("WebSocket closing: $reason (code: $code)")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    stopPingTimer()
                    listener.onLog("WebSocket closed: $reason (code: $code)")
                    listener.onDisconnected("Closed: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    stopPingTimer()
                    val errorMsg = "WebSocket connection failed: ${t.localizedMessage ?: t.message}"
                    Log.e(tag, errorMsg, t)
                    listener.onLog(errorMsg)
                    listener.onError(errorMsg)
                    listener.onDisconnected(errorMsg)
                }
            })
        } catch (e: Exception) {
            val errorMsg = "Failed to initiate connection: ${e.message}"
            Log.e(tag, errorMsg, e)
            listener.onError(errorMsg)
        }
    }

    fun disconnect() {
        stopPingTimer()
        try {
            sendLeave()
            webSocket?.close(1000, "Normal Closure")
        } catch (e: Exception) {
            Log.e(tag, "Error closing websocket", e)
        } finally {
            webSocket = null
        }
    }

    fun sendOffer(streamId: String, sdp: String) {
        val json = JSONObject().apply {
            put("type", "offer")
            put("id", streamId)
            put("label", "screenshare")
            put("source", clientId)
            put("username", username)
            put("sdp", sdp)
        }
        sendJson(json)
        listener.onLog("Sent WebRTC screenshare offer (stream: $streamId)")
    }

    fun sendIceCandidate(streamId: String, candidate: IceCandidate) {
        val candJson = JSONObject().apply {
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        }
        val json = JSONObject().apply {
            put("type", "ice")
            put("id", streamId)
            put("candidate", candJson)
        }
        sendJson(json)
    }

    fun sendCloseStream(streamId: String) {
        val json = JSONObject().apply {
            put("type", "close")
            put("id", streamId)
        }
        sendJson(json)
        listener.onLog("Closed stream: $streamId")
    }

    private fun sendHandshake() {
        val json = JSONObject().apply {
            put("type", "handshake")
            put("version", JSONArray().put("2"))
            put("id", clientId)
        }
        sendJson(json)
    }

    private fun sendJoin() {
        val json = JSONObject().apply {
            put("type", "join")
            put("kind", "join")
            put("group", groupName)
            put("username", username)
            if (password.isNotEmpty()) {
                put("password", password)
            }
        }
        sendJson(json)
        listener.onLog("Sent join request for group '$groupName' as '$username'")
    }

    private fun sendLeave() {
        val json = JSONObject().apply {
            put("type", "join")
            put("kind", "leave")
            put("group", groupName)
        }
        sendJson(json)
    }

    private fun sendPing() {
        val json = JSONObject().apply {
            put("type", "ping")
        }
        sendJson(json)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "handshake" -> {
                    listener.onLog("Handshake complete from server")
                    listener.onHandshakeComplete()
                    sendJoin()
                }
                "joined" -> {
                    val kind = json.optString("kind")
                    if (kind == "join" || kind == "change") {
                        val permissions = json.optJSONArray("permissions")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList()
                        listener.onLog("Joined room '$groupName' successfully (Permissions: $permissions)")

                        val rtcConfig = json.optJSONObject("rtcConfiguration")
                        val iceServers = parseIceServers(rtcConfig)
                        listener.onJoined(iceServers)
                    } else if (kind == "fail") {
                        val err = json.optString("value", "Failed to join room")
                        listener.onError("Join failed: $err")
                    }
                }
                "answer" -> {
                    val streamId = json.optString("id")
                    val sdp = json.optString("sdp")
                    listener.onLog("Received SDP answer for stream: $streamId")
                    listener.onAnswer(streamId, sdp)
                }
                "ice" -> {
                    val streamId = json.optString("id")
                    val candObj = json.optJSONObject("candidate")
                    if (candObj != null) {
                        val sdp = candObj.optString("candidate")
                        val sdpMid = candObj.optString("sdpMid")
                        val sdpMLineIndex = candObj.optInt("sdpMLineIndex", 0)
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        listener.onIceCandidate(streamId, candidate)
                    }
                }
                "ping" -> {
                    val pong = JSONObject().apply { put("type", "pong") }
                    sendJson(pong)
                }
                "pong" -> {
                    // Keepalive acknowledged
                }
                "close", "abort" -> {
                    val streamId = json.optString("id")
                    listener.onLog("Server requested close for stream $streamId")
                }
                "usermessage" -> {
                    val kind = json.optString("kind")
                    val message = json.optString("value")
                    if (kind == "error") {
                        listener.onError("Server error: $message")
                    } else {
                        listener.onLog("Server notification: $message")
                    }
                }
                else -> {
                    Log.d(tag, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing message: $text", e)
        }
    }

    private fun parseIceServers(rtcConfig: JSONObject?): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        if (rtcConfig != null && rtcConfig.has("iceServers")) {
            val iceArr = rtcConfig.getJSONArray("iceServers")
            for (i in 0 until iceArr.length()) {
                val serverObj = iceArr.getJSONObject(i)
                val urls = serverObj.opt("urls")
                val urlList = mutableListOf<String>()
                if (urls is JSONArray) {
                    for (u in 0 until urls.length()) {
                        urlList.add(urls.getString(u))
                    }
                } else if (urls is String) {
                    urlList.add(urls)
                }

                if (urlList.isNotEmpty()) {
                    val builder = PeerConnection.IceServer.builder(urlList)
                    if (serverObj.has("username")) {
                        builder.setUsername(serverObj.getString("username"))
                    }
                    if (serverObj.has("credential")) {
                        builder.setPassword(serverObj.getString("credential"))
                    }
                    servers.add(builder.createIceServer())
                }
            }
        }

        // Add standard fallback STUN if none provided
        if (servers.isEmpty()) {
            servers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        }
        return servers
    }

    private fun sendJson(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    private fun startPingTimer() {
        stopPingTimer()
        pingTask = scheduler.scheduleAtFixedRate({
            sendPing()
        }, 20, 20, TimeUnit.SECONDS)
    }

    private fun stopPingTimer() {
        pingTask?.cancel(true)
        pingTask = null
    }

    private fun formatWebSocketUrl(inputUrl: String): String {
        var trimmed = inputUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
            !trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")
        ) {
            trimmed = "https://$trimmed"
        }

        return when {
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://")
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://")
            else -> trimmed
        }.let {
            val base = if (it.endsWith("/")) it.dropLast(1) else it
            if (base.endsWith("/ws")) base else "$base/ws"
        }
    }

    /**
     * Trust-all SSL client for ClassRoot testing over local network IPs
     * where self-signed certificates (data/cert.pem) are used.
     */
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Keep open for WebSocket
            .build()
    }
}
