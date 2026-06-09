package com.example.paw_pantau

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.webrtc.*

class WebRTCHelper(
    private val context: Context,
    private val eglContext: EglBase.Context,
    private val role: String,
    private val roomId: String, // Tambahkan roomId
    private val onRemoteStream: (VideoTrack) -> Unit
) {
    private val TAG = "WebRTCHelper"
    private val database by lazy { FirebaseDatabase.getInstance().reference.child("rooms").child(roomId) }
    var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private val iceCandidateQueue = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            Log.d(TAG, "PeerConnectionFactory initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PeerConnectionFactory", e)
        }
    }

    fun startStreaming(videoTrack: VideoTrack) {
        Log.d(TAG, "CCTV: Start Streaming")
        database.removeValue().addOnCompleteListener {
            Log.d(TAG, "CCTV: Database cleared, creating peer connection")
            createPeerConnection()
            // Menambahkan track dengan stream ID agar receiver bisa mengenalinya
            val streamId = "ARDAMS"
            peerConnection?.addTrack(videoTrack, listOf(streamId))
            
            val constraints = MediaConstraints()
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            
            peerConnection?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    super.onCreateSuccess(sdp)
                    Log.d(TAG, "CCTV: Offer Created")
                    sdp?.let {
                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                Log.d(TAG, "CCTV: Local Description Set Success")
                                database.child("offer").setValue(mapOf("sdp" to it.description, "type" to it.type.canonicalForm()))
                                Log.d(TAG, "CCTV: Offer Sent to Firebase")
                            }
                        }, it)
                    }
                }
            }, constraints)
            
            listenForAnswer()
            listenForIceCandidates("monitor_candidates")
        }
    }

    fun startMonitoring() {
        Log.d(TAG, "MONITOR: Start Monitoring")
        // Ensure database is clean or at least we are listening
        createPeerConnection()
        
        // Add transceiver for video to ensure we can receive it
        peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, 
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))

        listenForOffer()
        listenForIceCandidates("cctv_candidates")
    }

    private fun createPeerConnection() {
        Log.d(TAG, "Creating Peer Connection for role: $role")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        // Penting: Izinkan transisi state SDP
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "$role: New ICE Candidate generated")
                    val target = if (role == "CCTV") "cctv_candidates" else "monitor_candidates"
                    database.child(target).push().setValue(mapOf(
                        "sdp" to it.sdp,
                        "sdpMid" to it.sdpMid,
                        "sdpMLineIndex" to it.sdpMLineIndex
                    ))
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "$role: ICE Connection State: $newState")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "$role: Remote Track Received!")
                val track = receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "$role: Video Track Found, invoking callback")
                    onRemoteStream(track)
                }
            }
            
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                Log.d(TAG, "$role: Signaling State: $newState")
            }
            
            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "$role: Stream added (Legacy)")
                stream?.videoTracks?.firstOrNull()?.let {
                    onRemoteStream(it)
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "$role: ICE Gathering State: $newState")
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "$role: Renegotiation Needed")
            }
        })
    }

    private fun listenForOffer() {
        database.child("offer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && role == "MONITOR") {
                    Log.d(TAG, "MONITOR: Offer received from Firebase")
                    val sdp = snapshot.child("sdp").value as String
                    val type = snapshot.child("type").value as String
                    val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                    
                    peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            Log.d(TAG, "MONITOR: Remote Description (Offer) Set Success")
                            isRemoteDescriptionSet = true
                            drainIceCandidateQueue()
                            
                            val constraints = MediaConstraints()
                            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            
                            peerConnection?.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(answer: SessionDescription?) {
                                    super.onCreateSuccess(answer)
                                    Log.d(TAG, "MONITOR: Answer Created")
                                    answer?.let {
                                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                                            override fun onSetSuccess() {
                                                Log.d(TAG, "MONITOR: Local Description (Answer) Set Success")
                                                database.child("answer").setValue(mapOf("sdp" to it.description, "type" to it.type.canonicalForm()))
                                                Log.d(TAG, "MONITOR: Answer Sent to Firebase")
                                            }
                                        }, it)
                                    }
                                }
                            }, constraints)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "MONITOR: Failed to set remote description: $error")
                        }
                    }, description)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase Error: ${error.message}")
            }
        })
    }

    private fun listenForAnswer() {
        database.child("answer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && role == "CCTV") {
                    Log.d(TAG, "CCTV: Answer received from Firebase")
                    val sdp = snapshot.child("sdp").value as String
                    val type = snapshot.child("type").value as String
                    peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            Log.d(TAG, "CCTV: Remote Description (Answer) Set Success")
                            isRemoteDescriptionSet = true
                            drainIceCandidateQueue()
                        }
                    }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForIceCandidates(path: String) {
        database.child(path).addChildEventListener(object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                Log.d(TAG, "$role: Remote ICE Candidate received from $path")
                try {
                    val sdpMid = snapshot.child("sdpMid").value?.toString() ?: ""
                    val sdpMLineIndex = snapshot.child("sdpMLineIndex").value?.toString()?.toInt() ?: 0
                    val sdp = snapshot.child("sdp").value?.toString() ?: ""
                    
                    if (sdp.isNotEmpty()) {
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        if (isRemoteDescriptionSet) {
                            peerConnection?.addIceCandidate(candidate)
                        } else {
                            Log.d(TAG, "$role: Queuing ICE Candidate")
                            iceCandidateQueue.add(candidate)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding ICE candidate: ${e.message}")
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun drainIceCandidateQueue() {
        Log.d(TAG, "$role: Draining ICE Candidate Queue (size: ${iceCandidateQueue.size})")
        for (candidate in iceCandidateQueue) {
            peerConnection?.addIceCandidate(candidate)
        }
        iceCandidateQueue.clear()
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
    }

    fun dispose() {
        close()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {
            Log.e("WebRTCHelper", "SDP Create Failure: $p0")
        }
        override fun onSetFailure(p0: String?) {
            Log.e("WebRTCHelper", "SDP Set Failure: $p0")
        }
    }
}
