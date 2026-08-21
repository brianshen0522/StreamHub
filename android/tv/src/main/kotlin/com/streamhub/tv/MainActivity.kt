package com.streamhub.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamhub.tv.ui.StreamHubTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamHubTvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Overscan-safe padding — roughly 5% is the convention, and
                    // a TV may crop anything closer to the edge.
                    Column(
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 27.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("StreamHub", style = MaterialTheme.typography.displaySmall)
                        // Deliberately not the server address or its API path.
                        // Nothing about the deployment belongs on a screen a
                        // viewer can see; the build id is the one thing that is
                        // useful and says nothing.
                        Text(
                            "Build ${BuildConfig.GIT_SHA}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
