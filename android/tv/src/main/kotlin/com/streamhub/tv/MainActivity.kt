package com.streamhub.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Always dark: a ten-foot UI is watched in a dark room, and the TV
            // material library has no light scheme worth using here.
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Overscan-safe padding — roughly 5% is the convention, and
                    // a TV may crop anything closer to the edge.
                    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 27.dp)) {
                        Text("StreamHub", style = MaterialTheme.typography.displaySmall)
                        Text(
                            "${ApiConfig.BASE_PATH} · ${ClientKind.TV.header}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
