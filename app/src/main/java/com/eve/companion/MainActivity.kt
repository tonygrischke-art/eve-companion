package com.eve.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Remove MANAGE_EXTERNAL_STORAGE - requires special handling
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        //     add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        // }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            checkOverlayAndStart()
        } else {
            setContent { MaterialTheme { PermissionScreen(onRequest = { requestAllPermissions() }) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkOverlayAndStart()
    }

    private fun checkOverlayAndStart() {
        if (Settings.canDrawOverlays(this)) {
            requestPermissionsIfNeeded()
        } else {
            setContent { MaterialTheme { SetupScreen(onSetup = { requestOverlay() }) } }
        }
    }

    private fun requestAllPermissions() {
        permissionLauncher.launch(requiredPermissions.toTypedArray())
    }

    private fun requestOverlay() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                // Remove NEW_TASK flag as it may cause issues with singleTask launchMode
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("Eve", "Failed to open overlay settings", e)
            Toast.makeText(this, "Please enable Display over other apps manually in Settings > Apps > Special access", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            startEve()
        }
    }

    private fun startEve() {
        val serviceIntent = Intent(this, EveOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            val allGranted = requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                startEve()
            }
        }
    }
}

@Composable
fun SetupScreen(onSetup: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "p")
    val scale by inf.animateFloat(1f, 1.1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "s")
    val glow by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "g")
    
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0010), Color(0xFF150025)))), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(24.dp)) {
            Box(Modifier.size(140.dp).scale(scale), contentAlignment = Alignment.Center) {
                Box(Modifier.size(140.dp).background(Brush.radialGradient(listOf(Color(0xFFBB00FF).copy(glow * 0.3f), Color.Transparent)), CircleShape))
                Box(Modifier.size(100.dp).background(Brush.radialGradient(listOf(Color(0xFFEE88FF), Color(0xFFBB00FF), Color(0xFF7700AA))), CircleShape))
                Box(Modifier.size(22.dp).offset((-18).dp, (-18).dp).background(Color.White.copy(0.35f), CircleShape))
            }
            Text("EVE", fontSize = 48.sp, fontWeight = FontWeight.Black, letterSpacing = 14.sp, color = Color(0xFFEE88FF))
            Text("Fully Autonomous AI Companion", fontSize = 14.sp, color = Color(0xFFBB88CC), letterSpacing = 2.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("Tap to enable Display over other apps", fontSize = 12.sp, color = Color(0xFFBB88CC).copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Button(onClick = onSetup, modifier = Modifier.fillMaxWidth(0.7f).height(54.dp),
                colors = ButtonDefaults.buttonColors(Color(0xFFBB00FF)), shape = RoundedCornerShape(16.dp)) {
                Text("SETUP EVE", fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Powerful permissions required for full autonomous control", fontSize = 10.sp, color = Color(0xFF886699), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0010), Color(0xFF150025)))), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)) {
            Text("EVE", fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 10.sp, color = Color(0xFFEE88FF))
            Text("Permissions Required", fontSize = 14.sp, color = Color(0xFFBB88CC))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionItem("🎤 Microphone", "Voice commands")
                PermissionItem("📷 Camera", "Take photos & videos")
                PermissionItem("📱 Screen", "Screen recording")
                PermissionItem("📞 Phone", "Make calls")
                PermissionItem("💬 SMS", "Send messages")
                PermissionItem("📁 Storage", "Save files")
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth(0.7f).height(48.dp),
                colors = ButtonDefaults.buttonColors(Color(0xFFBB00FF)), shape = RoundedCornerShape(12.dp)) {
                Text("GRANT ALL", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PermissionItem(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(icon, fontSize = 16.sp)
        Text(text, fontSize = 12.sp, color = Color(0xFFEECCFF))
    }
}
