package com.eve.companion
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EveOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lc = LifecycleRegistry(this)
    private val ssrc = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = ssrc.savedStateRegistry
    override val lifecycle: Lifecycle get() = lc
    override val viewModelStore = ViewModelStore()
    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val SYSTEM = "You are Eve, a vibrant upbeat AI companion. Sharp, warm, playful. Short punchy sentences. Fully on-device and private."

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
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 200 }
        rootView = ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@EveOverlayService)
            setViewTreeViewModelStoreOwner(this@EveOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EveOverlayService)
            setContent { MaterialTheme { EveBubble(wm, params, this, scope, SYSTEM) { stopSelf() } } }
        }
        wm.addView(rootView, params)
        lc.currentState = Lifecycle.State.RESUMED
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); lc.currentState = Lifecycle.State.DESTROYED; wm.removeView(rootView); super.onDestroy() }
}

@Composable
fun EveBubble(wm: WindowManager, params: WindowManager.LayoutParams, view: View, scope: CoroutineScope, system: String, onClose: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf("Eve: Hey! I'm Eve. What's up?") }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<Pair<String, String>>() }
    val inf = rememberInfiniteTransition(label = "e")
    val pulse by inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p")
    val glow by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "g")

    suspend fun ask(msg: String): String = withContext(Dispatchers.IO) {
        try {
            val msgs = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                history.forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) }
                put(JSONObject().put("role", "user").put("content", msg))
            }
            val body = JSONObject().put("model", "gemma3").put("messages", msgs).put("max_tokens", 300).put("temperature", 0.8).put("stream", false)
            val conn = (URL("http://localhost:8080/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 10000; readTimeout = 60000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            history.add("user" to msg); history.add("assistant" to reply)
            if (history.size > 20) { history.removeAt(0); history.removeAt(0) }
            reply
        } catch (e: Exception) { "Brain not running! Start llama-server in Termux first." }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (expanded) {
            Card(Modifier.width(280.dp).padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(Color(0xFF150025).copy(alpha = 0.97f)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFFBB00FF).copy(0.4f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("EVE", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = Color(0xFFBB00FF))
                        TextButton(onClose, contentPadding = PaddingValues(4.dp)) { Text("x", color = Color(0xFF886699)) }
                    }
                    Text(chat, color = Color(0xFFEECCFF), fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
                    if (thinking) Text("thinking...", color = Color(0xFFBB00FF), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(input, { input = it }, Modifier.weight(1f),
                            placeholder = { Text("talk to eve...", fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFBB00FF), unfocusedBorderColor = Color(0xFF553366), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            shape = RoundedCornerShape(12.dp), maxLines = 2,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                        Box(Modifier.size(40.dp).background(Brush.radialGradient(listOf(Color(0xFFBB00FF), Color(0xFFFF006E))), CircleShape).clickable {
                            if (input.isNotBlank() && !thinking) {
                                val msg = input.trim(); input = ""; thinking = true; chat += "
You: " + msg
                                scope.launch { val r = ask(msg); chat += "
Eve: " + r; thinking = false }
                            }
                        }, Alignment.Center) { Text(">", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Box(Modifier.size(62.dp).scale(if (expanded) 1f else pulse)
            .pointerInput(Unit) { detectDragGestures { _, d -> params.x = (params.x + d.x).toInt(); params.y = (params.y + d.y).toInt(); wm.updateViewLayout(view, params) } }
            .clickable { expanded = !expanded }, Alignment.Center) {
            Box(Modifier.size(62.dp).background(Brush.radialGradient(listOf(Color(0xFFBB00FF).copy(glow * 0.4f), Color.Transparent)), CircleShape))
            Box(Modifier.size(48.dp).background(Brush.radialGradient(listOf(Color(0xFFEE88FF), Color(0xFFBB00FF), Color(0xFF7700AA))), CircleShape))
            Box(Modifier.size(12.dp).offset((-7).dp, (-7).dp).background(Color.White.copy(0.4f), CircleShape))
        }
    }
}