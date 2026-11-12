package com.streamhub.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.sfx.TvSfx

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
    private val nav = TvNav()
    private val sfx: TvSfx by lazy { TvSfx(this) }
    private val engine: TvFocusEngine by lazy { TvFocusEngine(nav, sfx) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.onExitApp = { finish() }
        // `visibilitychange`: silent and suspended while the app is not shown.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = sfx.setHidden(false)
            override fun onStop(owner: LifecycleOwner) = sfx.setHidden(true)
            override fun onDestroy(owner: LifecycleOwner) = sfx.release()
        })
        setContent { TvApp(container, engine, nav, sfx) }
    }

    /**
     * Every key goes to the focus engine first, ACTION_DOWN and ACTION_UP
     * (PARITY §4). Back is handled on the down, so OnBackPressedDispatcher —
     * which fires on the up — never sees it. Keys the engine leaves alone (a
     * caret move inside a text field, volume) continue as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        engine.onKeyEvent(event) || super.dispatchKeyEvent(event)
}
