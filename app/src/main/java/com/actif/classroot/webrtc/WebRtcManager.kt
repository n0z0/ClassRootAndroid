package com.actif.classroot.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

class WebRtcManager(
    private val context: Context,
    private val serverUrl: String,
    private val groupName: String,
    private val username: String,
    private val password: String = "",
    private val captureIntentData: Intent,
    private val captureWidth: Int = 1280,
    private val captureHeight: Int = 720,
    private val captureFps: Int = 30,
    private val statusListener: StatusListener
) : GaleneSignaling.Listener {

    interface StatusListener {
        fun onStatusChanged(status: String)
        fun onLog(message: String)
        fun onError(message: String)
        fun onStreamStarted()
        fun onStreamStopped()
    }

    private val tag = "WebRtcManager"
    private val rootEglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private var signaling: GaleneSignaling? = null
    private var activeStreamId: String? = null
    private var isStreaming = false

    fun start() {
        try {
            statusListener.onLog("Initializing WebRTC Factory...")
            statusListener.onStatusChanged("Menghubungkan...")

            initializePeerConnectionFactory()
            createVideoTrackFromScreen()

            signaling = GaleneSignaling(
                serverUrl = serverUrl,
                groupName = groupName,
                username = username,
                password = password,
                listener = this
            )
            signaling?.connect()
        } catch (e: Exception) {
            val err = "Gagal memulai WebRTC: ${e.message}"
            Log.e(tag, err, e)
            statusListener.onError(err)
            stop()
        }
    }

    fun stop() {
        if (!isStreaming && signaling == null) return
        statusListener.onLog("Menghentikan screenshare...")
        statusListener.onStatusChanged("Berhenti")

        activeStreamId?.let { streamId ->
            try {
                signaling?.sendCloseStream(streamId)
            } catch (e: Exception) {
                Log.e(tag, "Error closing stream", e)
            }
        }
        activeStreamId = null

        try {
            signaling?.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Error disconnecting signaling", e)
        }
        signaling = null

        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.e(tag, "Error disposing peer connection", e)
        }
        peerConnection = null

        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping screen capturer", e)
        }
        screenCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        videoSource?.dispose()
        videoSource = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        isStreaming = false
        statusListener.onStreamStopped()
        statusListener.onStatusChanged("Terputus")
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun createVideoTrackFromScreen() {
        val capturer = ScreenCapturerAndroid(captureIntentData, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                statusListener.onLog("MediaProjection berhenti oleh sistem Android")
                stop()
            }
        })
        screenCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", rootEglBase.eglBaseContext)
        val factory = peerConnectionFactory ?: throw IllegalStateException("Factory null")

        videoSource = factory.createVideoSource(true) // isScreencast = true
        capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        capturer.startCapture(captureWidth, captureHeight, captureFps)

        localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource).apply {
            setEnabled(true)
        }
        statusListener.onLog("Screen capturer aktif ($captureWidth x $captureHeight @ $captureFps fps)")
    }

    override fun onConnected() {
        statusListener.onStatusChanged("WebSocket Terhubung")
    }

    override fun onHandshakeComplete() {
        statusListener.onStatusChanged("Handshake Selesai")
    }

    override fun onJoined(iceServers: List<PeerConnection.IceServer>) {
        statusListener.onStatusChanged("Bergabung ke Room '$groupName'")
        statusListener.onLog("Mempersiapkan PeerConnection untuk screenshare...")

        createPeerConnectionAndOffer(iceServers)
    }

    private fun createPeerConnectionAndOffer(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val streamId = UUID.randomUUID().toString()
        activeStreamId = streamId

        val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                statusListener.onLog("ICE State: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        isStreaming = true
                        statusListener.onStatusChanged("Aktif Berbagi Layar")
                        statusListener.onStreamStarted()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        statusListener.onError("ICE Connection gagal")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        statusListener.onLog("ICE Disconnected")
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                statusListener.onLog("ICE Gathering: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                activeStreamId?.let { sId ->
                    signaling?.sendIceCandidate(sId, candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {}
        }) ?: run {
            statusListener.onError("Gagal membuat PeerConnection")
            return
        }
        peerConnection = pc

        // Add local screen video track
        localVideoTrack?.let { track ->
            val init = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf(streamId)
            )
            pc.addTransceiver(track, init)
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                statusListener.onLog("Local Offer SDP berhasil dibuat")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        statusListener.onLog("Local Description diterapkan, mengirim offer ke ClassRoot...")
                        signaling?.sendOffer(streamId, desc.description)
                    }

                    override fun onCreateFailure(err: String?) {
                        statusListener.onError("Gagal setLocalDescription: $err")
                    }

                    override fun onSetFailure(err: String?) {
                        statusListener.onError("Gagal setLocalDescription: $err")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                statusListener.onError("Gagal createOffer: $err")
            }

            override fun onSetFailure(err: String?) {
                statusListener.onError("Gagal createOffer: $err")
            }
        }, constraints)
    }

    override fun onAnswer(streamId: String, sdp: String) {
        if (streamId != activeStreamId) return
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                statusListener.onLog("Remote SDP Answer berhasil diterapkan")
            }

            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {
                statusListener.onError("Gagal setRemoteDescription: $err")
            }
        }, answerDesc)
    }

    override fun onIceCandidate(streamId: String, candidate: IceCandidate) {
        if (streamId != activeStreamId) return
        peerConnection?.addIceCandidate(candidate)
    }

    override fun onError(message: String) {
        statusListener.onError(message)
    }

    override fun onDisconnected(reason: String) {
        statusListener.onStatusChanged("Terputus ($reason)")
        stop()
    }

    override fun onLog(message: String) {
        statusListener.onLog(message)
    }
}
