package com.streamhub.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.streamhub.mobile.ui.StreamHubTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark whatever the system is set to, so the bars have to be
        // told as much: left to follow the system, a phone in light mode draws
        // dark status icons on top of a near-black app and they disappear.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val container = AppContainer(applicationContext)

        setContent {
            StreamHubTheme {
                StreamHubApp(container)
            }
        }
    }
}
