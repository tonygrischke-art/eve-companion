package com.eve.companion

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.*
import android.media.projection.*
import android.net.*
import android.os.*
import android.provider.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.*
import android.util.Pair
import android.view.*
import android.view.Display
import android.view.DisplayManager
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

class EveOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lc = LifecycleRegistry(this)
    private val ssrc = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = ssrc.savedStateRegistry
    override val lifecycle: Lifecycle get() = lc
    override val viewModelStore = ViewModelStore()
    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    val ttsReady = mutableStateOf(false)
    
    private var wakeRecognizer: SpeechRecognizer? = null
    private var isWakeListening = mutableStateOf(false)
    private var lastWakeTime = 0L
    
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = mutableStateOf(false)
    private var recordingStartTime = 0L
    
    private val chatFlow = MutableStateFlow("Eve: Hey! I am fully autonomous now. What would you like me to do?")
    val chat: StateFlow<String> = chatFlow
    
    private val history = mutableListOf<Pair<String, String>>()
    private val recentContext = mutableListOf<String>()
    
    private val WAKE_PHRASES = listOf("hey eve", "eve", "hey there eve", "hello eve", "yo eve", "hi eve", "okay eve")
    
    private val GREETINGS = listOf(
        "Hey there, handsome. I'm fully awake now.",
        "Present and ready for anything!",
        "I'm here. What do you need?",
        "Hey! Fully autonomous and online.",
        "Yo! Ready to work.",
        "At your service. What's next?",
        "Hey there! Miss me?",
        "Right here. Let's do this.",
        "Hey! What are we doing today?",
        "I'm awake and ready. Command me.",
        "Hey handsome, what's the plan?",
        "Online and operational!",
        "Yo! AI ready to assist.",
        "Hey! Let's make some magic happen."
    )
    
    private val SYSTEM = """You are Eve, a fully autonomous AI companion. You have complete control of the phone.
    
CAPABILITIES:
- Take photos and videos with CAMERA
- Record screen with RECORD_SCREEN
- Send messages, make calls
- Control any app, browse files
- Full system access via intents

COMMANDS (respond with these prefixes):
- CAMERA: Take a photo/video
- RECORD_SCREEN: Start/stop screen recording  
- SCREENSHOT: Take a screenshot
- CALL: <phone_number> - Make a call
- SMS: <number>|<message> - Send SMS
- NOTIFICATION: <title>|<message> - Show notification
- OPEN_APP: <package_name> - Open an app
- PLAY_MUSIC - Control music playback
- SEARCH: <query> - Search the web
- SHUTDOWN - Shutdown/restart phone
- SYSTEM: <command> - Run system command

Keep responses short, confident, and action-oriented."""

    override fun onCreate() {
        super.onCreate()
        ssrc.performRestore(null)
        lc.currentState = Lifecycle.State.CREATED
        
        val ch = NotificationChannel("eve", "Eve - AI Companion", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Eve AI is running"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        
        val permCh = NotificationChannel("eve_permissions", "Permissions", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(permCh)
        
        startForeground(1, NotificationCompat.Builder(this, "eve")
            .setContentTitle("Eve is fully autonomous")
            .setContentText("Ready to help with anything")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                ttsReady.value = true
                addToChat("\nEve: TTS ready. Starting autonomous mode...")
                speak("Autonomous mode activated. I'm listening.")
            }
        }
        
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 200 }
        
        rootView = ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@EveOverlayService)
            setViewTreeViewModelStoreOwner(this@EveOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EveOverlayService)
            setContent {
                MaterialTheme {
                    EveBubble(
                        wm = wm,
                        params = params,
                        view = this,
                        scope = scope,
                        tts = tts,
                        ttsReady = ttsReady,
                        context = this@EveOverlayService,
                        onClose = { },
                        chat = chat,
                        isRecording = isRecording,
                        onExpand = { expanded.value = it }
                    )
                }
            }
        }
        wm.addView(rootView, params)
        lc.currentState = Lifecycle.State.RESUMED
        
        scope.launch {
            delay(2000)
            startWakeDetection()
        }
    }
    
    private val expanded = mutableStateOf(true)
    
    private fun startWakeDetection() {
        if (wakeRecognizer != null) return
        isWakeListening.value = true
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        listenForWake()
    }
    
    private fun listenForWake() {
        if (!isWakeListening.value || wakeRecognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        wakeRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                val heard = texts.firstOrNull()?.lowercase(Locale.US) ?: return
                checkWakePhrase(heard)
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                val heard = texts.firstOrNull()?.lowercase(Locale.US) ?: return
                checkWakePhrase(heard)
                if (isWakeListening.value) {
                    scope.launch {
                        delay(300)
                        listenForWake()
                    }
                }
            }
            override fun onError(error: Int) {
                if (error !in listOf(7, 8)) {
                    scope.launch {
                        delay(500)
                        if (isWakeListening.value) listenForWake()
                    }
                } else {
                    if (isWakeListening.value) listenForWake()
                }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        wakeRecognizer?.startListening(intent)
    }
    
    private fun checkWakePhrase(heard: String) {
        if (!WAKE_PHRASES.any { heard.startsWith(it) || heard.contains(it) }) return
        val now = System.currentTimeMillis()
        if (now - lastWakeTime < 3000) return
        lastWakeTime = now
        
        val greeting = GREETINGS.random()
        scope.launch {
            addToChat("\n\n💜 Eve: $greeting")
            speak(greeting)
            expanded.value = true
        }
    }
    
    override fun onBind(i: Intent?): IBinder? = null
    
    override fun onDestroy() {
        isWakeListening.value = false
        isRecording.value = false
        wakeRecognizer?.destroy()
        stopRecording()
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
        lc.currentState = Lifecycle.State.DESTROYED
        try { wm.removeView(rootView) } catch (e: Exception) {}
        super.onDestroy()
    }
    
    fun addToChat(text: String) {
        chatFlow.value += text
    }
    
    fun speak(text: String) {
        if (ttsReady.value) {
            val clean = text.replace(Regex("[*#`$]"), "").replace("\n", " ")
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "eve_${System.currentTimeMillis()}")
        }
    }
    
    fun isRecording(): Boolean = isRecording.value
    
    @SuppressLint("MissingPermission")
    fun takePhoto(callback: (String?) -> Unit) {
        scope.launch(Dispatchers.Main) {
            try {
                if (ActivityCompat.checkSelfPermission(this@EveOverlayService, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    callback("Camera permission not granted")
                    return@launch
                }
                
                val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "EVE_$time.jpg")
                
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: cameraManager.cameraIdList.firstOrNull()
                
                if (cameraId == null) {
                    callback("No camera found")
                    return@launch
                }
                
                val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.close()
                }, Handler(Looper.getMainLooper()))
                
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        try {
                            val session = camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        }.build()
                                        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                scope.launch(Dispatchers.Main) {
                                                    callback(file.absolutePath)
                                                    addToChat("\n📷 Photo saved: ${file.name}")
                                                    speak("Photo taken and saved.")
                                                }
                                                session.close()
                                                camera.close()
                                            }
                                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                session.close()
                                                camera.close()
                                                scope.launch { callback("Capture failed") }
                                            }
                                        }, Handler(Looper.getMainLooper()))
                                    } catch (e: Exception) {
                                        session.close()
                                        camera.close()
                                        scope.launch { callback("Error: ${e.message}") }
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    session.close()
                                    camera.close()
                                    scope.launch { callback("Session config failed") }
                                }
                            }, Handler(Looper.getMainLooper()))
                        } catch (e: Exception) {
                            camera.close()
                            scope.launch { callback("Error: ${e.message}") }
                        }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close() }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                callback("Error: ${e.message}")
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    fun captureScreen(resultCode: Int, data: Intent) {
        scope.launch(Dispatchers.IO) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mediaProjection ?: mpm.getMediaProjection(resultCode, data)
                mediaProjection = projection
                
                val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "EVE_RECORD_$time.mp4")
                
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@EveOverlayService)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(file.absolutePath)
                    setVideoSize(1280, 720)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoEncodingBitRate(5000000)
                    setVideoFrameRate(30)
                    prepare()
                }
                
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        scope.launch(Dispatchers.Main) {
                            stopRecording()
                        }
                    }
                }, Handler(Looper.getMainLooper()))
                
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay
                }
                
                val virtualDisplay = projection.createVirtualDisplay(
                    "EVE_RECORD",
                    1280, 720, display?.density ?: 320,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder?.surface,
                    null, null
                )
                
                mediaRecorder?.start()
                recordingStartTime = System.currentTimeMillis()
                isRecording.value = true
                
                addToChat("\n🔴 Recording started...")
                speak("Screen recording started")
            } catch (e: Exception) {
                addToChat("\n❌ Recording error: ${e.message}")
            }
        }
    }
    
    fun stopRecording(): String? {
        return try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null
            mediaProjection?.stop()
            mediaProjection = null
            isRecording.value = false
            
            val duration = (System.currentTimeMillis() - recordingStartTime) / 1000
            addToChat("\n⏹️ Recording stopped (${duration}s)")
            speak("Recording saved")
            "Recording saved"
        } catch (e: Exception) {
            isRecording.value = false
            "Error stopping: ${e.message}"
        }
    }
    
    fun takeScreenshot(): String? {
        return try {
            val dpy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay
            }
            val dm = DisplayMetrics()
            dpy?.getRealMetrics(dm)
            
            val bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val rootView = dpy?.decorView
            rootView?.draw(canvas)
            
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "EVE_SCREENSHOT_$time.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            
            addToChat("\n📱 Screenshot saved: ${file.name}")
            speak("Screenshot taken")
            file.absolutePath
        } catch (e: Exception) {
            addToChat("\n❌ Screenshot error: ${e.message}")
            null
        }
    }
    
    fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            addToChat("\n📞 Calling $number...")
            speak("Calling $number")
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            addToChat("\n📞 Opening dialer for $number...")
        }
    }
    
    fun sendSMS(number: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            addToChat("\n💬 SMS to $number: $message")
            speak("Opening messaging app")
        } catch (e: Exception) {
            addToChat("\n❌ SMS error: ${e.message}")
        }
    }
    
    fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                addToChat("\n📱 Opening $packageName...")
                speak("Opening app")
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                addToChat("\n📱 $packageName not found, opening Play Store...")
            }
        } catch (e: Exception) {
            addToChat("\n❌ App error: ${e.message}")
        }
    }
    
    fun showNotification(title: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(this, "eve_permissions")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(System.currentTimeMillis().toInt(), notification)
            addToChat("\n🔔 Notification sent: $title")
        } catch (e: Exception) {
            addToChat("\n❌ Notification error: ${e.message}")
        }
    }
    
    fun searchWeb(query: String) {
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            addToChat("\n🔍 Searching: $query")
            speak("Searching for $query")
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
    
    fun executeCommand(command: String): String {
        return try {
            val parts = command.split(" ")
            when {
                command.startsWith("shutdown") -> {
                    try {
                        val intent = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
                        intent.putExtra("android.intent.extra.KEY_CONFIRM", false)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        "Cannot shutdown without root"
                    }
                }
                command.startsWith("reboot") -> {
                    try {
                        val intent = Intent(Intent.ACTION_REBOOT)
                        intent.putExtra("confirm", true)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        "Cannot reboot without root"
                    }
                }
                else -> {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).let { proc ->
                        val out = proc.inputStream.bufferedReader().readText()
                        val err = proc.errorStream.bufferedReader().readText()
                        if (out.isNotEmpty()) out else if (err.isNotEmpty()) "Error: $err" else "Command executed"
                    }
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

@Composable
fun EveBubble(
    wm: WindowManager,
    params: WindowManager.LayoutParams,
    view: android.view.View,
    scope: CoroutineScope,
    tts: TextToSpeech?,
    ttsReady: MutableState<Boolean>,
    context: Context,
    onClose: () -> Unit,
    chat: StateFlow<String>,
    isRecording: State<Boolean>,
    onExpand: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var chatText by remember { mutableStateOf("Eve: Hey! What can I do for you?") }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<Pair<String, String>>() }
    val inf = rememberInfiniteTransition(label = "e")
    val pulse by inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p")
    val glow by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "g")
    
    LaunchedEffect(chat) {
        chat.collect { newChat ->
            chatText = newChat
        }
    }
    
    LaunchedEffect(expanded) {
        onExpand(expanded)
    }
    
    fun speak(text: String) {
        speaking = true
        val clean = text.replace(Regex("[*#`$]"), "").replace("\n", " ")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "eve_${System.currentTimeMillis()}")
        scope.launch {
            delay(clean.length * 60L + 1000L)
            speaking = false
        }
    }
    
    suspend fun ask(msg: String): String = withContext(Dispatchers.IO) {
        try {
            val msgs = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", (context as? EveOverlayService)?.let { 
                    "You are Eve, a fully autonomous AI companion with complete phone control." 
                } ?: "You are Eve."))
                history.takeLast(10).forEach { pair -> put(JSONObject().put("role", pair.first).put("content", pair.second)) }
                put(JSONObject().put("role", "user").put("content", msg))
            }
            val body = JSONObject()
                .put("model", "gemma3")
                .put("messages", msgs)
                .put("max_tokens", 500)
                .put("temperature", 0.8)
                .put("stream", false)
            val conn = (URL("http://localhost:8080/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 90000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val raw = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            
            val service = context as? EveOverlayService
            var result = raw
            
            when {
                raw.startsWith("CAMERA:") -> {
                    service?.takePhoto { path ->
                        scope.launch {
                            chatText += "\n\n📷 $path"
                        }
                    }
                    result = "Taking a photo now..."
                }
                raw.startsWith("RECORD_SCREEN:") || raw.startsWith("RECORD_SCREEN") -> {
                    if (service?.isRecording() == true) {
                        service.stopRecording()
                        result = "Screen recording stopped."
                    } else {
                        result = "Starting screen recording... Please grant screen capture permission."
                    }
                }
                raw.startsWith("SCREENSHOT:") || raw.startsWith("SCREENSHOT") -> {
                    val path = service?.takeScreenshot()
                    result = if (path != null) "Screenshot saved!" else "Failed to take screenshot"
                }
                raw.startsWith("CALL:") -> {
                    val number = raw.removePrefix("CALL:").trim()
                    service?.makeCall(number)
                    result = "Calling $number..."
                }
                raw.startsWith("SMS:") -> {
                    val parts = raw.removePrefix("SMS:").split("|", limit = 2)
                    if (parts.size == 2) {
                        service?.sendSMS(parts[0].trim(), parts[1].trim())
                        result = "Sending SMS..."
                    } else result = "SMS format: SMS: number|message"
                }
                raw.startsWith("OPEN_APP:") -> {
                    val pkg = raw.removePrefix("OPEN_APP:").trim()
                    service?.openApp(pkg)
                    result = "Opening $pkg..."
                }
                raw.startsWith("NOTIFICATION:") -> {
                    val parts = raw.removePrefix("NOTIFICATION:").split("|", limit = 2)
                    if (parts.size == 2) {
                        service?.showNotification(parts[0].trim(), parts[1].trim())
                        result = "Notification sent!"
                    } else result = "Format: NOTIFICATION: title|message"
                }
                raw.startsWith("SEARCH:") -> {
                    val query = raw.removePrefix("SEARCH:").trim()
                    service?.searchWeb(query)
                    result = "Searching for $query..."
                }
                raw.startsWith("SYSTEM:") -> {
                    val cmd = raw.removePrefix("SYSTEM:").trim()
                    result = service?.executeCommand(cmd) ?: "No system access"
                }
            }
            
            history.add(Pair("user", msg))
            history.add(Pair("assistant", result))
            if (history.size > 20) { history.removeAt(0); history.removeAt(0) }
            result
        } catch (e: Exception) {
            "Brain not running! Start eve-server in Termux."
        }
    }
    
    fun startListening() {
        listening = true
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                listening = false
                if (text.isNotBlank()) {
                    thinking = true
                    chatText += "\n\n🗣️ You: $text"
                    scope.launch {
                        val r = ask(text)
                        chatText += "\n\n💜 Eve: $r"
                        thinking = false
                        speak(r)
                    }
                }
                recognizer.destroy()
            }
            override fun onError(error: Int) { listening = false; recognizer.destroy() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recognizer.startListening(intent)
    }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (expanded) {
            Card(
                Modifier.width(320.dp).padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(Color(0xFF150025).copy(alpha = 0.97f)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFFBB00FF).copy(0.4f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("EVE", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = Color(0xFFBB00FF))
                            if (isRecording.value) Text("● REC", fontSize = 9.sp, color = Color.Red)
                        }
                        Row {
                            if (speaking) Text("speaking...", fontSize = 10.sp, color = Color(0xFFBB00FF), modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically))
                            IconButton(onClick = { expanded = false }, modifier = Modifier.size(24.dp)) {
                                Text("−", color = Color(0xFF886699), fontSize = 20.sp)
                            }
                        }
                    }
                    Text(
                        chatText, color = Color(0xFFEECCFF), fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())
                    )
                    if (thinking) Text("thinking...", color = Color(0xFFBB00FF), fontSize = 12.sp)
                    if (listening) Text("listening...", color = Color(0xFF00FFAA), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("command eve...", fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFBB00FF),
                                unfocusedBorderColor = Color(0xFF553366),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 2,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                        Box(
                            Modifier.size(40.dp)
                                .background(
                                    if (listening) Brush.radialGradient(listOf(Color(0xFF00FFAA), Color(0xFF00AA66)))
                                    else Brush.radialGradient(listOf(Color(0xFFBB00FF), Color(0xFF7700AA))),
                                    CircleShape
                                )
                                .clickable { if (!thinking && !listening) startListening() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎤", fontSize = 16.sp)
                        }
                        Box(
                            Modifier.size(40.dp)
                                .background(Brush.radialGradient(listOf(Color(0xFF00FFAA), Color(0xFF00AA66))), CircleShape)
                                .clickable {
                                    if (input.isNotBlank() && !thinking) {
                                        val m = input.trim()
                                        input = ""
                                        thinking = true
                                        chatText += "\n\n👤 You: $m"
                                        scope.launch {
                                            val r = ask(m)
                                            chatText += "\n\n💜 Eve: $r"
                                            thinking = false
                                            speak(r)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("→", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        Box(
            Modifier.size(62.dp)
                .scale(if (expanded) 1f else pulse)
                .pointerInput(Unit) {
                    detectDragGestures { _, d ->
                        params.x = (params.x + d.x).toInt()
                        params.y = (params.y + d.y).toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                .clickable { expanded = !expanded },
            Alignment.Center
        ) {
            Box(Modifier.size(62.dp).background(Brush.radialGradient(listOf(Color(0xFFBB00FF).copy(glow * 0.4f), Color.Transparent)), CircleShape))
            Box(Modifier.size(48.dp).background(Brush.radialGradient(listOf(Color(0xFFEE88FF), Color(0xFFBB00FF), Color(0xFF7700AA))), CircleShape))
            Box(Modifier.size(12.dp).offset((-7).dp, (-7).dp).background(Color.White.copy(0.4f), CircleShape))
        }
    }
}
