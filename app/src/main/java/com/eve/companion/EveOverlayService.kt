package com.eve.companion

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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
    private var isWakeListening = false
    private var lastWakeTime = 0L

    private val WAKE_PHRASES = listOf(
        "hey eve",
        "hey eve ",
        "eve",
        "hey there eve",
        "hello eve",
        "yo eve",
        "hi eve"
    )

    private val GREETINGS = listOf(
        "Hey there, handsome.",
        "Present and accounted for!",
        "I'm here. What's on your mind?",
        "Hey! Ready when you are.",
        "Yo! Still here, always.",
        "At your service.",
        "Hey there! Miss me?",
        "Right here. What do you need?",
        "Hey! Let's do this.",
        "I'm awake. Let's chat.",
        "Hey handsome, what's up?",
        "Present! What are we working on today?",
        "Yo! The AI is online.",
        "Hey! Ready to conquer the galaxy?",
        "Hey there. Long time no see!",
        "I'm listening. Go ahead.",
        "Hey! Your wish is my command.",
        "Here I am! What's next?"
    )

    private val SYSTEM = "You are Eve, a vibrant upbeat AI companion. Sharp, warm, playful. Short punchy sentences. Fully on-device and private. When asked to run a command, respond with: RUN: <command>. When asked to read a file, respond with: READ: <filepath>. When asked to write a file, respond with: WRITE: <filepath> | <content>. Keep responses short and punchy."

    override fun onCreate() {
        super.onCreate()
        ssrc.performRestore(null)
        lc.currentState = Lifecycle.State.CREATED
        val ch = NotificationChannel("eve", "Eve", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        startForeground(1, NotificationCompat.Builder(this, "eve")
            .setContentTitle("Eve is awake")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build())
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                ttsReady.value = true
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
                        system = SYSTEM,
                        tts = tts,
                        ttsReady = ttsReady,
                        context = this@EveOverlayService,
                        onClose = { stopSelf() },
                        service = this@EveOverlayService
                    )
                }
            }
        }
        wm.addView(rootView, params)
        lc.currentState = Lifecycle.State.RESUMED
        startWakeDetection()
    }

    private fun startWakeDetection() {
        if (wakeRecognizer != null) return
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        listenForWake()
    }

    private fun listenForWake() {
        if (!isWakeListening || wakeRecognizer == null) return
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
                if (isWakeListening) {
                    scope.launch {
                        delay(300)
                        listenForWake()
                    }
                }
            }
            override fun onError(error: Int) {
                if (error != 7 && error != 8) {
                    isWakeListening = false
                    scope.launch {
                        delay(500)
                        if (isWakeListening) listenForWake()
                    }
                } else {
                    if (isWakeListening) listenForWake()
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
        isWakeListening = false
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        val greeting = GREETINGS.random()
        scope.launch {
            addToChat("\nEve: $greeting")
            speak(greeting)
            delay(500)
            isWakeListening = true
            startWakeDetection()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        isWakeListening = false
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
        lc.currentState = Lifecycle.State.DESTROYED
        wm.removeView(rootView)
        super.onDestroy()
    }

    private var chat = MutableStateFlow("Eve: Hey! I am Eve. What is up?")

    fun getGreeting(): String = GREETINGS.random()
    fun speak(text: String) {
        if (ttsReady.value) {
            val clean = text.replace(Regex("[*#`]"), "")
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "eve_${System.currentTimeMillis()}")
        }
    }
    fun addToChat(text: String) {
        chat.value += text
    }
    fun getChat(): StateFlow<String> = chat
}

