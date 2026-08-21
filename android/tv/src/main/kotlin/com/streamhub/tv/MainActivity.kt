package com.streamhub.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.Surface
import com.streamhub.tv.ui.StreamHubTvTheme

class MainActivity : ComponentActivity() {

    /**
     * One container for the process.
     *
     * It owns the realtime socket, and that socket is what makes this
     * television visible to a phone — so it has to outlive any one screen and
     * be built before the first frame rather than by whichever composable
     * happens to need it first.
     */
    private val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamHubTvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreamHubTvApp(container)
                }
            }
        }
    }
}
