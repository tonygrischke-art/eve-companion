package com.eve.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) { startEve(); return }
        setContent { MaterialTheme { EveHome { openAppSettings() } } }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) startEve()
    }

    private fun openAppSettings() {
        Toast.makeText(this, "Enable Display over other apps for Eve", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (e2: Exception) { }
        }
    }

    private fun startEve() {
        ContextCompat.startForegroundService(this, Intent(this, EveOverlayService::class.java))
        finish()
    }
}

@Composable
fun EveHome(onLaunch: () -> Unit) {
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
            Text("local  private  always on", fontSize = 12.sp, color = Color(0xFFBB88CC), letterSpacing = 2.sp, textAlign = TextAlign.Center)
            Text("Tap below then enable Display over other apps", fontSize = 11.sp, color = Color(0xFFBB88CC).copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Button(onClick = onLaunch, modifier = Modifier.fillMaxWidth(0.7f).height(54.dp),
                colors = ButtonDefaults.buttonColors(Color(0xFFBB00FF)), shape = RoundedCornerShape(16.dp)) {
                Text("OPEN SETTINGS", fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
        }
    }
}