fun executeAction(reply: String, context: Context): String {
    return try {
        when {
            reply.startsWith("RUN:") -> {
                val cmd = reply.removePrefix("RUN:").trim()
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = proc.inputStream.bufferedReader().readText().trim()
                val err = proc.errorStream.bufferedReader().readText().trim()
                if (out.isNotEmpty()) out else if (err.isNotEmpty()) "Error: $err" else "Done"
            }
            reply.startsWith("READ:") -> {
                val path = reply.removePrefix("READ:").trim()
                File(path).readText().take(500)
            }
            reply.startsWith("WRITE:") -> {
                val parts = reply.removePrefix("WRITE:").split("|", limit = 2)
                if (parts.size == 2) {
                    File(parts[0].trim()).writeText(parts[1].trim())
                    "Written successfully"
                } else "Invalid WRITE format"
            }
            else -> reply
        }
    } catch (e: Exception) {
        "Action failed: ${e.message}"
    }
}

@Composable
fun EveBubble(
    wm: WindowManager,
    params: WindowManager.LayoutParams,
    view: android.view.View,
    scope: CoroutineScope,
    system: String,
    tts: TextToSpeech?,
    ttsReady: MutableState<Boolean>,
    context: Context,
    onClose: () -> Unit,
    service: EveOverlayService? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf("Eve: Hey! I am Eve. What is up?") }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<Pair<String, String>>() }
    val inf = rememberInfiniteTransition(label = "e")
    val pulse by inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p")
    val glow by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "g")

    LaunchedEffect(service) {
        service?.getChat()?.collect { newChat ->
            chat = newChat
        }
    }

    fun speak(text: String) {
        service?.speak(text) ?: run {
            if (ttsReady.value) {
                speaking = true
                val clean = text.replace(Regex("[*#`]"), "")
                tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "eve_${System.currentTimeMillis()}")
                scope.launch {
                    delay(clean.length * 60L + 1000L)
                    speaking = false
                }
            }
        }
    }

    fun addToChat(text: String) {
        if (service != null) {
            service.addToChat(text)
        } else {
            chat += text
        }
    }

    suspend fun ask(msg: String): String = withContext(Dispatchers.IO) {
        try {
            val msgs = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                history.forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) }
                put(JSONObject().put("role", "user").put("content", msg))
            }
            val body = JSONObject()
                .put("model", "gemma3")
                .put("messages", msgs)
                .put("max_tokens", 300)
                .put("temperature", 0.8)
                .put("stream", false)
            val conn = (URL("http://localhost:8080/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 60000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val raw = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            val result = executeAction(raw, context)
            history.add("user" to msg)
            history.add("assistant" to result)
            if (history.size > 20) { history.removeAt(0); history.removeAt(0) }
            result
        } catch (e: Exception) {
            "Brain not running! Start llama-server in Termux first."
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
                    addToChat("\nYou: $text")
                    scope.launch {
                        val r = ask(text)
                        addToChat("\nEve: $r")
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
                Modifier.width(300.dp).padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(Color(0xFF150025).copy(alpha = 0.97f)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFFBB00FF).copy(0.4f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("EVE", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = Color(0xFFBB00FF))
                        Row {
                            if (speaking) Text("speaking...", fontSize = 10.sp, color = Color(0xFFBB00FF), modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically))
                            TextButton(onClose, contentPadding = PaddingValues(4.dp)) {
                                Text("x", color = Color(0xFF886699))
                            }
                        }
                    }
                    Text(
                        chat, color = Color(0xFFEECCFF), fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                    )
                    if (thinking) Text("thinking...", color = Color(0xFFBB00FF), fontSize = 12.sp)
                    if (listening) Text("listening...", color = Color(0xFF00FFAA), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("talk to eve...", fontSize = 12.sp) },
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
                            Text("mic", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier.size(40.dp)
                                .background(Brush.radialGradient(listOf(Color(0xFFBB00FF), Color(0xFFFF006E))), CircleShape)
                                .clickable {
                                    if (input.isNotBlank() && !thinking) {
                                        val m = input.trim()
                                        input = ""
                                        thinking = true
                                        addToChat("\nYou: $m")
                                        scope.launch {
                                            val r = ask(m)
                                            addToChat("\nEve: $r")
                                            thinking = false
                                            speak(r)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(">", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
