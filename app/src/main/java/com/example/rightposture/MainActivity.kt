package com.example.rightposture

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.rightposture.nav.AppHost
import com.example.rightposture.ui.theme.RightPostureTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The patient is several feet from the phone doing an exercise, so nothing touches the
        // screen for the length of a set. Without this the display sleeps mid-rep and the camera
        // stream stops with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { RightPostureTheme { AppHost() } }
    }
}
