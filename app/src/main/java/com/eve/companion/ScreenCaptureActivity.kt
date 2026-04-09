package com.eve.companion

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            EveOverlayService::class.java.getDeclaredField("pendingScreenCapture")
                .apply { isAccessible = true }
                .set(null, Pair(resultCode, data))
            
            val intent = Intent(this, EveOverlayService::class.java).apply {
                putExtra("screen_capture_result", resultCode)
                putExtra("screen_capture_data", data)
            }
            startService(intent)
        }
        finish()
    }
}
